import { spawn } from 'node:child_process';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const BRIDGE_PATH = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'bridge.mjs');

/** Drives bridge.mjs over stdio like an MCP client would. */
export class BridgeProcess {
  constructor({ args = [], env = {}, requestTimeoutMs = 15000 } = {}) {
    this.requestTimeoutMs = requestTimeoutMs;
    this.pending = new Map();
    this.unmatched = [];
    this.stderr = '';
    this.nextId = 1;
    this.child = spawn(process.execPath, [BRIDGE_PATH, ...args], {
      env: { ...process.env, ...env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    this.child.stderr.on('data', (chunk) => {
      this.stderr += chunk.toString();
    });
    this.child.on('error', (err) => {
      this.stderr += `\n[spawn error] ${err.message}`;
    });
    this.rl = readline.createInterface({ input: this.child.stdout });
    this.rl.on('line', (line) => this.#onLine(line));
  }

  #onLine(line) {
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      this.stderr += `\n[unparsed stdout] ${line}`;
      return;
    }
    const id = message?.id;
    if (id !== undefined && id !== null && this.pending.has(id)) {
      const entry = this.pending.get(id);
      this.pending.delete(id);
      clearTimeout(entry.timer);
      entry.resolve(message);
      return;
    }
    this.unmatched.push(message);
  }

  send(message) {
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  sendRaw(text) {
    this.child.stdin.write(`${text}\n`);
  }

  request(method, params) {
    const id = this.nextId++;
    const message = { jsonrpc: '2.0', id, method };
    if (params !== undefined) message.params = params;
    const promise = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Timed out waiting for ${method} response.\nstderr:\n${this.stderr}`));
      }, this.requestTimeoutMs);
      this.pending.set(id, { resolve, timer, reject });
    });
    this.send(message);
    return promise;
  }

  async stop() {
    try {
      this.child.stdin.end();
    } catch {
      // already closed
    }
    await new Promise((resolve) => {
      const timer = setTimeout(() => {
        try {
          this.child.kill();
        } catch {
          // ignore
        }
        resolve();
      }, 3000);
      this.child.once('exit', () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }
}

export async function waitFor(predicate, { timeoutMs = 3000, intervalMs = 25 } = {}) {
  const start = Date.now();
  for (;;) {
    const value = predicate();
    if (value) return value;
    if (Date.now() - start > timeoutMs) throw new Error('waitFor timed out');
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}
