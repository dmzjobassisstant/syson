/**
 * SysON Auth — RBAC login overlay + JWT interceptor.
 * Injected into index.html before the Sirius Web app loads.
 * Zero dependencies. Self-contained.
 *
 * ⚠️ AI AGENTS: DO NOT REFACTOR THIS FILE CASUALLY.
 * The login overlay has been broken multiple times by broad auth.js rewrites.
 * Keep the unauthenticated boot path intact: loadState() -> blockApp() -> showLogin('').
 * blockApp() MUST inject '#root { display: none !important; }' because Vite/React
 * can override root.style.display. showLogin() MUST wait for document.body because
 * this script is loaded from <head>. Successful login MUST reload the page so the
 * React app boots fresh with the JWT already present.
 *
 * Before claiming success after any change, run:
 *   bash scripts/check-syson-login-regression.sh
 * See AGENTS.md for the full guardrail notes.
 */
(function () {
  'use strict';

  const STORAGE_KEY = 'syson_auth';
  const API_BASE = window.location.origin;

  // ── State ────────────────────────────────────────────────────────────────
  let state = { token: null, email: null, roles: [], tenantId: null };

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) state = JSON.parse(raw);
    } catch (_) { /* ignore */ }
  }

  function saveState() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }

  function clearState() {
    state = { token: null, email: null, roles: [], tenantId: null };
    localStorage.removeItem(STORAGE_KEY);
  }

  function parseJWT(token) {
    try {
      const payload = token.split('.')[1];
      return JSON.parse(atob(payload));
    } catch (_) {
      return {};
    }
  }

  // ── HTTP Interceptor ─────────────────────────────────────────────────────
  const _origFetch = window.fetch;
  window.fetch = function (url, options) {
    if (state.token) {
      const opts = options || {};
      opts.headers = opts.headers || {};
      if (opts.headers instanceof Headers) {
        opts.headers.set('Authorization', 'Bearer ' + state.token);
      } else {
        opts.headers['Authorization'] = 'Bearer ' + state.token;
      }
      return _origFetch(url, opts);
    }
    return _origFetch(url, options);
  };

  // Intercept XMLHttpRequest for WebSocket upgrade headers
  const _origXHROpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    this._syson_url = url;
    return _origXHROpen.apply(this, arguments);
  };
  const _origXHRSetHeader = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function (header, value) {
    if (header === 'Authorization') return; // let our interceptor handle it
    return _origXHRSetHeader.apply(this, arguments);
  };
  const _origXHRSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function (body) {
    if (state.token) {
      _origXHRSetHeader.call(this, 'Authorization', 'Bearer ' + state.token);
    }
    return _origXHRSend.apply(this, arguments);
  };

  // ── Login API ────────────────────────────────────────────────────────────
  async function login(email, password) {
    const res = await _origFetch(API_BASE + '/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });
    if (!res.ok) {
      const msg = res.status === 401 ? 'Invalid credentials' : 'Login failed';
      throw new Error(msg);
    }
    const data = await res.json();
    const claims = parseJWT(data.token);
    state.token = data.token;
    state.email = data.email;
    state.roles = data.roles || [];
    state.tenantId = claims.tenantId || null;
    saveState();
    return data;
  }

  async function refreshToken() {
    if (!state.token) throw new Error('No token');
    const res = await _origFetch(API_BASE + '/api/auth/refresh', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + state.token },
    });
    if (!res.ok) throw new Error('Token refresh failed');
    const data = await res.json();
    state.token = data.token;
    saveState();
    return data;
  }

  function logout() {
    clearState();
    window.location.reload();
  }

  // ── Root Blocker ──────────────────────────────────────────────────────────
  // CSS !important rule injected BEFORE the Vite bundle loads — prevents
  // React from overriding display:none with its own styles.
  let _blockerEl = null;

  function blockApp() {
    if (_blockerEl) return;
    _blockerEl = document.createElement('style');
    _blockerEl.id = 'syson-root-blocker';
    _blockerEl.textContent = '#root { display: none !important; }';
    document.head.insertBefore(_blockerEl, document.head.firstChild);
  }

  function unblockApp() {
    if (_blockerEl) {
      _blockerEl.remove();
      _blockerEl = null;
    }
    // Also remove any leftover inline display:none on #root
    const root = document.getElementById('root');
    if (root && root.style.display === 'none') {
      root.style.display = '';
    }
  }

  // ── UI ───────────────────────────────────────────────────────────────────
  const STYLES = `
    #syson-auth-overlay {
      position: fixed; inset: 0; z-index: 99999;
      display: flex; align-items: center; justify-content: center;
      background: #1a1a2e;
      font-family: 'Lato', 'Roboto', 'Helvetica Neue', Arial, sans-serif;
    }
    #syson-auth-card {
      background: #16213e; border-radius: 12px; padding: 2.5rem 2rem;
      width: 100%; max-width: 400px; box-shadow: 0 8px 32px rgba(0,0,0,0.4);
    }
    #syson-auth-card h1 {
      color: #e0e0e0; font-size: 1.5rem; font-weight: 600;
      margin: 0 0 0.25rem; text-align: center;
    }
    #syson-auth-card .subtitle {
      color: #888; font-size: 0.85rem; text-align: center; margin-bottom: 1.5rem;
    }
    #syson-auth-card label {
      display: block; color: #aaa; font-size: 0.8rem;
      margin-bottom: 0.3rem; font-weight: 500;
    }
    #syson-auth-card input {
      width: 100%; padding: 0.7rem 0.8rem; border-radius: 6px;
      border: 1px solid #2a2a4a; background: #0f0f23; color: #e0e0e0;
      font-size: 0.95rem; margin-bottom: 1rem; box-sizing: border-box;
      transition: border-color 0.2s;
    }
    #syson-auth-card input:focus {
      outline: none; border-color: #4a90d9;
    }
    #syson-auth-card button {
      width: 100%; padding: 0.75rem; border-radius: 6px; border: none;
      background: #4a90d9; color: #fff; font-size: 1rem; font-weight: 600;
      cursor: pointer; transition: background 0.2s; margin-top: 0.5rem;
    }
    #syson-auth-card button:hover { background: #3a7bc8; }
    #syson-auth-card button:disabled { background: #555; cursor: not-allowed; }
    #syson-auth-card .error {
      color: #e74c3c; font-size: 0.82rem; text-align: center;
      margin-top: 0.5rem; min-height: 1.2em;
    }
    #syson-user-bar {
      display: none; align-items: center; gap: 8px;
      color: #ccc; font-size: 0.82rem;
      padding: 4px 12px; border-radius: 6px;
      background: rgba(255,255,255,0.04);
    }
    #syson-user-bar .role-badge {
      background: #4a90d9; color: #fff; font-size: 0.7rem;
      padding: 1px 6px; border-radius: 4px; font-weight: 600;
      text-transform: uppercase;
    }
    #syson-user-bar .role-badge.superuser { background: #e67e22; }
    #syson-user-bar .role-badge.admin { background: #9b59b6; }
    #syson-logout-btn {
      background: none; border: 1px solid #555; color: #ccc;
      padding: 2px 8px; border-radius: 4px; cursor: pointer;
      font-size: 0.75rem;
    }
    #syson-logout-btn:hover { background: rgba(255,255,255,0.08); }
  `;

  function showLogin(errorMsg) {
    // auth.js is loaded in <head>, so body may not exist yet.
    // Keep the root blocker active, then render the login overlay once <body> exists.
    if (!document.body) {
      document.addEventListener('DOMContentLoaded', () => showLogin(errorMsg), { once: true });
      return;
    }

    // Inject styles
    const styleEl = document.createElement('style');
    styleEl.textContent = STYLES;
    document.head.appendChild(styleEl);

    // Build overlay
    const overlay = document.createElement('div');
    overlay.id = 'syson-auth-overlay';
    overlay.innerHTML = `
      <div id="syson-auth-card">
        <h1>SysON</h1>
        <p class="subtitle">Sign in to continue</p>
        <form id="syson-login-form">
          <label for="syson-email">Username</label>
          <input id="syson-email" type="text" placeholder="admin" autocomplete="username" autofocus />
          <label for="syson-password">Password</label>
          <input id="syson-password" type="password" placeholder="••••••••" autocomplete="current-password" />
          <button type="submit" id="syson-login-btn">Sign In</button>
          <div class="error" id="syson-error">${errorMsg || ''}</div>
        </form>
      </div>
    `;
    document.body.appendChild(overlay);

    const form = document.getElementById('syson-login-form');
    const btn = document.getElementById('syson-login-btn');
    const errorEl = document.getElementById('syson-error');

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const email = document.getElementById('syson-email').value.trim();
      const password = document.getElementById('syson-password').value;
      if (!email || !password) {
        errorEl.textContent = 'Username and password are required';
        return;
      }
      btn.disabled = true;
      btn.textContent = 'Signing in…';
      errorEl.textContent = '';
      try {
        await login(email, password);
        // Force a full reload so the React app initializes fresh with
        // the token already in localStorage — avoids broken state from
        // GraphQL queries that failed during the login overlay.
        window.location.reload();
      } catch (err) {
        errorEl.textContent = err.message;
        btn.disabled = false;
        btn.textContent = 'Sign In';
      }
    });
  }

  function mountUserBar() {
    if (!state.email) return;
    // Try to insert into Sirius nav bar — find a suitable anchor
    const tryMount = () => {
      const nav = document.querySelector('[class*="navigationBar"]') 
               || document.querySelector('header')
               || document.querySelector('[class*="navbar"]');
      if (!nav) {
        setTimeout(tryMount, 500);
        return;
      }
      let bar = document.getElementById('syson-user-bar');
      if (!bar) {
        bar = document.createElement('div');
        bar.id = 'syson-user-bar';
        nav.appendChild(bar);
      }
      const roleClass = (state.roles[0] || '').toLowerCase();
      const badgeHTML = state.roles.map(r =>
        `<span class="role-badge ${r.toLowerCase()}">${r}</span>`
      ).join('');
      var adminButtonHTML = isAdminUser() ? '<button id="syson-admin-btn" title="Administration">Admin</button>' : '';
      bar.innerHTML = `
        <span>${state.email}</span>
        ${badgeHTML}
        <button id="syson-dashboard-btn" title="Dashboard">Dashboard</button>
        ${adminButtonHTML}
        <button id="syson-logout-btn" title="Sign out">Sign out</button>
      `;
      bar.style.display = 'flex';
      document.getElementById('syson-logout-btn').addEventListener('click', logout);
      document.getElementById('syson-dashboard-btn').addEventListener('click', showDashboard);
      var adminBtn = document.getElementById('syson-admin-btn');
      if (adminBtn) adminBtn.addEventListener('click', showAdminConsole);
    };
    tryMount();
  }

  // ── Dashboard ────────────────────────────────────────────────────────────
  function showDashboard() {
    var existing = document.getElementById('syson-dashboard-overlay');
    if (existing) existing.remove();

    var overlay = document.createElement('div');
    overlay.id = 'syson-dashboard-overlay';
    overlay.style.cssText = 'position:fixed;inset:0;z-index:100000;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,0.65);font-family:Lato,Roboto,Arial,sans-serif;';

    var headers = { 'Authorization': 'Bearer ' + state.token };

    var showOverlay = function(userData, projectsData) {
      var email = (userData && userData.email) || state.email || 'N/A';
      var name = (userData && userData.name) || '-';
      var roles = state.roles || [];
      var projHTML = '';
      if (projectsData && projectsData.length) {
        projHTML = projectsData.map(function(p) {
          return '<div style="display:flex;justify-content:space-between;align-items:center;padding:8px 12px;border-radius:6px;background:rgba(255,255,255,0.03);margin-bottom:4px;">'
            + '<span style="color:#e0e0e0;font-size:0.82rem;font-family:monospace;">' + (p.projectId || '').substring(0,16) + '…</span>'
            + '<span style="background:#4a90d9;color:#fff;font-size:0.68rem;padding:2px 8px;border-radius:4px;font-weight:600;text-transform:uppercase;">' + (p.role || '') + '</span>'
            + '</div>';
        }).join('');
      } else {
        projHTML = '<p style="color:#666;font-size:0.82rem;">No projects assigned.</p>';
      }

      overlay.innerHTML = '<div style="background:#16213e;border-radius:12px;padding:2rem;width:100%;max-width:520px;max-height:80vh;overflow-y:auto;box-shadow:0 8px 32px rgba(0,0,0,0.5);color:#e0e0e0;">'
        + '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:1.5rem;">'
        + '<h2 style="margin:0;font-size:1.2rem;">Dashboard</h2>'
        + '<button id="syson-dash-close" style="background:none;border:none;color:#888;font-size:1.5rem;cursor:pointer;line-height:1;">×</button>'
        + '</div>'
        + '<div style="margin-bottom:1.5rem;padding-bottom:1.5rem;border-bottom:1px solid #2a2a4a;">'
        + '<h3 style="color:#aaa;font-size:0.8rem;text-transform:uppercase;margin:0 0 0.75rem;">Profile</h3>'
        + '<div style="color:#e0e0e0;font-size:0.88rem;">'
        + '<div style="margin-bottom:0.3rem;"><strong style="color:#888;">Email:</strong> ' + email.replace(/&/g,'&amp;').replace(/</g,'&lt;') + '</div>'
        + '<div style="margin-bottom:0.3rem;"><strong style="color:#888;">Name:</strong> ' + name.replace(/&/g,'&amp;').replace(/</g,'&lt;') + '</div>'
        + '<div><strong style="color:#888;">Roles:</strong> ' + roles.join(', ') + '</div>'
        + '</div></div>'
        + '<div style="margin-bottom:1.5rem;padding-bottom:1.5rem;border-bottom:1px solid #2a2a4a;">'
        + '<h3 style="color:#aaa;font-size:0.8rem;text-transform:uppercase;margin:0 0 0.75rem;">Change Password</h3>'
        + '<form id="syson-pw-form">'
        + '<input id="syson-cur-pw" type="password" placeholder="Current password" style="width:100%;padding:0.6rem;border-radius:6px;border:1px solid #2a2a4a;background:#0f0f23;color:#e0e0e0;font-size:0.88rem;margin-bottom:0.5rem;box-sizing:border-box;" />'
        + '<input id="syson-new-pw" type="password" placeholder="New password" style="width:100%;padding:0.6rem;border-radius:6px;border:1px solid #2a2a4a;background:#0f0f23;color:#e0e0e0;font-size:0.88rem;margin-bottom:0.5rem;box-sizing:border-box;" />'
        + '<button type="submit" style="width:100%;padding:0.6rem;border-radius:6px;border:none;background:#4a90d9;color:#fff;font-size:0.9rem;font-weight:600;cursor:pointer;">Update Password</button>'
        + '<div id="syson-pw-msg" style="color:#4caf50;font-size:0.8rem;text-align:center;margin-top:0.5rem;min-height:1.2em;"></div>'
        + '</form></div>'
        + '<div><h3 style="color:#aaa;font-size:0.8rem;text-transform:uppercase;margin:0 0 0.75rem;">My Projects</h3>' + projHTML + '</div>'
        + '</div>';

      document.body.appendChild(overlay);

      document.getElementById('syson-dash-close').addEventListener('click', function() { overlay.remove(); });
      overlay.addEventListener('click', function(e) { if (e.target === overlay) overlay.remove(); });

      document.getElementById('syson-pw-form').addEventListener('submit', function(e) {
        e.preventDefault();
        var m = document.getElementById('syson-pw-msg');
        var cp = document.getElementById('syson-cur-pw').value;
        var np = document.getElementById('syson-new-pw').value;
        if (!cp || !np) { m.style.color='#e74c3c'; m.textContent='Both fields are required'; return; }
        _origFetch(API_BASE + '/api/v1/user/me/password', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + state.token },
          body: JSON.stringify({ currentPassword: cp, newPassword: np })
        }).then(function(res) {
          if (res.ok) { m.style.color='#4caf50'; m.textContent='Password updated!'; document.getElementById('syson-cur-pw').value=''; document.getElementById('syson-new-pw').value=''; }
          else { return res.text().then(function(t) { m.style.color='#e74c3c'; m.textContent=t || 'Failed'; }); }
        })['catch'](function() { m.style.color='#e74c3c'; m.textContent='Network error'; });
      });
    };

    // Fetch profile and projects
    var fetchProfile = _origFetch(API_BASE + '/api/v1/user/me', { headers: headers });
    var fetchProjects = _origFetch(API_BASE + '/api/v1/user/me/projects', { headers: headers });

    Promise.all([fetchProfile, fetchProjects]).then(function(results) {
      var userData = null, projectsData = null;
      return results[0].json().then(function(d) { userData = d; })['catch'](function(){}).then(function() {
        return results[1].json().then(function(d) { projectsData = d; })['catch'](function(){});
      }).then(function() {
        showOverlay(userData, projectsData);
      });
    })['catch'](function() { showOverlay(null, null); });
  }

  // ── Enterprise Admin Console ─────────────────────────────────────────────
  function isAdminUser() {
    var roles = state.roles || [];
    return roles.some(function(role) {
      var normalized = String(role || '').toLowerCase();
      return normalized === 'admin' || normalized === 'superuser';
    });
  }

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function adminFetch(path, options) {
    var opts = options || {};
    opts.headers = opts.headers || {};
    opts.headers['Authorization'] = 'Bearer ' + state.token;
    if (opts.body && !opts.headers['Content-Type']) opts.headers['Content-Type'] = 'application/json';
    return _origFetch(API_BASE + path, opts).then(function(res) {
      if (!res.ok) throw new Error('HTTP ' + res.status);
      return res.json()['catch'](function() { return {}; });
    });
  }

  function showAdminConsole() {
    if (!isAdminUser()) return;
    var existing = document.getElementById('syson-admin-overlay');
    if (existing) existing.remove();

    var overlay = document.createElement('div');
    overlay.id = 'syson-admin-overlay';
    overlay.style.cssText = 'position:fixed;inset:0;z-index:100001;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,0.72);font-family:Lato,Roboto,Arial,sans-serif;';
    overlay.innerHTML = '<div style="background:#111827;border:1px solid #24324a;border-radius:14px;width:min(1100px,94vw);max-height:86vh;overflow:auto;box-shadow:0 18px 60px rgba(0,0,0,.55);color:#e5e7eb;">'
      + '<div style="display:flex;justify-content:space-between;align-items:center;padding:18px 22px;border-bottom:1px solid #24324a;">'
      + '<div><h2 style="margin:0;font-size:1.15rem;">Access Administration</h2><p style="margin:4px 0 0;color:#8b98aa;font-size:.82rem;">Accounts, password resets, project roles, and audit history</p></div>'
      + '<button id="syson-admin-close" style="background:none;border:none;color:#8b98aa;font-size:1.6rem;cursor:pointer;">×</button></div>'
      + '<div style="display:grid;grid-template-columns:1fr 1fr;gap:18px;padding:20px;">'
      + '<section style="background:#0b1220;border:1px solid #1f2a3d;border-radius:10px;padding:14px;"><h3 style="margin:0 0 12px;font-size:.9rem;color:#cbd5e1;text-transform:uppercase;letter-spacing:.04em;">Users</h3><div id="syson-admin-users" style="font-size:.84rem;color:#94a3b8;">Loading users…</div></section>'
      + '<section style="background:#0b1220;border:1px solid #1f2a3d;border-radius:10px;padding:14px;"><h3 style="margin:0 0 12px;font-size:.9rem;color:#cbd5e1;text-transform:uppercase;letter-spacing:.04em;">Create User</h3>'
      + '<form id="syson-admin-create-user"><input id="syson-admin-email" placeholder="email" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><input id="syson-admin-name" placeholder="name" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><input id="syson-admin-password" type="password" placeholder="temporary password" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><select id="syson-admin-role" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><option value="viewer">Viewer</option><option value="editor">Editor</option><option value="admin">Admin</option><option value="superuser">Superuser</option></select><button style="width:100%;padding:9px;border:0;border-radius:6px;background:#2563eb;color:white;font-weight:700;cursor:pointer;">Create Account</button><div id="syson-admin-create-msg" style="min-height:18px;margin-top:8px;font-size:.8rem;"></div></form></section>'
      + '<section style="grid-column:1/-1;background:#0b1220;border:1px solid #1f2a3d;border-radius:10px;padding:14px;"><h3 style="margin:0 0 12px;font-size:.9rem;color:#cbd5e1;text-transform:uppercase;letter-spacing:.04em;">Audit History</h3><div id="syson-admin-audit" style="font-size:.82rem;color:#94a3b8;">Loading audit events…</div></section>'
      + '</div></div>';
    document.body.appendChild(overlay);
    document.getElementById('syson-admin-close').addEventListener('click', function() { overlay.remove(); });
    overlay.addEventListener('click', function(e) { if (e.target === overlay) overlay.remove(); });

    var renderUsers = function(users) {
      var box = document.getElementById('syson-admin-users');
      if (!users || !users.length) { box.textContent = 'No users found.'; return; }
      box.innerHTML = users.map(function(u) {
        var active = u.active ? 'Active' : 'Disabled';
        return '<div style="display:grid;grid-template-columns:1fr auto;gap:10px;align-items:center;padding:9px 0;border-bottom:1px solid #1f2a3d;">'
          + '<div><div style="color:#e5e7eb;font-weight:600;">' + escapeHtml(u.email) + '</div><div style="font-size:.76rem;color:#64748b;">' + escapeHtml(u.name || '') + ' · ' + active + '</div></div>'
          + '<button data-reset-user="' + escapeHtml(u.id) + '" style="padding:5px 8px;border:1px solid #334155;border-radius:5px;background:#111827;color:#cbd5e1;cursor:pointer;">Reset PW</button></div>';
      }).join('');
      Array.prototype.slice.call(box.querySelectorAll('[data-reset-user]')).forEach(function(btn) {
        btn.addEventListener('click', function() {
          var pw = window.prompt('New temporary password');
          if (!pw) return;
          adminFetch('/api/v1/user/admin/users/' + btn.getAttribute('data-reset-user') + '/password', { method:'PUT', body: JSON.stringify({ password: pw }) })
            .then(function() { btn.textContent = 'Reset'; })['catch'](function(err) { btn.textContent = err.message; });
        });
      });
    };

    var renderAudit = function(events) {
      var box = document.getElementById('syson-admin-audit');
      if (!events || !events.length) { box.textContent = 'No audit events yet.'; return; }
      box.innerHTML = events.slice(0, 50).map(function(ev) {
        return '<div style="display:grid;grid-template-columns:180px 1fr 90px;gap:10px;padding:7px 0;border-bottom:1px solid #1f2a3d;">'
          + '<span style="color:#64748b;">' + escapeHtml(ev.createdAt || '') + '</span><span style="color:#e5e7eb;">' + escapeHtml(ev.action || '') + ' → ' + escapeHtml(ev.targetType || '') + ':' + escapeHtml(ev.targetId || '') + '</span><span style="color:#94a3b8;">' + escapeHtml(ev.outcome || '') + '</span></div>';
      }).join('');
    };

    adminFetch('/api/v1/user/admin/users').then(renderUsers)['catch'](function(err) { document.getElementById('syson-admin-users').textContent = err.message; });
    adminFetch('/api/v1/user/admin/audit/events?limit=50').then(renderAudit)['catch'](function(err) { document.getElementById('syson-admin-audit').textContent = err.message; });

    document.getElementById('syson-admin-create-user').addEventListener('submit', function(e) {
      e.preventDefault();
      var msg = document.getElementById('syson-admin-create-msg');
      var payload = { email: document.getElementById('syson-admin-email').value.trim(), name: document.getElementById('syson-admin-name').value.trim(), password: document.getElementById('syson-admin-password').value, tenantId: state.tenantId, tenantRole: document.getElementById('syson-admin-role').value };
      if (!payload.email || !payload.password) { msg.style.color = '#f87171'; msg.textContent = 'Email and password required'; return; }
      adminFetch('/api/v1/user/admin/users', { method:'POST', body: JSON.stringify(payload) }).then(function() {
        msg.style.color = '#4ade80'; msg.textContent = 'Account created'; return adminFetch('/api/v1/user/admin/users').then(renderUsers);
      })['catch'](function(err) { msg.style.color = '#f87171'; msg.textContent = err.message; });
    });
  }

  // ── Boot ─────────────────────────────────────────────────────────────────
  loadState();

  if (state.token) {
    // Already authenticated — let the app load, mount user bar after render
    // Periodically refresh token (every 4 hours)
    setInterval(() => {
      refreshToken().catch(() => {
        clearState();
        window.location.reload();
      });
    }, 4 * 60 * 60 * 1000);
    // Mount user bar after DOM is ready
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', mountUserBar);
    } else {
      mountUserBar();
    }
  } else {
    // Not authenticated — show login and block app load
    blockApp();
    showLogin('');
  }
})();
