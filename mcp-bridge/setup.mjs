#!/usr/bin/env node
/**
 * PocketMCP setup helper — zero dependencies, Node 20+.
 *
 * Connects your AI coding tool to your phone with as little typing as
 * possible: finds the phone on the LAN (mDNS) or uses a saved profile,
 * verifies health + API key + tool discovery, then writes ready-made
 * configurations for opencode, Claude Code, Codex CLI, Cursor and
 * Claude Desktop — or prints copy-paste snippets.
 *
 * Hybrid by design: every tool can connect either
 *   1. directly over the phone's Streamable HTTP endpoint (native, fastest), or
 *   2. through the bundled zero-dependency stdio bridge (universal fallback).
 *
 * Usage:
 *   node setup.mjs                 # interactive wizard
 *   node setup.mjs --yes           # non-interactive: auto-detect tools, write configs
 *   node setup.mjs doctor          # diagnose connectivity end-to-end
 *   node setup.mjs discover        # LAN discovery only
 *   node setup.mjs print opencode  # print config for one tool (no writes)
 *
 * Options:
 *   --url <url>          phone endpoint (defaults: profile -> LAN discovery -> 127.0.0.1)
 *   --api-key <key>      API key from the PocketMCP app
 *   --harness <list>     comma-separated: opencode,claude-code,codex,cursor,claude-desktop,generic
 *   --mode <mode>        http (default) | bridge | both — connection style per tool
 *   --project            write project-scoped configs (current directory) where supported
 *   --yes, -y            assume "yes" for prompts; enable non-interactive writes
 *   --no-write           never modify files; only print snippets and commands
 *   --no-profile         do not save ~/.pocketmcp.json
 *   --json               machine-readable output (discover/doctor/print)
 *   --call <tool>        doctor: also invoke one tool to verify execution
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  BRIDGE_VERSION,
  PhoneClient,
  PhoneClientError,
  discoverPhones,
  loadProfile,
  normalizeMcpUrl,
  pickPhoneAddress,
  profilePath,
  saveProfile,
} from './bridge.mjs';

export const HARNESS_ORDER = ['opencode', 'claude-code', 'codex', 'cursor', 'claude-desktop', 'generic'];
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
export const BRIDGE_PATH = path.join(SCRIPT_DIR, 'bridge.mjs');

// ---------------------------------------------------------------------------
// Tiny output helpers
// ---------------------------------------------------------------------------

const useColor = process.stdout.isTTY && process.env.NO_COLOR === undefined;
const color = (code) => (text) => (useColor ? `\u001b[${code}m${text}\u001b[0m` : text);
const bold = color('1');
const dim = color('2');
const green = color('32');
const red = color('31');
const yellow = color('33');
const cyan = color('36');

const ok = (text) => `${green('✔')} ${text}`;
const bad = (text) => `${red('✘')} ${text}`;
const warn = (text) => `${yellow('⚠')} ${text}`;

function print(line = '') {
  process.stdout.write(`${line}\n`);
}

function jsonOut(value) {
  process.stdout.write(`${JSON.stringify(value, null, 2)}\n`);
}

function indent(text, prefix = '    ') {
  return String(text)
    .split('\n')
    .map((line) => `${prefix}${line}`)
    .join('\n');
}

// ---------------------------------------------------------------------------
// Argument parsing
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const options = {
    command: null,
    positionals: [],
    harnesses: null,
    mode: 'http',
    url: '',
    apiKey: '',
    project: false,
    yes: false,
    noWrite: false,
    noProfile: false,
    json: false,
    call: null,
    timeoutMs: 8000,
    verbose: false,
    help: false,
  };
  const rest = [...argv];
  if (rest[0] && !rest[0].startsWith('-')) options.command = rest.shift();
  for (let i = 0; i < rest.length; i += 1) {
    const arg = rest[i];
    const next = () => rest[++i] ?? '';
    switch (arg) {
      case '--url':
      case '-u':
        options.url = next();
        break;
      case '--api-key':
      case '-k':
        options.apiKey = next();
        break;
      case '--harness':
        options.harnesses = [...new Set(next().split(',').map((h) => h.trim()).filter(Boolean))];
        break;
      case '--mode':
        options.mode = next().trim().toLowerCase();
        break;
      case '--project':
        options.project = true;
        break;
      case '--yes':
      case '-y':
        options.yes = true;
        break;
      case '--no-write':
        options.noWrite = true;
        break;
      case '--no-profile':
        options.noProfile = true;
        break;
      case '--json':
        options.json = true;
        break;
      case '--call':
        options.call = next();
        break;
      case '--timeout-ms':
        options.timeoutMs = Number.parseInt(next(), 10) || options.timeoutMs;
        break;
      case '--verbose':
        options.verbose = true;
        break;
      case '--help':
      case '-h':
        options.help = true;
        break;
      default:
        if (arg.startsWith('-')) throw new Error(`Unknown option: ${arg}`);
        options.positionals.push(arg);
    }
  }
  if (!['http', 'bridge', 'both'].includes(options.mode)) {
    throw new Error(`Invalid --mode: ${options.mode} (expected http, bridge or both)`);
  }
  return options;
}

const HELP_TEXT = `PocketMCP setup ${BRIDGE_VERSION}

Usage:
  node setup.mjs [command] [options]

Commands:
  setup                  Interactive wizard (default). Finds/verifies the phone,
                         writes configs for the tools you pick.
  doctor                 Diagnose connectivity: health, auth, protocol, tools.
  discover               Search the LAN for PocketMCP phones (mDNS).
  print <harness>        Print one tool's config without writing anything.
                         Harnesses: ${HARNESS_ORDER.join(', ')}
  help                   Show this help.

Options:
  -u, --url <url>        Phone endpoint (default: profile -> LAN discovery -> 127.0.0.1)
  -k, --api-key <key>    API key from the PocketMCP app (blank = no auth)
      --harness <list>   Comma-separated tools to configure
      --mode <mode>      Connection style: http (direct Streamable HTTP, default),
                         bridge (stdio bridge), both (write http, print bridge)
      --project          Project-scoped configs (current directory) where supported
  -y, --yes              Non-interactive; confirm writes
      --no-write         Print snippets/commands only, never modify files
      --no-profile       Do not save ~/.pocketmcp.json
      --json             Machine-readable output (discover/doctor/print)
      --call <tool>      doctor: invoke a tool to verify execution (e.g. device_info)
      --timeout-ms <ms>  Per-request timeout (default 8000)

Hybrid connections — pick per tool:
  http (default)   The tool talks to http://<phone-ip>:8080/mcp directly.
  bridge           The tool launches the bundled stdio bridge (node bridge.mjs);
                   works with every MCP client, stdio-only ones included.
  both             Write the direct-HTTP config and print the bridge variant.

Examples:
  node setup.mjs                                   # guided setup, all detected tools
  node setup.mjs --yes --harness opencode,cursor   # scripted setup (direct HTTP)
  node setup.mjs --harness claude-desktop --mode bridge
  node setup.mjs doctor --call device_info         # full end-to-end check
`;

// ---------------------------------------------------------------------------
// Shell helpers
// ---------------------------------------------------------------------------

export function commandExists(command, platform = process.platform) {
  try {
    const probe = platform === 'win32' ? 'where' : 'which';
    const result = spawnSync(probe, [command], { stdio: 'ignore' });
    return result.status === 0;
  } catch {
    return false;
  }
}

function quoteForShell(value) {
  return /\s/.test(value) ? `"${value.replace(/"/g, '\\"')}"` : value;
}

export function runCliCommand(command, args, platform = process.platform) {
  try {
    // On Windows the CLI shims are .cmd files; cmd.exe needs pre-quoted args.
    const finalArgs = platform === 'win32' ? args.map(quoteForShell) : args;
    const result = spawnSync(command, finalArgs, {
      encoding: 'utf8',
      shell: platform === 'win32',
    });
    return {
      ok: result.status === 0,
      status: result.status,
      stdout: (result.stdout ?? '').trim(),
      stderr: (result.stderr ?? '').trim(),
    };
  } catch (err) {
    return { ok: false, status: null, stdout: '', stderr: err.message };
  }
}

// ---------------------------------------------------------------------------
// Config file helpers (pure, unit-tested)
// ---------------------------------------------------------------------------

function readTextFile(file) {
  try {
    return { exists: true, text: fs.readFileSync(file, 'utf8') };
  } catch {
    return { exists: false, text: '' };
  }
}

function backupFile(file) {
  if (!fs.existsSync(file)) return null;
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const backup = `${file}.backup-${stamp}`;
  fs.copyFileSync(file, backup);
  return backup;
}

/** Parses JSON, or returns null when the file uses comments (JSONC). */
export function parseJsonLoose(text) {
  if (!text.trim()) return {};
  try {
    const value = JSON.parse(text);
    return value && typeof value === 'object' ? value : {};
  } catch {
    return null;
  }
}

