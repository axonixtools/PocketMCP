import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import {
  HARNESS_ORDER,
  HARNESS_REGISTRY,
  jsonText,
  mergeJsonEntry,
  parseJsonLoose,
  planClaudeCode,
  planClaudeDesktop,
  planCodex,
  planCursor,
  planOpencode,
  replaceTomlSection,
} from '../setup.mjs';

const URL = 'http://192.0.2.50:8080/mcp';
const KEY = 'secret-key';

let dirs;

beforeEach(() => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketmcp-setup-'));
  dirs = {
    root,
    home: path.join(root, 'home'),
    cwd: path.join(root, 'cwd'),
  };
  fs.mkdirSync(dirs.home, { recursive: true });
  fs.mkdirSync(dirs.cwd, { recursive: true });
});

afterEach(() => {
  fs.rmSync(dirs.root, { recursive: true, force: true });
});

function makeCtx(overrides = {}) {
  return {
    url: URL,
    apiKey: KEY,
    homeDir: dirs.home,
    cwd: dirs.cwd,
    platform: 'linux',
    env: {},
    bridgePath: '/repo/mcp-bridge/bridge.mjs',
    project: false,
    mode: 'http',
    commandExists: () => false,
    ...overrides,
  };
}

describe('registry', () => {
  it('exposes every documented harness with a planner', () => {
    for (const harness of HARNESS_ORDER) {
      assert.ok(HARNESS_REGISTRY[harness], `missing ${harness}`);
      assert.equal(typeof HARNESS_REGISTRY[harness].plan, 'function');
    }
  });
});

describe('JSON/TOML helpers', () => {
  it('merges entries without touching sibling data', () => {
    const base = { mcpServers: { other: { url: 'http://x' } }, note: 'keep' };
    const merged = mergeJsonEntry(base, ['mcpServers', 'pocketmcp'], { url: URL });
    assert.equal(merged.mcpServers.other.url, 'http://x');
    assert.equal(merged.mcpServers.pocketmcp.url, URL);
    assert.equal(merged.note, 'keep');
    assert.equal(base.mcpServers.pocketmcp, undefined, 'input must not be mutated');
  });

  it('detects JSONC-style files', () => {
    assert.equal(parseJsonLoose('// comment\n{"a":1}'), null);
    assert.deepEqual(parseJsonLoose('{"a":1}'), { a: 1 });
    assert.deepEqual(parseJsonLoose(''), {});
  });

  it('replaces a TOML section and keeps following sections', () => {
    const text = '[mcp_servers.pocketmcp]\nurl = "http://old"\n\n[other]\nx = 1\n';
    const out = replaceTomlSection(text, 'mcp_servers.pocketmcp', '[mcp_servers.pocketmcp]\nurl = "http://new"');
    assert.match(out, /http:\/\/new/);
    assert.doesNotMatch(out, /http:\/\/old/);
    assert.match(out, /\[other\]/);
    assert.match(out, /x = 1/);
  });

  it('appends a TOML section into an empty document', () => {
    const out = replaceTomlSection('', 'mcp_servers.pocketmcp', '[mcp_servers.pocketmcp]\nurl = "http://new"');
    assert.equal(out, '[mcp_servers.pocketmcp]\nurl = "http://new"\n');
  });
});

describe('planCursor', () => {
  it('creates a mergeable remote entry (http mode)', () => {
    const file = path.join(dirs.home, '.cursor', 'mcp.json');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, jsonText({ mcpServers: { other: { url: 'http://x' } } }));

    const plan = planCursor(makeCtx());
    assert.equal(plan.writable, true);
    fs.writeFileSync(plan.file, plan.content);
    const merged = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.ok(merged.mcpServers.other);
    assert.equal(merged.mcpServers.pocketmcp.url, URL);
    assert.equal(merged.mcpServers.pocketmcp.headers['X-API-Key'], KEY);
  });

  it('builds a stdio bridge entry in bridge mode', () => {
    const plan = planCursor(makeCtx({ mode: 'bridge' }));
    const entry = JSON.parse(plan.content).mcpServers.pocketmcp;
    assert.equal(entry.command, 'node');
    assert.deepEqual(entry.args, ['/repo/mcp-bridge/bridge.mjs']);
    assert.equal(entry.env.POCKET_MCP_URL, URL);
    assert.equal(entry.env.POCKET_MCP_API_KEY, KEY);
  });
});

describe('planCodex', () => {
  it('appends and then idempotently replaces the section', () => {
    const file = path.join(dirs.home, '.codex', 'config.toml');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, '[other]\nx = 1\n');

    const first = planCodex(makeCtx());
    assert.equal(first.writable, true);
    fs.writeFileSync(file, first.content);
    const afterFirst = fs.readFileSync(file, 'utf8');
    assert.equal((afterFirst.match(/\[mcp_servers\.pocketmcp\]/g) ?? []).length, 1);
    assert.match(afterFirst, /http_headers = \{ "X-API-Key" = "secret-key" \}/);
    assert.match(afterFirst, /\[other\]/);

    const second = planCodex(makeCtx());
    assert.equal((second.content.match(/\[mcp_servers\.pocketmcp\]/g) ?? []).length, 1);
    assert.match(second.content, /\[other\]/);
  });

  it('emits a command/args/env block in bridge mode', () => {
    const plan = planCodex(makeCtx({ mode: 'bridge' }));
    assert.match(plan.content, /command = "node"/);
    assert.match(plan.content, /args = \["\/repo\/mcp-bridge\/bridge\.mjs"\]/);
    assert.match(plan.content, /env = \{ "POCKET_MCP_URL" = "http:\/\/192\.0\.2\.50:8080\/mcp", "POCKET_MCP_API_KEY" = "secret-key" \}/);
  });
});

