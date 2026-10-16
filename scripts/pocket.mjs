#!/usr/bin/env node
/**
 * pocket — instant PocketMCP command runner. No AI in the loop.
 *
 * Routes human phrasings straight to phone tools with a local intent library
 * (sub-millisecond regex matching on the PC). Supports multi-command chains
 * ("open youtube and turn on the torch"). Anything that does not match goes to
 * the phone's on-device NLU (human_command) in the SAME single round trip —
 * still no desktop model involved.
 *
 * Usage:
 *   pocket "open youtube"
 *   pocket "turn on the flashlight and open youtube"
 *   pocket "find my phone"
 *   pocket "call alex"
 *   pocket "what's alex's number"
 *   pocket --dry-run "lock the phone, torch on"
 *   pocket --raw flashlight '{"action":"status"}'
 *   pocket --bench 20 "torch status"
 *   pocket help
 *
 * Endpoint + key default to the saved bridge profile (~/.pocketmcp.json).
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// --- argument parsing --------------------------------------------------------

const argv = process.argv.slice(2);
let url = null;
let apiKey = null;
let rawTool = null;
let rawJson = '{}';
let benchCount = 0;
let timeoutMs = 10000;
let dryRun = false;
let wantHelp = false;
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
  } else if (arg === '--dry-run') {
    dryRun = true;
  } else if (arg === '--help' || arg === '-h') {
    wantHelp = true;
  } else {
    words.push(arg);
  }
}

const HELP = `pocket — instant phone commands (no AI in the loop)

USAGE
  pocket <phrase>                run a phrase (fast local routing)
  pocket "a and b"               chain: "open youtube and torch on"
  pocket --dry-run "<phrase>"    show the plan, call nothing
  pocket --raw <tool> '<json>'   call any MCP tool directly
  pocket --bench [N] "<phrase>"  time N runs (single command only)
  pocket help                    this cheat sheet

LIGHTS & SOUND
  torch on | off | toggle | status        light on / turn the light off
  volume up | down [n] | volume 5         louder / quieter / set level
  mute | unmute | volume status

FIND & LOCATE
  find my phone | where's my phone        rings loud + location
  stop ringing
  where am i | my location | location

PEOPLE
  call <name>                             dials via phone brain
  text <name> <words> | message <name> ...| whatsapp <name> ...
  <name>'s number | number of <name>      contact lookup

APPS
  open | launch | start <app>             close <app>
  list apps

PHONE CONTROL
  home | back | recents | lock            open notifications | quick settings
  scroll down [n] | screenshot            what's on my screen
  battery | device info | storage

NOTIFICATIONS
  notifications | read my notifications | clear notifications

RECORDING
  start recording | stop recording | recording status

ANYTHING ELSE
  goes to the phone's on-device brain (still no desktop AI in the path)
`;

let phrase = words.join(' ').trim();
// Tolerate "pocket open youtube" / "hey pocket, open youtube" typed as one phrase.
phrase = phrase.replace(/^(?:ok(?:ay)?\s+|hey\s+)?(?:pocket|pocketmcp)\b[,:\s]*/i, '').trim();