/**
 * Merges `entryPath` (e.g. ['mcp','servers','pocketmcp']) into a JSON config
 * object while preserving everything else. Returns the new object.
 */
export function mergeJsonEntry(base, entryPath, entry) {
  const root = base && typeof base === 'object' ? { ...base } : {};
  let cursor = root;
  for (const key of entryPath.slice(0, -1)) {
    const existing = cursor[key];
    cursor[key] = existing && typeof existing === 'object' && !Array.isArray(existing) ? { ...existing } : {};
    cursor = cursor[key];
  }
  cursor[entryPath[entryPath.length - 1]] = entry;
  return root;
}

export function jsonText(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

/**
 * Replaces `[section]` in a TOML document (including an immediately preceding
 * PocketMCP comment line) or appends the block at the end. Returns new text.
 */
export function replaceTomlSection(text, section, block) {
  const lines = text.length ? text.split(/\r?\n/) : [];
  const headerIndex = lines.findIndex((line) => line.trim() === `[${section}]`);
  if (headerIndex === -1) {
    const body = text.replace(/\s*$/, '');
    const prefix = body ? `${body}\n\n` : '';
    return `${prefix}${block}\n`;
  }
  let start = headerIndex;
  if (start > 0 && lines[start - 1].trim().startsWith('# PocketMCP')) start -= 1;
  let end = lines.length;
  for (let i = headerIndex + 1; i < lines.length; i += 1) {
    if (lines[i].trim().startsWith('[')) {
      end = i;
      break;
    }
  }
  const merged = [...lines.slice(0, start), ...block.split('\n'), ...lines.slice(end)];
  return `${merged.join('\n').replace(/\s*$/, '')}\n`;
}

function tomlString(value) {
  return `"${String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

// ---------------------------------------------------------------------------
// Entry builders shared by planners
// ---------------------------------------------------------------------------

/** Entry for clients that launch the stdio bridge as a subprocess. */
export function bridgeStdioEntry(ctx) {
  const env = { POCKET_MCP_URL: ctx.url };
  if (ctx.apiKey) env.POCKET_MCP_API_KEY = ctx.apiKey;
  return { command: 'node', args: [ctx.bridgePath], env };
}

/** opencode uses `command` arrays and `environment` for local servers. */
function bridgeOpencodeEntry(ctx) {
  const environment = { POCKET_MCP_URL: ctx.url };
  if (ctx.apiKey) environment.POCKET_MCP_API_KEY = ctx.apiKey;
  return { type: 'local', command: ['node', ctx.bridgePath], environment };
}

function codexTomlBlock(ctx, { bridge }) {
  const lines = ['# PocketMCP — added by `node mcp-bridge/setup.mjs`. Safe to edit or remove.', '[mcp_servers.pocketmcp]'];
  if (bridge) {
    lines.push(`command = "node"`);
    lines.push(`args = [${tomlString(ctx.bridgePath)}]`);
    const env = [`"POCKET_MCP_URL" = ${tomlString(ctx.url)}`];
    if (ctx.apiKey) env.push(`"POCKET_MCP_API_KEY" = ${tomlString(ctx.apiKey)}`);
    lines.push(`env = { ${env.join(', ')} }`);
  } else {
    lines.push(`url = ${tomlString(ctx.url)}`);
    if (ctx.apiKey) lines.push(`http_headers = { "X-API-Key" = ${tomlString(ctx.apiKey)} }`);
  }
  return lines.join('\n');
}

function httpSnippet(url, apiKey) {
  const entry = { type: 'http', url };
  if (apiKey) entry.headers = { 'X-API-Key': apiKey };
  return jsonText({ mcpServers: { pocketmcp: entry } });
}

// ---------------------------------------------------------------------------
// Harness planners (pure; used by setup and `print`)
// ---------------------------------------------------------------------------

export function planOpencode(ctx) {
  const useBridge = ctx.mode === 'bridge';
  const dir = ctx.project ? ctx.cwd : path.join(ctx.homeDir, '.config', 'opencode');
  const candidates = [path.join(dir, 'opencode.json'), path.join(dir, 'opencode.jsonc')];
  const existing = candidates.find((file) => fs.existsSync(file)) ?? candidates[0];
  const { text } = readTextFile(existing);
  const parsed = parseJsonLoose(text);

  const entry = useBridge
    ? bridgeOpencodeEntry(ctx)
    : { type: 'remote', url: ctx.url, oauth: false, ...(ctx.apiKey ? { headers: { 'X-API-Key': ctx.apiKey } } : {}) };
  const bridgeEntry = bridgeOpencodeEntry(ctx);

  const cliArgs = useBridge
    ? ['mcp', 'add', 'pocketmcp', ...(ctx.project ? [] : ['--global']), '--env', `POCKET_MCP_URL=${ctx.url}`, ...(ctx.apiKey ? ['--env', `POCKET_MCP_API_KEY=${ctx.apiKey}`] : []), '--', 'node', ctx.bridgePath]
    : ['mcp', 'add', 'pocketmcp', ...(ctx.project ? [] : ['--global']), '--url', ctx.url, ...(ctx.apiKey ? ['--header', `X-API-Key=${ctx.apiKey}`] : [])];
  const cliDisplay = `opencode ${cliArgs.map((a) => (/[\s=]/.test(a) && !a.startsWith('--') ? `"${a}"` : a)).join(' ')}`;

  const plan = {
    harness: 'opencode',
    title: 'OpenCode',
    transport: useBridge ? 'stdio bridge (local process)' : 'remote HTTP (native Streamable HTTP)',
    file: existing,
    snippet: jsonText({ mcp: { servers: { pocketmcp: entry } } }),
    cli: ctx.commandExists('opencode') ? { command: 'opencode', args: cliArgs, display: redactKey(cliDisplay, ctx.apiKey) } : null,
    cliCommand: cliDisplay,
    notes: useBridge
      ? ['OpenCode launches `node bridge.mjs` and speaks stdio to it.']
      : [
          'OpenCode V2 config lives under mcp.servers (opencode.jsonc).',
          'oauth is disabled for this entry so requests use the X-API-Key header.',
        ],
    post: 'Restart OpenCode, then run `opencode mcp list` — pocketmcp should show connected.',
  };
  if (!useBridge) plan.bridgeSnippet = jsonText({ mcp: { servers: { pocketmcp: bridgeEntry } } });

  if (parsed === null) {
    plan.writable = false;
    plan.skipReason = `${path.basename(existing)} contains JSONC comments; merge the snippet by hand or run the CLI command`;
    return plan;
  }

  let merged = mergeJsonEntry(parsed, ['mcp', 'servers', 'pocketmcp'], entry);
  if (!merged.$schema) {
    const { $schema, ...rest } = merged;
    merged = { $schema: 'https://opencode.ai/config.json', ...rest };
  }
  plan.writable = true;
  plan.content = jsonText(merged);
  return plan;
}

export function planClaudeCode(ctx) {
  const useBridge = ctx.mode === 'bridge';
  const scope = ctx.project ? 'project' : 'user';
  const args = useBridge
    ? [
        'mcp',
        'add',
        ...(ctx.apiKey ? ['--env', `POCKET_MCP_API_KEY=${ctx.apiKey}`] : []),
        '--env',
        `POCKET_MCP_URL=${ctx.url}`,
        '--transport',
        'stdio',
        'pocketmcp',
        '--',
        'node',
        ctx.bridgePath,
        '--scope',
        scope,
      ]
    : [
        'mcp',
        'add',
        '--transport',
        'http',
        'pocketmcp',
        ctx.url,
        ...(ctx.apiKey ? ['--header', `X-API-Key: ${ctx.apiKey}`] : []),
        '--scope',
        scope,
      ];
  const cliDisplay = `claude ${args.map((a) => (a.includes(' ') ? `"${a}"` : a)).join(' ')}`;
  const hasCli = ctx.commandExists('claude');

  const jsonEntry = useBridge
    ? bridgeStdioEntry(ctx)
    : { type: 'http', url: ctx.url, ...(ctx.apiKey ? { headers: { 'X-API-Key': ctx.apiKey } } : {}) };

  const plan = {
    harness: 'claude-code',
    title: 'Claude Code',
    transport: useBridge ? 'stdio bridge (local process)' : 'remote HTTP',
    snippet: jsonText({ mcpServers: { pocketmcp: jsonEntry } }),
    cli: hasCli ? { command: 'claude', args, display: redactKey(cliDisplay, ctx.apiKey) } : null,
    cliCommand: cliDisplay,
    file: ctx.project ? path.join(ctx.cwd, '.mcp.json') : null,
    notes: useBridge
      ? ['Claude Code starts `node bridge.mjs` per session; the JSON snippet uses the same command.']
      : [
          'User scope makes the phone available in every project (`--scope user`).',
          'Project scope writes .mcp.json and asks for approval on first use.',
        ],
    post: 'Run `claude mcp list` — pocketmcp should show ✔ Connected. Restart running sessions.',
  };
  if (!useBridge) plan.bridgeSnippet = jsonText({ mcpServers: { pocketmcp: bridgeStdioEntry(ctx) } });

  if (!hasCli) {
    if (ctx.project) {
      plan.writable = true;
      plan.file = path.join(ctx.cwd, '.mcp.json');
      const { text } = readTextFile(plan.file);
      const base = parseJsonLoose(text);
      if (base === null) {
        plan.writable = false;
        plan.skipReason = '.mcp.json is not plain JSON; merge the snippet by hand';
        return plan;
      }
      plan.content = jsonText(mergeJsonEntry(base, ['mcpServers', 'pocketmcp'], jsonEntry));
    } else {
      plan.writable = false;
      plan.skipReason = 'Claude Code CLI (`claude`) not found; run the command below or merge the snippet';
    }
    return plan;
  }

  plan.writable = false; // Config is written by the CLI, not by us.
  return plan;
}

/** Minimal TOML sanity check: verify our own section if present. */
export function tryParseToml(text) {
  if (!text.includes('[mcp_servers.pocketmcp]')) return false;
  const lines = text.split(/\r?\n/);
  let inSection = false;
  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith('[')) {
      inSection = trimmed === '[mcp_servers.pocketmcp]';
      continue;
    }
    if (inSection && trimmed && !trimmed.startsWith('#') && !trimmed.includes('=')) return false;
  }
  return true;
}

export function planCodex(ctx) {
  const useBridge = ctx.mode === 'bridge';
  const file = ctx.project
    ? path.join(ctx.cwd, '.codex', 'config.toml')
    : path.join(ctx.homeDir, '.codex', 'config.toml');
  const { text } = readTextFile(file);
  const block = codexTomlBlock(ctx, { bridge: useBridge });
  const content = replaceTomlSection(text, 'mcp_servers.pocketmcp', block);
  const parsed = tryParseToml(content);
  const plan = {
    harness: 'codex',
    title: 'Codex CLI',
    transport: useBridge ? 'stdio bridge (local process)' : 'remote HTTP (native Streamable HTTP)',
    file,
    writable: parsed,
    skipReason: parsed ? null : 'config.toml could not be parsed after the change; add the block manually',
    content,
    snippet: `${block}\n`,
    notes: ctx.project ? ['Project scope (.codex/config.toml) only applies in trusted projects.'] : [],
    post: 'Restart Codex, then run `codex mcp list` — pocketmcp should be listed.',
  };
  if (!useBridge) plan.bridgeSnippet = `${codexTomlBlock(ctx, { bridge: true })}\n`;
  return plan;
}

export function planCursor(ctx) {
  const useBridge = ctx.mode === 'bridge';
  const file = ctx.project
    ? path.join(ctx.cwd, '.cursor', 'mcp.json')
    : path.join(ctx.homeDir, '.cursor', 'mcp.json');
  const { text } = readTextFile(file);
  const base = parseJsonLoose(text);
  const entry = useBridge
    ? bridgeStdioEntry(ctx)
    : { url: ctx.url, ...(ctx.apiKey ? { headers: { 'X-API-Key': ctx.apiKey } } : {}) };

  const plan = {
    harness: 'cursor',
    title: 'Cursor',
    transport: useBridge ? 'stdio bridge (local process)' : 'remote HTTP',
    file,
    snippet: jsonText({ mcpServers: { pocketmcp: entry } }),
    notes: useBridge
      ? ['Cursor starts `node bridge.mjs` itself; no extra terminal needed.']
      : ['Cursor infers the transport from url; no type field is required.'],
    post: 'Reload Cursor (it re-reads mcp.json on restart or via Settings → MCP).',
  };
  if (!useBridge) plan.bridgeSnippet = jsonText({ mcpServers: { pocketmcp: bridgeStdioEntry(ctx) } });

  if (base === null) {
    plan.writable = false;
    plan.skipReason = 'mcp.json is not plain JSON; merge the snippet by hand';
    return plan;
  }
  plan.writable = true;
  plan.content = jsonText(mergeJsonEntry(base, ['mcpServers', 'pocketmcp'], entry));
  return plan;
}

export function claudeDesktopConfigPath(ctx) {
  if (ctx.platform === 'win32') {
    const appData = ctx.env.APPDATA || path.join(ctx.homeDir, 'AppData', 'Roaming');
    return path.join(appData, 'Claude', 'claude_desktop_config.json');
  }
  if (ctx.platform === 'darwin') {
    return path.join(ctx.homeDir, 'Library', 'Application Support', 'Claude', 'claude_desktop_config.json');
  }
  return path.join(ctx.homeDir, '.config', 'Claude', 'claude_desktop_config.json');
}

export function planClaudeDesktop(ctx) {
  const file = claudeDesktopConfigPath(ctx);
  const { text } = readTextFile(file);
  const base = parseJsonLoose(text);
  const entry = bridgeStdioEntry(ctx);
  const plan = {
    harness: 'claude-desktop',
    title: 'Claude Desktop',
    transport: 'stdio bridge (local process)',
    file,
    snippet: jsonText({ mcpServers: { pocketmcp: entry } }),
    notes: [
      'Claude Desktop config files launch local (stdio) servers, so this uses the bundled bridge — Node must be installed.',
      ctx.mode === 'http'
        ? 'Direct HTTP: add a Connector in Settings → Connectors (URL + X-API-Key header) if your version supports custom headers.'
        : 'The bridge needs no extra terminal — the app starts it automatically.',
    ],
    post: 'Quit and reopen Claude Desktop. Tool approval appears on first use.',
  };
  if (base === null) {
    plan.writable = false;
    plan.skipReason = 'claude_desktop_config.json is not plain JSON; merge the snippet by hand';
    return plan;
  }
  plan.writable = true;
  plan.content = jsonText(mergeJsonEntry(base, ['mcpServers', 'pocketmcp'], entry));
  return plan;
}

export function planGeneric(ctx) {
  return {
    harness: 'generic',
    title: 'Generic MCP client',
    transport: 'remote HTTP or stdio bridge',
    file: null,
    writable: false,
    snippet: httpSnippet(ctx.url, ctx.apiKey),
    bridgeSnippet: jsonText({ mcpServers: { pocketmcp: bridgeStdioEntry(ctx) } }),
    notes: [
      'Direct HTTP: most clients accept {"mcpServers": {...}} with url + headers.',
      'Claude Code needs "type": "http"; Cursor ignores type; opencode uses mcp.servers (see `print opencode`).',
      'Stdio bridge: any stdio-only client (Windsurf, Cline, older apps) can launch bridge.mjs.',
    ],
    post: 'Paste the snippet into your client config and restart it.',
  };
}

export const HARNESS_REGISTRY = {
  opencode: {
    title: 'OpenCode',
    plan: planOpencode,
    detect: (ctx) => fs.existsSync(path.join(ctx.homeDir, '.config', 'opencode')) || ctx.commandExists('opencode'),
  },
  'claude-code': {
    title: 'Claude Code',
    plan: planClaudeCode,
    detect: (ctx) => ctx.commandExists('claude'),
  },
  codex: {
    title: 'Codex CLI',
    plan: planCodex,
    detect: (ctx) => fs.existsSync(path.join(ctx.homeDir, '.codex')) || ctx.commandExists('codex'),
  },
  cursor: {
    title: 'Cursor',
    plan: planCursor,
    detect: (ctx) => fs.existsSync(path.join(ctx.homeDir, '.cursor')),
  },
  'claude-desktop': {
    title: 'Claude Desktop',
    plan: planClaudeDesktop,
    detect: (ctx) => fs.existsSync(path.dirname(claudeDesktopConfigPath(ctx))),
  },
  generic: {
    title: 'Generic MCP snippet',
    plan: planGeneric,
    detect: () => false,
  },
};

export function makeContext(options) {
  return {
    url: options.url,
    apiKey: options.apiKey,
    homeDir: options.homeDir ?? os.homedir(),
    cwd: options.cwd ?? process.cwd(),
    platform: options.platform ?? process.platform,
    env: options.env ?? process.env,
    bridgePath: options.bridgePath ?? BRIDGE_PATH,
    project: Boolean(options.project),
    mode: options.mode ?? 'http',
    commandExists: options.commandExists ?? commandExists,
  };
}

function redactKey(text, apiKey) {
  if (!apiKey) return text;
  return String(text).split(apiKey).join('***');
}

// ---------------------------------------------------------------------------
// Connection resolution + verification
// ---------------------------------------------------------------------------

const isInteractive = () => Boolean(process.stdin.isTTY && process.stdout.isTTY);

async function resolveTarget(options, ctx, { allowPrompt = true } = {}) {
  let url = options.url || process.env.POCKET_MCP_URL || '';
  let apiKey = options.apiKey ?? process.env.POCKET_MCP_API_KEY ?? '';
  let source = null;

  if (!url) {
    const profile = loadProfile(ctx.homeDir);
    if (profile) {
      url = profile.url;
      if (!apiKey) apiKey = profile.apiKey ?? '';
      source = `profile ${profilePath(ctx.homeDir)}`;
    }
  }

  if (!url) {
    print(dim('Searching the LAN for PocketMCP…'));
    const devices = await discoverPhones({ timeoutMs: 4000 });
    if (devices.length > 0) {
      const first = devices[0];
      const address = pickPhoneAddress(first);
      if (address) {
        url = `http://${address}:${first.port}/mcp`;
        source = `LAN discovery (${first.name})`;
        print(ok(`Found ${bold(first.name)} at ${cyan(`${address}:${first.port}`)}`));
        if (devices.length > 1) {
          print(dim(`   ${devices.length - 1} more device(s) also responded; using the first.`));
        }
      }
    } else {
      print(dim('   No phone found via mDNS (uncommon on guest Wi-Fi / VPN networks).'));
    }
  }

  if (!url && allowPrompt && isInteractive()) {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    try {
      const answer = (await rl.question('Phone address [http://127.0.0.1:8080/mcp]: ')).trim();
      url = answer || 'http://127.0.0.1:8080/mcp';
      source = answer ? 'entered manually' : 'default';
    } finally {
      rl.close();
    }
  }

  if (!url) {
    url = 'http://127.0.0.1:8080/mcp';
    source = 'default (adb forward / localhost)';
  }

  url = normalizeMcpUrl(url);

  if (!apiKey && allowPrompt && isInteractive()) {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    try {
      const answer = (await rl.question('API key from the app (Enter to skip if none): ')).trim();
      apiKey = answer;
      if (!answer) print(warn('No API key set. Connecting only works if the app has no key configured.'));
    } finally {
      rl.close();
    }
  }

  return { url, apiKey, source: source ?? 'requested' };
}

