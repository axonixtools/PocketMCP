#!/usr/bin/env node
/**
 * PocketMCP stdio bridge v2 — zero dependencies, Node 20+.
 *
 * Speaks the MCP stdio protocol to an AI coding tool (Claude Desktop,
 * Cursor, Windsurf, ...) and proxies tool discovery/execution to PocketMCP's
 * Streamable HTTP endpoint on the phone.
 *
 * Tools that talk Streamable HTTP natively (opencode, Claude Code, Codex,
 * Cursor, ...) can connect directly to http://<phone-ip>:8080/mcp and do not
 * need this bridge. Run `node setup.mjs` for guided, per-tool setup.
 *
 * Usage:
 *   node bridge.mjs                        # auto-resolve: profile -> LAN discovery -> 127.0.0.1
 *   node bridge.mjs --url http://192.0.2.10:8080/mcp --api-key <key> --verbose
 *   node bridge.mjs <phone_url> <api_key>
 *
 * Environment:
 *   POCKET_MCP_URL               phone endpoint (http://ip:8080/mcp)
 *   POCKET_MCP_API_KEY           API key configured in the app
 *   POCKET_MCP_TIMEOUT_MS        per-request timeout (default 30000)
 *   POCKET_MCP_TOOLS_TTL_MS      tools/list cache TTL (default 30000)
 *   POCKET_MCP_DISABLE_TOOL_CACHE=1
 *   POCKET_MCP_VERBOSE=1
 *   POCKET_MCP_PROFILE           path to connection profile (default ~/.pocketmcp.json)
 *
 * stdout carries MCP protocol traffic only. Every log goes to stderr.
 */

import dgram from 'node:dgram';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { pathToFileURL } from 'node:url';

export const BRIDGE_VERSION = '2.0.0';
export const PHONE_PROTOCOL_VERSION = '2024-11-05';
export const DEFAULT_MCP_URL = 'http://127.0.0.1:8080/mcp';
export const MDNS_SERVICE_TYPE = '_mcp._tcp.local';
const MDNS_ADDRESS = '224.0.0.251';
const MDNS_PORT = 5353;

// Protocol revisions the bridge itself can speak over stdio (classic
// initialize handshake). Anything newer falls back to the newest listed one.
const STDIO_PROTOCOL_VERSIONS = ['2024-11-05', '2025-03-26', '2025-06-18'];
const STDIO_FALLBACK_VERSION = '2025-06-18';

const BRIDGE_INSTRUCTIONS =
  'PocketMCP exposes a paired Android phone (device info, notifications, ' +
  'messages, contacts, apps, screen automation, shell, sensors) as MCP tools ' +
  'over your LAN. Prefer tools/list results for exact names and arguments.';

// ---------------------------------------------------------------------------
// Small utilities
// ---------------------------------------------------------------------------

function redactUrl(raw) {
  try {
    const u = new URL(raw);
    for (const param of ['key', 'api_key']) {
      if (u.searchParams.has(param)) u.searchParams.set(param, '***');
    }
    return u.toString();
  } catch {
    return String(raw);
  }
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isConnectionError(err) {
  const code = err?.cause?.code ?? err?.code;
  return (
    code === 'ECONNREFUSED' ||
    code === 'ECONNRESET' ||
    code === 'ENETUNREACH' ||
    code === 'EHOSTUNREACH' ||
    code === 'ENOTFOUND' ||
    code === 'EAI_AGAIN' ||
    code === 'EPIPE'
  );
}

function isTimeoutError(err) {
  return err?.name === 'TimeoutError' || err?.name === 'AbortError';
}

function sanitizeErrorMessage(err) {
  const message = err instanceof Error ? err.message : String(err);
  return message.replace(/\s+/g, ' ').slice(0, 500);
}

/**
 * Accepts many shapes of user input and returns a canonical MCP endpoint:
 *   "192.0.2.10:8080"        -> http://192.0.2.10:8080/mcp
 *   "http://192.0.2.10:8080" -> http://192.0.2.10:8080/mcp
 *   "ws://127.0.0.1:8080/ws"   -> http://127.0.0.1:8080/mcp
 *   "https://host/mcp"         -> unchanged
 */
export function normalizeMcpUrl(raw) {
  let value = String(raw ?? '').trim();
  if (!value) throw new Error('Empty PocketMCP URL');
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(value)) value = `http://${value}`;

  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`Invalid PocketMCP URL: ${raw}`);
  }

  if (url.protocol === 'ws:') url.protocol = 'http:';
  else if (url.protocol === 'wss:') url.protocol = 'https:';
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(`Unsupported PocketMCP URL scheme: ${url.protocol}`);
  }

  let pathname = url.pathname.replace(/\/+$/, '');
  if (!pathname || pathname === '') pathname = '/mcp';
  else if (pathname === '/ws' || pathname === '/sse') pathname = '/mcp';
  url.pathname = pathname;
  url.hash = '';
  return url.toString();
}

