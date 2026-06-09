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

  function normalizeRoles(roles) {
    return (roles || []).map(function(role) { return String(role || '').toLowerCase(); });
  }

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) state = JSON.parse(raw);
      state.roles = normalizeRoles(state.roles);
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
      var payload = token.split('.')[1] || '';
      payload = payload.replace(/-/g, '+').replace(/_/g, '/');
      while (payload.length % 4) payload += '=';
      return JSON.parse(atob(payload));
    } catch (_) {
      return {};
    }
  }

  function normalizeGraphQLRequestBody(body) {
    try {
      if (typeof body !== 'string' || body.indexOf('createProject') === -1 || body.indexOf('CreateProjectInput') === -1) {
        return body;
      }
      var payload = JSON.parse(body);
      var input = payload && payload.variables && payload.variables.input;
      if (input && Object.prototype.hasOwnProperty.call(input, 'templateId') && !Object.prototype.hasOwnProperty.call(input, 'natures')) {
        input.natures = [];
        return JSON.stringify(payload);
      }
    } catch (_) {
      // Preserve the original request body on parse errors.
    }
    return body;
  }

  // ── HTTP Interceptor ─────────────────────────────────────────────────────
  const _origFetch = window.fetch;
  window.fetch = function (url, options) {
    if (state.token) {
      const opts = options || {};
      opts.headers = opts.headers || {};
      opts.body = normalizeGraphQLRequestBody(opts.body);
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
    state.roles = normalizeRoles(data.roles || []);
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
    state.email = data.email || state.email;
    state.roles = normalizeRoles(data.roles || state.roles || []);
    var claims = parseJWT(data.token);
    state.tenantId = claims.tenantId || state.tenantId || null;
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
      display: none; align-items: center; gap: 10px;
      color: #f8fafc; font-size: 0.82rem; line-height: 1;
      padding: 5px 10px; border-radius: 999px;
      background: rgba(15,23,42,0.82); border: 1px solid rgba(255,255,255,0.18);
      white-space: nowrap; margin-left: 12px; max-width: 48vw;
    }
    #syson-user-bar .syson-user-email { max-width: 190px; overflow: hidden; text-overflow: ellipsis; }
    #syson-user-bar .role-badge {
      display: inline-flex; align-items: center;
      background: #4a90d9; color: #fff; font-size: 0.68rem;
      padding: 3px 7px; border-radius: 999px; font-weight: 700;
      text-transform: uppercase;
    }
    #syson-user-bar .role-badge.superuser { background: #e67e22; }
    #syson-user-bar .role-badge.admin { background: #9b59b6; }
    #syson-logout-btn, #syson-dashboard-btn, #syson-admin-btn {
      background: rgba(255,255,255,0.06); border: 1px solid #64748b; color: #e2e8f0;
      padding: 4px 9px; border-radius: 999px; cursor: pointer;
      font-size: 0.72rem; line-height: 1; font-weight: 700;
    }
    #syson-dashboard-btn { border-color: #2563eb; color: #dbeafe; }
    #syson-admin-btn { border-color: #9b59b6; color: #f3e8ff; font-weight: 700; }
    #syson-logout-btn:hover, #syson-dashboard-btn:hover, #syson-admin-btn:hover { background: rgba(255,255,255,0.08); }
  `;

  function ensureAuthStyles() {
    if (document.getElementById('syson-auth-styles')) return;
    const styleEl = document.createElement('style');
    styleEl.id = 'syson-auth-styles';
    styleEl.textContent = STYLES;
    document.head.appendChild(styleEl);
  }

  function showLogin(errorMsg) {
    // auth.js is loaded in <head>, so body may not exist yet.
    // Keep the root blocker active, then render the login overlay once <body> exists.
    if (!document.body) {
      document.addEventListener('DOMContentLoaded', () => showLogin(errorMsg), { once: true });
      return;
    }

    // Inject styles
    ensureAuthStyles();

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

  function fixVisibleTranslationKeys() {
    var replacements = {
      'useProjectsTableColumns.name': 'Name',
      'projectsTable.actions': 'Actions'
    };
    function apply() {
      var walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT);
      var node;
      while ((node = walker.nextNode())) {
        var text = (node.nodeValue || '').trim();
        if (replacements[text]) node.nodeValue = node.nodeValue.replace(text, replacements[text]);
      }
    }
    if (!document.body) return;
    apply();
    if (!window.__sysonTranslationKeyObserver) {
      window.__sysonTranslationKeyObserver = new MutationObserver(apply);
      window.__sysonTranslationKeyObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
    }
  }

  function mountUserBar() {
    ensureAuthStyles();
    fixVisibleTranslationKeys();
    if (!state.email) return;

    function doMount() {
      // Find the best anchor for the user bar.
      // In the project browser: header / [class*="navigationBar"]
      // In the editor workbench: [class*="css-"] toolbar or the right-side
      //   icon cluster.  We try several selectors so the bar appears on
      //   every page (project list, editor, admin).
      var nav = document.querySelector('[class*="navigationBar"]')
             || document.querySelector('header [class*="toolbar"]')
             || document.querySelector('header')
             || document.querySelector('[class*="navbar"]');

      var bar = document.getElementById('syson-user-bar');
      if (!bar) {
        bar = document.createElement('div');
        bar.id = 'syson-user-bar';
      }

      // Always prefer attaching to a nav element.  If none found, use a
      // fixed floating position so the bar is never lost.
      if (nav && !nav.contains(bar)) {
        nav.appendChild(bar);
      } else if (!nav && !document.body.contains(bar)) {
        bar.style.position = 'fixed';
        bar.style.top = '10px';
        bar.style.right = '12px';
        bar.style.zIndex = '10000';
        bar.style.boxShadow = '0 8px 24px rgba(0,0,0,.25)';
        document.body.appendChild(bar);
      }

      var badgeHTML = state.roles.map(function(r) {
        return '<span class="role-badge ' + r.toLowerCase() + '">' + r + '</span>';
      }).join('');
      var adminButtonHTML = isSuperUser() ? '<button id="syson-admin-btn" title="Administration">Admin</button>' : '';
      bar.innerHTML =
        '<span class="syson-user-email">' + state.email + '</span>' +
        badgeHTML +
        '<button id="syson-dashboard-btn" title="Dashboard">Dashboard</button>' +
        adminButtonHTML +
        '<button id="syson-logout-btn" title="Sign out">Sign out</button>';
      bar.style.display = 'flex';
      document.getElementById('syson-logout-btn').addEventListener('click', logout);
      document.getElementById('syson-dashboard-btn').addEventListener('click', showDashboard);
      var adminBtn = document.getElementById('syson-admin-btn');
      if (adminBtn) adminBtn.addEventListener('click', showAdminConsole);
    }

    // Initial mount
    doMount();

    // Keep the user bar alive across React route transitions.
    // When the user navigates from /projects to /projects/:id/edit,
    // React replaces the DOM subtree, which removes our bar.
    // A MutationObserver on body re-mounts it automatically.
    var _userBarObserver = null;
    function startUserBarGuard() {
      if (_userBarObserver) return;
      _userBarObserver = new MutationObserver(function(mutations) {
        var bar = document.getElementById('syson-user-bar');
        if (!bar || !document.body.contains(bar)) {
          // Bar was removed by a React re-render — re-mount after a tick
          // so the new DOM is settled.
          setTimeout(doMount, 100);
        }
      });
      _userBarObserver.observe(document.body, { childList: true, subtree: true });
    }
    startUserBarGuard();
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

      var adminDashboardHTML = isSuperUser() ? '<div style="margin-bottom:1.5rem;padding-bottom:1.5rem;border-bottom:1px solid #2a2a4a;">'
        + '<h3 style="color:#aaa;font-size:0.8rem;text-transform:uppercase;margin:0 0 0.75rem;">Role Based Access Control</h3>'
        + '<p style="color:#888;font-size:0.82rem;margin:0 0 0.75rem;">Manage accounts, project access roles, password resets, and audit history.</p>'
        + '<button id="syson-access-management-btn" style="width:100%;padding:0.65rem;border-radius:6px;border:none;background:#7c3aed;color:#fff;font-size:0.9rem;font-weight:700;cursor:pointer;">Open Access Management</button>'
        + '</div>' : '';

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
        + adminDashboardHTML
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
      var accessBtn = document.getElementById('syson-access-management-btn');
      if (accessBtn) accessBtn.addEventListener('click', function() { overlay.remove(); showAdminConsole(); });
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

  function isSuperUser() {
    var roles = state.roles || [];
    return roles.some(function(role) {
      return String(role || '').toLowerCase() === 'superuser';
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
    if (!isSuperUser()) return;
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
      + '<form id="syson-admin-create-user"><input id="syson-admin-email" placeholder="email" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><input id="syson-admin-name" placeholder="name" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><input id="syson-admin-password" type="password" placeholder="temporary password" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><select id="syson-admin-role" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><option value="viewer">Viewer</option><option value="user">User</option><option value="admin">Admin</option><option value="superuser">Superuser</option></select><button style="width:100%;padding:9px;border:0;border-radius:6px;background:#2563eb;color:white;font-weight:700;cursor:pointer;">Create Account</button><div id="syson-admin-create-msg" style="min-height:18px;margin-top:8px;font-size:.8rem;"></div></form></section>'
      + '<section style="grid-column:1/-1;background:#0b1220;border:1px solid #1f2a3d;border-radius:10px;padding:14px;"><h3 style="margin:0 0 12px;font-size:.9rem;color:#cbd5e1;text-transform:uppercase;letter-spacing:.04em;">Project Access Management</h3>'
      + '<div style="display:grid;grid-template-columns:2fr 1fr auto;gap:8px;margin-bottom:10px;"><input id="syson-project-id" placeholder="Project ID" style="padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><button id="syson-load-project-members" style="padding:9px;border:0;border-radius:6px;background:#475569;color:white;font-weight:700;cursor:pointer;">Load Members</button><span id="syson-project-msg" style="align-self:center;color:#94a3b8;font-size:.8rem;"></span></div>'
      + '<div style="display:grid;grid-template-columns:2fr 2fr 1fr auto;gap:8px;margin-bottom:10px;"><select id="syson-project-user" style="padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"></select><input id="syson-project-user-id" placeholder="or paste user UUID" style="padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><select id="syson-project-role" style="padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><option value="viewer">Viewer</option><option value="user">User</option><option value="admin">Admin</option></select><button id="syson-grant-project-role" style="padding:9px;border:0;border-radius:6px;background:#7c3aed;color:white;font-weight:700;cursor:pointer;">Grant Role</button></div>'
      + '<div id="syson-project-members" style="font-size:.84rem;color:#94a3b8;">Enter a project ID to manage members.</div></section>'
      + '<section style="grid-column:1/-1;background:#0b1220;border:1px solid #1f2a3d;border-radius:10px;padding:14px;"><h3 style="margin:0 0 12px;font-size:.9rem;color:#cbd5e1;text-transform:uppercase;letter-spacing:.04em;">Project Settings</h3>'
      + '<div style="display:flex;align-items:center;gap:12px;margin-bottom:8px;">'
      + '<input type="checkbox" id="syson-toggle-locking" style="width:18px;height:18px;cursor:pointer;" />'
      + '<label for="syson-toggle-locking" style="font-size:.85rem;color:#cbd5e1;cursor:pointer;">Enable Element Locking (per-project)</label>'
      + '</div>'
      + '<div id="syson-locking-projects" style="font-size:.82rem;color:#94a3b8;">Select a project to toggle locking:</div>'
      + '<div id="syson-locking-project-list" style="margin-top:8px;"></div>'
      + '</section>'
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
        return '<div style="display:grid;grid-template-columns:1fr auto auto;gap:8px;align-items:center;padding:9px 0;border-bottom:1px solid #1f2a3d;">'
          + '<div><div style="color:#e5e7eb;font-weight:600;">' + escapeHtml(u.email) + '</div><div style="font-size:.76rem;color:#64748b;">' + escapeHtml(u.name || '') + ' · ' + active + '</div></div>'
          + '<button data-reset-user="' + escapeHtml(u.id) + '" style="padding:5px 8px;border:1px solid #334155;border-radius:5px;background:#111827;color:#cbd5e1;cursor:pointer;">Reset PW</button>'
          + '<button data-toggle-user="' + escapeHtml(u.id) + '" data-active="' + (u.active ? 'true' : 'false') + '" style="padding:5px 8px;border:1px solid #334155;border-radius:5px;background:#111827;color:#cbd5e1;cursor:pointer;">' + (u.active ? 'Deactivate' : 'Reactivate') + '</button></div>';
      }).join('');
      Array.prototype.slice.call(box.querySelectorAll('[data-reset-user]')).forEach(function(btn) {
        btn.addEventListener('click', function() {
          var pw = window.prompt('New temporary password');
          if (!pw) return;
          adminFetch('/api/v1/user/admin/users/' + btn.getAttribute('data-reset-user') + '/password', { method:'PUT', body: JSON.stringify({ password: pw }) })
            .then(function() { btn.textContent = 'Reset'; })['catch'](function(err) { btn.textContent = err.message; });
        });
      });
      Array.prototype.slice.call(box.querySelectorAll('[data-toggle-user]')).forEach(function(btn) {
        btn.addEventListener('click', function() {
          var userId = btn.getAttribute('data-toggle-user');
          var active = btn.getAttribute('data-active') === 'true';
          var action = active ? 'deactivate' : 'reactivate';
          adminFetch('/api/v1/user/admin/users/' + userId + '/' + action, { method:'PUT' })
            .then(function() { return adminFetch('/api/v1/user/admin/users').then(renderUsers); })['catch'](function(err) { btn.textContent = err.message; });
        });
      });
    };

    var renderAudit = function(data) {
      var box = document.getElementById('syson-admin-audit');
      var events = data && data.content ? data.content : (Array.isArray(data) ? data : []);
      if (!events.length) { box.textContent = 'No RBAC audit events yet.'; return; }
      box.innerHTML = events.slice(0, 50).map(function(ev) {
        var ts = ev.createdAt || '';
        if (ts.length > 19) ts = ts.substring(0, 19).replace('T', ' ');
        var changes = '';
        if (ev.oldValue || ev.newValue) {
          try {
            var old = ev.oldValue ? JSON.parse(ev.oldValue) : {};
            var nw = ev.newValue ? JSON.parse(ev.newValue) : {};
            if (old.role || nw.role) changes = 'role: ' + (old.role || '–') + ' → ' + (nw.role || '–');
            else if (old.active !== undefined || nw.active !== undefined) changes = 'active: ' + (old.active !== undefined ? old.active : '–') + ' → ' + (nw.active !== undefined ? nw.active : '–');
            else changes = JSON.stringify(nw).substring(0, 60);
          } catch(e) { changes = (ev.newValue || '').substring(0, 60); }
        }
        return '<div style="display:grid;grid-template-columns:140px 130px 1fr 1fr;gap:8px;padding:6px 0;border-bottom:1px solid #1f2a3d;font-size:.8rem;">'
          + '<span style="color:#64748b;">' + escapeHtml(ts) + '</span>'
          + '<span style="color:#60a5fa;">' + escapeHtml(ev.eventType || '') + '</span>'
          + '<span style="color:#e5e7eb;">' + escapeHtml(ev.actorEmail || '') + ' → ' + escapeHtml(ev.targetEmail || ev.targetId || '') + '</span>'
          + '<span style="color:#94a3b8;">' + escapeHtml(changes) + '</span></div>';
      }).join('');
    };

    var renderProjectMembers = function(members) {
      var box = document.getElementById('syson-project-members');
      if (!members || !members.length) { box.textContent = 'No project members found.'; return; }
      box.innerHTML = members.map(function(m) {
        return '<div style="display:grid;grid-template-columns:1fr 100px auto;gap:10px;align-items:center;padding:8px 0;border-bottom:1px solid #1f2a3d;">'
          + '<div><div style="color:#e5e7eb;font-weight:600;">' + escapeHtml(m.email || m.userId) + '</div><div style="font-size:.76rem;color:#64748b;">' + escapeHtml(m.userId) + '</div></div>'
          + '<span style="color:#cbd5e1;text-transform:uppercase;font-size:.76rem;">' + escapeHtml(m.role || '') + '</span>'
          + '<button data-revoke-project-user="' + escapeHtml(m.userId) + '" style="padding:5px 8px;border:1px solid #334155;border-radius:5px;background:#111827;color:#cbd5e1;cursor:pointer;">Revoke</button></div>';
      }).join('');
      Array.prototype.slice.call(box.querySelectorAll('[data-revoke-project-user]')).forEach(function(btn) {
        btn.addEventListener('click', function() {
          var projectId = document.getElementById('syson-project-id').value.trim();
          if (!projectId) return;
          adminFetch('/api/v1/user/admin/projects/' + encodeURIComponent(projectId) + '/members/' + btn.getAttribute('data-revoke-project-user'), { method:'DELETE' })
            .then(loadProjectMembers)['catch'](function(err) { document.getElementById('syson-project-msg').textContent = err.message; });
        });
      });
    };

    var loadProjectMembers = function() {
      var projectId = document.getElementById('syson-project-id').value.trim();
      var msg = document.getElementById('syson-project-msg');
      if (!projectId) { msg.textContent = 'Project ID required'; return Promise.resolve(); }
      msg.textContent = 'Loading…';
      return adminFetch('/api/v1/user/admin/projects/' + encodeURIComponent(projectId) + '/members')
        .then(function(members) { msg.textContent = ''; renderProjectMembers(members); })
        ['catch'](function(err) { msg.textContent = err.message; });
    };

    adminFetch('/api/v1/user/admin/users').then(function(users) {
      renderUsers(users);
      var select = document.getElementById('syson-project-user');
      if (select) {
        select.innerHTML = '<option value="">Select user…</option>' + (users || []).map(function(u) {
          return '<option value="' + escapeHtml(u.id) + '">' + escapeHtml(u.email) + '</option>';
        }).join('');
      }
    })['catch'](function(err) { document.getElementById('syson-admin-users').textContent = err.message; });
    adminFetch('/api/v1/user/admin/audit-trail?size=50').then(renderAudit)['catch'](function(err) { document.getElementById('syson-admin-audit').textContent = err.message; });

    // Project Settings: element locking toggle
    adminFetch('/api/v1/user/admin/users').then(function() {
      // Load projects for the locking toggle
      _origFetch('/api/v1/user/projects').then(function(r) { return r.ok ? r.json() : []; }).then(function(projects) {
        var list = document.getElementById('syson-locking-project-list');
        if (!list) return;
        if (!projects || !projects.length) { list.textContent = 'No projects found.'; return; }
        list.innerHTML = projects.map(function(p) {
          return '<div style="display:flex;align-items:center;gap:10px;padding:6px 0;border-bottom:1px solid #1f2a3d;">'
            + '<input type="checkbox" class="syson-project-lock-toggle" data-project-id="' + escapeHtml(p.id) + '" style="width:16px;height:16px;cursor:pointer;" />'
            + '<span style="color:#e5e7eb;font-size:.85rem;">' + escapeHtml(p.name || p.id) + '</span>'
            + '<span style="color:#64748b;font-size:.75rem;margin-left:auto;">' + escapeHtml(p.id).substring(0, 8) + '…</span>'
            + '</div>';
        }).join('');
        // Load current settings for each project
        projects.forEach(function(p) {
          _origFetch('/api/v1/projects/' + p.id + '/settings/element-locking').then(function(r) { return r.ok ? r.json() : { enabled: false }; })
            .then(function(s) {
              var cb = list.querySelector('[data-project-id="' + p.id + '"]');
              if (cb) cb.checked = !!s.enabled;
            }).catch(function() {});
        });
        // Handle toggle changes
        list.addEventListener('change', function(e) {
          if (!e.target.classList.contains('syson-project-lock-toggle')) return;
          var pid = e.target.getAttribute('data-project-id');
          var enabled = e.target.checked;
          _origFetch('/api/v1/projects/' + pid + '/settings/element-locking', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled: enabled })
          }).then(function(r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            e.target.parentElement.style.borderLeft = '3px solid ' + (enabled ? '#4ade80' : '#64748b');
            setTimeout(function() { e.target.parentElement.style.borderLeft = ''; }, 2000);
          }).catch(function(err) {
            e.target.checked = !enabled;
            alert('Failed to update: ' + err.message);
          });
        });
      }).catch(function() {});
    }).catch(function() {});

    document.getElementById('syson-load-project-members').addEventListener('click', loadProjectMembers);
    document.getElementById('syson-grant-project-role').addEventListener('click', function() {
      var projectId = document.getElementById('syson-project-id').value.trim();
      var selectedUser = document.getElementById('syson-project-user').value;
      var pastedUser = document.getElementById('syson-project-user-id').value.trim();
      var userId = pastedUser || selectedUser;
      var role = document.getElementById('syson-project-role').value;
      var msg = document.getElementById('syson-project-msg');
      if (!projectId || !userId) { msg.textContent = 'Project ID and user required'; return; }
      adminFetch('/api/v1/user/admin/projects/' + encodeURIComponent(projectId) + '/members', { method:'POST', body: JSON.stringify({ userId: userId, role: role }) })
        .then(loadProjectMembers)['catch'](function(err) { msg.textContent = err.message; });
    });

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

  function handleAdminDeepLink() {
    var href = String(window.location.href || '');
    if (isSuperUser() && (href.indexOf('sysonAdmin=1') !== -1 || href.indexOf('#/admin/access') !== -1 || href.indexOf('#/account/access-management') !== -1)) {
      setTimeout(showAdminConsole, 800);
    }
  }

  // ── Element History Button ────────────────────────────────────────────────
  // Injects a "📋 History" button into the Sirius properties panel header.
  // When clicked, fetches element change history and shows an overlay.

  function injectHistoryButton() {
    if (!state.token) return;
    // Watch for the properties panel to appear
    var observer = new MutationObserver(function(mutations) {
      // Look for the properties panel — it has a header with the element type name
      var propsPanel = document.querySelector('[data-testid="properties"], .properties-panel, [class*="PropertiesView"], [class*="properties-view"]');
      if (!propsPanel) {
        // Try alternative: look for the details/properties section in the right panel
        var panels = document.querySelectorAll('[class*="panel"], [class*="Panel"]');
        for (var i = 0; i < panels.length; i++) {
          var text = panels[i].textContent || '';
          if (text.indexOf('Properties') !== -1 && text.indexOf('Declared Name') !== -1) {
            propsPanel = panels[i];
            break;
          }
        }
      }
      if (!propsPanel) return;
      // Don't add duplicate
      if (propsPanel.querySelector('#syson-history-btn')) return;
      // Find the header area
      var header = propsPanel.querySelector('h3, h4, [class*="header"], [class*="Header"]');
      if (!header) header = propsPanel.firstElementChild;
      if (!header) return;
      // Create history button
      var btn = document.createElement('button');
      btn.id = 'syson-history-btn';
      btn.textContent = '📋 History';
      btn.title = 'View element change history';
      btn.style.cssText = 'margin-left:8px;padding:2px 8px;font-size:11px;background:#1a73e8;color:#fff;border:none;border-radius:3px;cursor:pointer;vertical-align:middle;';
      btn.addEventListener('click', function() { showElementHistory(); });
      header.appendChild(btn);
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  function getElementIdFromPanel() {
    // 1. Check URL search params (?objectId=...)
    var params = new URLSearchParams(window.location.search);
    var fromParam = params.get('objectId') || params.get('elementId') || params.get('selectedObjectId');
    if (fromParam) return fromParam;

    // 2. Check URL hash params (#...&objectId=...)
    var hash = window.location.hash || '';
    var hashMatch = hash.match(/objectId=([^&]+)/);
    if (hashMatch) return hashMatch[1];

    // 3. Check data attributes on details/properties panel
    var detailsPanel = document.querySelector('[data-testid="details"], [class*="DetailsView"], [class*="details-view"], [class*="PropertiesView"], [class*="properties-view"]');
    if (detailsPanel) {
      var allAttrs = detailsPanel.querySelectorAll('[data-elementid], [data-objectid]');
      if (allAttrs.length > 0) return allAttrs[0].getAttribute('data-elementid') || allAttrs[0].getAttribute('data-objectid');
    }

    // 4. Check React fiber on the properties/details panel for selected object
    var panel = detailsPanel || document.querySelector('[class*="PropertiesView"], [class*="properties-view"]');
    if (panel && typeof getReactFiber === 'function') {
      var fiber = getReactFiber(panel);
      if (fiber) {
        var f = fiber;
        for (var j = 0; j < 15 && f; j++) {
          var p = f.memoizedProps || f.pendingProps || {};
          if (p.selectedObjectId) return p.selectedObjectId;
          if (p.objectId) return p.objectId;
          if (p.editingContextId) return p.editingContextId;
          f = f.return;
        }
      }
    }

    // 5. UUID regex scan of details panel text (last resort)
    var panelText = (detailsPanel || document.body).textContent || '';
    var uuidMatch = panelText.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
    if (uuidMatch) return uuidMatch[0];

    return null;
  }

  function getProjectIdFromUrl() {
    var match = window.location.pathname.match(/\/projects\/([^/]+)/);
    return match ? match[1] : null;
  }

  function showElementHistory() {
    var projectId = getProjectIdFromUrl();
    var elementId = getElementIdFromPanel();
    if (!projectId || !elementId) {
      alert('Cannot determine project or element ID. Open a project and select an element first.');
      return;
    }
    // Fetch element history
    var url = '/api/v1/user/projects/' + projectId + '/elements/' + elementId + '/history';
    _origFetch(url).then(function(resp) {
      if (!resp.ok) throw new Error('HTTP ' + resp.status);
      return resp.json();
    }).then(function(data) {
      renderHistoryOverlay(data, projectId, elementId);
    }).catch(function(err) {
      alert('Failed to load element history: ' + err.message);
    });
  }

  function renderHistoryOverlay(data, projectId, elementId) {
    // Remove existing overlay
    var existing = document.getElementById('syson-history-overlay');
    if (existing) existing.remove();

    var overlay = document.createElement('div');
    overlay.id = 'syson-history-overlay';
    overlay.style.cssText = 'position:fixed;top:0;right:0;width:480px;height:100vh;background:#1a1a2e;color:#e0e0e0;z-index:10001;overflow-y:auto;box-shadow:-4px 0 20px rgba(0,0,0,0.5);font-family:system-ui,sans-serif;';

    var header = document.createElement('div');
    header.style.cssText = 'padding:16px;background:#16213e;border-bottom:1px solid #333;display:flex;justify-content:space-between;align-items:center;';
    header.innerHTML = '<div><strong style="font-size:14px;">📋 Element History</strong><br><span style="font-size:11px;color:#888;">' + elementId.substring(0, 30) + '...</span></div>';

    var closeBtn = document.createElement('button');
    closeBtn.textContent = '×';
    closeBtn.style.cssText = 'background:none;border:none;color:#fff;font-size:20px;cursor:pointer;padding:4px 8px;';
    closeBtn.addEventListener('click', function() { overlay.remove(); });
    header.appendChild(closeBtn);
    overlay.appendChild(header);

    var content = document.createElement('div');
    content.style.cssText = 'padding:16px;';

    var history = data.history || [];
    if (history.length === 0) {
      content.innerHTML = '<p style="color:#888;text-align:center;margin-top:40px;">No change history found for this element.</p>';
    } else {
      content.innerHTML = '<p style="color:#888;font-size:12px;margin-bottom:12px;">' + history.length + ' version(s) found</p>';
      for (var i = 0; i < history.length; i++) {
        var entry = history[i];
        var op = entry.operation || 'unknown';
        var color = op === 'create' ? '#4caf50' : op === 'delete' ? '#f44336' : '#ff9800';
        var card = document.createElement('div');
        card.style.cssText = 'background:#16213e;border-left:3px solid ' + color + ';padding:10px 12px;margin-bottom:8px;border-radius:0 4px 4px 0;';
        card.innerHTML =
          '<div style="display:flex;justify-content:space-between;align-items:center;">' +
            '<span style="color:' + color + ';font-weight:bold;text-transform:uppercase;font-size:11px;">' + op + '</span>' +
            '<span style="color:#666;font-size:10px;">' + (entry.committedAt || '') + '</span>' +
          '</div>' +
          '<div style="font-size:12px;margin-top:4px;">' +
            '<span style="color:#aaa;">Branch:</span> ' + (entry.branchName || 'main') +
            (entry.author ? ' &nbsp;<span style="color:#aaa;">Author:</span> ' + entry.author : '') +
          '</div>' +
          (entry.changedFields ? '<div style="font-size:11px;margin-top:4px;color:#888;">Changed: ' + entry.changedFields + '</div>' : '') +
          (entry.message ? '<div style="font-size:11px;margin-top:2px;color:#666;">' + entry.message + '</div>' : '');
        content.appendChild(card);
      }
    }
    overlay.appendChild(content);
    document.body.appendChild(overlay);
  }

  // ── Element Locking ──────────────────────────────────────────────────────
  // Blue text for lockable elements, red/gray for locked-by-others,
  // right-click context menu for Lock/Unlock.

  var _lockCache = {};           // stableId -> lock object
  var _lockPollTimer = null;
  var _currentUserId = null;     // set from JWT
  var _lockingEnabled = false;   // per-project setting

  function getCurrentUserId() {
    if (_currentUserId) return _currentUserId;
    try {
      var payload = JSON.parse(atob(state.token.split('.')[1]));
      _currentUserId = payload.sub || payload.userId || payload.id || null;
      return _currentUserId;
    } catch (e) { return null; }
  }

  function fetchProjectLocks(projectId) {
    if (!projectId || !state.token || !_lockingEnabled) return;
    _origFetch('/api/v1/projects/' + projectId + '/element-locks')
      .then(function(r) { return r.ok ? r.json() : []; })
      .then(function(locks) {
        _lockCache = {};
        for (var i = 0; i < locks.length; i++) {
          _lockCache[locks[i].stableId] = locks[i];
        }
        applyLockStyles();
      })
      .catch(function() {});
  }

  function applyLockStyles() {
    if (!_lockingEnabled) return; // locking disabled — leave default styles
    var myId = getCurrentUserId();
    // Find all tree items in the explorer
    var treeItems = document.querySelectorAll('[class*="tree-item"], [class*="TreeItem"], [class*="treeNode"], [role="treeitem"]');
    for (var i = 0; i < treeItems.length; i++) {
      var item = treeItems[i];
      // Try to extract stable ID from data attributes or text
      var stableId = item.getAttribute('data-stable-id') || item.getAttribute('data-elementid') || item.getAttribute('data-node-id');
      if (!stableId) {
        // Try from React fiber internals
        var fiber = getReactFiber(item);
        if (fiber) {
          var props = fiber.memoizedProps || fiber.pendingProps || {};
          stableId = props['data-stable-id'] || props.id || props.nodeId || null;
          // Try traversing up the fiber tree for node data
          if (!stableId) {
            var f = fiber;
            for (var j = 0; j < 8 && f; j++) {
              var p = f.memoizedProps || f.pendingProps || {};
              if (p.node && p.node.id) { stableId = p.node.id; break; }
              if (p.objectId) { stableId = p.objectId; break; }
              if (p.item && p.item.id) { stableId = p.item.id; break; }
              f = f.return;
            }
          }
        }
      }
      if (!stableId) continue;

      var lock = _lockCache[stableId];
      // Reset styles first
      item.style.color = '';

      if (lock) {
        if (lock.ownerUserId === myId) {
          // Locked by current user — subtle green indicator
          item.style.color = '#4caf50';
          item.title = '🔒 Locked by you';
        } else {
          // Locked by another user — red/gray
          item.style.color = '#ef5350';
          item.title = '🔒 Locked by ' + (lock.ownerUsername || 'another user');
        }
      } else {
        // Available for locking — blue text
        item.style.color = '#42a5f5';
        item.title = 'Available — right-click to lock';
      }
    }
  }

  function getReactFiber(el) {
    for (var key in el) {
      if (key.startsWith('__reactFiber$') || key.startsWith('__reactInternalInstance$')) {
        return el[key];
      }
    }
    return null;
  }

  function injectLockContextMenu() {
    // Remove existing lock menu if any
    document.addEventListener('contextmenu', function(e) {
      var target = e.target.closest('[class*="tree-item"], [class*="TreeItem"], [class*="treeNode"], [role="treeitem"]');
      if (!target) return;

      var projectId = getProjectIdFromUrl();
      if (!projectId) return;

      // Extract stableId
      var stableId = target.getAttribute('data-stable-id') || target.getAttribute('data-elementid') || target.getAttribute('data-node-id');
      if (!stableId) {
        var fiber = getReactFiber(target);
        if (fiber) {
          var f = fiber;
          for (var j = 0; j < 8 && f; j++) {
            var p = f.memoizedProps || f.pendingProps || {};
            if (p.node && p.node.id) { stableId = p.node.id; break; }
            if (p.objectId) { stableId = p.objectId; break; }
            if (p.item && p.item.id) { stableId = p.item.id; break; }
            f = f.return;
          }
        }
      }
      if (!stableId) return;

      e.preventDefault();
      e.stopPropagation();

      // Remove any existing menu
      var old = document.getElementById('syson-lock-menu');
      if (old) old.remove();

      var myId = getCurrentUserId();
      var lock = _lockCache[stableId];
      var isMine = lock && lock.ownerUserId === myId;
      var isOthers = lock && lock.ownerUserId !== myId;

      var menu = document.createElement('div');
      menu.id = 'syson-lock-menu';
      menu.style.cssText = 'position:fixed;z-index:10002;background:#1e293b;border:1px solid #475569;border-radius:6px;box-shadow:0 4px 16px rgba(0,0,0,0.5);min-width:180px;font-family:system-ui,sans-serif;font-size:13px;';

      var items = [];
      if (!_lockingEnabled) {
        // Locking disabled — show grayed-out options
        items.push({ label: '🔒 Lock Element', action: null, disabled: true });
        items.push({ label: '🔒 Lock Element Tree', action: null, disabled: true });
        items.push({ label: '(Element locking disabled in project settings)', action: null, disabled: true });
      } else if (!lock) {
        items.push({ label: '🔒 Lock Element', action: function() { lockElement(projectId, stableId); } });
        items.push({ label: '🔒 Lock Element Tree', action: function() { lockElementRecursive(projectId, stableId); } });
      } else if (isMine) {
        items.push({ label: '🔓 Unlock Element', action: function() { unlockElement(projectId, stableId); } });
        items.push({ label: '🔓 Unlock Element Tree', action: function() { unlockElementRecursive(projectId, stableId); } });
        items.push({ label: '🔒 Lock Status: Locked by you', action: null, disabled: true });
      } else {
        items.push({ label: '🔒 Locked by ' + (lock.ownerUsername || 'other'), action: null, disabled: true });
        items.push({ label: '⏰ Expires: ' + (lock.expiresAt ? new Date(lock.expiresAt).toLocaleTimeString() : 'N/A'), action: null, disabled: true });
      }

      // Add History option
      items.push({ label: '📋 View History', action: function() { showElementHistoryById(projectId, stableId); } });

      for (var i = 0; i < items.length; i++) {
        var item = items[i];
        var row = document.createElement('div');
        row.textContent = item.label;
        row.style.cssText = 'padding:8px 14px;cursor:' + (item.disabled ? 'default' : 'pointer') + ';color:' + (item.disabled ? '#64748b' : '#e2e8f0') + ';';
        if (!item.disabled && item.action) {
          row.addEventListener('mouseenter', function() { this.style.background = '#334155'; });
          row.addEventListener('mouseleave', function() { this.style.background = 'transparent'; });
          row.addEventListener('click', (function(fn, m) { return function() { m.remove(); fn(); }; })(item.action, menu));
        }
        menu.appendChild(row);
      }

      // Position near cursor
      menu.style.left = Math.min(e.clientX, window.innerWidth - 200) + 'px';
      menu.style.top = Math.min(e.clientY, window.innerHeight - 150) + 'px';
      document.body.appendChild(menu);

      // Close on click elsewhere
      setTimeout(function() {
        document.addEventListener('click', function closeMenu() {
          menu.remove();
          document.removeEventListener('click', closeMenu);
        }, { once: true });
      }, 50);
    }, true); // capture phase
  }

  function lockElement(projectId, stableId) {
    var branchId = getBranchIdFromUrl() || '00000000-0000-0000-0000-000000000000';
    _origFetch('/api/v1/projects/' + projectId + '/elements/' + stableId + '/lock', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ branchId: branchId, reason: 'Editing', ttlMinutes: 120, sessionId: 'web', deviceId: 'browser' })
    }).then(function(r) {
      if (r.status === 409) return r.json().then(function(d) { alert('Cannot lock: ' + (d.error || 'Already locked')); });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function() {
      fetchProjectLocks(projectId);
    }).catch(function(err) { alert('Lock failed: ' + err.message); });
  }

  function unlockElement(projectId, stableId) {
    var branchId = getBranchIdFromUrl() || '00000000-0000-0000-0000-000000000000';
    _origFetch('/api/v1/projects/' + projectId + '/elements/' + stableId + '/lock?branchId=' + branchId, {
      method: 'DELETE'
    }).then(function(r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      fetchProjectLocks(projectId);
    }).catch(function(err) { alert('Unlock failed: ' + err.message); });
  }

  function lockElementRecursive(projectId, stableId) {
    var branchId = getBranchIdFromUrl() || '00000000-0000-0000-0000-000000000000';
    _origFetch('/api/v1/projects/' + projectId + '/elements/' + stableId + '/lock-recursive', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ branchId: branchId, reason: 'Recursive lock', ttlMinutes: 120, sessionId: 'web', deviceId: 'browser' })
    }).then(function(r) {
      if (r.status === 409) return r.json().then(function(d) {
        var msg = 'Cannot lock tree:\n';
        var conflicts = d.conflicts || [];
        for (var i = 0; i < conflicts.length; i++) {
          msg += '• ' + (conflicts[i].stableId || '').substring(0, 20) + '... locked by ' + (conflicts[i].lockedBy || 'unknown') + '\n';
        }
        alert(msg);
        throw new Error('conflict');
      });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function(data) {
      fetchProjectLocks(projectId);
      alert('Locked ' + (data.lockedCount || 0) + ' element(s) in tree.');
    }).catch(function(err) { if (err.message !== 'conflict') alert('Recursive lock failed: ' + err.message); });
  }

  function unlockElementRecursive(projectId, stableId) {
    var branchId = getBranchIdFromUrl() || '00000000-0000-0000-0000-000000000000';
    _origFetch('/api/v1/projects/' + projectId + '/elements/' + stableId + '/lock-recursive?branchId=' + branchId, {
      method: 'DELETE'
    }).then(function(r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function(data) {
      fetchProjectLocks(projectId);
      alert('Unlocked ' + (data.released || 0) + ' element(s) in tree.');
    }).catch(function(err) { alert('Recursive unlock failed: ' + err.message); });
  }

  function showElementHistoryById(projectId, stableId) {
    _origFetch('/api/v1/user/projects/' + projectId + '/elements/' + stableId + '/history')
      .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function(data) { renderHistoryOverlay(data, projectId, stableId); })
      .catch(function(err) { alert('Failed to load history: ' + err.message); });
  }

  function getBranchIdFromUrl() {
    var hash = window.location.hash || '';
    var match = hash.match(/branchId=([^&]+)/);
    return match ? match[1] : null;
  }

  // Auto-unlock on save: hook into beforeunload or intercept save actions
  function setupAutoUnlockOnSave() {
    // Listen for Ctrl+S
    document.addEventListener('keydown', function(e) {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        var projectId = getProjectIdFromUrl();
        if (projectId && Object.keys(_lockCache).length > 0) {
          var branchId = getBranchIdFromUrl() || '00000000-0000-0000-0000-000000000000';
          _origFetch('/api/v1/projects/' + projectId + '/element-locks/release-all', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ branchId: branchId })
          }).then(function() { fetchProjectLocks(projectId); }).catch(function() {});
        }
      }
    });
  }

  // ── Read-Only Enforcement for Locked Elements ───────────────────────────
  // When an element is locked by another user, the properties panel becomes
  // read-only: input fields disabled, edit controls greyed out, banner shown.

  var _readOnlyStyle = null;

  function enforceReadOnlyForLockedElements() {
    if (!state.token) return;
    // Watch for properties panel updates
    var observer = new MutationObserver(function() {
      applyReadOnlyState();
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  function applyReadOnlyState() {
    var myId = getCurrentUserId();
    var projectId = getProjectIdFromUrl();
    if (!projectId || !myId) return;

    // Remove existing read-only banner
    var existingBanner = document.getElementById('syson-readonly-banner');
    if (existingBanner) existingBanner.remove();

    // Get the currently selected element ID
    var elementId = getElementIdFromPanel();
    if (!elementId) return;

    // Check if this element is locked by another user
    var lock = _lockCache[elementId];
    if (!lock || lock.ownerUserId === myId) {
      // Not locked or locked by us — restore normal state
      removeReadOnlyMode();
      return;
    }

    // Element is locked by another user — enforce read-only
    var lockOwner = lock.ownerUsername || 'another user';
    var lockExpiry = lock.expiresAt ? new Date(lock.expiresAt).toLocaleTimeString() : 'N/A';

    // Inject read-only banner into properties panel
    var propsPanel = document.querySelector('[class*="PropertiesView"], [class*="properties-view"], [data-testid="properties"]');
    if (!propsPanel) {
      var panels = document.querySelectorAll('[class*="panel"], [class*="Panel"]');
      for (var i = 0; i < panels.length; i++) {
        var text = panels[i].textContent || '';
        if (text.indexOf('Properties') !== -1 || text.indexOf('Declared Name') !== -1) {
          propsPanel = panels[i];
          break;
        }
      }
    }

    if (propsPanel && !document.getElementById('syson-readonly-banner')) {
      var banner = document.createElement('div');
      banner.id = 'syson-readonly-banner';
      banner.style.cssText = 'background:#7c2d12;color:#fed7aa;padding:6px 12px;font-size:11px;font-family:system-ui,sans-serif;border-bottom:1px solid #9a3412;display:flex;align-items:center;gap:6px;';
      banner.innerHTML = '🔒 <strong>Read-only</strong> — locked by ' + escapeHtml(lockOwner) + ' (expires ' + lockExpiry + ')';
      // Insert banner at the top of the properties panel
      if (propsPanel.firstChild) {
        propsPanel.insertBefore(banner, propsPanel.firstChild);
      } else {
        propsPanel.appendChild(banner);
      }
    }

    // Disable all input fields and edit controls in the properties panel
    applyReadOnlyMode();
  }

  function applyReadOnlyMode() {
    if (_readOnlyStyle) return; // already applied
    _readOnlyStyle = document.createElement('style');
    _readOnlyStyle.id = 'syson-readonly-style';
    _readOnlyStyle.textContent = ''
      + '#syson-readonly-banner ~ * input,'
      + '#syson-readonly-banner ~ * textarea,'
      + '#syson-readonly-banner ~ * select,'
      + '#syson-readonly-banner ~ * [contenteditable="true"] {'
      + '  pointer-events: none !important;'
      + '  opacity: 0.6 !important;'
      + '  background: #1e293b !important;'
      + '  color: #94a3b8 !important;'
      + '  cursor: not-allowed !important;'
      + '}'
      + '#syson-readonly-banner ~ * button:not(#syson-history-btn):not(#syson-readonly-banner button) {'
      + '  pointer-events: none !important;'
      + '  opacity: 0.5 !important;'
      + '  cursor: not-allowed !important;'
      + '}'
      // Also disable Monaco editor if present
      + '#syson-readonly-banner ~ * .monaco-editor {'
      + '  opacity: 0.6 !important;'
      + '}'
      + '#syson-readonly-banner ~ * .monaco-editor .view-lines {'
      + '  pointer-events: none !important;'
      + '}';
    document.head.appendChild(_readOnlyStyle);
  }

  function removeReadOnlyMode() {
    if (_readOnlyStyle) {
      _readOnlyStyle.remove();
      _readOnlyStyle = null;
    }
  }

  function escapeHtml(str) {
    var div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  }

  function injectElementLockUI() {
    if (!state.token) return;
    var projectId = getProjectIdFromUrl();
    if (!projectId) return;

    // Check if element locking is enabled for this project
    _origFetch('/api/v1/projects/' + projectId + '/settings/element-locking')
      .then(function(r) { return r.ok ? r.json() : { enabled: false }; })
      .then(function(setting) {
        _lockingEnabled = !!setting.enabled;
        if (!_lockingEnabled) {
          // Locking disabled — only set up context menu (for grayed-out items)
          injectLockContextMenu();
          return;
        }
        // Locking enabled — full setup
        fetchProjectLocks(projectId);
        _lockPollTimer = setInterval(function() { fetchProjectLocks(projectId); }, 30000);
        var treeObserver = new MutationObserver(function() {
          setTimeout(applyLockStyles, 200);
        });
        treeObserver.observe(document.body, { childList: true, subtree: true });
        injectLockContextMenu();
        setupAutoUnlockOnSave();
        enforceReadOnlyForLockedElements();
      })
      .catch(function() {
        // On error, assume disabled
        _lockingEnabled = false;
        injectLockContextMenu();
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
      document.addEventListener('DOMContentLoaded', function() { mountUserBar(); handleAdminDeepLink(); injectHistoryButton(); injectElementLockUI(); });
    } else {
      mountUserBar();
      handleAdminDeepLink();
      injectHistoryButton();
      injectElementLockUI();
    }
  } else {
    // Not authenticated — show login and block app load
    blockApp();
    showLogin('');
  }
})();
