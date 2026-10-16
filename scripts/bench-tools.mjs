#!/usr/bin/env node
/**
 * PocketMCP latency benchmark — measures real round-trip time per tool call.
 *
 * Usage:
 *   node scripts/bench-tools.mjs                          # profile from ~/.pocketmcp.json
 *   node scripts/bench-tools.mjs --url http://IP:8080/mcp --api-key KEY
 *   node scripts/bench-tools.mjs --tools device_info,flashlight --iterations 20
 *
 * Zero dependencies. Uses Node's built-in fetch (keep-alive), so the numbers
 * match what AI harnesses see rather than cold curl invocations.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const argv = process.argv.slice(2);
const flag = (name, fallback) => {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : fallback;
};

let profileUrl = null;
let profileKey = null;
try {
  const profile = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.pocketmcp.json'), 'utf8'));
  profileUrl = profile.url ?? null;
  profileKey = profile.apiKey ?? null;
} catch {
  // no profile yet — rely on flags
}

const url = flag('--url', profileUrl);
const apiKey = flag('--api-key', profileKey);
const iterations = Math.max(1, Number(flag('--iterations', 10)));
const warmup = Math.max(0, Number(flag('--warmup', 2)));
const tools = flag('--tools', 'device_info,flashlight')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

if (!url) {
  console.error('No endpoint. Pass --url http://IP:8080/mcp or save a profile via mcp-bridge/setup.mjs.');
  process.exit(1);
}

// Read-only argument presets so the default run never changes device state.
const PRESETS = {
  flashlight: { action: 'status' },
  notifications: {},
  volume_control: {},
};

async function callTool(tool) {
  const callArgs = PRESETS[tool] ?? {};
  const body = JSON.stringify({
    jsonrpc: '2.0',
    id: 1,
    method: 'tools/call',
    params: { name: tool, arguments: callArgs },
  });
  const started = performance.now();
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json, text/event-stream',
        ...(apiKey ? { 'X-API-Key': apiKey } : {}),
      },
      body,
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
    const failed = !response.ok || !parsed || parsed.error;
    return { ms, ok: !failed, error: parsed?.error?.message ?? (!response.ok ? `HTTP ${response.status}` : null) };
  } catch (error) {
    return { ms: performance.now() - started, ok: false, error: error.message };
  }
}

const percentile = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))];
const fmt = (n) => `${n.toFixed(1).padStart(8)}`;

console.log(`PocketMCP benchmark → ${url}`);
console.log(`iterations: ${iterations} (+${warmup} warmup) · tools: ${tools.join(', ')}\n`);
console.log('tool'.padEnd(20) + 'ok'.padStart(4) + 'min'.padStart(10) + 'avg'.padStart(10) + 'p50'.padStart(10) + 'p95'.padStart(10) + 'max'.padStart(10));

let anyOk = false;
for (const tool of tools) {
  for (let i = 0; i < warmup; i += 1) {
    await callTool(tool);
  }
  const samples = [];
  let okCount = 0;
  let lastError = null;
  for (let i = 0; i < iterations; i += 1) {
    const result = await callTool(tool);
    samples.push(result.ms);
    if (result.ok) {
      okCount += 1;
      anyOk = true;
    } else {
      lastError = result.error;
    }
  }
  const sorted = [...samples].sort((a, b) => a - b);
  const avg = samples.reduce((a, b) => a + b, 0) / samples.length;
  console.log(
    tool.padEnd(20) +
      String(okCount).padStart(4) +
      fmt(sorted[0]) +
      fmt(avg) +
      fmt(percentile(sorted, 50)) +
      fmt(percentile(sorted, 95)) +
      fmt(sorted[sorted.length - 1])
  );
  if (okCount === 0 && lastError) {
    console.log(`  ↳ all calls failed: ${lastError}`);
  }
}

console.log('\nall values in milliseconds, measured end-to-end (PC → Wi-Fi → phone → tool → back)');
process.exit(anyOk ? 0 : 1);