/** Extracts the JSON-RPC payload from a text/event-stream response body. */
export function extractJsonRpcFromSse(raw, expectedId) {
  const payloads = [];
  for (const event of String(raw ?? '').split(/\r?\n\r?\n/)) {
    const dataLines = event
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).replace(/^ /, ''));
    if (dataLines.length === 0) continue;
    try {
      payloads.push(JSON.parse(dataLines.join('\n')));
    } catch {
      // Ignore non-JSON keep-alive payloads.
    }
  }
  if (payloads.length === 0) return null;
  if (expectedId !== undefined && expectedId !== null) {
    const match = payloads.find((payload) => payload && payload.id === expectedId);
    if (match) return match;
  }
  return (
    payloads.find((payload) => payload && (payload.result !== undefined || payload.error !== undefined)) ??
    payloads[payloads.length - 1]
  );
}

function isInvalidSessionPayload(payload) {
  const error = payload?.error;
  if (!error) return false;
  if (error.code === -32001 && /session/i.test(String(error.message ?? ''))) return true;
  const message = String(error.message ?? '').toLowerCase();
  return message.includes('session') && (message.includes('invalid') || message.includes('expired') || message.includes('not found'));
}

// ---------------------------------------------------------------------------
// Connection profile (~/.pocketmcp.json), written by setup.mjs
// ---------------------------------------------------------------------------

export function profilePath(homeDir) {
  if (process.env.POCKET_MCP_PROFILE) return process.env.POCKET_MCP_PROFILE;
  return path.join(homeDir ?? os.homedir(), '.pocketmcp.json');
}

export function loadProfile(homeDir) {
  try {
    const raw = fs.readFileSync(profilePath(homeDir), 'utf8');
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed.url !== 'string' || !parsed.url.trim()) return null;
    return {
      url: parsed.url.trim(),
      apiKey: typeof parsed.apiKey === 'string' ? parsed.apiKey : '',
      updatedAt: parsed.updatedAt,
    };
  } catch {
    return null;
  }
}

export function saveProfile({ url, apiKey }, homeDir) {
  const target = profilePath(homeDir);
  const payload = {
    url,
    apiKey: apiKey ?? '',
    updatedAt: new Date().toISOString(),
    createdBy: `pocketmcp-bridge ${BRIDGE_VERSION}`,
  };
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
  try {
    fs.chmodSync(target, 0o600);
  } catch {
    // chmod is best-effort (not meaningful on every platform).
  }
  return target;
}

// ---------------------------------------------------------------------------
// mDNS discovery (stdlib UDP/DNS, no dependencies)
// ---------------------------------------------------------------------------

function encodeName(name) {
  const labels = String(name).replace(/\.$/, '').split('.').filter(Boolean);
  const parts = [];
  for (const label of labels) {
    const bytes = Buffer.from(label, 'utf8');
    parts.push(Buffer.from([bytes.length]), bytes);
  }
  parts.push(Buffer.from([0]));
  return Buffer.concat(parts);
}

export function buildMdnsQuery(name, type) {
  const header = Buffer.alloc(12);
  header.writeUInt16BE(0, 0); // id
  header.writeUInt16BE(0, 2); // flags: standard query
  header.writeUInt16BE(1, 4); // qdcount
  const question = Buffer.alloc(4);
  question.writeUInt16BE(type, 0);
  question.writeUInt16BE(0x8001, 2); // IN + unicast-response bit
  return Buffer.concat([header, encodeName(name), question]);
}

