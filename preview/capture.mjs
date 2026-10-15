#!/usr/bin/env node
/**
 * PocketMCP design-capture tool.
 *
 * Renders preview/app-preview.html in headless Chrome and produces:
 *   docs/screenshots/<tab>-<theme>.png   (per-screen captures, 2x)
 *   docs/screenshots/all-<theme>.png     (4-phone collages)
 *   docs/demo.mp4                        (animated walkthrough of the UI)
 *   docs/demo.gif                        (short highlight loop for the README)
 *
 * Usage:
 *   node preview/capture.mjs shots
 *   node preview/capture.mjs video
 *   node preview/capture.mjs both
 *
 * Requires: Chrome or Edge installed, ffmpeg on PATH.
 */
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const PREVIEW_URL = pathToFileURL(path.join(HERE, 'app-preview.html')).href;
const SHOT_DIR = path.join(ROOT, 'docs', 'screenshots');
const DOCS_DIR = path.join(ROOT, 'docs');
const TABS = ['server', 'logs', 'tools', 'settings'];
const PORT = 9300 + (process.pid % 500);

const CHROMES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function findChrome() {
  for (const c of CHROMES) if (fs.existsSync(c)) return c;
  throw new Error('No Chrome/Edge found');
}

class CDP {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.listeners = new Map();
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id !== undefined) {
        const p = this.pending.get(msg.id);
        if (p) {
          this.pending.delete(msg.id);
          msg.error ? p.reject(new Error(msg.error.message)) : p.resolve(msg.result);
        }
      } else if (msg.method) {
        for (const cb of this.listeners.get(msg.method) || []) cb(msg.params);
      }
    };
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  on(method, cb) {
    if (!this.listeners.has(method)) this.listeners.set(method, []);
    this.listeners.get(method).push(cb);
  }
}

