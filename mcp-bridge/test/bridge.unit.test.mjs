import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';
import {
  assembleDevices,
  buildMdnsQuery,
  extractJsonRpcFromSse,
  loadProfile,
  normalizeMcpUrl,
  parseMdnsMessage,
  resolveBridgeTarget,
  saveProfile,
} from '../bridge.mjs';

describe('normalizeMcpUrl', () => {
  const cases = [
    ['192.0.2.10:8080', 'http://192.0.2.10:8080/mcp'],
    ['http://192.0.2.10:8080', 'http://192.0.2.10:8080/mcp'],
    ['http://192.0.2.10:8080/', 'http://192.0.2.10:8080/mcp'],
    ['http://192.0.2.10:8080/mcp', 'http://192.0.2.10:8080/mcp'],
    ['ws://127.0.0.1:8080/ws', 'http://127.0.0.1:8080/mcp'],
    ['wss://phone.example.ts.net/ws', 'https://phone.example.ts.net/mcp'],
    ['https://phone.example.ts.net/mcp', 'https://phone.example.ts.net/mcp'],
    ['http://phone.local:8080/sse', 'http://phone.local:8080/mcp'],
  ];
  for (const [input, expected] of cases) {
    it(`normalizes ${input}`, () => {
      assert.equal(normalizeMcpUrl(input), expected);
    });
  }

  it('rejects empty and invalid input', () => {
    assert.throws(() => normalizeMcpUrl(''));
    assert.throws(() => normalizeMcpUrl('http://'));
  });
});

describe('extractJsonRpcFromSse', () => {
  it('picks the payload matching the expected id', () => {
    const body = [
      'event: message',
      'data: {"jsonrpc":"2.0","id":1,"result":{"tools":[]}}',
      '',
      'event: message',
      'data: {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"x"}]}}',
      '',
    ].join('\n');
    const payload = extractJsonRpcFromSse(body, 2);
    assert.equal(payload.id, 2);
    assert.equal(payload.result.tools[0].name, 'x');
  });

  it('falls back to the first result payload', () => {
    const body = 'data: {"jsonrpc":"2.0","method":"ping"}\n\ndata: {"jsonrpc":"2.0","id":9,"result":{}}\n\n';
    const payload = extractJsonRpcFromSse(body);
    assert.equal(payload.id, 9);
  });

  it('returns null for keep-alive-only streams', () => {
    assert.equal(extractJsonRpcFromSse(': keep-alive\n\n'), null);
  });
});

describe('mDNS codec', () => {
  it('builds a PTR query for _mcp._tcp.local', () => {
    const query = buildMdnsQuery('_mcp._tcp.local', 12);
    assert.equal(query.readUInt16BE(4), 1); // qdcount
    assert.equal(query[12], 4);
    assert.equal(query.subarray(13, 17).toString(), '_mcp');
    assert.equal(query[17], 4);
    assert.equal(query.subarray(18, 22).toString(), '_tcp');
    assert.equal(query[22], 5);
    assert.equal(query.subarray(23, 28).toString(), 'local');
    assert.equal(query[28], 0);
    assert.equal(query.readUInt16BE(29), 12); // PTR
    assert.equal(query.readUInt16BE(31), 0x8001); // IN + QU
  });

  it('parses a synthetic response with compression pointers', () => {
    const { buffer, instance, target } = buildSyntheticResponse();
    const records = parseMdnsMessage(buffer);

    const ptr = records.find((record) => record.type === 12);
    assert.equal(ptr.data, `${instance}.`);

    const srv = records.find((record) => record.type === 33);
    assert.equal(srv.data.port, 8080);
    assert.equal(srv.data.target, `${target}.`);

    const address = records.find((record) => record.type === 1);
    assert.equal(address.data, '192.0.2.50');
    assert.equal(address.name, `${target}.`);

    const devices = assembleDevices(records);
    assert.equal(devices.length, 1);
    assert.equal(devices[0].name, 'pocketmcp-test');
    assert.equal(devices[0].port, 8080);
    assert.deepEqual(devices[0].addresses, ['192.0.2.50']);
  });
});

