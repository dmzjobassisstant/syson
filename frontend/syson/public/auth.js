/**
 * SysON Auth — RBAC login overlay + JWT interceptor.
 * Injected into index.html before the Sirius Web app loads.
 * Zero dependencies. Self-contained.
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
      bar.innerHTML = `
        <span>${state.email}</span>
        ${badgeHTML}
        <button id="syson-logout-btn" title="Sign out">Sign out</button>
      `;
      bar.style.display = 'flex';
      document.getElementById('syson-logout-btn').addEventListener('click', logout);
    };
    tryMount();
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
