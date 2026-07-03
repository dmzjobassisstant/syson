"""
SysON Agent — REST API Server

Flask app that exposes the agent engine to the frontend.
The Java backend proxies chat requests to this service, or the frontend can call directly.

Endpoints:
  POST /api/agent/process          — Process a user request (main entry point)
  GET  /api/agent/conversations    — List conversations for a project
  GET  /api/agent/conversation/:id — Get conversation with messages
  DELETE /api/agent/conversation/:id — Delete conversation
  GET  /api/agent/health           — Health check
  GET  /api/agent/settings         — Get settings (API key never returned)
  POST /api/agent/settings         — Save settings (endpoint, api_key, model)
"""

import os
import sys
import json
import logging
from pathlib import Path
from typing import Optional
from flask import Flask, request, jsonify

# Ensure agent package is importable
sys.path.insert(0, str(Path(__file__).parent))

from engine import AgentEngine

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(name)s: %(message)s')
logger = logging.getLogger(__name__)

app = Flask(__name__)

# ============================================================
# Settings Management (stored in environment / file, never returned)
# ============================================================

SETTINGS_FILE = os.environ.get("SYSON_AGENT_SETTINGS", str(Path.home() / ".syson" / "agent_settings.json"))

# Keys that map to environment variables for the running process
ENV_KEY_MAP = {
    "llm_endpoint": "SYSON_LLM_ENDPOINT",
    "llm_api_key": "SYSON_LLM_API_KEY",
    "llm_model": "SYSON_LLM_MODEL",
    "syson_url": "SYSON_URL",
}


def load_settings() -> dict:
    """Load settings from file."""
    if os.path.exists(SETTINGS_FILE):
        with open(SETTINGS_FILE) as f:
            return json.load(f)
    return {}


def save_settings(settings: dict):
    """Save settings to file and update environment variables."""
    os.makedirs(os.path.dirname(SETTINGS_FILE), exist_ok=True)
    
    # Don't persist syson_token — it's per-session
    persist = {k: v for k, v in settings.items() if k != 'syson_token'}
    
    with open(SETTINGS_FILE, 'w') as f:
        json.dump(persist, f, indent=2)
    
    # Update environment variables for the running process
    for key, env_var in ENV_KEY_MAP.items():
        if key in settings and settings[key]:
            os.environ[env_var] = settings[key]
    
    # Set file permissions (owner read/write only — protects API key)
    os.chmod(SETTINGS_FILE, 0o600)
    logger.info("Settings saved and environment updated")


def get_config() -> dict:
    """Get current configuration from settings file + env vars."""
    file_settings = load_settings()
    return {
        'llm_endpoint': os.environ.get('SYSON_LLM_ENDPOINT', file_settings.get('llm_endpoint', '')),
        'llm_api_key': os.environ.get('SYSON_LLM_API_KEY', file_settings.get('llm_api_key', '')),
        'llm_model': os.environ.get('SYSON_LLM_MODEL', file_settings.get('llm_model', '')),
        'syson_url': os.environ.get('SYSON_URL', file_settings.get('syson_url', 'http://localhost:8080')),
    }


def get_engine(syson_token: str = None) -> AgentEngine:
    """Create an AgentEngine with current settings."""
    config = get_config()
    
    # Token from request or default admin
    token = syson_token or os.environ.get('SYSON_TOKEN', '')
    
    if not config['llm_endpoint']:
        raise ValueError("LLM endpoint not configured. POST to /api/agent/settings first.")
    if not config['llm_api_key']:
        raise ValueError("LLM API key not configured. POST to /api/agent/settings first.")
    
    return AgentEngine(
        syson_url=config['syson_url'],
        syson_token=token,
        llm_endpoint=config['llm_endpoint'],
        llm_api_key=config['llm_api_key'],
        llm_model=config['llm_model']
    )


# ============================================================
# Endpoints
# ============================================================

@app.route('/api/agent/health', methods=['GET'])
def health():
    config = get_config()
    return jsonify({
        'status': 'ok',
        'llm_configured': bool(config['llm_endpoint'] and config['llm_api_key']),
        'llm_endpoint': config['llm_endpoint'][:50] + '...' if config['llm_endpoint'] else '(not set)',
        'llm_model': config['llm_model'],
        'syson_url': config['syson_url']
    })


@app.route('/api/agent/settings', methods=['GET'])
def get_settings():
    """Get settings — API key is NEVER returned. Only show whether it's set."""
    config = get_config()

    # Include Hermes container status so the UI can show if Hermes is running
    hermes_status = {"running": False}
    try:
        from hermes_settings_adapter import _get_hermes_status
        hermes_status = _get_hermes_status()
    except Exception:
        pass

    return jsonify({
        'llm_endpoint': config['llm_endpoint'],
        'llm_model': config['llm_model'],
        'syson_url': config['syson_url'],
        'api_key_set': bool(config['llm_api_key']),
        'hermes': hermes_status
    })