async function verifyConnection(url, apiKey, timeoutMs = 8000) {
  const client = new PhoneClient({ url, apiKey, timeoutMs });
  const report = {
    url: normalizeMcpUrl(url),
    apiKeySet: Boolean(apiKey),
    health: null,
    server: null,
    tools: null,
    error: null,
  };

  try {
    const health = await client.health({ timeoutMs: Math.min(timeoutMs, 5000) });
    report.health = { status: health.status, ok: health.ok, ms: health.ms };
  } catch (err) {
    report.error = {
      stage: 'health',
      kind: err instanceof PhoneClientError ? err.kind : 'transport',
      message: err.message,
    };
    return report;
  }

  try {
    const started = Date.now();
    await client.initialize();
    const tools = await client.listTools();
    report.server = {
      name: client.serverInfo?.name ?? 'PocketMCP',
      version: client.serverInfo?.version ?? null,
      protocolVersion: client.protocolVersion,
      toolsListMs: Date.now() - started,
    };
    report.tools = tools.map((tool) => tool.name);
  } catch (err) {
    report.error = {
      stage: 'auth-or-tools',
      kind: err instanceof PhoneClientError ? err.kind : 'transport',
      message: err.message,
    };
  }
  return report;
}

function printVerification(report) {
  const { health, server, tools, error } = report;
  if (health) {
    print(ok(`Health: HTTP ${health.status} in ${health.ms} ms ${dim(report.url)}`));
  } else if (error) {
    print(bad(`Health check failed: ${error.message}`));
  }
  if (server) {
    print(ok(`Protocol: ${bold(server.name)} ${server.version ?? ''} · MCP ${server.protocolVersion ?? 'unknown'}`));
  }
  if (tools) {
    print(ok(`Tools: ${tools.length} available (list served in ${server?.toolsListMs ?? '?'} ms)`));
  } else if (error && error.stage === 'auth-or-tools') {
    print(bad(error.message));
  }
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

async function cmdDiscover(options) {
  const devices = await discoverPhones({ timeoutMs: Math.max(options.timeoutMs, 4000) });
  if (options.json) {
    jsonOut({ devices });
    return devices.length > 0 ? 0 : 1;
  }
  if (devices.length === 0) {
    print(bad('No PocketMCP devices found on this network.'));
    print(dim('Tips: phone and computer must share the same Wi-Fi; some routers block mDNS.'));
    return 1;
  }
  for (const device of devices) {
    const address = pickPhoneAddress(device) ?? 'unknown';
    print(`${green('●')} ${bold(device.name)} ${dim(`-> http://${address}:${device.port}/mcp`)}`);
  }
  return 0;
}

async function cmdDoctor(options) {
  const ctx = makeContext(options);
  const { url, apiKey, source } = await resolveTarget(options, ctx);
  if (!options.json) {
    print(bold('PocketMCP doctor'));
    print(dim(`Endpoint: ${url} (${source})`));
    print();
  }
  const report = await verifyConnection(url, apiKey, options.timeoutMs);
  report.source = source;

  let callResult = null;
  if (options.call && report.tools) {
    const client = new PhoneClient({ url, apiKey, timeoutMs: Math.max(options.timeoutMs, 20000) });
    try {
      const payload = await client.callTool(options.call, {});
      callResult = payload?.error
        ? { ok: false, message: payload.error.message }
        : { ok: true, preview: JSON.stringify(payload?.result ?? {}).slice(0, 400) };
    } catch (err) {
      callResult = { ok: false, message: err.message };
    }
    report.call = callResult;
  }

  if (options.json) {
    jsonOut(report);
  } else {
    printVerification(report);
    if (callResult) {
      print(
        callResult.ok
          ? ok(`tools/call ${options.call}: ok ${dim(callResult.preview)}`)
          : bad(`tools/call ${options.call}: ${callResult.message}`)
      );
    }
    print();
    if (!report.error && (!options.call || callResult?.ok)) {
      print(`${green(bold('Everything checks out.'))} ${dim(`${report.tools?.length ?? 0} tools ready.`)}`);
    } else {
      print(dim('Next steps:'));
      if (report.error?.kind === 'auth') print(dim('  • Copy the current API key from the app (Quick Connect) and retry with --api-key.'));
      if (report.error?.kind === 'transport') print(dim('  • Is the server started in the app? Same Wi-Fi? Try --url http://<phone-ip>:8080/mcp.'));
      if (!report.error && callResult && !callResult.ok) print(dim("  • Tool execution failed — check the app's Logs tab for details."));
    }
  }

  const healthy = !report.error && (!options.call || callResult?.ok);
  return healthy ? 0 : 1;
}

function selectHarnesses(options, ctx) {
  if (options.harnesses && options.harnesses.length > 0) {
    const unknown = options.harnesses.filter((h) => !HARNESS_REGISTRY[h]);
    if (unknown.length > 0) throw new Error(`Unknown harness: ${unknown.join(', ')} (expected ${HARNESS_ORDER.join(', ')})`);
    return options.harnesses;
  }
  const detected = HARNESS_ORDER.filter((h) => h !== 'generic' && HARNESS_REGISTRY[h].detect(ctx));
  if (options.command === 'setup' || options.command === null) {
    if (!isInteractive() || options.yes) {
      return detected.length > 0 ? detected : ['generic'];
    }
    return null; // ask interactively
  }
  return detected;
}

async function askHarnesses(ctx) {
  print();
  print(bold('Which tool(s) should connect to your phone?'));
  const detected = HARNESS_ORDER.filter((h) => h !== 'generic' && HARNESS_REGISTRY[h].detect(ctx));
  HARNESS_ORDER.forEach((harness, index) => {
    const info = HARNESS_REGISTRY[harness];
    const mark = detected.includes(harness) ? green(' (detected)') : '';
    print(`  ${bold(String(index + 1))}. ${info.title}${mark}`);
  });
  const fallback = detected.length > 0 ? detected : ['generic'];
  const defaultText = fallback.map((h) => HARNESS_ORDER.indexOf(h) + 1).join(',');
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  try {
    const answer = (
      await rl.question(`Selection [default: ${defaultText}] (numbers separated by commas, or "a" for all): `)
    ).trim();
    if (!answer) return fallback;
    if (answer.toLowerCase() === 'a' || answer.toLowerCase() === 'all') return [...HARNESS_ORDER];
    const selected = [];
    for (const part of answer.split(/[\s,]+/)) {
      const index = Number.parseInt(part, 10);
      if (Number.isFinite(index) && index >= 1 && index <= HARNESS_ORDER.length) selected.push(HARNESS_ORDER[index - 1]);
    }
    return selected.length > 0 ? [...new Set(selected)] : fallback;
  } finally {
    rl.close();
  }
}

async function cmdSetup(options) {
  const ctx = makeContext(options);

  if (isInteractive() && !options.json) {
    print();
    print(bold(cyan('PocketMCP setup')) + dim(`  bridge ${BRIDGE_VERSION}`));
    print(dim('Connects your AI coding tool to your phone — direct HTTP or stdio bridge.'));
  }

  const { url, apiKey, source } = await resolveTarget(options, ctx);
  ctx.url = url;
  ctx.apiKey = apiKey;

  print(ok(`Phone endpoint: ${cyan(url)} ${dim(`(${source})`)}`));
  print(dim('Verifying connection…'));
  const report = await verifyConnection(url, apiKey, options.timeoutMs);
  printVerification(report);

  let proceed = true;
  if (report.error) {
    print();
    print(warn('The phone did not respond fully. Configs can still be written — the tools will connect once this is fixed.'));
    print(dim('Run `node setup.mjs doctor` any time to re-check.'));
    if (isInteractive() && !options.yes) {
      const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
      try {
        const answer = (await rl.question('Continue anyway? [y/N]: ')).trim().toLowerCase();
        proceed = answer === 'y' || answer === 'yes';
      } finally {
        rl.close();
      }
    }
  }
  if (!proceed) {
    print(dim('Nothing written.'));
    return 1;
  }

  let harnesses = selectHarnesses(options, ctx);
  if (!harnesses) harnesses = await askHarnesses(ctx);
  if (harnesses.length === 0) {
    print(dim('Nothing selected.'));
    return 0;
  }

  const plans = harnesses.map((harness) => HARNESS_REGISTRY[harness].plan(ctx));
  for (const plan of plans) plan.mode = options.mode;

  print();
  print(bold(`Plan ${dim(`(connection mode: ${options.mode})`)}`));
  for (const plan of plans) {
    const action = plan.writable
      ? `write ${dim(plan.file)}`
      : plan.cli
        ? `run ${dim(`${plan.cli.command} CLI`)}`
        : 'print snippet';
    print(`  ${bold(plan.title)} ${dim(`(${plan.transport})`)} -> ${action}`);
    if (plan.skipReason) print(dim(`     ${plan.skipReason}`));
  }

  let confirmed = false;
  if (options.noWrite) {
    confirmed = false;
  } else if (options.yes) {
    confirmed = true;
  } else if (isInteractive()) {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    try {
      const answer = (await rl.question(`\nApply these changes? ${dim('[Y/n]')} `)).trim().toLowerCase();
      confirmed = answer === '' || answer === 'y' || answer === 'yes';
    } finally {
      rl.close();
    }
  } else {
    print(dim('Non-interactive run: writing configs requires --yes. Printing snippets instead.'));
  }

  print();
  for (const plan of plans) {
    applyPlan(plan, { confirmed });
  }

  if (!options.noProfile) {
    const saved = saveProfile({ url, apiKey }, ctx.homeDir);
    print(ok(`Saved connection profile ${dim(saved)} — the bridge reuses it automatically.`));
  }

  print();
  print(bold('Done. Next steps'));
  for (const plan of plans) {
    print(`  ${green('•')} ${plan.title}: ${plan.post}`);
  }
  if (plans.some((p) => !p.writable)) {
    print(dim('  Snippets above can be pasted into each client\'s config file.'));
  }
  print(dim(`  Switch connection style any time: --mode http | bridge | both`));
  return 0;
}

function applyPlan(plan, { confirmed }) {
  const result = { harness: plan.harness, written: false, path: null, cliRun: null };
  if (!confirmed) {
    printSection(`${plan.title} — ${plan.transport}`, plan);
    return result;
  }

  if (plan.writable && plan.content && plan.file) {
    const backup = backupFile(plan.file);
    fs.mkdirSync(path.dirname(plan.file), { recursive: true });
    fs.writeFileSync(plan.file, plan.content, 'utf8');
    result.written = true;
    result.path = plan.file;
    print(ok(`Wrote ${bold(plan.file)}${backup ? dim(` (backup: ${path.basename(backup)})`) : ''}`));
    printBridgeAlternative(plan);
    return result;
  }

  if (plan.cli) {
    const run = runCliCommand(plan.cli.command, plan.cli.args);
    if (run.ok) {
      result.cliRun = { command: plan.cli.display, ok: true };
      print(ok(`Ran ${bold(plan.cli.display)}`));
      if (run.stdout) print(dim(indent(run.stdout)));
      printBridgeAlternative(plan);
      return result;
    }
    result.cliRun = { command: plan.cli.display, ok: false, error: run.stderr || run.stdout };
    print(warn(`Could not run ${plan.cli.command} automatically: ${run.stderr || run.stdout || 'unknown error'}`));
    print(dim('   Run it yourself:'));
    print(indent(plan.cliCommand ?? plan.cli.display));
    return result;
  }

  printSection(`${plan.title} — ${plan.transport}`, plan);
  return result;
}

function printBridgeAlternative(plan) {
  if (plan.mode === 'both' && plan.bridgeSnippet) {
    print(dim('  stdio bridge alternative (mode=both):'));
    print(indent(plan.bridgeSnippet.trimEnd()));
  }
}

function printSection(title, plan) {
  print(bold(`${title}`));
  if (plan.file) print(dim(`  target: ${plan.file}`));
  if (plan.skipReason) print(warn(`  ${plan.skipReason}`));
  if (plan.cliCommand) {
    print(dim('  command:'));
    print(indent(plan.cliCommand));
  }
  if (plan.snippet) {
    print(dim('  config snippet:'));
    print(indent(plan.snippet.trimEnd()));
  }
  if (plan.bridgeSnippet) {
    print(dim('  stdio bridge alternative:'));
    print(indent(plan.bridgeSnippet.trimEnd()));
  }
  for (const note of plan.notes ?? []) print(dim(`  note: ${note}`));
  if (plan.post) print(dim(`  after: ${plan.post}`));
  print();
}

function cmdPrint(options) {
  const name = options.positionals[0];
  if (!name || !HARNESS_REGISTRY[name]) {
    if (name) print(bad(`Unknown harness: ${name}`));
    print(dim(`Available: ${HARNESS_ORDER.join(', ')}`));
    return 2;
  }
  const ctx = makeContext(options);
  ctx.url = normalizeMcpUrl(options.url || process.env.POCKET_MCP_URL || 'http://127.0.0.1:8080/mcp');
  ctx.apiKey = options.apiKey ?? process.env.POCKET_MCP_API_KEY ?? '';
  const plan = HARNESS_REGISTRY[name].plan(ctx);
  if (options.json) {
    jsonOut(plan);
    return 0;
  }
  printSection(`${plan.title} — ${plan.transport}`, plan);
  return 0;
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

async function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
  } catch (err) {
    process.stderr.write(`${err.message}\n`);
    return 2;
  }

  if (options.help || options.command === 'help') {
    process.stdout.write(HELP_TEXT);
    return 0;
  }

  switch (options.command) {
    case 'discover':
      return cmdDiscover(options);
    case 'doctor':
      return cmdDoctor(options);
    case 'print':
      return cmdPrint(options);
    case 'setup':
    case null:
      return cmdSetup(options);
    default:
      process.stderr.write(`Unknown command: ${options.command}\n\n${HELP_TEXT}`);
      return 2;
  }
}

const isDirectRun = process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url;
if (isDirectRun) {
  main()
    .then((code) => {
      process.exitCode = code ?? 0;
    })
    .catch((err) => {
      process.stderr.write(`[pocketmcp-setup] ${err?.stack ?? err}\n`);
      process.exitCode = 1;
    });
}