function parseName(buf, offset) {
  const labels = [];
  let pos = offset;
  let next = null;
  let guard = 0;
  while (guard++ < 128) {
    if (pos >= buf.length) break;
    const len = buf[pos];
    if (len === 0) {
      pos += 1;
      if (next === null) next = pos;
      break;
    }
    if ((len & 0xc0) === 0xc0) {
      if (pos + 1 >= buf.length) break;
      const pointer = ((len & 0x3f) << 8) | buf[pos + 1];
      if (next === null) next = pos + 2;
      pos = pointer;
      continue;
    }
    if (pos + 1 + len > buf.length) break;
    labels.push(buf.toString('utf8', pos + 1, pos + 1 + len));
    pos += 1 + len;
  }
  return { name: `${labels.join('.').toLowerCase()}.`, next: next ?? pos };
}

function formatIpv6(buf) {
  const groups = [];
  for (let i = 0; i < 16; i += 2) groups.push(buf.readUInt16BE(i).toString(16));
  return groups.join(':');
}

export function parseMdnsMessage(buf) {
  const records = [];
  if (buf.length < 12) return records;
  const qdcount = buf.readUInt16BE(4);
  const ancount = buf.readUInt16BE(6);
  const nscount = buf.readUInt16BE(8);
  const arcount = buf.readUInt16BE(10);

  let offset = 12;
  for (let i = 0; i < qdcount; i += 1) {
    const question = parseName(buf, offset);
    offset = question.next + 4;
    if (offset > buf.length) return records;
  }

  for (let i = 0; i < ancount + nscount + arcount; i += 1) {
    const parsed = parseMdnsRecord(buf, offset);
    if (!parsed) break;
    records.push(parsed.record);
    offset = parsed.next;
  }
  return records;
}

function parseMdnsRecord(buf, offset) {
  const { name, next } = parseName(buf, offset);
  let pos = next;
  if (pos + 10 > buf.length) return null;
  const type = buf.readUInt16BE(pos);
  const cls = buf.readUInt16BE(pos + 2);
  const ttl = buf.readUInt32BE(pos + 4);
  const rdlength = buf.readUInt16BE(pos + 8);
  pos += 10;
  const rdStart = pos;
  const rdEnd = pos + rdlength;
  if (rdEnd > buf.length) return null;

  let data = null;
  if (type === 12) {
    data = parseName(buf, rdStart).name;
  } else if (type === 33) {
    if (rdlength >= 6) {
      data = {
        priority: buf.readUInt16BE(rdStart),
        weight: buf.readUInt16BE(rdStart + 2),
        port: buf.readUInt16BE(rdStart + 4),
        target: parseName(buf, rdStart + 6).name,
      };
    }
  } else if (type === 16) {
    const entries = [];
    let p = rdStart;
    while (p < rdEnd) {
      const len = buf[p];
      entries.push(buf.toString('utf8', p + 1, Math.min(p + 1 + len, rdEnd)));
      p += 1 + len;
    }
    data = entries;
  } else if (type === 1 && rdlength === 4) {
    data = `${buf[rdStart]}.${buf[rdStart + 1]}.${buf[rdStart + 2]}.${buf[rdStart + 3]}`;
  } else if (type === 28 && rdlength === 16) {
    data = formatIpv6(buf.slice(rdStart, rdStart + 16));
  }

  return {
    record: { name, type, class: cls & 0x7fff, ttl, data },
    next: rdEnd,
  };
}

