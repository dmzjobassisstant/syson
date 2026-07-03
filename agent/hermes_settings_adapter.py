"""
Hermes Settings Adapter
=======================

Bridges the existing SysON Agent settings UI (POST /api/agent/settings on port 5000)
to the Hermes Agent container configuration.

When a user saves LLM settings via the UI, this adapter:

1. Writes the API key into Hermes's .env file   (DEEPSEEK_API_KEY)
2. Updates config.yaml endpoint/model if changed (deepseek base_url / model name)
3. Restarts the Hermes container to pick up new env vars
4. Reports status back so the UI can show Hermes readiness

Architecture
------------
    ┌─────────────┐   POST /api/agent/settings    ┌──────────────────┐
    │  auth.js    │ ─────────────────────────────► │  Old Agent       │
    │ Settings UI │  {llm_endpoint, llm_api_key,   │  server.py:5000  │
    │             │   llm_model, syson_url}        │  ↓ calls         │
    └─────────────┘                                 │  THIS ADAPTER    │
                                                     │  ↓ writes        │
                                                     │  .env + YAML     │
                                                     │  ↓ restarts      │
                                                     │  docker-compose  │
                                                     └──────────────────┘

The old agent continues to work (it saves to ~/.syson/agent_settings.json as before).
Hermes is started/updated in parallel — both systems share the same API key.

Security
--------
- The .env file is written with 0600 permissions (owner read/write only).
- The API key is NEVER returned in any API response.
- The adapter writes the key only to the local .env file — never logs it.

Author: Hermes Integration
"""

import os
import re
import json
import shutil
import logging
import subprocess
from pathlib import Path
from datetime import datetime
from typing import Optional

logger = logging.getLogger(__name__)

# ============================================================
# Paths — resolve relative to the hermes-integration directory
# ============================================================

# The adapter lives in /root/syson-fork/agent/, hermes-integration is sibling's child
_AGENT_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _AGENT_DIR.parent  # /root/syson-fork
HERMES_DIR = _REPO_ROOT / "hermes-integration"
ENV_FILE = HERMES_DIR / ".env"
ENV_EXAMPLE = HERMES_DIR / ".env.example"
CONFIG_FILE = HERMES_DIR / "hermes-home" / "config.yaml"
COMPOSE_FILE = HERMES_DIR / "docker-compose.yml"

# Where the old agent stores its settings (shared read)
OLD_SETTINGS_FILE = Path(os.environ.get(
    "SYSON_AGENT_SETTINGS",
    str(Path.home() / ".syson" / "agent_settings.json")
))


# ============================================================
# .env Management
# ============================================================

def _ensure_env_exists():
    """Create .env from .env.example if it doesn't exist."""
    if not ENV_FILE.exists():
        if ENV_EXAMPLE.exists():
            shutil.copy2(ENV_EXAMPLE, ENV_FILE)
            logger.info(f"Created {ENV_FILE} from .env.example")
        else:
            # Write a minimal .env if no example exists
            ENV_FILE.write_text(
                "# Hermes Agent .env — managed by hermes_settings_adapter.py\n"
                f"# Created: {datetime.now().isoformat()}\n\n"
                "HERMES_AUTH_TOKEN=changeme\n"
                "SYSON_AUTH_TOKEN=changeme\n"
                "DEEPSEEK_API_KEY=\n"
            )
            logger.info(f"Created minimal {ENV_FILE}")
    os.chmod(ENV_FILE, 0o600)


def _update_env_var(key: str, value: str):
    """
    Update or insert a KEY=VALUE line in the .env file.
    Preserves comments and other variables.
    """
    _ensure_env_exists()
    lines = ENV_FILE.read_text().splitlines()

    pattern = re.compile(rf'^{re.escape(key)}\s*=')
    found = False

    for i, line in enumerate(lines):
        if pattern.match(line):
            lines[i] = f"{key}={value}"
            found = True
            break

    if not found:
        lines.append(f"{key}={value}")

    ENV_FILE.write_text("\n".join(lines) + "\n")
    os.chmod(ENV_FILE, 0o600)


