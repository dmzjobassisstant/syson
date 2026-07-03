#!/usr/bin/env node
/**
 * gateway_client.js
 *
 * Backend communication script for the SaaS to talk to Hermes Gateway.
 *
 * Usage:
 *   node gateway_client.js "Create a package called VehicleModel with 3 part definitions"
 *
 * Or as a module:
 *   const { sendToHermes } = require('./gateway_client');
 *   const result = await sendToHermes("What elements are in the Scooter1 project?");
 *
 * Environment:
 *   HERMES_GATEWAY_URL  — Hermes gateway endpoint (default: http://localhost:8642)
 *   HERMES_AUTH_TOKEN    — Auth token (must match the one in Hermes container)
 */

const http = require('http');
const https = require('https');
const { URL } = require('url');

const GATEWAY_URL = process.env.HERMES_GATEWAY_URL || 'http://localhost:8642';
const AUTH_TOKEN = process.env.HERMES_AUTH_TOKEN;

if (!AUTH_TOKEN) {
  console.error('ERROR: HERMES_AUTH_TOKEN environment variable is required.');
  process.exit(1);
}

/**
 * Send a chat message to the Hermes Gateway and return the response.
 *
 * @param {string} message - The user's chat input
 * @param {object} opts    - Optional: { sessionId, context, timeout }
 * @returns {Promise<object>} - { reply, sessionId, toolCalls, tokensUsed }
 */
async function sendToHermes(message, opts = {}) {
  const { sessionId = null, context = {}, timeout = 120000 } = opts;

  const payload = JSON.stringify({
    message,
    sessionId,
    context,
    auth: { token: AUTH_TOKEN },
  });

  const url = new URL(GATEWAY_URL.endsWith('/') ? GATEWAY_URL + 'api/chat' : GATEWAY_URL + '/api/chat');
  const transport = url.protocol === 'https:' ? https : http;

  return new Promise((resolve, reject) => {
    const req = transport.request(
      {
        hostname: url.hostname,
        port: url.port || (url.protocol === 'https:' ? 443 : 80),
        path: url.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${AUTH_TOKEN}`,
          'Content-Length': Buffer.byteLength(payload),
        },
        timeout,
      },
      (res) => {
        let body = '';
        res.on('data', (chunk) => (body += chunk));
        res.on('end', () => {
          if (res.statusCode !== 200) {
            reject(new Error(`Hermes Gateway returned ${res.statusCode}: ${body.substring(0, 500)}`));
            return;
          }
          try {
            const data = JSON.parse(body);
            resolve({
              reply: data.reply || data.response || data.message || '',
              sessionId: data.sessionId || data.session_id || null,
              toolCalls: data.toolCalls || data.tool_calls || [],
              tokensUsed: data.tokensUsed || data.usage || null,
              raw: data,
            });
          } catch (e) {
            reject(new Error(`Failed to parse Hermes response: ${e.message}\nRaw: ${body.substring(0, 500)}`));
          }
        });
      }
    );

    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('Request timed out')); });
    req.write(payload);
    req.end();
  });
}

/**
 * Stream a chat message (SSE — Server-Sent Events).
 * Useful for real-time UI updates as Hermes processes tools.
 *
 * @param {string} message
 * @param {function} onEvent - Callback for each SSE event
 * @returns {Promise<void>}
 */
async function streamFromHermes(message, onEvent) {
  const payload = JSON.stringify({ message, stream: true, auth: { token: AUTH_TOKEN } });
  const url = new URL(GATEWAY_URL + '/api/chat/stream');
  const transport = url.protocol === 'https:' ? https : http;

  return new Promise((resolve, reject) => {
    const req = transport.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${AUTH_TOKEN}`,
          'Accept': 'text/event-stream',
        },
      },
      (res) => {
        let buffer = '';
        res.on('data', (chunk) => {
          buffer += chunk.toString();
          const lines = buffer.split('\n');
          buffer = lines.pop(); // keep incomplete line
          for (const line of lines) {
            if (line.startsWith('data: ')) {
              try { onEvent(JSON.parse(line.slice(6))); } catch (_) {}
            }
          }
        });
        res.on('end', () => resolve());
      }
    );
    req.on('error', reject);
    req.write(payload);
    req.end();
  });
}

// ── CLI entry point ──────────────────────────────────────
if (require.main === module) {
  const message = process.argv[2];
  if (!message) {
    console.error('Usage: node gateway_client.js "your message here"');
    process.exit(1);
  }

  sendToHermes(message)
    .then((result) => {
      console.log('─'.repeat(60));
      console.log('Reply:', result.reply);
      if (result.toolCalls.length > 0) {
        console.log('Tool calls:', result.toolCalls.length);
      }
      console.log('Session:', result.sessionId);
      console.log('─'.repeat(60));
    })
    .catch((err) => {
      console.error('Error:', err.message);
      process.exit(1);
    });
}

module.exports = { sendToHermes, streamFromHermes };