export function assembleDevices(records) {
  const suffix = `.${MDNS_SERVICE_TYPE}.`;
  const addressRecords = new Map();
  for (const record of records) {
    if ((record.type === 1 || record.type === 28) && typeof record.data === 'string') {
      const list = addressRecords.get(record.name) ?? [];
      if (!list.includes(record.data)) list.push(record.data);
      addressRecords.set(record.name, list);
    }
  }

  const devices = new Map();
  for (const record of records) {
    if (record.type !== 33 || !record.data || !record.name.endsWith(suffix)) continue;
    const instance = record.name.slice(0, -suffix.length);
    if (!instance) continue;
    const key = `${record.data.target}:${record.data.port}`;
    if (devices.has(key)) continue;
    const addresses = (addressRecords.get(record.data.target) ?? []).slice();
    addresses.sort((a, b) => (a.includes(':') ? 1 : 0) - (b.includes(':') ? 1 : 0));
    devices.set(key, {
      name: instance,
      host: record.data.target,
      port: record.data.port,
      addresses,
    });
  }
  return [...devices.values()];
}

function sendMdnsQuery(socket, name, type) {
  try {
    const packet = buildMdnsQuery(name, type);
    socket.send(packet, MDNS_PORT, MDNS_ADDRESS, () => {});
  } catch {
    // Multicast may be unavailable on the current network; discovery is optional.
  }
}

/**
 * One-shot mDNS lookup for PocketMCP phones advertising `_mcp._tcp.local`.
 * Resolves to an array of { name, host, port, addresses }. Never throws.
 */
export async function discoverPhones({ timeoutMs = 4000, logger = () => {} } = {}) {
  return new Promise((resolve) => {
    let socket;
    try {
      socket = dgram.createSocket('udp4');
    } catch {
      resolve([]);
      return;
    }

    const records = [];
    const queriedTargets = new Set();
    let settled = false;
    let earlyTimer = null;

    const finish = () => {
      if (settled) return;
      settled = true;
      clearTimeout(overallTimer);
      if (earlyTimer) clearTimeout(earlyTimer);
      try {
        socket.close();
      } catch {
        // already closed
      }
      resolve(assembleDevices(records));
    };

    const overallTimer = setTimeout(finish, timeoutMs);

    const onResponse = () => {
      const devices = assembleDevices(records);
      const missing = devices.filter((d) => d.addresses.length === 0 && !queriedTargets.has(d.host));
      for (const device of missing) {
        queriedTargets.add(device.host);
        sendMdnsQuery(socket, device.host, 1);
      }
      if (devices.some((d) => d.addresses.length > 0) && missing.length === 0 && !earlyTimer) {
        earlyTimer = setTimeout(finish, 350);
      }
    };

    socket.on('message', (msg) => {
      try {
        records.push(...parseMdnsMessage(msg));
      } catch {
        // Ignore malformed packets from other services on the network.
      }
      onResponse();
    });
    socket.on('error', (err) => {
      logger(`LAN discovery unavailable: ${sanitizeErrorMessage(err)}`);
      finish();
    });

    try {
      socket.bind(0, () => {
        logger(`Searching the LAN for PocketMCP (${MDNS_SERVICE_TYPE}) ...`);
        sendMdnsQuery(socket, MDNS_SERVICE_TYPE, 12);
      });
    } catch {
      finish();
    }
  });
}

export function pickPhoneAddress(device) {
  if (!device?.addresses?.length) return null;
  return device.addresses.find((a) => !a.includes(':')) ?? device.addresses[0];
}

/**
 * Resolves the phone endpoint the bridge should use.
 * Precedence: explicit options -> environment -> profile -> LAN discovery -> localhost.
 */
export async function resolveBridgeTarget({
  url,
  apiKey,
  homeDir,
  env = process.env,
  logger = () => {},
  discover = true,
  discoverTimeoutMs = 4000,
} = {}) {
  let resolvedUrl = url || env.POCKET_MCP_URL || '';
  let resolvedKey = apiKey ?? env.POCKET_MCP_API_KEY ?? '';
  let source = null;

  if (resolvedUrl) {
    source = url ? 'command line' : 'environment';
  } else {
    const profile = loadProfile(homeDir);
    if (profile) {
      resolvedUrl = profile.url;
      if (!resolvedKey) resolvedKey = profile.apiKey ?? '';
      source = `profile (${profilePath(homeDir)})`;
    }
  }

  if (!resolvedUrl && discover) {
    const devices = await discoverPhones({ timeoutMs: discoverTimeoutMs, logger });
    if (devices.length > 0) {
      const [first, ...rest] = devices;
      const address = pickPhoneAddress(first);
      if (address) {
        resolvedUrl = `http://${address}:${first.port}/mcp`;
        source = `LAN discovery (${first.name})`;
        if (rest.length > 0) {
          logger(`Found ${devices.length} PocketMCP devices; using ${first.name}. Others: ${rest.map((d) => d.name).join(', ')}`);
        }
      }
    }
  }

  if (!resolvedUrl) {
    resolvedUrl = DEFAULT_MCP_URL;
    source = 'default (adb forward / localhost)';
  }

  return { url: normalizeMcpUrl(resolvedUrl), apiKey: resolvedKey ?? '', source };
}

