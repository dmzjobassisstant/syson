/**
 * SysMLv2 Architect — RBAC login overlay + JWT interceptor.
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
      background: #261e58;
      font-family: 'Roboto', 'Helvetica Neue', Arial, sans-serif;
    }
    #syson-auth-card {
      background: #fff; border-radius: 4px; padding: 2.5rem 2rem;
      width: 100%; max-width: 400px; box-shadow: 0 2px 4px rgba(0,0,0,0.2), 0 8px 16px rgba(0,0,0,0.15);
    }
    #syson-auth-card h1 {
      color: #292253; font-size: 1.5rem; font-weight: 600;
      margin: 0 0 0.25rem; text-align: center;
    }
    #syson-auth-card .subtitle {
      color: #6b6b8d; font-size: 0.85rem; text-align: center; margin-bottom: 1.5rem;
    }
    #syson-auth-card label {
      display: block; color: #292253; font-size: 0.8rem;
      margin-bottom: 0.3rem; font-weight: 500;
    }
    #syson-auth-card input {
      width: 100%; padding: 0.7rem 0.8rem; border-radius: 6px;
      border: 1px solid #c9c4eb; background: #f1f0fa; color: #292253;
      font-size: 0.95rem; margin-bottom: 1rem; box-sizing: border-box;
      transition: border-color 0.2s;
    }
    #syson-auth-card input:focus {
      outline: none; border-color: #261e58;
    }
    #syson-auth-card button {
      width: 100%; padding: 0.75rem; border-radius: 6px; border: none;
      background: #261e58; color: #fff; font-size: 1rem; font-weight: 600;
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
      background: #261e58; color: #fff; font-size: 0.68rem;
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
        <h1>SysMLv2 Architect</h1>
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
      // Change document title from "SysON" to "SysMLv2 Architect"
      if (document.title !== 'SysMLv2 Architect') {
        document.title = 'SysMLv2 Architect';
      }

      // Replace native "SysMLv2" branding in the header with "SysMLv2 Architect"
      var nativeTitle = document.querySelector('[class*="navigationBar"] [class*="title"]')
                     || document.querySelector('[class*="navigationBar"] h1')
                     || document.querySelector('[class*="navigationBar"] span');
      if (nativeTitle && /sysmlv2/i.test(nativeTitle.textContent) && !/architect/i.test(nativeTitle.textContent)) {
        nativeTitle.textContent = 'SysMLv2 Architect';
      }

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

      // Always force these layout properties on every mount/update; the SPA may
      // retain #syson-user-bar across route changes and auth.js refreshes.
      bar.style.position = 'fixed';
      bar.style.top = '4px';
      // Original header placement, offset left by exactly 1/15 viewport width.
      bar.style.left = 'calc(280px - 6.6667vw)';
      bar.style.right = 'auto';
      bar.style.zIndex = '10000';
      bar.style.boxShadow = '0 2px 8px rgba(0,0,0,.15)';
      if (!document.body.contains(bar)) {
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
    overlay.style.cssText = 'position:fixed;inset:0;z-index:100000;display:flex;align-items:center;justify-content:center;background:rgba(38,30,88,0.65);font-family:Roboto,Helvetica Neue,Arial,sans-serif;';

    var headers = { 'Authorization': 'Bearer ' + state.token };

    var showOverlay = function(userData, projectsData) {
      var email = (userData && userData.email) || state.email || 'N/A';
      var name = (userData && userData.name) || '-';
      var roles = state.roles || [];
      var projHTML = '';
      if (projectsData && projectsData.length) {
        projHTML = projectsData.map(function(p) {
          var pid = p.projectId || '';
          var pname = p.projectName || pid;
          // Truncate long names
          var displayName = pname.length > 35 ? pname.substring(0,32) + '…' : pname;
          return '<div style="display:flex;justify-content:space-between;align-items:center;padding:8px 12px;border-radius:6px;background:rgba(255,255,255,0.03);margin-bottom:4px;">'
            + '<div><span style="color:#e0e0e0;font-size:0.85rem;font-weight:500;">' + escapeHtml(displayName) + '</span>'
            + '<br><span style="color:#555;font-size:0.68rem;font-family:monospace;">' + pid.substring(0,16) + '…</span></div>'
            + '<div style="display:flex;gap:6px;align-items:center;">'
            + '<button class="syson-vc-btn" data-project-id="' + escapeHtml(pid) + '" style="padding:3px 8px;border:1px solid #3b82f6;border-radius:4px;background:transparent;color:#3b82f6;font-size:.68rem;cursor:pointer;font-weight:600;">🔀 VC</button>'
            + '<span style="background:#261e58;color:#fff;font-size:0.68rem;padding:2px 8px;border-radius:4px;font-weight:600;text-transform:uppercase;">' + (p.role || '') + '</span>'
            + '</div></div>';
        }).join('');
      } else {
        projHTML = '<p style="color:#666;font-size:0.82rem;">No projects assigned.</p>';
      }

      var adminDashboardHTML = isSuperUser() ? '<div style="margin-bottom:1.5rem;padding-bottom:1.5rem;border-bottom:1px solid #2a2a4a;">'
        + '<h3 style="color:#aaa;font-size:0.8rem;text-transform:uppercase;margin:0 0 0.75rem;">Role Based Access Control</h3>'
        + '<p style="color:#888;font-size:0.82rem;margin:0 0 0.75rem;">Manage accounts, project access roles, password resets, and audit history.</p>'
        + '<button id="syson-access-management-btn" style="width:100%;padding:0.65rem;border-radius:6px;border:none;background:#be1a78;color:#fff;font-size:0.9rem;font-weight:700;cursor:pointer;">Open Access Management</button>'
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
        + '<button type="submit" style="width:100%;padding:0.6rem;border-radius:6px;border:none;background:#261e58;color:#fff;font-size:0.9rem;font-weight:600;cursor:pointer;">Update Password</button>'
        + '<div id="syson-pw-msg" style="color:#4caf50;font-size:0.8rem;text-align:center;margin-top:0.5rem;min-height:1.2em;"></div>'
        + '</form></div>'
        + '<div><h3 style="color:#aaa;font-size:0.8rem;text-transform:uppercase;margin:0 0 0.75rem;">My Projects</h3>' + projHTML + '</div>'
        + '</div>';

      document.body.appendChild(overlay);

      document.getElementById('syson-dash-close').addEventListener('click', function() { overlay.remove(); });
      var accessBtn = document.getElementById('syson-access-management-btn');
      if (accessBtn) accessBtn.addEventListener('click', function() { overlay.remove(); showAdminConsole(); });
      overlay.addEventListener('click', function(e) { if (e.target === overlay) overlay.remove(); });

      // VC buttons in project list
      Array.prototype.slice.call(overlay.querySelectorAll('.syson-vc-btn')).forEach(function(btn) {
        btn.addEventListener('click', function() {
          var pid = btn.getAttribute('data-project-id');
          if (pid) { overlay.remove(); showProjectVC(pid); }
        });
      });

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
    overlay.style.cssText = 'position:fixed;inset:0;z-index:100001;display:flex;align-items:center;justify-content:center;background:rgba(38,30,88,0.65);font-family:Roboto,Helvetica Neue,Arial,sans-serif;';
    overlay.innerHTML = '<div style="background:#111827;border:1px solid #24324a;border-radius:14px;width:min(1100px,94vw);max-height:86vh;overflow:auto;box-shadow:0 18px 60px rgba(0,0,0,.55);color:#e5e7eb;">'
      + '<div style="display:flex;justify-content:space-between;align-items:center;padding:18px 22px;border-bottom:1px solid #24324a;">'
      + '<div><h2 style="margin:0;font-size:1.15rem;">Access Administration</h2><p style="margin:4px 0 0;color:#8b98aa;font-size:.82rem;">Accounts, password resets, project roles, and audit history</p></div>'
      + '<button id="syson-admin-close" style="background:none;border:none;color:#8b98aa;font-size:1.6rem;cursor:pointer;">×</button></div>'
      + '<div style="display:grid;grid-template-columns:1fr 1fr;gap:18px;padding:20px;">'
      + '<section style="background:#0b1220;border:1px solid #1f2a3d;border-radius:10px;padding:14px;"><h3 style="margin:0 0 12px;font-size:.9rem;color:#cbd5e1;text-transform:uppercase;letter-spacing:.04em;">Users</h3><div id="syson-admin-users" style="font-size:.84rem;color:#94a3b8;">Loading users…</div></section>'
      + '<section style="background:#0b1220;border:1px solid #1f2a3d;border-radius:10px;padding:14px;"><h3 style="margin:0 0 12px;font-size:.9rem;color:#cbd5e1;text-transform:uppercase;letter-spacing:.04em;">Create User</h3>'
      + '<form id="syson-admin-create-user"><input id="syson-admin-email" placeholder="email" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><input id="syson-admin-name" placeholder="name" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><input id="syson-admin-password" type="password" placeholder="temporary password" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><select id="syson-admin-role" style="width:100%;box-sizing:border-box;margin-bottom:8px;padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><option value="viewer">Viewer</option><option value="editor">Editor</option><option value="admin">Admin</option><option value="superuser">Superuser</option></select><button style="width:100%;padding:9px;border:0;border-radius:6px;background:#261e58;color:white;font-weight:700;cursor:pointer;">Create Account</button><div id="syson-admin-create-msg" style="min-height:18px;margin-top:8px;font-size:.8rem;"></div></form></section>'
      + '<section style="grid-column:1/-1;background:#0b1220;border:1px solid #1f2a3d;border-radius:10px;padding:14px;"><h3 style="margin:0 0 12px;font-size:.9rem;color:#cbd5e1;text-transform:uppercase;letter-spacing:.04em;">Project Access Management</h3>'
      + '<div style="display:grid;grid-template-columns:2fr 1fr auto;gap:8px;margin-bottom:10px;"><input id="syson-project-id" placeholder="Project ID" style="padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><button id="syson-load-project-members" style="padding:9px;border:0;border-radius:6px;background:#475569;color:white;font-weight:700;cursor:pointer;">Load Members</button><span id="syson-project-msg" style="align-self:center;color:#94a3b8;font-size:.8rem;"></span></div>'
      + '<div style="display:grid;grid-template-columns:2fr 2fr 1fr auto;gap:8px;margin-bottom:10px;"><select id="syson-project-user" style="padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"></select><input id="syson-project-user-id" placeholder="or paste user UUID" style="padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><select id="syson-project-role" style="padding:9px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;"><option value="viewer">Viewer</option><option value="editor">Editor</option><option value="admin">Admin</option></select><button id="syson-grant-project-role" style="padding:9px;border:0;border-radius:6px;background:#be1a78;color:white;font-weight:700;cursor:pointer;">Grant Role</button></div>'
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
      + '<section style="grid-column:1/-1;background:#0b1220;border:1px solid #1f2a3d;border-radius:10px;padding:14px;"><h3 style="margin:0 0 12px;font-size:.9rem;color:#cbd5e1;text-transform:uppercase;letter-spacing:.04em;">Version Control</h3>'
      + '<p style="color:#64748b;font-size:.82rem;margin:0 0 10px;">View branches, commits, baselines, tags, and manage default branch per project.</p>'
      + '<div style="display:flex;gap:8px;align-items:center;margin-bottom:10px;"><input id="syson-vc-project-id" placeholder="Project ID" style="flex:1;padding:8px;border-radius:6px;border:1px solid #334155;background:#020617;color:#e5e7eb;font-size:.82rem;" /><button id="syson-vc-open" style="padding:8px 16px;border:0;border-radius:6px;background:#3b82f6;color:white;font-weight:700;font-size:.82rem;cursor:pointer;">Open VC</button></div>'
      + '<div id="syson-vc-project-list" style="font-size:.82rem;color:#94a3b8;">Enter a project ID or click "Open VC" from the Dashboard.</div>'
      + '</section>'
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
      _origFetch(API_BASE + '/api/v1/user/me/projects', { headers: { 'Authorization': 'Bearer ' + state.token } }).then(function(r) { return r.ok ? r.json() : []; }).then(function(projects) {
        var list = document.getElementById('syson-locking-project-list');
        if (!list) return;
        if (!projects || !projects.length) { list.textContent = 'No projects found.'; return; }
        list.innerHTML = projects.map(function(p) {
          var pid = p.projectId || p.id || '';
          var pname = p.projectName || p.name || pid;
          return '<div style="display:flex;align-items:center;gap:10px;padding:6px 0;border-bottom:1px solid #1f2a3d;">'
            + '<input type="checkbox" class="syson-project-lock-toggle" data-project-id="' + escapeHtml(pid) + '" style="width:16px;height:16px;cursor:pointer;" />'
            + '<span style="color:#e5e7eb;font-size:.85rem;">' + escapeHtml(pname) + '</span>'
            + '<span style="color:#64748b;font-size:.75rem;margin-left:auto;">' + escapeHtml(pid).substring(0, 8) + '…</span>'
            + '</div>';
        }).join('');
        // Load current settings for each project
        projects.forEach(function(p) {
          var pid = p.projectId || p.id || '';
          _origFetch(API_BASE + '/api/v1/projects/' + pid + '/settings/element-locking', { headers: { 'Authorization': 'Bearer ' + state.token } }).then(function(r) { return r.ok ? r.json() : { enabled: false }; })
            .then(function(s) {
              var cb = list.querySelector('[data-project-id="' + pid + '"]');
              if (cb) cb.checked = !!s.enabled;
            }).catch(function() {});
        });
        // Handle toggle changes
        list.addEventListener('change', function(e) {
          if (!e.target.classList.contains('syson-project-lock-toggle')) return;
          var pid = e.target.getAttribute('data-project-id');
          var enabled = e.target.checked;
          _origFetch(API_BASE + '/api/v1/projects/' + pid + '/settings/element-locking', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + state.token },
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

    // Version Control button in admin console
    var vcOpenBtn = document.getElementById('syson-vc-open');
    if (vcOpenBtn) {
      vcOpenBtn.addEventListener('click', function() {
        var pid = document.getElementById('syson-vc-project-id').value.trim();
        if (!pid) { alert('Enter a Project ID'); return; }
        showProjectVC(pid);
      });
    }

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

  // ── Project Version Control ─────────────────────────────────────────────
  // GitGraph-style version control visualization inspired by BowTie Pilot.

  function showProjectVC(projectId) {
    if (!projectId) { alert('No project selected'); return; }
    var existing = document.getElementById('syson-vc-overlay');
    if (existing) existing.remove();

    var overlay = document.createElement('div');
    overlay.id = 'syson-vc-overlay';
    overlay.style.cssText = 'position:fixed;inset:0;z-index:100002;display:flex;align-items:center;justify-content:center;background:rgba(38,30,88,0.65);font-family:Roboto,Helvetica Neue,Arial,sans-serif;';
    overlay.innerHTML = '<div style="background:#0f172a;border:1px solid #1e293b;border-radius:14px;width:min(1200px,96vw);max-height:90vh;overflow:auto;box-shadow:0 20px 60px rgba(0,0,0,.6);color:#e2e8f0;">'
      + '<div style="display:flex;justify-content:space-between;align-items:center;padding:16px 20px;border-bottom:1px solid #1e293b;">'
      + '<div><h2 style="margin:0;font-size:1.1rem;">🔀 Version Control</h2>'
      + '<p style="margin:4px 0 0;color:#64748b;font-size:.78rem;">Project: ' + escapeHtml(projectId.substring(0,16)) + '…</p></div>'
      + '<button id="syson-vc-close" style="background:none;border:none;color:#64748b;font-size:1.6rem;cursor:pointer;">×</button></div>'
      + '<div id="syson-vc-content" style="padding:16px 20px;"><p style="color:#64748b;">Loading version control data…</p></div>'
      + '</div>';
    document.body.appendChild(overlay);
    document.getElementById('syson-vc-close').addEventListener('click', function() { overlay.remove(); });
    overlay.addEventListener('click', function(e) { if (e.target === overlay) overlay.remove(); });

    // Fetch VC data
    var headers = { 'Authorization': 'Bearer ' + state.token };
    Promise.all([
      _origFetch(API_BASE + '/api/v1/projects/' + projectId + '/version-control/overview', { headers: headers }),
      _origFetch(API_BASE + '/api/v1/projects/' + projectId + '/version-control/tree', { headers: headers }),
      _origFetch(API_BASE + '/api/v1/projects/' + projectId + '/settings/default-branch', { headers: headers }),
    ]).then(function(results) {
      return Promise.all(results.map(function(r) { return r.ok ? r.json() : null; }));
    }).then(function(data) {
      var overview = data[0] || {};
      var tree = data[1] || {};
      var defaultBranch = data[2] || {};
      renderVCContent(projectId, overview, tree, defaultBranch);
    })['catch'](function(err) {
      document.getElementById('syson-vc-content').innerHTML = '<p style="color:#f87171;">Error: ' + escapeHtml(err.message) + '</p>';
    });
  }

  function renderVCContent(projectId, overview, tree, defaultBranch) {
    var box = document.getElementById('syson-vc-content');
    if (!box) return;
    var branches = tree.branches || [];
    var commits = tree.commits || [];
    var baselines = tree.baselines || [];
    var tags = tree.tags || [];
    var currentDefault = defaultBranch.branchId || '';

    // Stats cards
    var statsHTML = '<div style="display:flex;gap:10px;flex-wrap:wrap;margin-bottom:16px;">'
      + vcStatCard('🌿', overview.branchCount || 0, 'Branches', '#3b82f6')
      + vcStatCard('📦', overview.commitCount || 0, 'Commits', '#be1a78')
      + vcStatCard('📋', overview.baselineCount || 0, 'Baselines', '#dc2626')
      + vcStatCard('🏷️', overview.tagCount || 0, 'Tags', '#059669')
      + vcStatCard('🔄', overview.openMRCount || 0, 'Open MRs', '#d97706')
      + '</div>';

    // GitGraph SVG (enhanced — lanes, routed edges, baseline diamonds, tags, density/theme, click-to-diff)
    var densityOpts = ['baselines', 'standard', 'full'];
    var ggHeader = '<div style="display:flex;justify-content:space-between;align-items:center;padding:10px 14px;border-bottom:1px solid #1e293b;">'
      + '<h3 style="margin:0;font-size:.85rem;color:#94a3b8;text-transform:uppercase;letter-spacing:.04em;">Revision Graph</h3>'
      + '<div style="display:flex;gap:10px;align-items:center;">'
      + '<div id="syson-gg-density" style="display:inline-flex;border-radius:999px;border:1px solid #334155;overflow:hidden;">'
      + densityOpts.map(function (m) { return '<button type="button" data-density="' + m + '" aria-pressed="false" style="border:0;padding:5px 11px;font-size:11px;font-weight:700;cursor:pointer;background:transparent;color:#94a3b8;">' + m + '</button>'; }).join('')
      + '</div>'
      + '<div id="syson-gg-theme" style="display:inline-flex;border-radius:999px;border:1px solid #334155;overflow:hidden;">'
      + '<button type="button" data-theme="light" aria-pressed="false" style="border:0;padding:5px 11px;font-size:11px;font-weight:700;cursor:pointer;background:transparent;color:#94a3b8;">Light</button>'
      + '<button type="button" data-theme="dark" aria-pressed="false" style="border:0;padding:5px 11px;font-size:11px;font-weight:700;cursor:pointer;background:transparent;color:#94a3b8;">Dark</button>'
      + '</div>'
      + '</div></div>';
    var graphHTML = '<div id="syson-gitgraph-wrap" style="background:#020617;border:1px solid #1e293b;border-radius:10px;margin-bottom:16px;overflow:hidden;">'
      + ggHeader
      + '<div id="syson-gitgraph-scroll" style="overflow:auto;max-height:60vh;background:#020617;">'
      + renderGitGraph(branches, commits, baselines, tags)
      + '</div>'
      + '<div id="syson-gitgraph-diff" style="border-top:1px solid #1e293b;"></div>'
      + '</div>';

    // Branch selector
    var branchOptions = branches.map(function(b) {
      var selected = b.branchId === currentDefault ? ' selected' : '';
      return '<option value="' + escapeHtml(b.branchId) + '"' + selected + '>' + escapeHtml(b.name || b.branchId) + ' (' + escapeHtml(b.branchType || 'unknown') + ')</option>';
    }).join('');
    var branchSelectorHTML = '<div style="background:#020617;border:1px solid #1e293b;border-radius:10px;padding:12px;margin-bottom:16px;">'
      + '<h3 style="margin:0 0 10px;font-size:.85rem;color:#94a3b8;text-transform:uppercase;letter-spacing:.04em;">Default Branch (Model Loading Context)</h3>'
      + '<div style="display:flex;gap:8px;align-items:center;">'
      + '<select id="syson-vc-branch-select" style="flex:1;padding:8px;border-radius:6px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:.85rem;">'
      + '<option value="">— No default branch —</option>' + branchOptions + '</select>'
      + '<button id="syson-vc-set-branch" style="padding:8px 16px;border:0;border-radius:6px;background:#3b82f6;color:white;font-weight:700;font-size:.82rem;cursor:pointer;">Set Default</button>'
      + '</div>'
      + '<p style="margin:6px 0 0;color:#64748b;font-size:.75rem;">The selected branch determines which model context loads when opening this project. Element locking also uses this branch.</p>'
      + '<div id="syson-vc-branch-msg" style="margin-top:6px;font-size:.78rem;"></div>'
      + '</div>';

    // Branches list
    var branchesHTML = vcListSection('Branches', branches.map(function(b) {
      return '<div style="display:flex;justify-content:space-between;align-items:center;padding:6px 0;border-bottom:1px solid #1e293b;">'
        + '<div><span style="color:#3b82f6;font-weight:600;">' + escapeHtml(b.name || 'unnamed') + '</span>'
        + '<span style="color:#64748b;font-size:.75rem;margin-left:8px;">' + escapeHtml(b.branchType || '') + '</span></div>'
        + '<span style="color:#475569;font-size:.75rem;font-family:monospace;">' + (b.headCommitId ? b.headCommitId.substring(0,7) : '—') + '</span>'
        + '</div>';
    }));

    // Create branch form
    var createBranchHTML = '<div style="background:#020617;border:1px solid #1e293b;border-radius:10px;padding:12px;margin-bottom:16px;">'
      + '<h3 style="margin:0 0 10px;font-size:.85rem;color:#94a3b8;text-transform:uppercase;letter-spacing:.04em;">Create Branch</h3>'
      + '<div style="display:flex;gap:8px;align-items:center;">'
      + '<input id="syson-vc-new-branch-name" placeholder="branch name" style="flex:1;padding:8px;border-radius:6px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:.85rem;" />'
      + '<select id="syson-vc-new-branch-type" style="width:120px;padding:8px;border-radius:6px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:.85rem;">'
      + '<option value="feature">feature</option><option value="release">release</option><option value="hotfix">hotfix</option></select>'
      + '<button id="syson-vc-create-branch" style="padding:8px 16px;border:0;border-radius:6px;background:#261e58;color:white;font-weight:700;font-size:.82rem;cursor:pointer;">Create</button>'
      + '</div>'
      + '<div id="syson-vc-create-branch-msg" style="margin-top:6px;font-size:.78rem;min-height:1.2em;"></div>'
      + '</div>';

    // Baselines list
    var baselinesHTML = vcListSection('Baselines', baselines.map(function(b) {
      return '<div style="display:flex;justify-content:space-between;align-items:center;padding:6px 0;border-bottom:1px solid #1e293b;">'
        + '<div><span style="color:#dc2626;font-weight:600;">📋 ' + escapeHtml(b.name || b.baselineCode || 'unnamed') + '</span></div>'
        + '<span style="color:#475569;font-size:.75rem;">' + escapeHtml(b.status || '') + '</span>'
        + '</div>';
    }));

    // Tags list
    var tagsHTML = vcListSection('Tags', tags.map(function(t) {
      return '<div style="display:flex;justify-content:space-between;align-items:center;padding:6px 0;border-bottom:1px solid #1e293b;">'
        + '<span style="color:#059669;font-weight:600;">🏷️ ' + escapeHtml(t.name || 'unnamed') + '</span>'
        + '<span style="color:#475569;font-size:.75rem;">' + escapeHtml(t.description || '') + '</span>'
        + '</div>';
    }));

    // Open in Editor button
    var editorBtn = '<div style="margin-top:12px;">'
      + '<button onclick="window.location.href=\'/projects/' + escapeHtml(projectId) + '/edit\'" '
      + 'style="padding:10px 20px;border:0;border-radius:8px;background:#261e58;color:white;font-weight:700;font-size:.9rem;cursor:pointer;">'
      + '📂 Open in Editor</button>'
      + (currentDefault ? ' <span style="color:#64748b;font-size:.8rem;">Branch context: ' + escapeHtml(currentDefault.substring(0,8)) + '…</span>' : '')
      + '</div>';

    box.innerHTML = statsHTML + graphHTML + branchSelectorHTML + branchesHTML + createBranchHTML + baselinesHTML + tagsHTML + editorBtn;

    // Wire enhanced GitGraph interactivity (density/theme toggles, tooltips, click-to-diff).
    initGitGraph(projectId, branches, commits, baselines, tags);

    // Branch selector event
    var setBtn = document.getElementById('syson-vc-set-branch');
    if (setBtn) {
      setBtn.addEventListener('click', function() {
        var select = document.getElementById('syson-vc-branch-select');
        var branchId = select ? select.value : '';
        var msg = document.getElementById('syson-vc-branch-msg');
        if (!branchId) { msg.style.color = '#f87171'; msg.textContent = 'Select a branch first'; return; }
        setBtn.textContent = 'Saving…';
        _origFetch(API_BASE + '/api/v1/projects/' + projectId + '/settings/default-branch', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + state.token },
          body: JSON.stringify({ branchId: branchId }),
        }).then(function(r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.json();
        }).then(function() {
          msg.style.color = '#4ade80'; msg.textContent = 'Default branch updated!';
          setBtn.textContent = 'Set Default';
          // Store in localStorage for editor use
          try { localStorage.setItem('syson-vc-branch-' + projectId, branchId); } catch(e) {}
        })['catch'](function(err) {
          msg.style.color = '#f87171'; msg.textContent = 'Error: ' + err.message;
          setBtn.textContent = 'Set Default';
        });
      });
    }

    // Create branch event
    var createBtn = document.getElementById('syson-vc-create-branch');
    if (createBtn) {
      createBtn.addEventListener('click', function() {
        var nameInput = document.getElementById('syson-vc-new-branch-name');
        var typeSelect = document.getElementById('syson-vc-new-branch-type');
        var msg = document.getElementById('syson-vc-create-branch-msg');
        var branchName = (nameInput && nameInput.value.trim()) || '';
        var branchType = (typeSelect && typeSelect.value) || 'feature';
        if (!branchName) { msg.style.color = '#f87171'; msg.textContent = 'Enter a branch name'; return; }
        createBtn.textContent = 'Creating…';
        createBtn.disabled = true;
        _origFetch(API_BASE + '/api/v1/projects/' + projectId + '/branches', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + state.token },
          body: JSON.stringify({ tenantId: state.tenantId, name: branchName, branchType: branchType, parentBranchId: null }),
        }).then(function(r) {
          if (!r.ok) return r.json().then(function(e) { throw new Error(e.error || e.message || 'HTTP ' + r.status); });
          return r.json();
        }).then(function(branch) {
          msg.style.color = '#4ade80'; msg.textContent = 'Branch "' + branchName + '" created!';
          createBtn.textContent = 'Create';
          createBtn.disabled = false;
          nameInput.value = '';
          // Reload VC data to show the new branch
          setTimeout(function() { showProjectVC(projectId); }, 800);
        })['catch'](function(err) {
          msg.style.color = '#f87171'; msg.textContent = 'Error: ' + err.message;
          createBtn.textContent = 'Create';
          createBtn.disabled = false;
        });
      });
    }
  }

  function vcStatCard(icon, value, label, color) {
    return '<div style="display:flex;align-items:center;gap:10px;padding:10px 14px;border-radius:8px;background:' + color + '11;border:1px solid ' + color + '22;min-width:120px;">'
      + '<span style="font-size:18px;">' + icon + '</span>'
      + '<div><div style="font-size:18px;font-weight:700;color:' + color + ';">' + value + '</div>'
      + '<div style="font-size:.72rem;color:#64748b;">' + label + '</div></div></div>';
  }

  function vcListSection(title, items) {
    if (!items.length) return '';
    return '<div style="background:#020617;border:1px solid #1e293b;border-radius:10px;padding:12px;margin-bottom:12px;">'
      + '<h3 style="margin:0 0 8px;font-size:.85rem;color:#94a3b8;text-transform:uppercase;letter-spacing:.04em;">' + title + ' (' + items.length + ')</h3>'
      + items.join('') + '</div>';
  }

  // ── GitGraph: self-contained state + helpers (adapted from BowTie VersionGraph.tsx) ──
  // All GitGraph UI is built from these closures; the login boot path is untouched.
  var _gitGraphState = { density: 'standard', theme: 'dark' };
  var _gitGraphData = { branches: [], commits: [], baselines: [], tags: [], projectId: null };

  var _ggThemes = {
    light: { bg: '#f8fafc', grid: 'rgba(100,116,139,0.16)', guide: 'rgba(100,116,139,.10)',
      rowEven: 'rgba(248,250,252,.9)', rowOdd: 'rgba(226,232,240,.5)',
      text: '#0f172a', textMuted: '#475569', textDim: '#64748b', nodeFill: '#ffffff',
      baselineText: '#dc2626', centerDot: '#ffffff', railAlpha: 0.4, dark: false },
    dark:  { bg: '#020617', grid: '#1e293b', guide: 'rgba(148,163,184,.06)',
      rowEven: 'rgba(15,23,42,.45)', rowOdd: 'rgba(2,6,23,.20)',
      text: '#e2e8f0', textMuted: '#94a3b8', textDim: '#64748b', nodeFill: '#0f172a',
      baselineText: '#fca5a5', centerDot: '#ffffff', railAlpha: 0.42, dark: true }
  };
  var _ggPalette = ['#3b82f6', '#22c55e', '#f59e0b', '#ef4444', '#a855f7', '#06b6d4', '#ec4899', '#84cc16'];
  var _ggBranchTypeColors = { main: '#3b82f6', master: '#3b82f6', working: '#22c55e',
    feature: '#22c55e', review: '#f59e0b', release: '#ef4444', hotfix: '#ef4444', template: '#a855f7' };

  var _ggLEFT = 26, _ggLANE = 30, _ggROW = 34, _ggROWF = 38, _ggPADT = 30, _ggPADB = 26, _ggMSGGAP = 28, _ggMSGW = 640;

  function _vcParseParents(s) {
    if (Array.isArray(s)) return s.filter(function (x) { return x; });
    if (!s) return [];
    return String(s).split(',').map(function (x) { return String(x).trim(); }).filter(Boolean);
  }
  function _ggBranchColor(branchType, name, lane) {
    if (branchType && _ggBranchTypeColors[branchType]) return _ggBranchTypeColors[branchType];
    if (name && _ggBranchTypeColors[name]) return _ggBranchTypeColors[name];
    return _ggPalette[Math.abs(lane | 0) % _ggPalette.length];
  }
  function _ggAlpha(hex, a) {
    var c = String(hex).replace('#', '');
    if (c.length < 6) return hex;
    var r = parseInt(c.slice(0, 2), 16), g = parseInt(c.slice(2, 4), 16), b = parseInt(c.slice(4, 6), 16);
    return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
  }
  function _ggFmtDate(s) {
    if (!s) return '—';
    var d = new Date(s);
    if (isNaN(d.getTime())) return String(s);
    return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  }
  function _ggShort(id) { return id ? String(id).slice(0, 7) : '—'; }
  function _ggTrunc(t, n) { t = t || ''; return t.length > n ? t.slice(0, n - 1) + '…' : t; }
  function _ggLaneX(lane) { return _ggLEFT + lane * _ggLANE; }
  function _ggRoute(fx, fy, tx, ty) {
    if (fx === tx) return 'M ' + fx + ' ' + fy + ' L ' + tx + ' ' + ty;
    var bend = Math.max(8, Math.min(18, Math.abs(ty - fy) / 3));
    var my = fy + Math.sign(ty - fy || 1) * bend;
    return 'M ' + fx + ' ' + fy + ' C ' + fx + ' ' + my + ', ' + tx + ' ' + my + ', ' + tx + ' ' + (my + Math.sign(ty - fy || 1) * 2) + ' L ' + tx + ' ' + ty;
  }

  // Renders the commit-graph SVG as a string. Density/theme come from _gitGraphState.
  function renderGitGraph(branches, commits, baselines, tags) {
    branches = branches || []; commits = commits || []; baselines = baselines || []; tags = tags || [];
    var esc = escapeHtml;
    if (!commits.length) {
      return '<p style="color:#64748b;font-size:.82rem;text-align:center;padding:24px;font-family:Roboto,Helvetica Neue,Arial,sans-serif;">No commits yet. Create a branch and save a model to see the revision graph.</p>';
    }
    var theme = _ggThemes[_gitGraphState.theme] || _ggThemes.dark;
    var density = _gitGraphState.density || 'standard';

    // Normalize + sort branches (main/master first, then alpha) → lane assignment
    var sBranches = branches.slice().map(function (b) {
      return { id: String(b.branchId || ''), name: String(b.name || 'branch'), type: String(b.branchType || ''),
        headCommitId: b.headCommitId ? String(b.headCommitId) : null };
    }).filter(function (b) { return b.id; })
      .sort(function (a, b) {
        if (a.name === 'main' || a.name === 'master') return -1;
        if (b.name === 'main' || b.name === 'master') return 1;
        return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
      });
    var branchById = {};
    sBranches.forEach(function (b, i) { b.lane = i; branchById[b.id] = b; });
    var maxLane = Math.max(0, sBranches.length - 1);

    // Normalize commits
    var sCommits = commits.map(function (c) {
      return { id: String(c.commitId || ''), branchId: String(c.branchId || ''), commitNumber: Number(c.commitNumber || 0),
        message: String(c.message || 'No message'), committedAt: String(c.committedAt || ''),
        author: String(c.authorUserId || ''), parents: _vcParseParents(c.parentCommitIds) };
    }).filter(function (c) { return c.id; });
    var commitById = {};
    sCommits.forEach(function (c) { commitById[c.id] = c; });

    var headIds = {};
    sBranches.forEach(function (b) { if (b.headCommitId) headIds[b.headCommitId] = true; });

    var baselineByCommit = {};
    baselines.forEach(function (b) { var k = String(b.commitId || ''); if (k) { (baselineByCommit[k] || (baselineByCommit[k] = [])).push({ code: String(b.baselineCode || b.name || 'baseline'), name: String(b.name || '') }); } });
    var tagByCommit = {};
    tags.forEach(function (t) { var k = String(t.commitId || ''); if (k) { (tagByCommit[k] || (tagByCommit[k] = [])).push({ name: String(t.name || 'tag') }); } });

    // Density filter (mirrors BowTie DensityMode)
    function show(c) {
      if (density === 'full') return true;
      var isBl = !!baselineByCommit[c.id], isRoot = c.parents.length === 0, isHead = !!headIds[c.id];
      if (density === 'baselines') return isBl || isHead || isRoot;
      var isMerge = c.parents.length > 1;
      return isBl || isMerge || isHead || isRoot;
    }

    var visible = sCommits.filter(show).sort(function (a, b) {
      var at = new Date(a.committedAt).getTime(), bt = new Date(b.committedAt).getTime();
      var d = (isNaN(bt) ? 0 : bt) - (isNaN(at) ? 0 : at);
      return d || (b.commitNumber - a.commitNumber);
    });
    if (!visible.length) {
      return '<p style="color:#64748b;font-size:.82rem;text-align:center;padding:24px;">No commits match the current density filter (' + density + ').</p>';
    }

    var rowH = density === 'full' ? _ggROWF : _ggROW;
    var messageX = _ggLEFT + (maxLane + 1) * _ggLANE + _ggMSGGAP;
    var graphW = messageX + _ggMSGW;
    var graphH = _ggPADT + visible.length * rowH + _ggPADB;

    // Rows (commit → lane/x/y/color/kind)
    var rows = visible.map(function (commit, row) {
      var branch = branchById[commit.branchId];
      var lane = branch ? branch.lane : 0;
      var color = _ggBranchColor(branch ? branch.type : '', branch ? branch.name : '', lane);
      return { commit: commit, branch: branch, x: _ggLaneX(lane), y: _ggPADT + row * rowH, lane: lane, color: color,
        isMerge: commit.parents.length > 1, isBaseline: !!baselineByCommit[commit.id], isHead: !!headIds[commit.id] };
    });
    var rowById = {};
    rows.forEach(function (r) { rowById[r.commit.id] = r; });

    // Edges (parent → child), with ghost routing for filtered-out parents
    var edges = [];
    rows.forEach(function (row) {
      row.commit.parents.forEach(function (pid, index) {
        var pr = rowById[pid], ghost = false;
        if (!pr) {
          var parent = commitById[pid];
          if (!parent) return;
          var pBranch = branchById[parent.branchId];
          var pLane = pBranch ? pBranch.lane : row.lane;
          pr = { x: _ggLaneX(pLane), y: row.y + rowH * 1.4, color: _ggBranchColor(pBranch ? pBranch.type : '', pBranch ? pBranch.name : '', pLane) };
          ghost = true;
        } else {
          pr = { x: pr.x, y: pr.y, color: pr.color };
        }
        edges.push({ fx: row.x, fy: row.y, tx: pr.x, ty: pr.y,
          color: index > 0 ? pr.color : row.color, merge: index > 0 || row.isMerge, ghost: ghost });
      });
    });

    // Lane rails (translucent vertical bars per branch)
    var branchRows = {};
    rows.forEach(function (r) { var k = r.branch ? r.branch.id : r.commit.branchId; (branchRows[k] || (branchRows[k] = [])).push(r); });
    var rails = Object.keys(branchRows).map(function (k) {
      var list = branchRows[k], first = list[0], last = list[list.length - 1];
      return { x: first.x, y1: first.y, y2: Math.max(first.y, last.y), color: first.color };
    });

    // ── Build SVG ──
    var svg = '<svg id="syson-gitgraph-svg" width="' + graphW + '" height="' + graphH + '" viewBox="0 0 ' + graphW + ' ' + graphH +
      '" role="img" aria-label="Commit graph: ' + visible.length + ' of ' + sCommits.length + ' commits across ' + sBranches.length + ' branches"' +
      ' style="display:block;min-width:100%;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11.5px;">';
    svg += '<rect x="0" y="0" width="' + graphW + '" height="' + graphH + '" fill="' + theme.bg + '"/>';
    svg += '<defs><filter id="ggGlow" x="-80%" y="-80%" width="260%" height="260%">' +
      '<feGaussianBlur stdDeviation="2.4" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>';

    // Row stripes
    rows.forEach(function (r, i) {
      svg += '<rect x="0" y="' + (r.y - rowH / 2) + '" width="' + graphW + '" height="' + rowH + '" fill="' + (i % 2 === 0 ? theme.rowEven : theme.rowOdd) + '"/>';
    });
    // Lane guides
    for (var lane = 0; lane <= maxLane; lane++) {
      svg += '<line x1="' + _ggLaneX(lane) + '" y1="12" x2="' + _ggLaneX(lane) + '" y2="' + (graphH - 12) + '" stroke="' + theme.guide + '" stroke-width="1"/>';
    }
    // Rails
    rails.forEach(function (s) {
      svg += '<line x1="' + s.x + '" y1="' + s.y1 + '" x2="' + s.x + '" y2="' + s.y2 + '" stroke="' + _ggAlpha(s.color, theme.railAlpha) + '" stroke-width="4" stroke-linecap="round"/>';
    });
    // Edges
    edges.forEach(function (e) {
      var op = e.ghost ? 0.22 : (e.merge ? 0.78 : 0.6);
      svg += '<path d="' + _ggRoute(e.fx, e.fy, e.tx, e.ty) + '" fill="none" stroke="' + _ggAlpha(e.color, op) + '" ' +
        'stroke-width="' + (e.merge ? 3.2 : 2.4) + '" stroke-linecap="round" stroke-linejoin="round"' + (e.ghost ? ' stroke-dasharray="5 5"' : '') + '/>';
    });
    // Branch header labels
    sBranches.forEach(function (b) {
      svg += '<text x="' + _ggLaneX(b.lane) + '" y="12" fill="' + _ggBranchColor(b.type, b.name, b.lane) + '" font-size="9" text-anchor="middle" font-weight="700">' + esc(_ggTrunc(b.name, 10)) + '</text>';
    });

    // Commit nodes
    rows.forEach(function (row) {
      var c = row.commit;
      var r = row.isBaseline ? 7.5 : row.isMerge ? 6.5 : row.isHead ? 6.5 : 5.2;
      var titleText = (row.branch ? row.branch.name : c.branchId) + ' · #' + c.commitNumber +
        (row.isMerge ? ' · merge' : '') + (row.isBaseline ? ' · baseline' : '') + ' · ' + c.message + ' · ' + _ggShort(c.id) + ' · ' + _ggFmtDate(c.committedAt);
      svg += '<g class="syson-gg-node" tabindex="0" role="button" aria-label="commit ' + _ggShort(c.id) + ': ' + esc(_ggTrunc(c.message, 60)) + '"' +
        ' data-commit="' + esc(c.id) + '" data-branch="' + esc(c.branchId) + '" style="cursor:pointer;">';
      svg += '<title>' + esc(titleText) + '</title>';
      // Invisible hit target for easy hover/click
      svg += '<circle cx="' + row.x + '" cy="' + row.y + '" r="13" fill="transparent"/>';
      if (row.isMerge) {
        svg += '<rect x="' + (row.x - r) + '" y="' + (row.y - r) + '" width="' + (r * 2) + '" height="' + (r * 2) + '" rx="2" transform="rotate(45 ' + row.x + ' ' + row.y + ')" fill="' + theme.nodeFill + '" stroke="' + row.color + '" stroke-width="2.4" filter="url(#ggGlow)"/>';
      } else {
        svg += '<circle cx="' + row.x + '" cy="' + row.y + '" r="' + r + '" fill="' + (row.isBaseline ? row.color : theme.nodeFill) + '" stroke="' + row.color + '" stroke-width="' + (row.isBaseline || row.isHead ? 2.7 : 2.1) + '"' + (row.isBaseline || row.isHead ? ' filter="url(#ggGlow)"' : '') + '/>';
        if (row.isBaseline) {
          svg += '<circle cx="' + row.x + '" cy="' + row.y + '" r="' + (r + 2.5) + '" fill="none" stroke="' + theme.baselineText + '" stroke-width="1.4" opacity="0.7"/>';
        }
        svg += '<circle cx="' + row.x + '" cy="' + row.y + '" r="2.1" fill="' + (row.isBaseline ? theme.centerDot : row.color) + '"/>';
      }
      // Message / hash / date columns
      svg += '<text x="' + messageX + '" y="' + (row.y + 4) + '" fill="' + theme.text + '" font-size="12" font-weight="' + (row.isBaseline ? 700 : 500) + '">' + esc(_ggTrunc(c.message, 52)) + '</text>';
      svg += '<text x="' + (messageX + 380) + '" y="' + (row.y + 4) + '" fill="' + theme.textDim + '" font-size="10.5">' + _ggShort(c.id) + '</text>';
      svg += '<text x="' + (messageX + _ggMSGW - 90) + '" y="' + (row.y + 4) + '" fill="' + theme.textDim + '" font-size="10">' + _ggFmtDate(c.committedAt) + '</text>';
      // Baseline + tag pills
      var pillX = messageX + 470;
      (baselineByCommit[c.id] || []).forEach(function (bl) {
        var w = Math.max(42, bl.code.length * 7 + 18);
        svg += '<g transform="translate(' + pillX + ',' + (row.y - 9) + ')">' +
          '<rect x="0" y="0" width="' + w + '" height="18" rx="9" fill="rgba(239,68,68,.14)" stroke="rgba(239,68,68,.6)" stroke-width="1"/>' +
          '<polygon points="0,9 -5,4 -10,9 -5,14" fill="' + theme.baselineText + '"/>' +
          '<text x="14" y="12.5" fill="' + theme.baselineText + '" font-size="10.5" font-weight="700">' + esc(_ggTrunc(bl.code, 18)) + '</text></g>';
        pillX += w + 7;
      });
      (tagByCommit[c.id] || []).forEach(function (tg) {
        var w = Math.max(40, tg.name.length * 7 + 22);
        svg += '<g transform="translate(' + pillX + ',' + (row.y - 9) + ')">' +
          '<rect x="0" y="0" width="' + w + '" height="18" rx="9" fill="rgba(5,150,105,.14)" stroke="rgba(5,150,105,.6)" stroke-width="1"/>' +
          '<circle cx="8" cy="9" r="3" fill="#10b981"/>' +
          '<text x="16" y="12.5" fill="#10b981" font-size="10.5" font-weight="700">' + esc(_ggTrunc(tg.name, 16)) + '</text></g>';
        pillX += w + 7;
      });
      svg += '</g>';
    });

    svg += '</svg>';
    return svg;
  }

  // Re-renders the SVG into the scroll container using the current density/theme.
  function _ggReRender() {
    var scroll = document.getElementById('syson-gitgraph-scroll');
    if (!scroll) return;
    var t = _ggThemes[_gitGraphState.theme] || _ggThemes.dark;
    scroll.style.background = t.bg;
    scroll.innerHTML = renderGitGraph(_gitGraphData.branches, _gitGraphData.commits, _gitGraphData.baselines, _gitGraphData.tags);
    _ggSyncButtons();
  }

  // Updates density/theme button active styling + aria-pressed.
  function _ggSyncButtons() {
    var theme = _ggThemes[_gitGraphState.theme] || _ggThemes.dark;
    var muted = theme.dark ? '#94a3b8' : '#64748b';
    function styleBtns(container, attr, val) {
      if (!container) return;
      var btns = container.querySelectorAll('button[' + attr + ']');
      for (var i = 0; i < btns.length; i++) {
        var active = btns[i].getAttribute(attr) === val;
        btns[i].setAttribute('aria-pressed', active ? 'true' : 'false');
        btns[i].style.cssText = 'border:0;padding:5px 11px;font-size:11px;font-weight:700;cursor:pointer;' + (active ? 'background:#261e58;color:#fff;' : ('background:transparent;color:' + muted + ';'));
      }
    }
    styleBtns(document.getElementById('syson-gg-density'), 'data-density', _gitGraphState.density);
    styleBtns(document.getElementById('syson-gg-theme'), 'data-theme', _gitGraphState.theme);
  }

  // Wires density + theme toggle buttons (delegated, survive innerHTML re-render).
  function _ggWireControls() {
    var dens = document.getElementById('syson-gg-density');
    if (dens && !dens.getAttribute('data-wired')) {
      dens.setAttribute('data-wired', '1');
      dens.addEventListener('click', function (e) {
        var b = e.target.closest ? e.target.closest('button[data-density]') : null;
        if (!b) return;
        _gitGraphState.density = b.getAttribute('data-density');
        _ggReRender();
      });
    }
    var th = document.getElementById('syson-gg-theme');
    if (th && !th.getAttribute('data-wired')) {
      th.setAttribute('data-wired', '1');
      th.addEventListener('click', function (e) {
        var b = e.target.closest ? e.target.closest('button[data-theme]') : null;
        if (!b) return;
        _gitGraphState.theme = b.getAttribute('data-theme');
        _ggReRender();
      });
    }
  }

  // Lazily fetches and renders a commit's diff (ChangeDto list) via _origFetch.
  function showGitGraphDiff(projectId, branchId, commitId) {
    var panel = document.getElementById('syson-gitgraph-diff');
    if (!panel) return;
    panel.style.padding = '12px 14px';
    panel.innerHTML = '<div style="color:#94a3b8;font-size:.8rem;">Loading diff for ' + _ggShort(commitId) + '…</div>';
    var headers = { 'Authorization': 'Bearer ' + state.token };
    _origFetch(API_BASE + '/api/v1/projects/' + projectId + '/branches/' + branchId + '/commits/' + commitId + '/diff', { headers: headers })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (changes) {
        changes = changes || [];
        var h = '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">' +
          '<span style="color:#e2e8f0;font-weight:700;font-size:.85rem;">Diff · ' + _ggShort(commitId) + ' · ' + changes.length + ' change' + (changes.length !== 1 ? 's' : '') + '</span>' +
          '<button id="syson-gg-diff-close" style="background:none;border:0;color:#64748b;font-size:1.1rem;cursor:pointer;">×</button></div>';
        if (!changes.length) {
          h += '<div style="color:#64748b;font-size:.82rem;">No element changes recorded for this commit.</div>';
        } else {
          h += changes.map(function (ch) {
            var op = String(ch.operation || '?').toUpperCase();
            var opColor = op === 'CREATE' ? '#4ade80' : op === 'DELETE' ? '#f87171' : op === 'UPDATE' ? '#facc15' : '#94a3b8';
            return '<div style="display:flex;gap:8px;align-items:flex-start;padding:5px 0;border-bottom:1px solid #1e293b;font-size:.78rem;">' +
              '<span style="color:#475569;font-family:monospace;width:24px;">' + (ch.changeSeq != null ? ch.changeSeq : '·') + '</span>' +
              '<span style="color:' + opColor + ';font-weight:700;width:62px;">' + escapeHtml(op) + '</span>' +
              '<span style="color:#94a3b8;width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' + escapeHtml(ch.objectType || 'object') + '</span>' +
              '<span style="color:#64748b;font-family:monospace;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' + escapeHtml(String(ch.objectId || '').slice(0, 18)) + '</span>' +
              '<span style="color:#475569;max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="' + escapeHtml(ch.patch || '') + '">' + escapeHtml(_ggTrunc(ch.patch || '', 60)) + '</span>' +
              '</div>';
          }).join('');
        }
        panel.innerHTML = h;
        var cb = document.getElementById('syson-gg-diff-close');
        if (cb) cb.addEventListener('click', function () { panel.innerHTML = ''; panel.style.padding = '0'; });
      })['catch'](function (err) {
        panel.innerHTML = '<div style="color:#f87171;font-size:.82rem;">Diff error: ' + escapeHtml(err.message) + '</div>';
      });
  }

  // Self-contained wiring: density/theme controls + delegated SVG hover/keyboard/click.
  function initGitGraph(projectId, branches, commits, baselines, tags) {
    _gitGraphData = { branches: branches || [], commits: commits || [], baselines: baselines || [], tags: tags || [], projectId: projectId };
    _ggSyncButtons();
    _ggWireControls();

    var scroll = document.getElementById('syson-gitgraph-scroll');
    if (!scroll) return;
    var t = _ggThemes[_gitGraphState.theme] || _ggThemes.dark;
    scroll.style.background = t.bg;

    // Rich HTML tooltip (single, reused element)
    var tip = document.getElementById('syson-gg-tooltip');
    if (!tip) {
      tip = document.createElement('div');
      tip.id = 'syson-gg-tooltip';
      tip.style.cssText = 'position:fixed;z-index:100020;pointer-events:none;display:none;max-width:320px;padding:10px 12px;border-radius:8px;font-family:Roboto,Helvetica Neue,Arial,sans-serif;font-size:12px;line-height:1.5;box-shadow:0 12px 30px rgba(0,0,0,.35);';
      document.body.appendChild(tip);
    }

    function nodeOf(target) {
      var el = target;
      while (el && el !== scroll) { if (el.classList && el.classList.contains('syson-gg-node')) return el; el = el.parentNode; }
      return null;
    }
    function findCommit(id) {
      for (var i = 0; i < _gitGraphData.commits.length; i++) { if (String(_gitGraphData.commits[i].commitId) === id) return _gitGraphData.commits[i]; }
      return null;
    }
    function showTip(node, ev) {
      var c = findCommit(node.getAttribute('data-commit'));
      if (!c) return;
      var b = null;
      for (var j = 0; j < _gitGraphData.branches.length; j++) { if (String(_gitGraphData.branches[j].branchId) === String(c.branchId)) { b = _gitGraphData.branches[j]; break; } }
      var isMerge = _vcParseParents(c.parentCommitIds).length > 1;
      var theme = _ggThemes[_gitGraphState.theme] || _ggThemes.dark;
      var bColor = b ? _ggBranchColor(b.branchType, b.name, 0) : '#3b82f6';
      tip.style.background = theme.dark ? '#0f172a' : '#ffffff';
      tip.style.color = theme.text;
      tip.style.border = '1px solid ' + (theme.dark ? '#334155' : '#e2e8f0');
      tip.innerHTML = '<div style="font-weight:700;color:' + bColor + ';margin-bottom:2px;">' + escapeHtml(b ? b.name : c.branchId) + ' · #' + (c.commitNumber || 0) + (isMerge ? ' · merge' : '') + '</div>' +
        '<div style="margin-bottom:4px;">' + escapeHtml(c.message || 'No message') + '</div>' +
        '<div style="opacity:.8;font-size:11px;">' + _ggShort(c.commitId) + ' · ' + _ggFmtDate(c.committedAt) + (c.authorUserId ? ' · by ' + escapeHtml(String(c.authorUserId).slice(0, 8)) : '') + '</div>';
      tip.style.display = 'block';
      moveTip(ev);
    }
    function moveTip(ev) {
      var px = (ev && ev.clientX != null ? ev.clientX : 0) + 14, py = (ev && ev.clientY != null ? ev.clientY : 0) + 14;
      if (px + tip.offsetWidth > window.innerWidth - 8) px = window.innerWidth - tip.offsetWidth - 8;
      if (py + tip.offsetHeight > window.innerHeight - 8) py = window.innerHeight - tip.offsetHeight - 8;
      tip.style.left = px + 'px'; tip.style.top = py + 'px';
    }
    function hideTip() { tip.style.display = 'none'; }
    function triggerDiff(node) {
      hideTip();
      showGitGraphDiff(_gitGraphData.projectId, node.getAttribute('data-branch'), node.getAttribute('data-commit'));
    }

    // Delegated handlers on the (persistent) scroll container.
    scroll.addEventListener('mouseover', function (e) { var n = nodeOf(e.target); if (n) showTip(n, e); });
    scroll.addEventListener('mousemove', function (e) { if (tip.style.display === 'block') moveTip(e); });
    scroll.addEventListener('mouseout', function (e) { var n = nodeOf(e.target); if (n) { var rel = e.relatedTarget; if (!rel || !n.contains(rel)) hideTip(); } });
    scroll.addEventListener('click', function (e) { var n = nodeOf(e.target); if (n) triggerDiff(n); });
    scroll.addEventListener('keydown', function (e) {
      var n = e.target;
      if (n && n.classList && n.classList.contains('syson-gg-node') && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); triggerDiff(n); }
    });
  }

  // ── Element History Button ────────────────────────────────────────────────
  // Injects a "📋 History" button into the Sirius properties panel header.
  // When clicked, fetches element change history and shows an overlay.

  // ── Save Button ──────────────────────────────────────────────────────────
  // Injects a save button into the top navbar, next to the branch indicator.

  function injectSaveButton() {
    if (!state.token) return;
    function tryInject() {
      var existing = document.getElementById('syson-save-btn');
      if (!isProjectEditorUrl()) {
        if (existing) existing.remove();
        return;
      }
      var navBar = findEditorHeader();
      if (!navBar) return;
      if (existing && navBar.contains(existing)) return;
      if (existing) existing.remove();

      var saveBtn = document.createElement('button');
      saveBtn.id = 'syson-save-btn';
      saveBtn.innerHTML = '💾 Save';
      saveBtn.title = 'Save model — records history to version control';
      saveBtn.style.cssText = 'position:absolute;right:calc(250px + 6.6667vw);top:50%;transform:translateY(-50%);padding:4px 12px;font-size:12px;font-weight:600;background:#261e58;color:#e2e8f0;border:1px solid #3b82f6;border-radius:6px;cursor:pointer;white-space:nowrap;z-index:10;display:inline-flex;align-items:center;gap:4px;';
      saveBtn.addEventListener('mouseenter', function() { this.style.background = '#3b82f6'; this.style.color = '#fff'; });
      saveBtn.addEventListener('mouseleave', function() { this.style.background = '#261e58'; this.style.color = '#e2e8f0'; });
      saveBtn.addEventListener('click', function() { triggerSave(); });
      navBar.style.position = 'relative';
      navBar.appendChild(saveBtn);
    }
    installEditorChromeRouteGuard(tryInject);
    [50, 300, 1000, 3000, 6000].forEach(function(delay) { setTimeout(tryInject, delay); });
  }

  function triggerSave() {
    var projectId = getProjectIdFromUrl();
    if (!projectId) { alert('Open a project first to save.'); return; }
    var saveBtn = document.getElementById('syson-save-btn');
    if (saveBtn) { saveBtn.style.opacity = '0.5'; saveBtn.innerHTML = '⏳'; }
    // Resolve branch
    var branchId = localStorage.getItem('syson-vc-branch-' + projectId) || '';
    _origFetch(API_BASE + '/api/v1/projects/' + projectId + '/settings/default-branch', { headers: { 'Authorization': 'Bearer ' + state.token } })
      .then(function(r) { return r.ok ? r.json() : {}; })
      .then(function(d) { return d.branchId || ''; })
      .catch(function() { return ''; })
      .then(function(bId) {
        branchId = branchId || bId;
        return _origFetch(API_BASE + '/api/v1/projects/' + projectId + '/save', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + state.token },
          body: branchId ? JSON.stringify({ branchId: branchId }) : '{}'
        });
      })
      .then(function(r) { return r.ok ? r.json() : Promise.reject(r.statusText); })
      .then(function(result) {
        if (saveBtn) { saveBtn.innerHTML = '✅'; saveBtn.style.opacity = '1'; saveBtn.style.color = '#4ade80'; saveBtn.style.borderColor = '#4ade80'; }
        console.log('Save complete:', result.message);
        setTimeout(function() { if (saveBtn) { saveBtn.innerHTML = '💾 Save'; saveBtn.style.opacity = '1'; saveBtn.style.background = '#261e58'; saveBtn.style.color = '#e2e8f0'; saveBtn.style.borderColor = '#3b82f6'; }}, 2000);
      })
      .catch(function(err) {
        if (saveBtn) { saveBtn.innerHTML = '❌'; saveBtn.style.opacity = '1'; saveBtn.style.color = '#f87171'; saveBtn.style.borderColor = '#f87171'; }
        console.error('Save failed:', err);
        setTimeout(function() { if (saveBtn) { saveBtn.innerHTML = '💾 Save'; saveBtn.style.opacity = '1'; saveBtn.style.background = '#261e58'; saveBtn.style.color = '#e2e8f0'; saveBtn.style.borderColor = '#3b82f6'; }}, 2000);
      });
  }

  // ── Branch Indicator ─────────────────────────────────────────────────────
  // Injects a branch indicator into the top navigation bar.

  function injectBranchIndicator() {
    if (!state.token) return;
    var lastProjectId = null;
    var refreshTimer = null;

    function tryInject() {
      var projectId = getProjectIdFromUrl();
      var wrap = document.getElementById('syson-branch-wrap');
      if (!projectId || !isProjectEditorUrl()) {
        if (wrap) wrap.remove();
        return;
      }

      var navBar = findEditorHeader();
      if (!navBar) return;
      if (!wrap || !document.body.contains(wrap)) {
        wrap = document.createElement('div');
        wrap.id = 'syson-branch-wrap';
        wrap.style.cssText = 'position:absolute;right:calc(80px + 6.6667vw);top:50%;transform:translateY(-50%);display:inline-flex;align-items:center;gap:6px;z-index:10;font-family:Roboto,Helvetica Neue,Arial,sans-serif;';
        wrap.innerHTML = '<span id="syson-branch-ind" style="display:none;align-items:center;gap:4px;padding:3px 8px;font-size:11px;font-weight:600;color:#93c5fd;background:rgba(59,130,246,0.12);border:1px solid rgba(59,130,246,0.3);border-radius:4px;white-space:nowrap;">🌿 loading…</span>'
          + '<select id="syson-branch-select" title="Select branch to load and save into" style="max-width:150px;padding:3px 6px;font-size:11px;border:1px solid rgba(59,130,246,0.35);border-radius:4px;background:#0f172a;color:#dbeafe;"></select>'
          + '<button id="syson-branch-apply" title="Load selected branch into SysON and save future changes there" style="padding:3px 8px;font-size:11px;font-weight:700;border:1px solid rgba(34,197,94,0.45);border-radius:4px;background:rgba(34,197,94,0.16);color:#bbf7d0;cursor:pointer;">Apply</button>';
        navBar.style.position = 'relative';
        navBar.appendChild(wrap);
        var applyBtn = wrap.querySelector('#syson-branch-apply');
        if (applyBtn) applyBtn.addEventListener('click', applySelectedBranch);
      } else if (!navBar.contains(wrap)) {
        navBar.appendChild(wrap);
      }

      wrap.style.display = 'inline-flex';
      if (lastProjectId !== projectId || wrap.getAttribute('data-loaded') !== 'true') {
        lastProjectId = projectId;
        refreshBranchName();
      }
      if (!refreshTimer) {
        refreshTimer = setInterval(refreshBranchName, 30000);
      }
    }

    installEditorChromeRouteGuard(tryInject);
    [50, 300, 1000, 3000, 6000].forEach(function(delay) { setTimeout(tryInject, delay); });
  }

  function refreshBranchName() {
    var projectId = getProjectIdFromUrl();
    var wrap = document.getElementById('syson-branch-wrap');
    var ind = document.getElementById('syson-branch-ind');
    var select = document.getElementById('syson-branch-select');
    if (!projectId || !ind) return;
    if (wrap) wrap.setAttribute('data-loaded', 'false');
    var headers = { 'Authorization': 'Bearer ' + state.token };
    var branchId = '';
    try { branchId = localStorage.getItem('syson-vc-branch-' + projectId) || ''; } catch(e) {}

    var defaultBranchPromise = branchId
      ? Promise.resolve({ branchId: branchId })
      : _origFetch(API_BASE + '/api/v1/projects/' + projectId + '/settings/default-branch', { headers: headers })
          .then(function(r) { return r.ok ? r.json() : {}; })
          .catch(function() { return {}; });

    Promise.all([
      defaultBranchPromise,
      _origFetch(API_BASE + '/api/v1/projects/' + projectId + '/version-control/tree', { headers: headers })
        .then(function(r) { return r.ok ? r.json() : {}; })
        .catch(function() { return {}; })
    ]).then(function(data) {
      branchId = (data[0] && data[0].branchId) || branchId || '';
      var branches = (data[1] && data[1].branches) || [];
      if (!branchId && branches.length === 1) branchId = branches[0].branchId;
      var branch = null;
      for (var i = 0; i < branches.length; i++) {
        if (branches[i].branchId === branchId) { branch = branches[i]; break; }
      }
      if (select) {
        select.innerHTML = branches.map(function(b) {
          var selected = b.branchId === branchId ? ' selected' : '';
          return '<option value="' + escapeHtml(b.branchId) + '"' + selected + '>' + escapeHtml(b.name || b.branchId.substring(0,8)) + '</option>';
        }).join('');
      }
      var name = branch ? branch.name : (branchId ? branchId.substring(0,8) : 'main');
      ind.innerHTML = '🌿 Branch: ' + escapeHtml(name);
      ind.title = branchId ? ('Branch ID: ' + branchId) : 'No branch selected; using main/default context';
      if (wrap) wrap.setAttribute('data-loaded', 'true');
    }).catch(function() {
      ind.innerHTML = '🌿 Branch: main';
      if (wrap) wrap.setAttribute('data-loaded', 'true');
    });
  }

  function applySelectedBranch() {
    var projectId = getProjectIdFromUrl();
    var select = document.getElementById('syson-branch-select');
    var ind = document.getElementById('syson-branch-ind');
    var btn = document.getElementById('syson-branch-apply');
    if (!projectId || !select || !select.value) return;
    var branchId = select.value;
    if (btn) { btn.disabled = true; btn.textContent = 'Loading…'; }
    if (ind) ind.innerHTML = '🌿 Applying…';
    _origFetch(API_BASE + '/api/v1/projects/' + projectId + '/version-control/apply-branch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + state.token },
      body: JSON.stringify({ branchId: branchId })
    }).then(function(r) {
      if (!r.ok) return r.json().then(function(e) { throw new Error(e.error || e.message || 'HTTP ' + r.status); });
      return r.json();
    }).then(function(result) {
      try { localStorage.setItem('syson-vc-branch-' + projectId, branchId); } catch(e) {}
      if (btn) { btn.textContent = 'Applied'; }
      if (ind) ind.innerHTML = '🌿 Branch: ' + escapeHtml(result.name || branchId.substring(0,8));
      // Hard reload so the Sirius workbench fully re-bootstraps, dropping the
      // old WebSocket subscriptions and Apollo cache. The backend has already
      // disposed the editing context processor, so this forces a cold load
      // from document.content with the projected branch content.
      setTimeout(function() { window.location.reload(); }, 450);
    }).catch(function(err) {
      if (btn) { btn.disabled = false; btn.textContent = 'Apply'; }
      if (ind) ind.innerHTML = '🌿 Error';
      alert('Failed to apply branch: ' + err.message);
    });
  }

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
      btn.style.cssText = 'margin-left:8px;padding:2px 8px;font-size:11px;background:#261e58;color:#fff;border:none;border-radius:3px;cursor:pointer;vertical-align:middle;';
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

  function isProjectEditorUrl() {
    return /\/projects\/[^/]+\/edit(?:\/|$)/.test(window.location.pathname);
  }

  function findEditorHeader() {
    // Keep save/branch controls inside the opened project editor only. Avoid
    // mounting into the project browser header where no model is open.
    if (!isProjectEditorUrl()) return null;
    return document.querySelector('header [class*="toolbar"]')
        || document.querySelector('[class*="navigationBar"]')
        || document.querySelector('header');
  }

  function installEditorChromeRouteGuard(callback) {
    if (document.body) {
      try {
        var observer = new MutationObserver(function() { setTimeout(callback, 50); });
        observer.observe(document.body, { childList: true, subtree: true });
      } catch(e) {}
    }
    window.addEventListener('popstate', function() { setTimeout(callback, 50); });
    if (!history.__sysonEditorChromePatched) {
      var origPushState = history.pushState;
      var origReplaceState = history.replaceState;
      history.pushState = function() {
        var ret = origPushState.apply(this, arguments);
        setTimeout(function() { window.dispatchEvent(new Event('syson-route-changed')); }, 0);
        return ret;
      };
      history.replaceState = function() {
        var ret = origReplaceState.apply(this, arguments);
        setTimeout(function() { window.dispatchEvent(new Event('syson-route-changed')); }, 0);
        return ret;
      };
      history.__sysonEditorChromePatched = true;
    }
    window.addEventListener('syson-route-changed', function() { setTimeout(callback, 50); });
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
    overlay.style.cssText = 'position:fixed;top:0;right:0;width:480px;height:100vh;background:#1a1a2e;color:#e0e0e0;z-index:10001;overflow-y:auto;box-shadow:-4px 0 20px rgba(0,0,0,0.5);font-family:Roboto,Helvetica Neue,Arial,sans-serif;';

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
      menu.style.cssText = 'position:fixed;z-index:10002;background:#1e293b;border:1px solid #475569;border-radius:4px;box-shadow:0 2px 8px rgba(0,0,0,0.3);min-width:180px;font-family:Roboto,Helvetica Neue,Arial,sans-serif;font-size:13px;';

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
      banner.style.cssText = 'background:#7c2d12;color:#fed7aa;padding:6px 12px;font-size:11px;font-family:Roboto,Helvetica Neue,Arial,sans-serif;border-bottom:1px solid #9a3412;display:flex;align-items:center;gap:6px;';
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
      document.addEventListener('DOMContentLoaded', function() { mountUserBar(); handleAdminDeepLink(); injectHistoryButton(); injectElementLockUI(); injectSaveButton(); injectBranchIndicator(); });
    } else {
      mountUserBar();
      handleAdminDeepLink();
      injectHistoryButton();
      injectElementLockUI();
      injectSaveButton();
      injectBranchIndicator();
    }
  } else {
    // Not authenticated — show login and block app load
    blockApp();
    showLogin('');
  }
})();