def _generate_auth_token() -> str:
    """Generate a random Hermes auth token if none exists."""
    try:
        result = subprocess.run(
            ["openssl", "rand", "-hex", "32"],
            capture_output=True, text=True, timeout=5
        )
        return result.stdout.strip()
    except Exception:
        # Fallback — use Python secrets
        import secrets
        return secrets.token_hex(32)


def _ensure_hermes_auth_token() -> str:
    """Ensure HERMES_AUTH_TOKEN is set in .env, generate if default."""
    _ensure_env_exists()
    content = ENV_FILE.read_text()
    match = re.search(r'^HERMES_AUTH_TOKEN\s*=\s*(.+)$', content, re.MULTILINE)
    if match:
        token = match.group(1).strip()
        if token and token != "changeme-to-a-long-random-string" and token != "changeme":
            return token

    # Generate a new one
    token = _generate_auth_token()
    _update_env_var("HERMES_AUTH_TOKEN", token)
    logger.info("Generated new HERMES_AUTH_TOKEN")
    return token


# ============================================================
# config.yaml Management
# ============================================================

def _update_config_yaml(endpoint: Optional[str] = None, model: Optional[str] = None):
    """
    Update config.yaml with the endpoint/model from settings.
    Only updates if values are provided and differ.
    """
    if not CONFIG_FILE.exists():
        logger.warning(f"config.yaml not found at {CONFIG_FILE}")
        return

    content = CONFIG_FILE.read_text()
    changed = False

    if model:
        # Update the default model
        new_content = re.sub(
            r'(\s+default:\s*)\S+',
            rf'\g<1>{model}',
            content,
            count=1
        )
        if new_content != content:
            content = new_content
            changed = True

    if endpoint:
        # Map the old agent's chat completions endpoint to base_url
        # e.g. https://api.deepseek.com/v1/chat/completions → https://api.deepseek.com/v1
        base_url = endpoint.rstrip("/")
        base_url = re.sub(r'/chat/completions$', '', base_url)
        base_url = re.sub(r'/completions$', '', base_url)

        new_content = re.sub(
            r'(\s+base_url:\s*")([^"]*)(")',
            rf'\g<1>{base_url}\g<3>',
            content,
            count=1
        )
        if new_content != content:
            content = new_content
            changed = True

    if changed:
        # Backup before writing
        backup = CONFIG_FILE.with_suffix('.yaml.bak')
        shutil.copy2(CONFIG_FILE, backup)
        CONFIG_FILE.write_text(content)
        logger.info(f"Updated config.yaml (backup saved to {backup})")


# ============================================================
# Docker Compose Management
# ============================================================

def _docker_compose_cmd() -> list:
    """Return the correct docker compose command (v2 or v1)."""
    try:
        result = subprocess.run(
            ["docker", "compose", "version"],
            capture_output=True, timeout=5
        )
        if result.returncode == 0:
            return ["docker", "compose"]
    except Exception:
        pass
    return ["docker-compose"]


def _restart_hermes() -> dict:
    """
    Restart (or start) the Hermes stack via docker compose.
    Returns a status dict.
    """
    if not COMPOSE_FILE.exists():
        return {"running": False, "error": f"docker-compose.yml not found at {COMPOSE_FILE}"}

    cmd = _docker_compose_cmd()

    try:
        # Pull latest image (best-effort, don't fail if offline)
        subprocess.run(
            cmd + ["pull", "hermes"],
            cwd=str(HERMES_DIR),
            capture_output=True, text=True, timeout=120
        )
    except Exception:
        pass  # Offline is OK — use cached image

    try:
        result = subprocess.run(
            cmd + ["up", "-d"],
            cwd=str(HERMES_DIR),
            capture_output=True, text=True, timeout=180
        )

        if result.returncode == 0:
            logger.info("Hermes stack started/restarted successfully")
            return {
                "running": True,
                "started_at": datetime.now().isoformat(),
                "output": result.stdout.strip()[-500:] if result.stdout else ""
            }
        else:
            logger.error(f"Hermes start failed: {result.stderr}")
            return {
                "running": False,
                "error": result.stderr.strip()[-500:] if result.stderr else "unknown error"
            }
    except subprocess.TimeoutExpired:
        return {"running": False, "error": "docker compose up timed out (180s)"}
    except FileNotFoundError:
        return {"running": False, "error": "docker/docker-compose not found in PATH"}
    except Exception as e:
        return {"running": False, "error": str(e)}


