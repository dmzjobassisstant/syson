#!/usr/bin/env python3
"""
gateway_client.py — Python alternative to the JS client.

Usage:
  python gateway_client.py "Create a package called VehicleModel"

As a module:
  from gateway_client import send_to_hermes
  result = send_to_hermes("List all requirements in the project")
"""

import os
import sys
import json
import urllib.request
import urllib.error

GATEWAY_URL = os.environ.get("HERMES_GATEWAY_URL", "http://localhost:8642")
AUTH_TOKEN = os.environ.get("HERMES_AUTH_TOKEN", "")


def send_to_hermes(message: str, session_id: str = None, timeout: int = 120) -> dict:
    """Send a chat message to the Hermes Gateway."""
    if not AUTH_TOKEN:
        raise RuntimeError("HERMES_AUTH_TOKEN environment variable is required")

    payload = json.dumps({
        "message": message,
        "sessionId": session_id,
        "auth": {"token": AUTH_TOKEN},
    }).encode()

    req = urllib.request.Request(
        f"{GATEWAY_URL.rstrip('/')}/api/chat",
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {AUTH_TOKEN}",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode())
            return {
                "reply": data.get("reply") or data.get("response") or "",
                "session_id": data.get("sessionId") or data.get("session_id"),
                "tool_calls": data.get("toolCalls") or [],
                "raw": data,
            }
    except urllib.error.HTTPError as e:
        body = e.read().decode()[:500]
        raise RuntimeError(f"Hermes Gateway returned {e.code}: {body}")


if __name__ == "__main__":
    msg = sys.argv[1] if len(sys.argv) > 1 else None
    if not msg:
        print("Usage: python gateway_client.py 'your message'")
        sys.exit(1)
    result = send_to_hermes(msg)
    print("─" * 60)
    print("Reply:", result["reply"])
    print("Session:", result["session_id"])
    print("─" * 60)