async function launch() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'pmcp-capture-'));
  const child = spawn(findChrome(), [
    '--headless=new',
    `--remote-debugging-port=${PORT}`,
    '--remote-allow-origins=*',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--hide-scrollbars',
    `--user-data-dir=${profile}`,
    '--window-size=1400,900',
    'about:blank'
  ], { stdio: 'ignore' });

  const deadline = Date.now() + 20000;
  for (;;) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/version`);
      if (res.ok) break;
    } catch { /* not up yet */ }
    if (Date.now() > deadline) throw new Error('Chrome did not expose a debugging port');
    await sleep(250);
  }

  const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
  const target = list.find((t) => t.type === 'page');
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const cdp = new CDP(ws);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');

  return {
    cdp,
    async close() {
      try { ws.close(); } catch { /* ignore */ }
      try {
        execFileSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
      } catch { /* ignore */ }
      try { fs.rmSync(profile, { recursive: true, force: true }); } catch { /* ignore */ }
    }
  };
}

async function navigate(cdp, url) {
  const loaded = new Promise((res) => cdp.on('Page.loadEventFired', res));
  await cdp.send('Page.navigate', { url });
  await loaded;
  await sleep(200);
}

async function evalJS(cdp, expression, awaitPromise = false) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise });
  if (r.exceptionDetails) {
    throw new Error('Page JS error: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  }
  return r.result?.value;
}

async function waitForFonts(cdp) {
  await evalJS(cdp, 'document.fonts.ready.then(() => true)', true);
}

/* ------------------------------------------------------------------ shots */

async function captureShots(cdp) {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  await navigate(cdp, PREVIEW_URL);
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1700, height: 2000, deviceScaleFactor: 1, mobile: false
  });
  await waitForFonts(cdp);
  await sleep(1600); // entrance cascade + stat count-up settle

  for (const theme of ['light', 'dark']) {
    await evalJS(cdp, `document.documentElement.className = 'theme-${theme}'; true`);
    await sleep(900);
    for (let i = 0; i < TABS.length; i++) {
      const rect = await evalJS(cdp, `(() => {
        const p = [...document.querySelectorAll('.phone')][${i}];
        const r = p.getBoundingClientRect();
        return { x: r.x + window.scrollX, y: r.y + window.scrollY, w: r.width, h: r.height };
      })()`);
      const shot = await cdp.send('Page.captureScreenshot', {
        format: 'png',
        captureBeyondViewport: false,
        clip: { x: rect.x, y: rect.y, width: rect.w, height: rect.h, scale: 2 }
      });
      const file = path.join(SHOT_DIR, `${TABS[i]}-${theme}.png`);
      fs.writeFileSync(file, Buffer.from(shot.data, 'base64'));
      console.log(`  wrote ${path.relative(ROOT, file)}`);
    }
  }

  // collages
  for (const theme of ['light', 'dark']) {
    const inputs = TABS.flatMap((t) => ['-i', path.join(SHOT_DIR, `${t}-${theme}.png`)]);
    const out = path.join(SHOT_DIR, `all-${theme}.png`);
    execFileSync('ffmpeg', ['-y', ...inputs, '-filter_complex', 'hstack=inputs=4', out], { stdio: 'ignore' });
    console.log(`  wrote ${path.relative(ROOT, out)}`);
  }
}

/* ------------------------------------------------------------------ video */

function clickHelper(body) {
  return `(() => { const p = document.querySelectorAll('.phone')[0]; ${body} return true; })()`;
}

const STEPS = [
  { label: 'count-up replay', js: 'runStatCountUps(); true', wait: 1400 },
  { label: 'tab -> Logs', js: clickHelper(`[...p.querySelectorAll('.tab')].find(x => x.dataset.tab === 'logs').dispatchEvent(new MouseEvent('click', { bubbles: true }));`), wait: 2300 },
  { label: 'filter -> Errors', js: clickHelper(`[...p.querySelectorAll('.scrscreen[data-screen="logs"] .opt')].find(o => o.dataset.f === 'errors').dispatchEvent(new MouseEvent('click', { bubbles: true }));`), wait: 1900 },
  { label: 'filter -> All', js: clickHelper(`[...p.querySelectorAll('.scrscreen[data-screen="logs"] .opt')].find(o => o.dataset.f === 'all').dispatchEvent(new MouseEvent('click', { bubbles: true }));`), wait: 1100 },
  { label: 'tab -> Tools', js: clickHelper(`[...p.querySelectorAll('.tab')].find(x => x.dataset.tab === 'tools').dispatchEvent(new MouseEvent('click', { bubbles: true }));`), wait: 2400 },
  { label: 'chip -> Media', js: clickHelper(`[...p.querySelectorAll('.scrscreen[data-screen="tools"] .chip')].find(c => c.dataset.c === 'media').dispatchEvent(new MouseEvent('click', { bubbles: true }));`), wait: 2200 },
  { label: 'scroll tools list', js: clickHelper(`p.querySelector('.scrscreen.on').scrollTo({ top: 560, behavior: 'smooth' });`), wait: 1800 },
  { label: 'scroll back', js: clickHelper(`p.querySelector('.scrscreen.on').scrollTo({ top: 0, behavior: 'smooth' });`), wait: 1200 },
  { label: 'chip -> All', js: clickHelper(`[...p.querySelectorAll('.scrscreen[data-screen="tools"] .chip')].find(c => c.dataset.c === 'all').dispatchEvent(new MouseEvent('click', { bubbles: true }));`), wait: 900 },
  { label: 'tab -> Settings', js: clickHelper(`[...p.querySelectorAll('.tab')].find(x => x.dataset.tab === 'settings').dispatchEvent(new MouseEvent('click', { bubbles: true }));`), wait: 2300 },
  { label: 'toggle a switch', js: clickHelper(`p.querySelector('.scrscreen.on .sw').dispatchEvent(new MouseEvent('click', { bubbles: true }));`), wait: 1300 },
  { label: 'theme -> Dark', js: `document.querySelector('#themeToggle button[data-theme="dark"]').dispatchEvent(new MouseEvent('click', { bubbles: true })); true`, wait: 2600 }
];

async function setupVideoStage(cdp) {
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1200, height: 900, deviceScaleFactor: 1, mobile: false });
  await sleep(300);
  const size = await evalJS(cdp, `(() => { const r = document.querySelector('.phone').getBoundingClientRect(); return { w: r.width, h: r.height }; })()`);
  const M = 14;
  const Z = 2; // render at 2x for crisp frames
  const W = Math.round((size.w + M * 2) * Z);
  const H = Math.round((size.h + M * 2) * Z);
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });
  await evalJS(cdp, `(() => {
    document.querySelectorAll('.phone').forEach((p, j) => { if (j !== 0) p.style.display = 'none'; });
    document.querySelector('header.page-head').style.display = 'none';
    document.querySelector('.tokens').style.display = 'none';
    document.querySelector('footer').style.display = 'none';
    Object.assign(document.body.style, { margin: '0', padding: '0', zoom: '${Z}' });
    Object.assign(document.querySelector('.phones').style, { display: 'block', margin: '0', padding: '${M}px', maxWidth: 'none' });
    document.querySelector('.phone').style.margin = '0';
    document.documentElement.className = 'theme-light';
    return true;
  })()`);
  await sleep(500);
  return { W, H };
}

async function recordVideo(cdp) {
  fs.mkdirSync(DOCS_DIR, { recursive: true });
  await navigate(cdp, PREVIEW_URL);
  await waitForFonts(cdp);
  await setupVideoStage(cdp);

  // Probe: does screencast deliver frames in this headless build?
  const frames = [];
  let ackErrs = 0;
  cdp.on('Page.screencastFrame', (params) => {
    frames.push({ data: params.data, ts: params.metadata.timestamp });
    cdp.send('Page.screencastFrameAck', { sessionId: params.sessionId }).catch(() => { ackErrs++; });
  });
  await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 80, everyNthFrame: 1 }).catch(() => {});
  await sleep(1500);

  let mode = 'screencast';
  if (frames.length < 3) {
    mode = 'poll';
    console.log('  screencast unavailable, falling back to screenshot polling (~7 fps)');
    await cdp.send('Page.stopScreencast').catch(() => {});
    frames.length = 0;
  } else {
    console.log(`  screencast active (${frames.length} warm-up frames)`);
    frames.length = 0;
  }

  let polling = null;
  if (mode === 'poll') {
    let stop = false;
    polling = (async () => {
      while (!stop) {
        const t0 = Date.now();
        try {
          const shot = await cdp.send('Page.captureScreenshot', { format: 'jpeg', quality: 80 });
          frames.push({ data: shot.data, ts: t0 / 1000 });
        } catch { /* capture race, skip frame */ }
        await sleep(Math.max(0, 130 - (Date.now() - t0)));
      }
    })();
    polling.stop = () => { stop = true; };
  }

  for (const step of STEPS) {
    console.log(`  · ${step.label}`);
    await evalJS(cdp, step.js);
    await sleep(step.wait);
  }

  if (mode === 'screencast') {
    await cdp.send('Page.stopScreencast').catch(() => {});
  } else {
    polling.stop();
    await polling;
  }
  console.log(`  captured ${frames.length} frames (${mode})`);
  if (frames.length < 10) throw new Error('Not enough frames captured');

  // Assemble with ffmpeg via a concat list using real frame timestamps.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pmcp-frames-'));
  const lines = [];
  frames.forEach((f, i) => {
    const name = `f${String(i).padStart(5, '0')}.jpg`;
    fs.writeFileSync(path.join(dir, name), Buffer.from(f.data, 'base64'));
    const next = frames[i + 1];
    const dt = next ? Math.min(3, Math.max(0.016, next.ts - f.ts)) : 0.5;
    lines.push(`file '${name}'`);
    lines.push(`duration ${dt.toFixed(3)}`);
  });
  lines.push(`file 'f${String(frames.length - 1).padStart(5, '0')}.jpg'`);
  const listFile = path.join(dir, 'list.txt');
  fs.writeFileSync(listFile, lines.join('\n'));

  const mp4 = path.join(DOCS_DIR, 'demo.mp4');
  execFileSync('ffmpeg', [
    '-y', '-f', 'concat', '-safe', '0', '-i', listFile,
    '-vsync', 'vfr', '-vf', 'fps=30,format=yuv420p',
    '-c:v', 'libx264', '-crf', '19', '-preset', 'medium',
    '-movflags', '+faststart', mp4
  ], { stdio: 'ignore' });
  fs.rmSync(dir, { recursive: true, force: true });
  console.log(`  wrote ${path.relative(ROOT, mp4)}`);

  const gif = path.join(DOCS_DIR, 'demo.gif');
  execFileSync('ffmpeg', [
    '-y', '-i', mp4,
    '-ss', '7.8', '-t', '11.4',
    '-vf', 'fps=11,scale=300:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4',
    '-loop', '0', gif
  ], { stdio: 'ignore' });
  console.log(`  wrote ${path.relative(ROOT, gif)}`);
}

/* ------------------------------------------------------------------- main */

const mode = process.argv[2] || 'both';
console.log(`PocketMCP capture — mode: ${mode}`);
const session = await launch();
try {
  if (mode === 'shots' || mode === 'both') {
    console.log('Rendering screenshots…');
    await captureShots(session.cdp);
  }
  if (mode === 'video' || mode === 'both') {
    console.log('Recording walkthrough…');
    await recordVideo(session.cdp);
  }
  console.log('Done.');
} finally {
  await session.close();
}
