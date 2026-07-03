# Hermes Settings Adapter — Architecture & Operations

## Overview

The **Hermes Settings Adapter** bridges the existing SysON Agent settings UI
(the "Settings" button in the chat panel) to the Hermes Agent container stack.
When a user saves their LLM API key through the UI, both systems receive it:

- The **legacy agent** (Python/Flask on port 5000) saves to `~/.syson/agent_settings.json`
- The **Hermes container** (Docker, API server on port 8642) gets the key via `.env` + container restart

This means you enter your API key **once** in the existing UI, and both agents work.

---

## How It Works

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        Settings UI (auth.js)                             │
│                                                                          │
│  User enters: endpoint, model, API key, SysON URL                        │
│  Clicks "Save"                                                           │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │
                               │ POST /api/agent/settings
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    Legacy Agent (server.py:5000)                         │
│                                                                          │
│  1. Merges new settings with existing                                    │
│  2. Saves to ~/.syson/agent_settings.json (0600 perms)                   │
│  3. Updates process env vars (SYSON_LLM_API_KEY, etc.)                   │
│  4. Calls hermes_settings_adapter.sync_to_hermes(merged_settings)       │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                  hermes_settings_adapter.py                              │
│                                                                          │
│  1. Writes DEEPSEEK_API_KEY → hermes-integration/.env                    │
│  2. Generates HERMES_AUTH_TOKEN (if not set, openssl rand -hex 32)       │
│  3. Obtains SYSON_AUTH_TOKEN (admin login → JWT for MCP bridge)          │
│  4. Updates config.yaml (base_url, model name) if changed                │
│  5. Runs `docker compose up -d` to restart Hermes + MCP bridge           │
│  6. Returns status: {synced, running, auth_token, message}              │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    Hermes Container Stack                                │
│                                                                          │
│  ┌─────────────────────┐     ┌──────────────────────┐                   │
│  │ syson-hermes        │     │ syson-mcp-bridge     │                   │
│  │ (API server :8642)  │────▶│ (MCP tools :3001)    │                   │
│  │                     │     │                      │                   │
│  │ Uses DEEPSEEK_API_  │     │ Uses SYSON_AUTH_     │                   │
│  │ KEY to call LLM     │     │ TOKEN for SysON API  │                   │
│  └─────────────────────┘     └──────────────────────┘                   │
│                                                                          │
│  Both on syson-internal Docker network                                   │
│  Internet blocked via HTTP_PROXY=block.invalid                           │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## File Inventory

### Modified Files

| File | Purpose |
|------|---------|
| `agent/server.py` | Patched `POST /api/agent/settings` and `GET /api/agent/settings` to call the adapter and include Hermes status |
| `frontend/syson/public/auth.js` | Updated settings modal to show Hermes container status (running/off badge, sync progress, sync result) |

### New Files

| File | Purpose |
|------|---------|
| `agent/hermes_settings_adapter.py` | The adapter module — bridges settings UI → Hermes .env + Docker restart |
| `hermes-integration/.env` | Created from `.env.example` (API keys live here, 0600 perms) |
| `hermes-integration/HERMES_SETTINGS_ADAPTER.md` | This document |

### Existing Integration Files (unchanged by this task)

| File | Purpose |
|------|---------|
| `hermes-integration/docker-compose.yml` | Hermes + MCP bridge container definitions |
| `hermes-integration/hermes-home/config.yaml` | Hermes agent configuration (model, tools, guardrails) |
| `hermes-integration/hermes-home/SOUL.md` | System prompt with full SysON API spec |
| `hermes-integration/mcp-bridge/server.py` | 13 MCP tools → SysON REST/GraphQL |
| `hermes-integration/mcp-bridge/Dockerfile` | Bridge container build |
| `hermes-integration/gateway/gateway_client.js` | Node.js client for Hermes API |
| `hermes-integration/gateway/gateway_client.py` | Python client for Hermes API |
| `hermes-integration/tests/test_integration.py` | 20-test automated suite |

---

## Security Model

### API Key Flow

1. User enters key in the Settings UI (HTTPS, auth.js)
2. `POST /api/agent/settings` sends it to the legacy agent
3. Legacy agent saves to `~/.syson/agent_settings.json` (0600 perms)
4. Adapter writes the same key to `hermes-integration/.env` (0600 perms)
5. Docker Compose reads `.env` and injects `DEEPSEEK_API_KEY` into the Hermes container
6. **The key is NEVER returned in any API response** — `GET /api/agent/settings` only returns `api_key_set: true/false`

### Hermes API Server Auth

- The Hermes API server on port 8642 requires a Bearer token (`API_SERVER_KEY`)
- This token is auto-generated as `HERMES_AUTH_TOKEN` (64-char hex, `openssl rand -hex 32`)
- Stored in `.env`, injected into the container as `API_SERVER_KEY`
- Without this token, all API requests return `401 Unauthorized`

### Network Isolation

