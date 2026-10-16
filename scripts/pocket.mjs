#!/usr/bin/env node
/**
 * pocket — instant PocketMCP command runner.
 *
 * Turns short everyday commands into direct phone tool calls with NO AI in
 * the loop: "open youtube", "torch on", "volume down 2", "scroll down",
 * "lock", "screenshot", "ring". Anything that is not one of the built-in
 * fast patterns is sent to the phone's on-device NLU (human_command) in the
 * same single round trip — still no desktop LLM involved.
 *
 * Usage:
 *   pocket "open youtube"
 *   pocket torch on
 *   pocket --raw flashlight '{"action":"status"}'
 *   pocket --bench 20 "torch status"
 *   pocket --url http://192.0.2.50:8080/mcp --api-key KEY "volume up"
 *
 * Endpoint + key default to the saved bridge profile (~/.pocketmcp.json).
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// --- argument parsing ------------------------------------------------------

const argv = process.argv.slice(2);
let url = null;
let apiKey = null;
let rawTool = null;
let rawJson = '{}';
let benchCount = 0;
let timeoutMs = 10000;
const words = [];

for (let i = 0; i < argv.length; i += 1) {
  const arg = argv[i];
  if (arg === '--url') {
    url = argv[++i] ?? url;
  } else if (arg === '--api-key') {
    apiKey = argv[++i] ?? apiKey;
  } else if (arg === '--raw') {
    rawTool = argv[++i] ?? null;
    if (argv[i + 1] && argv[i + 1].startsWith('{')) rawJson = argv[++i];
  } else if (arg === '--bench') {
    const n = Number(argv[i + 1]);
    if (Number.isFinite(n) && n > 0) {
      benchCount = Math.min(200, Math.floor(n));
      i += 1;
    } else {
      benchCount = 10;
    }
  } else if (arg === '--timeout') {
    timeoutMs = Math.max(200, Number(argv[++i]) || timeoutMs);
  } else if (arg === '--help' || arg === '-h') {
    console.log('usage: pocket [--url U] [--api-key K] [--raw TOOL [JSON]] [--bench [N]] [--timeout MS] <command words...>');
    process.exit(0);
  } else {
    words.push(arg);
  }
}

const phrase = words.join(' ').trim();
const p = phrase.toLowerCase();

if (!rawTool && !phrase) {
  console.log('usage: pocket [--url U] [--api-key K] [--raw TOOL [JSON]] [--bench [N]] [--timeout MS] <command words...>');
  process.exit(1);
}

if (!url || !apiKey) {
  try {
    const profile = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.pocketmcp.json'), 'utf8'));
    url ??= profile.url ?? null;
    apiKey ??= profile.apiKey ?? null;
  } catch {
    // no profile — rely on flags
  }
}
if (!url) {
  console.error('No endpoint. Pass --url http://IP:8080/mcp or save a profile via mcp-bridge/setup.mjs.');
  process.exit(1);
}

// --- fast local routing (sub-millisecond, no model) -------------------------

/**
 * Each route returns either { tool, args } or { tool, args, repeat } for
 * gestures that need to be replayed N times.
 */