@app.route('/api/agent/settings', methods=['POST'])
def set_settings():
    """Save settings. API key is stored as environment variable, never returned."""
    data = request.json or {}

    settings = {}
    if 'llm_endpoint' in data:
        settings['llm_endpoint'] = data['llm_endpoint']
    if 'llm_api_key' in data and data['llm_api_key']:
        settings['llm_api_key'] = data['llm_api_key']
    if 'llm_model' in data:
        settings['llm_model'] = data['llm_model']
    if 'syson_url' in data:
        settings['syson_url'] = data['syson_url']

    # Merge with existing
    existing = load_settings()
    existing.update(settings)
    save_settings(existing)

    # ── Hermes sync ──────────────────────────────────────────
    # After saving to the old agent's settings file, propagate the
    # API key + endpoint to the Hermes container stack so both
    # systems share the same LLM credentials.
    hermes_status = {"synced": False, "message": "Adapter not called"}
    try:
        from hermes_settings_adapter import sync_to_hermes
        hermes_status = sync_to_hermes(existing)
        logger.info(f"Hermes sync result: {hermes_status.get('message', '?')}")
    except ImportError:
        logger.warning("hermes_settings_adapter not found — Hermes sync skipped")
        hermes_status = {"synced": False, "message": "Adapter module not installed"}
    except Exception as e:
        logger.exception("Hermes settings sync failed")
        hermes_status = {"synced": False, "message": f"Sync error: {e}"}

    return jsonify({
        'status': 'ok',
        'message': 'Settings saved',
        'api_key_set': bool(existing.get('llm_api_key')),
        'hermes': hermes_status
    })


@app.route('/api/agent/process', methods=['POST'])
def process():
    """Main entry point — process a user request through the agent."""
    data = request.json or {}
    
    project_id = data.get('projectId')
    prompt = data.get('prompt', '')
    conversation_id = data.get('conversationId')
    syson_token = data.get('sysonToken') or request.headers.get('Authorization', '').replace('Bearer ', '')
    
    if not project_id:
        return jsonify({'error': 'projectId is required'}), 400
    if not prompt:
        return jsonify({'error': 'prompt is required'}), 400
    
    try:
        engine = get_engine(syson_token)
    except ValueError as e:
        return jsonify({'error': str(e)}), 400
    
    try:
        result = engine.process_request(project_id, prompt, conversation_id)
        return jsonify(result)
    except Exception as e:
        logger.exception("Agent processing failed")
        return jsonify({'error': str(e), 'success': False}), 500


@app.route('/api/agent/conversations', methods=['GET'])
def conversations():
    project_id = request.args.get('projectId')
    if not project_id:
        return jsonify({'error': 'projectId query param required'}), 400
    
    config = get_config()
    db_path = os.environ.get("SYSON_AGENT_DB", str(Path.home() / ".syson" / "agent_memory.db"))
    
    from memory import ConversationMemory
    mem = ConversationMemory(db_path)
    convs = mem.list_conversations(project_id)
    return jsonify(convs)


@app.route('/api/agent/conversation/<conv_id>', methods=['GET'])
def get_conversation(conv_id):
    project_id = request.args.get('projectId')
    if not project_id:
        return jsonify({'error': 'projectId query param required'}), 400
    
    try:
        engine = get_engine()
        conv = engine.get_conversation(project_id, conv_id)
        if conv:
            return jsonify(conv)
        return jsonify({'error': 'Conversation not found'}), 404
    except ValueError as e:
        return jsonify({'error': str(e)}), 400


@app.route('/api/agent/conversation/<conv_id>', methods=['DELETE'])
def delete_conversation(conv_id):
    db_path = os.environ.get("SYSON_AGENT_DB", str(Path.home() / ".syson" / "agent_memory.db"))
    from memory import ConversationMemory
    mem = ConversationMemory(db_path)
    mem.delete_conversation(conv_id)
    return jsonify({'status': 'ok'})


# ============================================================
# Hermes Proxy — forwards chat to the Hermes API server (port 8642)
# Keeps HERMES_AUTH_TOKEN server-side; browser never sees it.
# ============================================================

import urllib.request
import urllib.error

HERMES_API_URL = os.environ.get("HERMES_API_URL", "http://127.0.0.1:8642")


def _get_hermes_auth_token() -> str:
    """Read the Hermes API_SERVER_KEY from the .env file."""
    env_path = Path(__file__).resolve().parent.parent / "hermes-integration" / ".env"
    if env_path.exists():
        for line in env_path.read_text().splitlines():
            if line.startswith("HERMES_AUTH_TOKEN="):
                token = line.split("=", 1)[1].strip()
                if token and token != "changeme-to-a-long-random-string":
                    return token
    return ""