describe('planOpencode', () => {
  it('creates opencode.json with mcp.servers and oauth disabled', () => {
    const plan = planOpencode(makeCtx());
    assert.equal(plan.writable, true);
    assert.ok(plan.file.endsWith('opencode.json'));
    const parsed = JSON.parse(plan.content);
    assert.equal(Object.keys(parsed)[0], '$schema');
    const entry = parsed.mcp.servers.pocketmcp;
    assert.equal(entry.type, 'remote');
    assert.equal(entry.oauth, false);
    assert.equal(entry.headers['X-API-Key'], KEY);
  });

  it('preserves other servers and existing schema in an existing file', () => {
    const file = path.join(dirs.home, '.config', 'opencode', 'opencode.json');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, jsonText({ $schema: 'https://opencode.ai/config.json', mcp: { servers: { context7: { type: 'remote', url: 'https://x' } } } }));
    const plan = planOpencode(makeCtx());
    const parsed = JSON.parse(plan.content);
    assert.equal(parsed.mcp.servers.context7.url, 'https://x');
    assert.equal(parsed.mcp.servers.pocketmcp.url, URL);
  });

  it('falls back to printing when the config uses JSONC comments', () => {
    const file = path.join(dirs.home, '.config', 'opencode', 'opencode.jsonc');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, '{\n  // my config\n  "mcp": {}\n}\n');
    const plan = planOpencode(makeCtx());
    assert.equal(plan.writable, false);
    assert.match(plan.skipReason, /JSONC/);
    assert.ok(plan.snippet.includes('oauth'));
  });

  it('exposes a CLI command with --global and the key header', () => {
    const plan = planOpencode(makeCtx({ commandExists: () => true }));
    assert.ok(plan.cli);
    const cli = plan.cli.args.join(' ');
    assert.match(cli, /--global/);
    assert.match(cli, /--url http:\/\/192\.0\.2\.50:8080\/mcp/);
    assert.match(cli, /--header X-API-Key=secret-key/);
  });

  it('builds a local command entry in bridge mode', () => {
    const plan = planOpencode(makeCtx({ mode: 'bridge' }));
    const entry = JSON.parse(plan.content).mcp.servers.pocketmcp;
    assert.equal(entry.type, 'local');
    assert.deepEqual(entry.command, ['node', '/repo/mcp-bridge/bridge.mjs']);
    assert.equal(entry.environment.POCKET_MCP_API_KEY, KEY);
  });
});

describe('planClaudeCode', () => {
  it('emits the add command and snippet when no CLI is installed', () => {
    const plan = planClaudeCode(makeCtx());
    assert.equal(plan.writable, false);
    assert.match(plan.cliCommand, /claude mcp add --transport http pocketmcp/);
    assert.match(plan.cliCommand, /--header "X-API-Key: secret-key"/);
    assert.match(plan.cliCommand, /--scope user/);
    const snippet = JSON.parse(plan.snippet);
    assert.equal(snippet.mcpServers.pocketmcp.type, 'http');
  });

  it('uses the CLI when available', () => {
    const plan = planClaudeCode(makeCtx({ commandExists: (cmd) => cmd === 'claude' }));
    assert.ok(plan.cli);
    assert.deepEqual(plan.cli.args.slice(0, 6), ['mcp', 'add', '--transport', 'http', 'pocketmcp', URL]);
    assert.match(plan.cli.display, /\*\*\*/); // key redacted in display
  });

  it('builds a stdio CLI invocation in bridge mode', () => {
    const plan = planClaudeCode(makeCtx({ commandExists: (cmd) => cmd === 'claude', mode: 'bridge' }));
    const args = plan.cli.args.join(' ');
    assert.match(args, /--transport stdio/);
    assert.match(args, /--env POCKET_MCP_URL=http:\/\/192\.0\.2\.50:8080\/mcp/);
    assert.match(args, /-- node \/repo\/mcp-bridge\/bridge\.mjs/);
  });

  it('writes .mcp.json for project scope when the CLI is missing', () => {
    const plan = planClaudeCode(makeCtx({ project: true }));
    assert.equal(plan.writable, true);
    assert.ok(plan.file.endsWith('.mcp.json'));
    const parsed = JSON.parse(plan.content);
    assert.equal(parsed.mcpServers.pocketmcp.headers['X-API-Key'], KEY);
  });
});

describe('planClaudeDesktop', () => {
  it('targets %APPDATA%\\Claude on Windows and merges existing config', () => {
    const appData = path.join(dirs.root, 'roaming');
    const file = path.join(appData, 'Claude', 'claude_desktop_config.json');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, jsonText({ mcpServers: { other: { command: 'x' } } }));

    const plan = planClaudeDesktop(makeCtx({ platform: 'win32', env: { APPDATA: appData } }));
    assert.equal(plan.file, file);
    assert.equal(plan.writable, true);
    const parsed = JSON.parse(plan.content);
    assert.ok(parsed.mcpServers.other);
    const entry = parsed.mcpServers.pocketmcp;
    assert.equal(entry.command, 'node');
    assert.equal(entry.env.POCKET_MCP_URL, URL);
  });

  it('targets Library/Application Support on macOS', () => {
    const plan = planClaudeDesktop(makeCtx({ platform: 'darwin' }));
    assert.ok(plan.file.includes(path.join('Library', 'Application Support', 'Claude')));
  });

  it('notes the custom connector option in http mode', () => {
    const plan = planClaudeDesktop(makeCtx());
    assert.ok(plan.notes.some((note) => /Connector/i.test(note)));
  });
});