const ROUTES = [
  {
    re: /^(?:turn\s+on|switch\s+on)\s+(?:the\s+)?(?:torch|flash\s?light)$/,
    route: () => ({ tool: 'flashlight', args: { action: 'on' }, label: 'flashlight · on' }),
  },
  {
    re: /^(?:turn\s+off|switch\s+off)\s+(?:the\s+)?(?:torch|flash\s?light)$/,
    route: () => ({ tool: 'flashlight', args: { action: 'off' }, label: 'flashlight · off' }),
  },
  {
    re: /^(?:the\s+)?(?:torch|flash\s?light)(?:\s+(on|off|toggle|status))?$/,
    route: (m) => ({ tool: 'flashlight', args: { action: m[1] ?? 'toggle' }, label: `flashlight · ${m[1] ?? 'toggle'}` }),
  },
  {
    re: /^(?:(music|ring|alarm|notification|call|voice_call|system)\s+)?volume\s+(up|down)(?:\s+(\d+))?$/,
    route: (m) => ({
      tool: 'volume_control',
      args: { action: m[2], stream: m[1] ?? 'music', steps: m[3] ? Number(m[3]) : 1 },
      label: `volume · ${m[1] ?? 'music'} ${m[2]}${m[3] ? ' ×' + m[3] : ''}`,
    }),
  },
  {
    re: /^volume\s+(mute|unmute|status)$/,
    route: (m) => ({ tool: 'volume_control', args: { action: m[1] }, label: `volume · ${m[1]}` }),
  },
  {
    re: /^volume\s+(\d{1,3})$/,
    route: (m) => ({ tool: 'volume_control', args: { action: 'set', level: Number(m[1]) }, label: `volume · set ${m[1]}` }),
  },
  {
    re: /^(mute|unmute)$/,
    route: (m) => ({ tool: 'volume_control', args: { action: m[1] }, label: `volume · ${m[1]}` }),
  },
  { re: /^(?:lock(?: the)?(?: phone| screen)?|lock screen|screen off)$/, route: () => ({ tool: 'global_action', args: { action: 'lock_screen' }, label: 'lock' }) },
  { re: /^(?:go\s+)?home$/, route: () => ({ tool: 'global_action', args: { action: 'home' }, label: 'home' }) },
  { re: /^(?:go\s+)?back$/, route: () => ({ tool: 'global_action', args: { action: 'back' }, label: 'back' }) },
  { re: /^(?:recent(?:\s+apps)?|app\s+switcher)$/, route: () => ({ tool: 'global_action', args: { action: 'recents' }, label: 'recents' }) },
  { re: /^(?:open\s+|show\s+)?(?:notifications?|notification\s+shade|notification\s+panel)$/, route: () => ({ tool: 'global_action', args: { action: 'notifications' }, label: 'notification shade' }) },
  { re: /^(?:open\s+|show\s+)?(?:quick\s+settings|settings\s+panel)$/, route: () => ({ tool: 'global_action', args: { action: 'quick_settings' }, label: 'quick settings' }) },
  { re: /^(?:open\s+)?power\s+(?:menu|dialog)$/, route: () => ({ tool: 'global_action', args: { action: 'power_dialog' }, label: 'power menu' }) },
  { re: /^(?:close|force\s+close)\s+(?:current\s+app|this\s+app)$/, route: () => ({ tool: 'global_action', args: { action: 'close_current_app' }, label: 'close app' }) },
  {
    re: /^(?:scroll|swipe)\s+(up|down|left|right)(?:\s+(\d+))?$/,
    route: (m) => ({
      tool: 'scroll_screen',
      args: { direction: m[1] },
      repeat: Math.min(8, m[2] ? Number(m[2]) : 1),
      label: `scroll · ${m[1]}${m[2] ? ' ×' + m[2] : ''}`,
    }),
  },
  { re: /^(?:take\s+)?(?:a\s+)?(?:screenshot|screen\s?shot)$/, route: () => ({ tool: 'take_screenshot', args: {}, label: 'screenshot' }) },
  { re: /^(?:ring|find\s+(?:my\s+)?phone|alert(?:\s+me)?)$/, route: () => ({ tool: 'phone_alert', args: { action: 'ring' }, label: 'phone alert · ring' }) },
  {
    re: /^close\s+([\w .'-]+)$/,
    route: (m) => ({ tool: 'launch_app', args: { action: 'close', voice_command: `close ${m[1]}` }, label: `close ${m[1]}` }),
  },
  {
    re: /^(?:open|launch|start|run)\s+(.+)$/,
    route: () => ({ tool: 'launch_app', args: { voice_command: phrase }, label: `launch · ${phrase.replace(/^(open|launch|start|run)\s+/i, '')}` }),
  },
];

function resolveCommand() {
  if (rawTool) {
    let args = {};
    try {
      args = JSON.parse(rawJson);
    } catch {
      console.error(`--raw JSON is not valid: ${rawJson}`);
      process.exit(1);
    }
    return { tool: rawTool, args, repeat: 1, label: `raw · ${rawTool}` };
  }
  for (const entry of ROUTES) {
    const m = p.match(entry.re);
    if (m) {
      const routed = entry.route(m);
      return { repeat: 1, ...routed };
    }
  }
  // On-device NLU fallback — still a single round trip, no desktop model.
  return { tool: 'human_command', args: { command: phrase }, repeat: 1, label: 'phone brain · human_command' };
}

// --- transport --------------------------------------------------------------

async function callTool(tool, args) {
  const body = JSON.stringify({
    jsonrpc: '2.0',
    id: 1,
    method: 'tools/call',
    params: { name: tool, arguments: args },
  });
  const started = performance.now();
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
      ...(apiKey ? { 'X-API-Key': apiKey } : {}),
    },
    body,
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await response.text();
  const ms = performance.now() - started;
  let parsed = null;
  try {
    const clean = text.includes('data:')
      ? text.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5)).join('')
      : text;
    parsed = JSON.parse(clean);
  } catch {
    parsed = null;
  }
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  if (parsed?.error) throw new Error(parsed.error.message ?? 'tool error');
  return { ms, result: parsed?.result };
}

function summarize(result) {
  const text = result?.content?.find((c) => c.type === 'text')?.text;
  const prefix = result?.isError ? '⚠️ ' : '';
  if (!text) return prefix + (JSON.stringify(result) ?? '');
  try {
    return prefix + JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return prefix + text;
  }
}

function percentile(sorted, pct) {
  return sorted[Math.min(sorted.length - 1, Math.floor((pct / 100) * sorted.length))];
}

// --- main -------------------------------------------------------------------

const command = resolveCommand();

try {
  if (benchCount > 0) {
    await callTool(command.tool, command.args); // warmup
    const samples = [];
    for (let i = 0; i < benchCount; i += 1) {
      const started = performance.now();
      await callTool(command.tool, command.args);
      samples.push(performance.now() - started);
    }
    const sorted = [...samples].sort((a, b) => a - b);
    const avg = samples.reduce((a, b) => a + b, 0) / samples.length;
    console.log(
      `⚡ ${command.label} (${command.tool}) · n=${benchCount} · ` +
      `min ${sorted[0].toFixed(0)} · avg ${avg.toFixed(0)} · p50 ${percentile(sorted, 50).toFixed(0)} · p95 ${percentile(sorted, 95).toFixed(0)} ms`
    );
    process.exit(0);
  }

  const started = performance.now();
  let result = null;
  for (let i = 0; i < (command.repeat ?? 1); i += 1) {
    result = await callTool(command.tool, command.args);
  }
  const totalMs = performance.now() - started;
  console.log(`⚡ ${totalMs.toFixed(0)} ms · ${command.label}`);
  console.log(summarize(result));
  process.exit(result?.isError ? 1 : 0);
} catch (error) {
  console.error(`✖ ${command.label}: ${error.message}`);
  process.exit(1);
}