def _get_hermes_status() -> dict:
    """Check if the Hermes container is running."""
    try:
        result = subprocess.run(
            ["docker", "ps", "--filter", "name=syson-hermes", "--format", "{{.Names}} {{.Status}}"],
            capture_output=True, text=True, timeout=10
        )
        output = result.stdout.strip()
        if "syson-hermes" in output:
            return {"running": True, "container_status": output}
        return {"running": False, "container_status": "not running"}
    except Exception as e:
        return {"running": False, "error": str(e)}


# ============================================================
# SYSON_AUTH_TOKEN — get JWT for MCP bridge
# ============================================================

def _ensure_syson_auth_token(syson_url: str = "http://localhost:8080"):
    """
    Authenticate to SysON as admin and store JWT for the MCP bridge.
    The bridge uses this internally — it's not exposed to the internet.
    """
    _ensure_env_exists()
    content = ENV_FILE.read_text()
    match = re.search(r'^SYSON_AUTH_TOKEN\s*=\s*(.+)$', content, re.MULTILINE)
    if match:
        token = match.group(1).strip()
        if token and token != "changeme-syson-jwt-or-api-key" and token != "changeme":
            return token  # Already set

    # Try to authenticate
    try:
        import urllib.request
        import urllib.error

        login_url = syson_url.rstrip("/") + "/api/auth/login"
        data = json.dumps({"username": "admin", "password": "admin"}).encode()
        req = urllib.request.Request(
            login_url, data=data,
            headers={"Content-Type": "application/json"},
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read())
            token = body.get("accessToken") or body.get("token") or ""
            if token:
                _update_env_var("SYSON_AUTH_TOKEN", token)
                logger.info("Obtained and stored SysON JWT for MCP bridge")
                return token
    except Exception as e:
        logger.warning(f"Could not auto-obtain SysON JWT: {e}")

    return ""


# ============================================================
# Main Entry Point — called by server.py after settings save
# ============================================================

def sync_to_hermes(settings: dict) -> dict:
    """
    Synchronize settings from the old agent to the Hermes stack.

    Called after save_settings() in server.py — receives the full
    merged settings dict.

    Args:
        settings: The full settings dict (llm_endpoint, llm_api_key,
                  llm_model, syson_url)

    Returns:
        {
            "synced": bool,
            "hermes": {...},        # container status
            "auth_token": str,      # the Hermes gateway token (for display once)
            "config_updated": bool, # whether config.yaml was changed
            "message": str
        }
    """
    result = {
        "synced": False,
        "hermes": {},
        "auth_token": "",
        "config_updated": False,
        "message": ""
    }

    # Only sync if we have an API key
    api_key = settings.get("llm_api_key")
    if not api_key:
        result["message"] = "No API key in settings — Hermes not updated"
        result["hermes"] = _get_hermes_status()
        return result

    logger.info("Syncing settings to Hermes stack...")

    try:
        # 1. Write API key to .env
        _update_env_var("DEEPSEEK_API_KEY", api_key)

        # 2. Ensure auth tokens exist
        hermes_token = _ensure_hermes_auth_token()
        syson_url = settings.get("syson_url", "http://localhost:8080")
        _ensure_syson_auth_token(syson_url)

        # 3. Update config.yaml if endpoint/model changed
        endpoint = settings.get("llm_endpoint") or None
        model = settings.get("llm_model") or None
        if endpoint or model:
            _update_config_yaml(endpoint=endpoint, model=model)
            result["config_updated"] = True

        # 4. Restart Hermes container
        restart_result = _restart_hermes()
        result["hermes"] = restart_result
        result["auth_token"] = hermes_token
        result["synced"] = restart_result.get("running", False)

        if result["synced"]:
            result["message"] = (
                "Settings synced to Hermes. Container restarted. "
                f"Gateway running on port 8642."
            )
        else:
            result["message"] = (
                f"Settings written to .env, but Hermes failed to start: "
                f"{restart_result.get('error', 'unknown')}"
            )

    except Exception as e:
        logger.exception("Failed to sync settings to Hermes")
        result["message"] = f"Sync failed: {e}"
        result["hermes"] = _get_hermes_status()

    return result