// ---------------------------------------------------------------------------
// Phone HTTP client (Streamable HTTP)
// ---------------------------------------------------------------------------

export class PhoneClientError extends Error {
  constructor(message, { kind = 'transport', status = null, payload = null } = {}) {
    super(message);
    this.name = 'PhoneClientError';
    this.kind = kind; // 'auth' | 'throttled' | 'session' | 'transport' | 'http' | 'protocol'
    this.status = status;
    this.payload = payload;
  }
}

export class PhoneClient {
  constructor({ url, apiKey = '', timeoutMs = 30000, logger = () => {} } = {}) {
    this.mcpUrl = normalizeMcpUrl(url);
    this.apiKey = apiKey ?? '';
    this.timeoutMs = timeoutMs;
    this.logger = logger;
    this.sessionId = null;
    this.serverInfo = null;
    this.protocolVersion = null;
    this.serverInstructions = null;
    this.#nextId = 1;
    this.#initPromise = null;
  }

  #nextId;
  #initPromise;

  #headers() {
    const headers = {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
    };
    if (this.apiKey) headers['X-API-Key'] = this.apiKey;
    if (this.sessionId) headers['Mcp-Session-Id'] = this.sessionId;
    return headers;
  }

  get healthUrl() {
    const url = new URL(this.mcpUrl);
    url.pathname = '/health';
    url.search = '';
    return url.toString();
  }

  async health({ timeoutMs = 5000 } = {}) {
    const started = Date.now();
    const response = await fetch(this.healthUrl, {
      headers: { accept: 'application/json' },
      signal: AbortSignal.timeout(timeoutMs),
    });
    const text = await response.text();
    let body = null;
    try {
      body = JSON.parse(text);
    } catch {
      body = { raw: text.slice(0, 500) };
    }
    return { status: response.status, ok: response.ok, body, ms: Date.now() - started };
  }

  /** Ensures a phone-side MCP session exists (initialize once, deduplicated). */
  async ensureSession() {
    if (this.sessionId) return this;
    if (!this.#initPromise) {
      this.#initPromise = this.initialize().finally(() => {
        this.#initPromise = null;
      });
    }
    await this.#initPromise;
    return this;
  }

  async initialize() {
    const payload = await this.#request(
      'initialize',
      {
        protocolVersion: PHONE_PROTOCOL_VERSION,
        capabilities: {},
        clientInfo: { name: 'pocketmcp-bridge', version: BRIDGE_VERSION },
      },
      { allowRepair: false, safeRetry: true }
    );
    if (payload?.error) {
      throw new PhoneClientError(`PocketMCP initialize failed: ${payload.error.message ?? 'unknown error'}`, {
        kind: 'protocol',
        payload,
      });
    }
    this.serverInfo = payload?.result?.serverInfo ?? null;
    this.protocolVersion = payload?.result?.protocolVersion ?? null;
    this.serverInstructions = payload?.result?.instructions ?? null;
    this.logger(
      `Connected to ${this.serverInfo?.name ?? 'PocketMCP'} ${this.serverInfo?.version ?? ''} (protocol ${this.protocolVersion ?? 'unknown'})`
    );
    return this;
  }

  async listTools() {
    await this.ensureSession();
    const payload = await this.#request('tools/list', {}, { safeRetry: true });
    if (payload?.error) {
      throw new PhoneClientError(`tools/list failed: ${payload.error.message ?? 'unknown error'}`, {
        kind: 'protocol',
        payload,
      });
    }
    return payload?.result?.tools ?? [];
  }

  async callTool(name, args) {
    await this.ensureSession();
    return this.#request('tools/call', { name, arguments: args ?? {} }, { safeRetry: false });
  }

  async #request(method, params, { allowRepair = true, safeRetry = true } = {}) {
    try {
      return await this.#post(method, params);
    } catch (err) {
      if (err instanceof PhoneClientError && err.kind === 'session' && allowRepair && method !== 'initialize') {
        this.logger('Phone session invalid; re-initializing and retrying once');
        this.sessionId = null;
        await this.initialize();
        return this.#post(method, params);
      }
      const retryable =
        isConnectionError(err) ||
        (safeRetry &&
          (isTimeoutError(err) ||
            (err instanceof PhoneClientError && err.kind === 'http' && (err.status ?? 0) >= 500)));
      if (retryable && (safeRetry || isConnectionError(err))) {
        this.logger(`Transient network error (${sanitizeErrorMessage(err)}); retrying once`);
        await delay(250);
        return this.#post(method, params);
      }
      throw err;
    }
  }

  async #post(method, params) {
    const id = this.#nextId++;
    const envelope = { jsonrpc: '2.0', id, method };
    if (params !== undefined) envelope.params = params;

    let response;
    try {
      response = await fetch(this.mcpUrl, {
        method: 'POST',
        headers: this.#headers(),
        body: JSON.stringify(envelope),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch (err) {
      if (isTimeoutError(err)) {
        throw new PhoneClientError(
          `PocketMCP did not respond within ${this.timeoutMs} ms at ${redactUrl(this.mcpUrl)}`,
          { kind: 'transport' }
        );
      }
      const hint = isConnectionError(err)
        ? ` — is the phone reachable at ${redactUrl(this.mcpUrl)}?`
        : '';
      throw new PhoneClientError(`PocketMCP request failed: ${sanitizeErrorMessage(err)}${hint}`, { kind: 'transport' });
    }

    const sessionHeader = response.headers.get('mcp-session-id');
    if (sessionHeader && sessionHeader !== this.sessionId) {
      this.sessionId = sessionHeader;
      this.logger(`Phone session established (${sessionHeader.slice(0, 8)}…)`);
    }

    const text = await response.text();
    const contentType = (response.headers.get('content-type') ?? '').toLowerCase();

    if (response.status === 401 || response.status === 403) {
      throw new PhoneClientError(
        'PocketMCP rejected the API key — copy the current key from the app (Settings or Quick Connect).',
        { kind: 'auth', status: response.status }
      );
    }
    if (response.status === 429) {
      throw new PhoneClientError(
        'PocketMCP throttled this client after repeated failed API key attempts. Wait a minute and retry with the correct key.',
        { kind: 'throttled', status: response.status }
      );
    }

    let payload = null;
    if (contentType.includes('text/event-stream')) {
      payload = extractJsonRpcFromSse(text, id);
      if (!payload) {
        throw new PhoneClientError('PocketMCP returned an SSE response without a JSON-RPC payload', {
          kind: 'protocol',
        });
      }
    } else if (text.trim()) {
      try {
        payload = JSON.parse(text);
      } catch {
        payload = null;
      }
    }

    if (payload?.error && (isInvalidSessionPayload(payload) || response.status === 404)) {
      throw new PhoneClientError('PocketMCP session rejected; will re-initialize', {
        kind: 'session',
        status: response.status,
        payload,
      });
    }

    if (!response.ok && response.status >= 500) {
      throw new PhoneClientError(`PocketMCP returned HTTP ${response.status}`, {
        kind: 'http',
        status: response.status,
        payload,
      });
    }
    if (!response.ok && !payload) {
      throw new PhoneClientError(`PocketMCP returned HTTP ${response.status}`, {
        kind: 'http',
        status: response.status,
      });
    }
    if (!payload) {
      throw new PhoneClientError('PocketMCP returned an empty or non-JSON response', { kind: 'protocol' });
    }
    return payload;
  }
}

// ---------------------------------------------------------------------------
// Stdio MCP server loop (runs when this file is executed directly)
// ---------------------------------------------------------------------------

function negotiateStdioVersion(requested) {
  if (typeof requested === 'string' && STDIO_PROTOCOL_VERSIONS.includes(requested)) return requested;
  return STDIO_FALLBACK_VERSION;
}

class StdioBridge {
  constructor(client, { toolsTtlMs = 30000, logger = () => {} } = {}) {
    this.client = client;
    this.toolsTtlMs = toolsTtlMs;
    this.logger = logger;
    this.toolCache = null;
  }

  start() {
    const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
    rl.on('line', (line) => {
      void this.#onLine(line);
    });
    rl.on('close', () => process.exit(0));
    process.stdout.on('error', (err) => {
      if (err?.code === 'EPIPE') process.exit(0);
    });
  }

  #write(message) {
    process.stdout.write(`${JSON.stringify(message)}\n`);
  }

  #respond(id, result) {
    this.#write({ jsonrpc: '2.0', id, result });
  }

  #respondError(id, code, message, data) {
    const error = { code, message };
    if (data !== undefined) error.data = data;
    this.#write({ jsonrpc: '2.0', id, error });
  }

  async #onLine(line) {
    const trimmed = String(line ?? '').trim();
    if (!trimmed) return;

    let message;
    try {
      message = JSON.parse(trimmed);
    } catch {
      this.#respondError(null, -32700, 'Parse error');
      return;
    }
    if (Array.isArray(message) || typeof message !== 'object' || message === null) {
      this.#respondError(null, -32600, 'Invalid request: batch and non-object messages are not supported');
      return;
    }
    if (message.method === undefined) return; // Responses from the client are not expected.

    const id = message.id;
    try {
      await this.#dispatch(id, message.method, message.params);
    } catch (err) {
      const message_ = sanitizeErrorMessage(err);
      this.logger(`Request '${message.method}' failed: ${message_}`);
      if (id !== undefined && id !== null) {
        this.#respondError(id, -32603, message_);
      }
    }
  }

  async #dispatch(id, method, params) {
    const isNotification = id === undefined || id === null;
    switch (method) {
      case 'initialize':
        this.#respond(id, {
          protocolVersion: negotiateStdioVersion(params?.protocolVersion),
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: 'PocketMCP Bridge', version: BRIDGE_VERSION },
          instructions: BRIDGE_INSTRUCTIONS,
        });
        return;

      case 'notifications/initialized':
        this.client.ensureSession().catch((err) => this.logger(`Phone session warm-up failed: ${sanitizeErrorMessage(err)}`));
        return;

      case 'ping':
        this.#respond(id, {});
        return;

      case 'tools/list': {
        const tools = await this.#listTools();
        this.#respond(id, { tools });
        return;
      }

      case 'tools/call': {
        const name = params?.name;
        if (typeof name !== 'string' || !name) {
          this.#respondError(id, -32602, 'Missing tool name');
          return;
        }
        const payload = await this.client.callTool(name, params?.arguments ?? {});
        if (payload?.error) {
          this.#respondError(id, payload.error.code ?? -32603, payload.error.message ?? `Tool ${name} failed`);
          return;
        }
        this.#respond(id, payload?.result ?? { content: [], isError: false });
        return;
      }

      case 'notifications/cancelled':
      case 'notifications/progress':
        return; // Nothing to do; tool calls are synchronous request/response here.

      default:
        if (!isNotification) this.#respondError(id, -32601, `Method not found: ${method}`);
        return;
    }
  }

  async #listTools() {
    if (this.toolsTtlMs > 0 && this.toolCache && Date.now() - this.toolCache.at < this.toolsTtlMs) {
      return this.toolCache.tools;
    }
    const tools = await this.client.listTools();
    if (this.toolsTtlMs > 0) this.toolCache = { tools, at: Date.now() };
    this.logger(`Discovered ${tools.length} tools`);
    return tools;
  }
}