def _hermes_request(method: str, path: str, body: Optional[dict] = None) -> tuple:
    """Make an authenticated request to the Hermes API server. Returns (status_code, json_response)."""
    token = _get_hermes_auth_token()
    if not token:
        return 503, {"error": "Hermes auth token not configured"}

    url = f"{HERMES_API_URL}{path}"
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        }
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            err_body = json.loads(e.read())
        except Exception:
            err_body = {"error": str(e)}
        return e.code, err_body
    except urllib.error.URLError as e:
        return 503, {"error": f"Hermes API unreachable: {e.reason}"}
    except Exception as e:
        return 502, {"error": str(e)}


@app.route('/api/hermes/health', methods=['GET'])
def hermes_health():
    """Proxy health check to Hermes."""
    status, body = _hermes_request("GET", "/health")
    return jsonify(body), (200 if status == 200 else status)


@app.route('/api/hermes/sessions', methods=['GET'])
def hermes_list_sessions():
    """List Hermes sessions."""
    status, body = _hermes_request("GET", "/api/sessions")
    return jsonify(body), (200 if status == 200 else status)


@app.route('/api/hermes/sessions', methods=['POST'])
def hermes_create_session():
    """Create a new Hermes session."""
    data = request.json or {}
    title = data.get('title', 'SysON Chat')
    status, body = _hermes_request("POST", "/api/sessions", {"title": title})
    # If title conflict (400), retry with a unique suffix
    if status == 400 and "already in use" in str(body).lower():
        import uuid
        unique_title = f"{title}-{uuid.uuid4().hex[:6]}"
        status, body = _hermes_request("POST", "/api/sessions", {"title": unique_title})
    # Accept 200 and 201 (Created) as success
    return jsonify(body), (200 if status in (200, 201) else status)


@app.route('/api/hermes/sessions/<session_id>', methods=['GET'])
def hermes_get_session(session_id):
    """Get a Hermes session with messages."""
    status, body = _hermes_request("GET", f"/api/sessions/{session_id}")
    return jsonify(body), (200 if status == 200 else status)


@app.route('/api/hermes/sessions/<session_id>', methods=['DELETE'])
def hermes_delete_session(session_id):
    """Delete a Hermes session."""
    status, body = _hermes_request("DELETE", f"/api/sessions/{session_id}")
    return jsonify(body), (200 if status == 200 else status)


@app.route('/api/hermes/sessions/<session_id>/chat', methods=['POST'])
def hermes_session_chat(session_id):
    """
    Send a message to a Hermes session and return the assistant response.
    This is the main chat endpoint — the frontend calls this instead of
    the old /api/agent/process.
    """
    data = request.json or {}
    message = data.get('message') or data.get('prompt') or ''
    if not message:
        return jsonify({'error': 'message is required'}), 400

    status, body = _hermes_request("POST", f"/api/sessions/{session_id}/chat", {"message": message})

    if status == 200:
        # Normalize the response for the frontend.
        # Hermes may return content as a string (plain text) or as an
        # array of content blocks [{type: "text", text: "..."}, ...].
        # Always extract plain text for the frontend.
        msg_content = ''
        if isinstance(body, dict):
            msg = body.get('message', body)
            raw = msg.get('content', '') if isinstance(msg, dict) else str(msg)
            if isinstance(raw, str):
                msg_content = raw
            elif isinstance(raw, list):
                msg_content = '\n'.join(
                    b.get('text', '') if isinstance(b, dict) and b.get('type') == 'text'
                    else str(b)
                    for b in raw
                )
            else:
                msg_content = str(raw)

        return jsonify({
            'response': msg_content,
            'conversationId': session_id,
            'session_id': session_id,
            'success': True,
            'hermes': True,
            'usage': body.get('usage', {}) if isinstance(body, dict) else {}
        })
    else:
        return jsonify(body), status


@app.route('/api/hermes/sessions/<session_id>/messages', methods=['GET'])
def hermes_session_messages(session_id):
    """Get message history for a Hermes session."""
    status, body = _hermes_request("GET", f"/api/sessions/{session_id}/messages")
    return jsonify(body), (200 if status == 200 else status)


# ============================================================
# Main
# ============================================================

if __name__ == '__main__':
    port = int(os.environ.get('SYSON_AGENT_PORT', '5000'))
    logger.info(f"Starting SysON Agent on port {port}")
    logger.info(f"Settings file: {SETTINGS_FILE}")
    config = get_config()
    logger.info(f"LLM configured: {bool(config['llm_endpoint'] and config['llm_api_key'])}")
    app.run(host='0.0.0.0', port=port, debug=False)
