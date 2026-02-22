/**
 * PocketMCP Stdio Bridge
 *
 * Bridges stdio MCP transport to PocketMCP's StreamableHTTP endpoint.
 *
 * Usage:
 *   node bridge.mjs <phone_url> <api_key>
 *   node bridge.mjs --url http://192.168.1.10:8080/mcp --api-key <key> --verbose
 *
 * Environment variables:
 *   POCKET_MCP_URL
 *   POCKET_MCP_API_KEY
 *   POCKET_MCP_TIMEOUT_MS
 *   POCKET_MCP_TOOLS_TTL_MS
 *   POCKET_MCP_DISABLE_TOOL_CACHE=1
 *   POCKET_MCP_VERBOSE=1
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import WebSocket from 'ws';
import { Bonjour } from 'bonjour-service';

const PROTOCOL_VERSION = '2025-03-26';
const CLIENT_INFO = { name: 'pocket-mcp-bridge', version: '1.1.0' };

function parsePositiveInt(value, fallback) {
  if (value === undefined || value === null || value === '') {
    return fallback;
  }
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return fallback;
  }
  return parsed;
}

function getOptionValue(args, index, flag) {
  if (index + 1 >= args.length) {
    throw new Error(`Missing value for ${flag}`);
  }
  return args[index + 1];
}

function normalizePhoneUrl(rawUrl) {
  const input = (rawUrl || '').trim();
  if (!input) {
    throw new Error('Phone URL is required');
  }

  const withProtocol = /^https?:\/\/|^wss?:\/\//i.test(input) ? input : `ws://${input}`;
  const parsed = new URL(withProtocol);

  if (!parsed.pathname || parsed.pathname === '/') {
    parsed.pathname = parsed.protocol.startsWith('ws') ? '/ws' : '/mcp';
  }

  if (parsed.pathname.endsWith('/')) {
    parsed.pathname = parsed.pathname.slice(0, -1);
  }

  return parsed.toString();
}

function printHelp() {
  const help = `PocketMCP Stdio Bridge

Usage:
  node bridge.mjs [phone_url] [api_key] [options]

Options:
  --url <url>             Phone MCP URL (defaults to POCKET_MCP_URL or http://127.0.0.1:8080/mcp)
  --api-key <key>         API key (defaults to POCKET_MCP_API_KEY)
  --timeout-ms <ms>       HTTP timeout per request (default: 20000)
  --tools-ttl-ms <ms>     Tool cache TTL in ms (default: 30000)
  --no-tool-cache         Disable tool caching
  --verbose               Enable verbose bridge logs
  -h, --help              Show this help

Environment variables:
  POCKET_MCP_URL, POCKET_MCP_API_KEY,
  POCKET_MCP_TIMEOUT_MS, POCKET_MCP_TOOLS_TTL_MS,
  POCKET_MCP_DISABLE_TOOL_CACHE=1, POCKET_MCP_VERBOSE=1
`;
  process.stderr.write(help);
}

function parseArgs(argv) {
  let explicitUrl = null;
  let explicitApiKey = null;
  let timeoutMs = parsePositiveInt(process.env.POCKET_MCP_TIMEOUT_MS, 20000);
  let toolsTtlMs = parsePositiveInt(process.env.POCKET_MCP_TOOLS_TTL_MS, 30000);
  let disableToolCache = process.env.POCKET_MCP_DISABLE_TOOL_CACHE === '1';
  let verbose = process.env.POCKET_MCP_VERBOSE === '1';
  const positional = [];

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];

    if (arg === '--help' || arg === '-h') {
      printHelp();
      process.exit(0);
    }

    if (arg === '--url') {
      explicitUrl = getOptionValue(argv, i, '--url');
      i += 1;
      continue;
    }

    if (arg.startsWith('--url=')) {
      explicitUrl = arg.slice('--url='.length);
      continue;
    }

    if (arg === '--api-key') {
      explicitApiKey = getOptionValue(argv, i, '--api-key');
      i += 1;
      continue;
    }

    if (arg.startsWith('--api-key=')) {
      explicitApiKey = arg.slice('--api-key='.length);
      continue;
    }

    if (arg === '--timeout-ms') {
      timeoutMs = parsePositiveInt(getOptionValue(argv, i, '--timeout-ms'), timeoutMs);
      i += 1;
      continue;
    }

    if (arg.startsWith('--timeout-ms=')) {
      timeoutMs = parsePositiveInt(arg.slice('--timeout-ms='.length), timeoutMs);
      continue;
    }

    if (arg === '--tools-ttl-ms') {
      toolsTtlMs = parsePositiveInt(getOptionValue(argv, i, '--tools-ttl-ms'), toolsTtlMs);
      i += 1;
      continue;
    }

    if (arg.startsWith('--tools-ttl-ms=')) {
      toolsTtlMs = parsePositiveInt(arg.slice('--tools-ttl-ms='.length), toolsTtlMs);
      continue;
    }

    if (arg === '--no-tool-cache') {
      disableToolCache = true;
      continue;
    }

    if (arg === '--verbose') {
      verbose = true;
      continue;
    }

    if (arg.startsWith('-')) {
      throw new Error(`Unknown option: ${arg}`);
    }

    positional.push(arg);
  }

  if (positional.length > 2) {
    throw new Error('Too many positional arguments. Expected [phone_url] [api_key].');
  }

  const envUrl = process.env.POCKET_MCP_URL;
  const envApiKey = process.env.POCKET_MCP_API_KEY || '';

  const targetUrl = explicitUrl || positional[0] || envUrl || null;
  const apiKey = explicitApiKey ?? positional[1] ?? envApiKey;

  return {
    apiKey,
    disableToolCache,
    rawPhoneUrl: targetUrl,
    timeoutMs,
    toolsTtlMs,
    verbose,
  };
}

function isTransientStatus(status) {
  return status === 408 || status === 429 || (status >= 500 && status <= 599);
}

function isTransientError(error) {
  if (!error || typeof error !== 'object') {
    return false;
  }

  if (error.name === 'AbortError') {
    return true;
  }

  const code = error.code || '';
  return ['ECONNRESET', 'ETIMEDOUT', 'ENOTFOUND', 'ECONNREFUSED', 'EAI_AGAIN'].includes(code);
}

function isInvalidSessionPayload(payload) {
  const error = payload?.error;
  if (!error) {
    return false;
  }

  const message = String(error.message || '').toLowerCase();
  return message.includes('session') && (message.includes('invalid') || message.includes('expired'));
}

function formatRpcError(error) {
  const code = error?.code !== undefined ? ` ${error.code}` : '';
  const message = error?.message || 'Unknown MCP error';
  if (error?.data !== undefined) {
    return `MCP${code}: ${message} (${JSON.stringify(error.data)})`;
  }
  return `MCP${code}: ${message}`;
}

function compactSnippet(text, maxLen = 220) {
  const normalized = String(text || '').replace(/\s+/g, ' ').trim();
  if (normalized.length <= maxLen) {
    return normalized;
  }
  return `${normalized.slice(0, maxLen)}...`;
}

function parseSsePayload(bodyText) {
  const blocks = bodyText.split(/\r?\n\r?\n/);

  for (const block of blocks) {
    const dataParts = [];
    const lines = block.split(/\r?\n/);

    for (const line of lines) {
      if (!line.startsWith('data:')) {
        continue;
      }
      dataParts.push(line.slice(5).trimStart());
    }

    if (dataParts.length === 0) {
      continue;
    }

    const eventPayload = dataParts.join('\n').trim();
    if (!eventPayload || eventPayload === '[DONE]') {
      continue;
    }

    try {
      return JSON.parse(eventPayload);
    } catch {
      // Ignore non-JSON events and keep scanning.
    }
  }

  throw new Error('No valid JSON-RPC payload found in SSE response');
}

async function parseResponsePayload(response) {
  const contentType = (response.headers.get('content-type') || '').toLowerCase();
  const rawText = await response.text();

  if (!rawText) {
    return { payload: null, rawText };
  }

  if (contentType.includes('text/event-stream')) {
    return { payload: parseSsePayload(rawText), rawText };
  }

  try {
    return { payload: JSON.parse(rawText), rawText };
  } catch {
    return { payload: null, rawText };
  }
}

function delay(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

let options;
try {
  options = parseArgs(process.argv.slice(2));
} catch (error) {
  process.stderr.write(`[phone-bridge] ${error.message}\n\n`);
  printHelp();
  process.exit(1);
}

const state = {
  cachedTools: null,
  cachedToolsAt: 0,
  initialized: false,
  nextId: 0,
  sessionId: null,
};

function log(message) {
  if (!options.verbose) {
    return;
  }
  process.stderr.write(`[phone-bridge] ${message}\n`);
}

function nextRpcId() {
  state.nextId += 1;
  return state.nextId;
}

function buildHeaders() {
  const headers = {
    Accept: 'application/json, text/event-stream',
    'Content-Type': 'application/json',
  };

  if (options.apiKey) {
    headers['X-API-Key'] = options.apiKey;
  }

  if (state.sessionId) {
    headers['Mcp-Session-Id'] = state.sessionId;
  }

  return headers;
}

async function postJson(body, allowRetry = true) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), options.timeoutMs);

  try {
    const response = await fetch(options.phoneUrl, {
      body: JSON.stringify(body),
      headers: buildHeaders(),
      method: 'POST',
      signal: controller.signal,
    });

    const maybeSessionId = response.headers.get('mcp-session-id');
    if (maybeSessionId && maybeSessionId !== state.sessionId) {
      state.sessionId = maybeSessionId;
      log(`Session established (${maybeSessionId.slice(0, 8)}...)`);
    }

    const { payload, rawText } = await parseResponsePayload(response);

    if (!response.ok) {
      const details = payload?.error
        ? formatRpcError(payload.error)
        : `HTTP ${response.status} ${response.statusText}${rawText ? ` (${compactSnippet(rawText)})` : ''}`;

      if (allowRetry && isTransientStatus(response.status)) {
        log(`Transient HTTP ${response.status}; retrying once`);
        await delay(200);
        return sendJsonRpc(body, false);
      }

      throw new Error(details);
    }

    return { payload, rawText };
  } catch (error) {
    if (allowRetry && isTransientError(error)) {
      log(`Transient network error (${error.message}); retrying once`);
      await delay(200);
      return sendJsonRpc(body, false);
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

let wsClient = null;
let wsCallbacks = new Map();

async function getWsClient() {
  if (wsClient && wsClient.readyState === WebSocket.OPEN) return wsClient;
  if (wsClient && wsClient.readyState === WebSocket.CONNECTING) {
    return new Promise((resolve) => {
      const interval = setInterval(() => {
        if (wsClient.readyState === WebSocket.OPEN) { clearInterval(interval); resolve(wsClient); }
      }, 50);
    });
  }

  return new Promise((resolve, reject) => {
    wsClient = new WebSocket(options.phoneUrl, { headers: buildHeaders() });
    wsClient.on('open', () => resolve(wsClient));
    wsClient.on('error', (err) => reject(err));
    wsClient.on('message', (data) => {
      try {
        const payload = JSON.parse(data);
        if (payload.id && wsCallbacks.has(payload.id)) {
          wsCallbacks.get(payload.id)(payload);
          wsCallbacks.delete(payload.id);
        }
      } catch (e) {}
    });
  });
}

async function sendJsonRpc(body, allowRetry) {
  if (options.phoneUrl.startsWith('ws')) {
    const ws = await getWsClient();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        wsCallbacks.delete(body.id);
        reject(new Error('WebSocket timeout'));
      }, options.timeoutMs);
      
      wsCallbacks.set(body.id, (payload) => {
        clearTimeout(timer);
        resolve({ payload, rawText: JSON.stringify(payload) });
      });
      ws.send(JSON.stringify(body));
    });
  }
  return postJson(body, allowRetry);
}

async function phoneMcpRequest(method, params = {}, id = nextRpcId(), allowSessionRepair = true) {
  const body = { jsonrpc: '2.0', id, method, params };
  const { payload } = await sendJsonRpc(body, true);

  if (!payload || typeof payload !== 'object') {
    throw new Error(`Invalid JSON-RPC payload for ${method}`);
  }

  if (payload.error) {
    if (allowSessionRepair && method !== 'initialize' && isInvalidSessionPayload(payload)) {
      log('Session appears invalid; re-initializing and retrying request');
      state.initialized = false;
      state.sessionId = null;
      await initPhoneSession(true);
      return phoneMcpRequest(method, params, id, false);
    }

    throw new Error(formatRpcError(payload.error));
  }

  return payload;
}

async function sendInitializedNotification() {
  const body = { jsonrpc: '2.0', method: 'notifications/initialized' };
  try {
    await postJson(body, false);
  } catch (error) {
    // Notification failures should not block the bridge startup.
    log(`initialized notification failed: ${error.message}`);
  }
}

async function initPhoneSession(force = false) {
  if (state.initialized && !force) {
    return;
  }

  const payload = await phoneMcpRequest(
    'initialize',
    {
      capabilities: {},
      clientInfo: CLIENT_INFO,
      protocolVersion: PROTOCOL_VERSION,
    },
    nextRpcId(),
    false,
  );

  state.initialized = true;
  await sendInitializedNotification();
  log(`Initialized session with phone (${payload.result?.serverInfo?.name || 'unknown server'})`);
}

function shouldUseCachedTools() {
  if (options.disableToolCache || !state.cachedTools) {
    return false;
  }

  const ageMs = Date.now() - state.cachedToolsAt;
  return ageMs <= options.toolsTtlMs;
}

async function fetchTools(forceRefresh = false) {
  if (!forceRefresh && shouldUseCachedTools()) {
    return state.cachedTools;
  }

  await initPhoneSession();
  const payload = await phoneMcpRequest('tools/list', {}, nextRpcId());

  const tools = payload?.result?.tools;
  if (!Array.isArray(tools)) {
    throw new Error('tools/list response did not include an array of tools');
  }

  state.cachedTools = tools;
  state.cachedToolsAt = Date.now();
  log(`Cached ${tools.length} tools`);
  return tools;
}

async function callTool(name, args) {
  await initPhoneSession();
  const payload = await phoneMcpRequest('tools/call', { arguments: args || {}, name }, nextRpcId());

  if (payload.result !== undefined) {
    return payload.result;
  }

  return {
    content: [{ text: JSON.stringify(payload), type: 'text' }],
  };
}

async function main() {
  if (!options.rawPhoneUrl) {
    log('No URL provided. Searching for PocketMCP via mDNS (_mcp._tcp)...');
    options.phoneUrl = await new Promise((resolve) => {
      const bonjour = new Bonjour();
      const timer = setTimeout(() => {
        bonjour.destroy();
        resolve(null);
      }, 5000);
      const browser = bonjour.find({ type: 'mcp' }, (service) => {
        clearTimeout(timer);
        browser.stop();
        bonjour.destroy();
        const ip = service.addresses.find((a) => a.includes('.')) || service.addresses[0];
        resolve(`ws://${ip}:${service.port}/ws`);
      });
    });
    if (!options.phoneUrl) {
      process.stderr.write('[phone-bridge] mDNS discovery failed. Defaulting to ws://127.0.0.1:8080/ws\n');
      options.phoneUrl = 'ws://127.0.0.1:8080/ws';
    } else {
      process.stderr.write(`[phone-bridge] Discovered phone at ${options.phoneUrl}\n`);
    }
  } else {
    options.phoneUrl = normalizePhoneUrl(options.rawPhoneUrl);
  }

  log(`Target: ${options.phoneUrl}`);

  try {
    await initPhoneSession();
    const tools = await fetchTools(true);
    const toolNames = tools.map((tool) => tool.name).join(', ');
    process.stderr.write(`[phone-bridge] Connected to ${options.phoneUrl}\n`);
    process.stderr.write(`[phone-bridge] ${tools.length} tools available${toolNames ? `: ${toolNames}` : ''}\n`);
  } catch (error) {
    process.stderr.write(`[phone-bridge] Failed to connect: ${error.message}\n`);
    process.exit(1);
  }

  const server = new Server({ name: 'phone', version: '1.1.0' }, { capabilities: { tools: {} } });

  server.setRequestHandler(ListToolsRequestSchema, async () => {
    const tools = await fetchTools(false);
    return { tools };
  });

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const toolName = request?.params?.name;
    const toolArgs = request?.params?.arguments || {};

    if (!toolName || typeof toolName !== 'string') {
      return {
        content: [{ text: 'Invalid tool name', type: 'text' }],
        isError: true,
      };
    }

    try {
      if (Array.isArray(state.cachedTools)) {
        const toolKnown = state.cachedTools.some((tool) => tool?.name === toolName);
        if (!toolKnown) {
          await fetchTools(true);
        }
      }

      return await callTool(toolName, toolArgs);
    } catch (error) {
      return {
        content: [{ text: `Error calling ${toolName}: ${error.message}`, type: 'text' }],
        isError: true,
      };
    }
  });

  const transport = new StdioServerTransport();
  await server.connect(transport);
  process.stderr.write('[phone-bridge] Stdio server ready\n');
}

main().catch((error) => {
  process.stderr.write(`[phone-bridge] Fatal error: ${error.message}\n`);
  process.exit(1);
});