- Hermes container has NO direct internet access (`HTTP_PROXY=block.invalid`)
- Only the MCP bridge can reach SysON backend (internal Docker network)
- The only exposed port is 8642 (API server)
- The MCP bridge has NO published ports — only Hermes can reach it

---

## User-Facing Behavior

### Settings Panel (auth.js)

When you open the Settings panel in the chat UI, you'll see:

1. **Standard fields**: LLM Endpoint, Model Name, API Key, SysON URL
2. **Hermes status box** (blue info panel):
   - 🟢 Running + container status — if Hermes is up
   - 🟡 Not running — if Hermes is down (will start on save)
   - Blue "Syncing to Hermes…" — during save
   - 🟢 "Synced & restarted" — on success
   - 🟠 Warning message — if sync had an issue

### Chat Status Bar

The chat status bar now shows:
- `✓ Agent ready (deepseek-chat) [Hermes ✓]` — both running
- `✓ Agent ready (deepseek-chat) [Hermes off]` — legacy agent only

### What Happens When You Save

1. Button shows "Saving…"
2. Hermes status shows "Syncing to Hermes…"
3. Backend writes `.env`, generates tokens, restarts containers (~5-15 seconds)
4. Button turns green "Saved ✓" or orange "Saved (Hermes issue)"
5. Panel closes after 2 seconds (success) or 4 seconds (warning)

---

## API Reference

### GET `/api/agent/settings`

Returns current settings (API key never exposed) + Hermes container status.

```json
{
  "llm_endpoint": "https://api.deepseek.com/v1/chat/completions",
  "llm_model": "deepseek-chat",
  "syson_url": "http://localhost:8080",
  "api_key_set": true,
  "hermes": {
    "running": true,
    "container_status": "syson-hermes Up 5 minutes"
  }
}
```

### POST `/api/agent/settings`

Saves settings and syncs to Hermes.

**Request:**
```json
{
  "llm_endpoint": "https://api.deepseek.com/v1/chat/completions",
  "llm_api_key": "sk-...",
  "llm_model": "deepseek-chat",
  "syson_url": "http://localhost:8080"
}
```

**Response:**
```json
{
  "status": "ok",
  "message": "Settings saved",
  "api_key_set": true,
  "hermes": {
    "synced": true,
    "hermes": {
      "running": true,
      "started_at": "2026-07-02T22:19:17.202112"
    },
    "auth_token": "b302471dc53140fe...",
    "config_updated": true,
    "message": "Settings synced to Hermes. Container restarted. Gateway running on port 8642."
  }
}
```

---

## Manual Operations

### Check Hermes Status

```bash
# Via API
curl -s http://localhost:5000/api/agent/settings | python3 -m json.tool

# Via Docker
docker ps --filter "name=syson-hermes" --filter "name=syson-mcp"

# Via health endpoint (needs auth token from .env)
TOKEN=$(grep '^HERMES_AUTH_TOKEN=' /root/syson-fork/hermes-integration/.env | cut -d= -f2)
curl -s http://localhost:8642/health
curl -s http://localhost:8642/v1/models -H "Authorization: Bearer $TOKEN"
```

### Restart Hermes Manually

```bash
cd /root/syson-fork/hermes-integration
docker compose restart hermes
```

### Update API Key Without UI

```bash
# Edit .env directly
vim /root/syson-fork/hermes-integration/.env
# Update DEEPSEEK_API_KEY=sk-...

# Restart
cd /root/syson-fork/hermes-integration
docker compose restart hermes
```

### Run Test Suite

```bash
cd /root/syson-fork/hermes-integration
python3 -m pytest tests/test_integration.py -v
```

### Troubleshooting

**Hermes container exits immediately:**
- Check logs: `docker logs syson-hermes`
- Ensure `.env` has valid `HERMES_AUTH_TOKEN` and `DEEPSEEK_API_KEY`
- Ensure `config.yaml` has `platforms.api_server.enabled: true`

**API server returns empty replies:**
- Ensure `API_SERVER_HOST=0.0.0.0` is set in docker-compose.yml
- Without it, the server binds to 127.0.0.1 inside the container and Docker port-forward can't reach it

**MCP bridge can't reach SysON:**
- Check `SYSON_AUTH_TOKEN` in `.env` — it must be a valid JWT
- The adapter auto-obtains one via admin/admin login, but it expires
- Re-save settings in the UI to refresh

**Settings save shows "Hermes issue":**
- Check the message field in the response
- Most common: Docker not running, or `.env` file missing
- Check: `docker ps` and `ls -la hermes-integration/.env`

---

## Test Results

```
======================== 20 passed in 70.45s ========================

Test Categories:
  1-3:   MCP Bridge health + tool discovery
  4-9:   Real SysON queries (search, tree, history, requirements, traceability)
  10-11: Hermes Gateway health + auth rejection
  12-16: Config validation (YAML, guardrails, toolset lockdown, API spec, MCP)
  17:    Gateway client libraries parse correctly
  18:    Cron traceability script runs
  19-20: Docker Compose structure + env vars
```