// ---------------------------------------------------------------------------
// CLI entry point
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const options = {
    url: '',
    apiKey: '',
    verbose: false,
    timeoutMs: null,
    toolsTtlMs: null,
    disableToolCache: false,
    help: false,
    version: false,
  };
  const positionals = [];
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => argv[++i];
    switch (arg) {
      case '--url':
      case '-u':
        options.url = next() ?? '';
        break;
      case '--api-key':
      case '-k':
        options.apiKey = next() ?? '';
        break;
      case '--verbose':
      case '-v':
        options.verbose = true;
        break;
      case '--timeout-ms':
      case '-t':
        options.timeoutMs = Number.parseInt(next() ?? '', 10);
        break;
      case '--tools-ttl-ms':
        options.toolsTtlMs = Number.parseInt(next() ?? '', 10);
        break;
      case '--no-tool-cache':
        options.disableToolCache = true;
        break;
      case '--help':
      case '-h':
        options.help = true;
        break;
      case '--version':
        options.version = true;
        break;
      default:
        if (arg.startsWith('-')) {
          throw new Error(`Unknown option: ${arg}`);
        }
        positionals.push(arg);
    }
  }
  if (!options.url && positionals[0]) options.url = positionals[0];
  if (!options.apiKey && positionals[1]) options.apiKey = positionals[1];
  return options;
}

