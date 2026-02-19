/**
 * PocketMCP Stdio Bridge
 * 
 * Bridges Antigravity's stdio MCP transport to the phone's StreamableHTTP endpoint.
 * This allows Antigravity to natively discover and call PocketMCP tools.
 * 
 * Usage: node bridge.mjs <phone_url> <api_key>
 * Example: node bridge.mjs http://192.168.100.136:8080/mcp 8dQXg9Q9yieBMyFRtkoMldUIh07vXcov
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';

const PHONE_URL = process.argv[2] || 'http://192.168.100.136:8080/mcp';
const API_KEY = process.argv[3] || '';

let sessionId = null;
let cachedTools = null;

/**
 * Send a JSON-RPC request to the phone's MCP HTTP endpoint
 */
async function phoneMcpRequest(method, params = {}, id = 1) {
  const headers = {
    'Content-Type': 'application/json',
    'Accept': 'application/json, text/event-stream',
  };
  if (API_KEY) headers['X-API-Key'] = API_KEY;
  if (sessionId) headers['Mcp-Session-Id'] = sessionId;

  const body = JSON.stringify({ jsonrpc: '2.0', id, method, params });

  const response = await fetch(PHONE_URL, { method: 'POST', headers, body });

  // Capture session ID from response
  const newSessionId = response.headers.get('mcp-session-id');
  if (newSessionId) sessionId = newSessionId;

  const contentType = response.headers.get('content-type') || '';

  // Handle SSE responses
  if (contentType.includes('text/event-stream')) {
    const text = await response.text();
    const lines = text.split('\n');
    for (const line of lines) {
      if (line.startsWith('data: ')) {
        try {
          return JSON.parse(line.slice(6));
        } catch { /* skip non-JSON data lines */ }
      }
    }
    throw new Error('No valid JSON-RPC response found in SSE stream');
  }

  return await response.json();
}

/**
 * Initialize the HTTP session with the phone
 */
async function initPhoneSession() {
  const response = await phoneMcpRequest('initialize', {
    protocolVersion: '2025-03-26',
    capabilities: {},
    clientInfo: { name: 'pocket-mcp-bridge', version: '1.0.0' },
  });

  // Send initialized notification
  const headers = {
    'Content-Type': 'application/json',
    'Accept': 'application/json, text/event-stream',
  };
  if (API_KEY) headers['X-API-Key'] = API_KEY;
  if (sessionId) headers['Mcp-Session-Id'] = sessionId;

  await fetch(PHONE_URL, {
    method: 'POST',
    headers,
    body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
  });

  return response;
}

/**
 * Fetch and cache available tools from the phone
 */
async function fetchTools() {
  if (cachedTools) return cachedTools;
  const response = await phoneMcpRequest('tools/list', {}, 2);
  cachedTools = response.result?.tools || [];
  return cachedTools;
}

async function main() {
  // Initialize HTTP session with phone
  try {
    await initPhoneSession();
    await fetchTools();
    const toolNames = cachedTools.map(t => t.name).join(', ');
    process.stderr.write(`[phone-bridge] Connected! ${cachedTools.length} tools: ${toolNames}\n`);
  } catch (err) {
    process.stderr.write(`[phone-bridge] Failed to connect to phone at ${PHONE_URL}: ${err.message}\n`);
    process.exit(1);
  }

  // Create stdio MCP server for Antigravity
  const server = new Server(
    { name: 'phone', version: '1.0.0' },
    { capabilities: { tools: {} } }
  );

  // Proxy tools/list
  server.setRequestHandler(ListToolsRequestSchema, async () => {
    const tools = await fetchTools();
    return { tools };
  });

  // Proxy tools/call
  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: args } = request.params;
    try {
      const response = await phoneMcpRequest('tools/call', { name, arguments: args || {} }, Date.now());
      return response.result || { content: [{ type: 'text', text: JSON.stringify(response) }] };
    } catch (err) {
      return {
        content: [{ type: 'text', text: `Error calling ${name}: ${err.message}` }],
        isError: true,
      };
    }
  });

  // Connect to stdio for Antigravity
  const transport = new StdioServerTransport();
  await server.connect(transport);
  process.stderr.write('[phone-bridge] Stdio server ready\n');
}

main().catch((err) => {
  process.stderr.write(`[phone-bridge] Fatal error: ${err.message}\n`);
  process.exit(1);
});