describe('connection profile + resolution', () => {
  let home;
  let previousProfileEnv;

  before(() => {
    previousProfileEnv = process.env.POCKET_MCP_PROFILE;
    delete process.env.POCKET_MCP_PROFILE;
    home = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketmcp-profile-'));
  });

  after(() => {
    if (previousProfileEnv !== undefined) process.env.POCKET_MCP_PROFILE = previousProfileEnv;
    fs.rmSync(home, { recursive: true, force: true });
  });

  it('saves and loads the profile', () => {
    const saved = saveProfile({ url: 'http://192.0.2.5:8080/mcp', apiKey: 'abc' }, home);
    assert.ok(fs.existsSync(saved));
    const profile = loadProfile(home);
    assert.equal(profile.url, 'http://192.0.2.5:8080/mcp');
    assert.equal(profile.apiKey, 'abc');
  });

  it('resolves options -> profile -> default without network access', async () => {
    const explicit = await resolveBridgeTarget({ url: '192.0.2.9:8080', env: {}, discover: false });
    assert.equal(explicit.url, 'http://192.0.2.9:8080/mcp');
    assert.equal(explicit.source, 'command line');

    const fromProfile = await resolveBridgeTarget({ homeDir: home, env: {}, discover: false });
    assert.equal(fromProfile.url, 'http://192.0.2.5:8080/mcp');
    assert.equal(fromProfile.apiKey, 'abc');
    assert.match(fromProfile.source, /profile/);

    const emptyHome = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketmcp-empty-'));
    try {
      const fallback = await resolveBridgeTarget({ homeDir: emptyHome, env: {}, discover: false });
      assert.equal(fallback.url, 'http://127.0.0.1:8080/mcp');
    } finally {
      fs.rmSync(emptyHome, { recursive: true, force: true });
    }
  });
});

// ---------------------------------------------------------------------------
// Synthetic mDNS packet builder
// ---------------------------------------------------------------------------

function encodeName(name) {
  const labels = name.replace(/\.$/, '').split('.');
  return Buffer.concat([
    ...labels.map((label) => Buffer.concat([Buffer.from([Buffer.byteLength(label)]), Buffer.from(label)])),
    Buffer.from([0]),
  ]);
}

function dnsRecord(nameBuffer, type, rdata) {
  const head = Buffer.alloc(10);
  head.writeUInt16BE(type, 0);
  head.writeUInt16BE(1, 2);
  head.writeUInt32BE(120, 4);
  head.writeUInt16BE(rdata.length, 8);
  return Buffer.concat([nameBuffer, head, rdata]);
}

function buildSyntheticResponse() {
  const instance = 'pocketmcp-test._mcp._tcp.local';
  const target = 'android-test.local';

  const header = Buffer.alloc(12);
  header.writeUInt16BE(0x8400, 2); // response + authoritative
  header.writeUInt16BE(3, 6); // 3 answer records

  const chunks = [header];
  let offset = 12;
  const add = (buffer) => {
    chunks.push(buffer);
    offset += buffer.length;
  };

  // PTR: _mcp._tcp.local -> pocketmcp-test._mcp._tcp.local
  add(dnsRecord(encodeName('_mcp._tcp.local'), 12, encodeName(instance)));

  // SRV: instance -> android-test.local:8080
  const srvName = encodeName(instance);
  const srvRdata = Buffer.concat([Buffer.from([0, 0, 0, 0, 0x1f, 0x90]), encodeName(target)]);
  const targetOffset = offset + srvName.length + 10 + 6;
  add(dnsRecord(srvName, 33, srvRdata));

  // A record whose owner name is a compression pointer to the SRV target.
  const pointer = Buffer.from([0xc0 | (targetOffset >> 8), targetOffset & 0xff]);
  add(dnsRecord(pointer, 1, Buffer.from([192, 0, 2, 50])));

  return { buffer: Buffer.concat(chunks), instance, target };
}