const HELP_TEXT = `PocketMCP stdio bridge ${BRIDGE_VERSION}

Usage:
  node bridge.mjs [options] [phone_url] [api_key]

Options:
  -u, --url <url>            Phone endpoint (http://ip:8080/mcp, ws://, ip:port)
  -k, --api-key <key>        API key from the PocketMCP app
  -v, --verbose              Verbose logging (stderr)
  -t, --timeout-ms <ms>      Per-request timeout (default 30000)
      --tools-ttl-ms <ms>    tools/list cache TTL (default 30000; 0 disables)
      --no-tool-cache        Always fetch the live tool list
  -h, --help                 Show this help
      --version              Show version

Without a URL the bridge resolves: profile (~/.pocketmcp.json) -> LAN discovery -> http://127.0.0.1:8080/mcp
Run \`node setup.mjs\` for guided setup of opencode, Claude Code, Codex, Cursor, Claude Desktop.
`;

async function runStdioBridge(argv) {
  let options;
  try {
    options = parseArgs(argv);
  } catch (err) {
    process.stderr.write(`${sanitizeErrorMessage(err)}\n`);
    process.exit(2);
  }
  if (options.help) {
    process.stdout.write(HELP_TEXT);
    process.exit(0);
  }
  if (options.version) {
    process.stdout.write(`${BRIDGE_VERSION}\n`);
    process.exit(0);
  }

  const verbose = options.verbose || process.env.POCKET_MCP_VERBOSE === '1';
  const logger = (message) => {
    if (verbose) process.stderr.write(`[pocketmcp-bridge] ${message}\n`);
  };
  const alwaysLogger = (message) => process.stderr.write(`[pocketmcp-bridge] ${message}\n`);

  const timeoutMs =
    Number.isFinite(options.timeoutMs) && options.timeoutMs > 0
      ? options.timeoutMs
      : Number.parseInt(process.env.POCKET_MCP_TIMEOUT_MS ?? '', 10) || 30000;

  const ttlMs = options.disableToolCache || process.env.POCKET_MCP_DISABLE_TOOL_CACHE === '1'
    ? 0
    : Number.isFinite(options.toolsTtlMs)
      ? options.toolsTtlMs
      : Number.parseInt(process.env.POCKET_MCP_TOOLS_TTL_MS ?? '', 10) || 30000;

  const target = await resolveBridgeTarget({
    url: options.url,
    apiKey: options.apiKey,
    logger,
  });

  alwaysLogger(
    `Bridging stdio <-> ${redactUrl(target.url)} (${target.source}${target.apiKey ? ', API key set' : ', no API key'})`
  );

  const client = new PhoneClient({
    url: target.url,
    apiKey: target.apiKey,
    timeoutMs,
    logger,
  });
  new StdioBridge(client, { toolsTtlMs: ttlMs, logger }).start();
}

const isDirectRun = process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url;
if (isDirectRun) {
  runStdioBridge(process.argv.slice(2)).catch((err) => {
    process.stderr.write(`[pocketmcp-bridge] Fatal: ${sanitizeErrorMessage(err)}\n`);
    process.exit(1);
  });
}