const isHelpPhrase = /^(?:help|commands|cheat\s?sheet|what can you do|\?)$/i.test(phrase);
if (wantHelp || isHelpPhrase || (!rawTool && !phrase)) {
  console.log(HELP);
  process.exit(0);
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

// --- intent library (local, sub-millisecond, no model) ----------------------

const step = (tool, args, label) => ({ tool, args, label });

const on = (tool, label) => () => [step(tool, { action: 'on' }, label)];
const off = (tool, label) => () => [step(tool, { action: 'off' }, label)];

const INTENTS = [
  // ----- flashlight ---------------------------------------------------------
  { re: /^(?:turn|switch|put)\s+on\s+(?:the\s+)?(?:torch|flash\s?light|light|flash)$/, steps: on('flashlight', 'flashlight · on') },
  { re: /^turn\s+(?:the\s+)?(?:torch|flash\s?light|light|flash)\s+on$/, steps: on('flashlight', 'flashlight · on') },
  { re: /^(?:enable|activate)\s+(?:the\s+)?(?:torch|flash\s?light|light)$/, steps: on('flashlight', 'flashlight · on') },
  { re: /^(?:turn|switch|put)\s+off\s+(?:the\s+)?(?:torch|flash\s?light|light|flash)$/, steps: off('flashlight', 'flashlight · off') },
  { re: /^turn\s+(?:the\s+)?(?:torch|flash\s?light|light|flash)\s+off$/, steps: off('flashlight', 'flashlight · off') },
  { re: /^(?:disable|deactivate)\s+(?:the\s+)?(?:torch|flash\s?light|light)$/, steps: off('flashlight', 'flashlight · off') },
  { re: /^(?:is\s+)?(?:the\s+)?(?:torch|flash\s?light|light)\s+(?:status|state)$/, steps: () => [step('flashlight', { action: 'status' }, 'flashlight · status')] },
  { re: /^is\s+(?:the\s+)?(?:torch|flash\s?light|light)\s+on$/, steps: () => [step('flashlight', { action: 'status' }, 'flashlight · status')] },
  {
    re: /^(?:the\s+)?(?:torch|flash\s?light|flash|light)(?:\s+(on|off|toggle))?$/,
    steps: (m) => [step('flashlight', { action: m[1] ?? 'toggle' }, `flashlight · ${m[1] ?? 'toggle'}`)],
  },

  // ----- volume -------------------------------------------------------------
  { re: /^(?:turn\s+up|increase|raise|boost)(?:\s+the)?\s+volume(?:\s+by\s+(\d+))?$/, steps: (m) => [step('volume_control', { action: 'up', stream: 'music', steps: m[1] ? Number(m[1]) : 1 }, `volume · music up${m[1] ? ' ×' + m[1] : ''}`)] },
  { re: /^(?:turn\s+down|decrease|lower|reduce)(?:\s+the)?\s+volume(?:\s+by\s+(\d+))?$/, steps: (m) => [step('volume_control', { action: 'down', stream: 'music', steps: m[1] ? Number(m[1]) : 1 }, `volume · music down${m[1] ? ' ×' + m[1] : ''}`)] },
  { re: /^(?:louder|volume louder|make it louder)$/, steps: () => [step('volume_control', { action: 'up', stream: 'music', steps: 1 }, 'volume · music up')] },
  { re: /^(?:quieter|volume quieter|lower it)$/, steps: () => [step('volume_control', { action: 'down', stream: 'music', steps: 1 }, 'volume · music down')] },
  {
    re: /^(?:(music|ring|alarm|notification|call|voice_call|system)\s+)?volume\s+(up|down)(?:\s+(\d+))?(?:\s+notches?)?$/,
    steps: (m) => [step('volume_control', { action: m[2], stream: m[1] ?? 'music', steps: m[3] ? Number(m[3]) : 1 }, `volume · ${m[1] ?? 'music'} ${m[2]}${m[3] ? ' ×' + m[3] : ''}`)],
  },
  { re: /^(?:set\s+)?volume\s+(?:to\s+)?(\d{1,3})$/, steps: (m) => [step('volume_control', { action: 'set', stream: 'music', level: Number(m[1]) }, `volume · set ${m[1]}`)] },
  { re: /^volume\s+(mute|unmute)$/, steps: (m) => [step('volume_control', { action: m[1] }, `volume · ${m[1]}`)] },
  { re: /^(mute|unmute)(?:\s+(?:the\s+)?(?:phone|it|sound|audio))?$/, steps: (m) => [step('volume_control', { action: m[1] }, `volume · ${m[1]}`)] },
  { re: /^(?:volume\s+status|what(?:'s| is) the volume)$/, steps: () => [step('volume_control', { action: 'status' }, 'volume · status')] },

  // ----- recording (before apps — "start recording" must not launch an app) --
  {
    re: /^(?:start|begin)(?:\s+a|\s+the)?(?:\s+new)?(?:\s+voice)?(?:\s+recording|\s+record)$|^record(?:\s+my)?\s+voice$/,
    steps: () => [step('voice_record', { action: 'start' }, 'voice recording · start')],
  },
  {
    re: /^(?:stop|end|finish)(?:\s+the|\s+my)?(?:\s+voice)?(?:\s+recording|\s+record)$/,
    steps: () => [step('voice_record', { action: 'stop' }, 'voice recording · stop')],
  },
  { re: /^(?:recording status|am i recording|is (?:my )?recording running)$/, steps: () => [step('voice_record', { action: 'status' }, 'voice recording · status')] },

  // ----- find phone / location ---------------------------------------------
  {
    re: /^(?:ring(?:\s+(?:it|my phone|the phone|phone))?|find(?:\s+my)?\s+phone|make(?:\s+my)?\s+phone\s+ring|lost(?:\s+my)?\s+phone|where(?:'s| is|s)\s+(?:my\s+)?phone|i\s+lost\s+my\s+phone)$/,
    steps: () => [
      step('phone_alert', { action: 'both', duration_seconds: 30, boost_ring_volume: true }, 'phone alert · ring+vibrate 30s'),
      step('get_location', { max_age_seconds: 86400 }, 'location (last known)'),
    ],
  },
  { re: /^(?:stop|end|kill)(?:\s+the)?(?:\s+ring(?:ing)?|\s+alert|\s+sound|\s+buzzing)$/, steps: () => [step('phone_alert', { action: 'stop' }, 'phone alert · stop')] },
  { re: /^(?:where am i|where i am|my location|current location|location|gps|locate me|where are we)$/, steps: () => [step('get_location', { max_age_seconds: 86400 }, 'location')] },

  // ----- people -------------------------------------------------------------
  {
    re: /^(?:what(?:'s| is)|give me|find|show me|get|tell me)(?:\s+the)?\s+(?:phone number|number|contact info|contact|mobile)(?:\s+(?:of|for))?\s+(.+)$|^(?:number|contact)(?:\s+(?:of|for))?\s+(.+)$|^(.+?)(?:'s|s')\s+(?:phone number|number|contact)$/,
    steps: (m) => {
      let name = (m[1] ?? m[2] ?? m[3] ?? '').trim();
      let previous;
      do {
        previous = name;
        name = name.replace(/^(?:what(?:'s| is)|whats|give me|find|show me|get|tell me|the|of|for|is)\s+/i, '');
      } while (name !== previous);
      return [step('search_contacts', { query: name.trim() }, `contacts · ${name.trim()}`)];
    },
  },
  { re: /^(?:call|dial|phone)\s+(?!number\b)(?:up\s+)?(.+)$|^give\s+(.+?)\s+a\s+(?:call|ring)$/, steps: (m, raw) => [step('human_command', { command: raw }, 'phone brain · call')] },
  { re: /^(?:message|text|sms|whatsapp|telegram|signal)\s+(.+)$|^send\s+(?:a\s+)?(?:message|text|sms|whatsapp)(?:\s+to)?\s+(.+)$|^tell\s+(.+)$/, steps: (m, raw) => [step('human_command', { command: raw }, 'phone brain · message')] },

  // ----- device -------------------------------------------------------------
  { re: /^(?:battery|battery (?:level|status|percentage|percent|charge)|how(?:'s| is) (?:my|the) battery|how much (?:battery|charge)(?: (?:is )?left)?|check(?: the)? battery)$/, steps: () => [step('device_info', {}, 'device info · battery')] },
  { re: /^(?:device|phone|system|hardware)\s+info$/, steps: () => [step('device_info', {}, 'device info')] },
  { re: /^(?:storage|free space|disk space)$/, steps: () => [step('device_info', {}, 'device info · storage')] },

  // ----- notifications ------------------------------------------------------
  { re: /^(?:notifications|read (?:my )?notifications|check (?:my )?notifications|any (?:new )?notifications|what notifications|what did i miss|show me my notifications)$/, steps: () => [step('notifications', {}, 'notifications')] },
  { re: /^(?:clear|dismiss|remove)(?:\s+all)?(?:\s+my)?\s+notifications$/, steps: (m, raw) => [step('human_command', { command: raw }, 'phone brain · notifications')] },

  // ----- global actions -----------------------------------------------------
  { re: /^(?:go\s+)?home$|^(?:go|take me)\s+(?:to\s+)?home$/, steps: () => [step('global_action', { action: 'home' }, 'home')] },
  { re: /^(?:go\s+)?back$|^(?:press\s+)?back$/, steps: () => [step('global_action', { action: 'back' }, 'back')] },
  { re: /^(?:recents|recent apps|app switcher|show recent apps)$/, steps: () => [step('global_action', { action: 'recents' }, 'recents')] },
  { re: /^(?:open|show|pull down)\s+(?:my\s+)?(?:notifications?|notification shade|notification panel)$/, steps: () => [step('global_action', { action: 'notifications' }, 'notification shade')] },
  { re: /^(?:open|show)\s+(?:quick settings|settings panel)$/, steps: () => [step('global_action', { action: 'quick_settings' }, 'quick settings')] },
  { re: /^(?:open|show)\s+(?:the\s+)?power\s+(?:menu|dialog)$/, steps: () => [step('global_action', { action: 'power_dialog' }, 'power menu')] },
  { re: /^(?:lock|lock the (?:phone|screen)|lock phone|screen off|turn off(?: the)? screen)$/, steps: () => [step('global_action', { action: 'lock_screen' }, 'lock screen')] },
  { re: /^(?:close|kill|force stop)\s+(?:the\s+)?(?:current|this)\s+app$/, steps: () => [step('global_action', { action: 'close_current_app' }, 'close current app')] },

  // ----- UI helpers ---------------------------------------------------------
  {
    re: /^(?:scroll|swipe)\s+(up|down|left|right)(?:\s+(\d+))?(?:\s+times?)?$/,
    steps: (m) => {
      const times = Math.min(10, m[2] ? Number(m[2]) : 1);
      return Array.from({ length: times }, () => step('scroll_screen', { direction: m[1] }, `scroll · ${m[1]}${times > 1 ? ' ×' + times : ''}`));
    },
  },
  { re: /^(?:take\s+(?:a\s+)?)?(?:screenshot|screen\s?shot)$|^capture (?:the )?screen$/, steps: () => [step('take_screenshot', {}, 'screenshot')] },
  { re: /^(?:what(?:'s| is) (?:on|showing on) (?:my|the) screen|read (?:my|the) screen|screen (?:state|content|snapshot)|what am i looking at)$/, steps: () => [step('screen_state', {}, 'screen state')] },

  // ----- apps ---------------------------------------------------------------
  { re: /^(?:open|launch|start|run|go to|switch to|bring up|jump to|pull up)\s+(?:the\s+)?(.+)$/, steps: (m, raw) => [step('launch_app', { voice_command: raw }, `launch · ${raw.replace(/^(?:open|launch|start|run|go to|switch to|bring up|jump to|pull up)\s+(?:the\s+)?/i, '')}`)] },
  { re: /^(?:close|kill|quit|exit)\s+(.+)$/, steps: (m) => [step('launch_app', { action: 'close', voice_command: `close ${m[1]}` }, `close · ${m[1]}`)] },
  { re: /^(?:list|show me)(?:\s+my)?\s+(?:installed\s+)?apps$|^what apps (?:do i have|are installed)$/, steps: () => [step('list_apps', {}, 'apps · list')] },
];

function normalize(raw) {
  return raw.toLowerCase().replace(/[?!.]+$/g, '').replace(/\s+/g, ' ').trim();
}

function resolveSingle(raw) {
  const p = normalize(raw);
  for (const intent of INTENTS) {
    const m = p.match(intent.re);
    if (m) return intent.steps(m, raw);
  }
  return null;
}

const CHAIN_SEPARATORS = [/\s+and then\s+/i, /\s+then\s+/i, /[,;]\s*/, /\s+and\s+/i];

function resolvePlan(raw) {
  const direct = resolveSingle(raw);
  if (direct) return { steps: direct, chained: false };
  for (const sep of CHAIN_SEPARATORS) {
    if (!sep.test(raw)) continue;
    const parts = raw.split(sep).map((part) => part.trim()).filter(Boolean);
    if (parts.length < 2) continue;
    const resolved = parts.map((part) => resolveSingle(part));
    if (resolved.every(Boolean)) {
      return { steps: resolved.flat(), chained: true };
    }
  }
  // On-device NLU fallback — still a single round trip, no desktop model.
  return { steps: [step('human_command', { command: phrase }, 'phone brain · human_command')], chained: false, fallback: true };
}

// --- transport ---------------------------------------------------------------

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

function summarize(result, max = 700) {
  const text = result?.content?.find((c) => c.type === 'text')?.text;
  const prefix = result?.isError ? '⚠️ ' : '';
  let out;
  if (!text) {
    out = prefix + (JSON.stringify(result) ?? '');
  } else {
    try {
      out = prefix + JSON.stringify(JSON.parse(text), null, 2);
    } catch {
      out = prefix + text;
    }
  }
  return out.length > max ? out.slice(0, max) + '…' : out;
}

function percentile(sorted, pct) {
  return sorted[Math.min(sorted.length - 1, Math.floor((pct / 100) * sorted.length))];
}

// --- main --------------------------------------------------------------------

let plan;
if (rawTool) {
  let args = {};
  try {
    args = JSON.parse(rawJson);
  } catch {
    console.error(`--raw JSON is not valid: ${rawJson}`);
    process.exit(1);
  }
  plan = { steps: [step(rawTool, args, `raw · ${rawTool}`)], chained: false };
} else {
  plan = resolvePlan(phrase);
}

if (dryRun) {
  console.log(plan.fallback ? `plan for "${phrase}" (falls through to phone brain):` : `plan for "${phrase}":`);
  plan.steps.forEach((s, i) => {
    console.log(`  ${i + 1}. ${s.label}  →  ${s.tool} ${JSON.stringify(s.args)}`);
  });
  process.exit(0);
}

try {
  if (benchCount > 0) {
    if (plan.steps.length > 1) {
      console.error('--bench supports single-step commands only (no chains).');
      process.exit(1);
    }
    const only = plan.steps[0];
    await callTool(only.tool, only.args); // warmup
    const samples = [];
    for (let i = 0; i < benchCount; i += 1) {
      const started = performance.now();
      await callTool(only.tool, only.args);
      samples.push(performance.now() - started);
    }
    const sorted = [...samples].sort((a, b) => a - b);
    const avg = samples.reduce((a, b) => a + b, 0) / samples.length;
    console.log(
      `⚡ ${only.label} (${only.tool}) · n=${benchCount} · ` +
      `min ${sorted[0].toFixed(0)} · avg ${avg.toFixed(0)} · p50 ${percentile(sorted, 50).toFixed(0)} · p95 ${percentile(sorted, 95).toFixed(0)} ms`
    );
    process.exit(0);
  }

  if (plan.chained) {
    console.log(`⚡ ${plan.steps.length} commands`);
  }
  const totalStart = performance.now();
  let anyFailed = false;
  for (const s of plan.steps) {
    try {
      const { ms, result } = await callTool(s.tool, s.args);
      console.log(`⚡ ${ms.toFixed(0)} ms · ${s.label}`);
      console.log(summarize(result));
      if (result?.isError) anyFailed = true;
    } catch (error) {
      anyFailed = true;
      console.error(`✖ ${s.label}: ${error.message}`);
    }
  }
  if (plan.chained) {
    console.log(`— total ${(performance.now() - totalStart).toFixed(0)} ms · ${plan.steps.length} steps`);
  }
  process.exit(anyFailed ? 1 : 0);
} catch (error) {
  console.error(`✖ ${phrase}: ${error.message}`);
  process.exit(1);
}
