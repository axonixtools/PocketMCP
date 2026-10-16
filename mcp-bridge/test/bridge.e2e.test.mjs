import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { BridgeProcess, waitFor } from './helpers/bridge-client.mjs';
import { startMockPhone } from './helpers/mock-phone.mjs';

describe('stdio bridge end-to-end', () => {
  let phone;
  let bridge;

  before(async () => {
    phone = await startMockPhone();
    bridge = new BridgeProcess({ args: ['--url', phone.url, '--api-key', 'test-key', '--verbose'] });
  });

  after(async () => {
    await bridge.stop();
    await phone.close();
  });

  it('answers initialize with the client protocol version when supported', async () => {
    const response = await bridge.request('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'test-client', version: '1.0.0' },
    });
    assert.equal(response.result.protocolVersion, '2025-06-18');
    assert.ok(response.result.capabilities.tools);
    assert.match(response.result.serverInfo.name, /PocketMCP/);
    assert.equal(typeof response.result.instructions, 'string');
  });

  it('downgrades unknown protocol versions to a classic handshake', async () => {
    const response = await bridge.request('initialize', { protocolVersion: '2099-01-01' });
    assert.equal(response.result.protocolVersion, '2025-06-18');
  });

  it('lists tools from the phone and sends the API key', async () => {
    const response = await bridge.request('tools/list', {});
    assert.deepEqual(
      response.result.tools.map((tool) => tool.name),
      ['device_info', 'echo', 'fail_tool']
    );
    assert.equal(phone.state.authSeen, 'test-key');
    assert.ok(phone.state.initialized >= 1);
  });

  it('caches tools/list within the TTL', async () => {
    const before = phone.state.toolsListCount;
    const response = await bridge.request('tools/list', {});
    assert.equal(response.result.tools.length, 3);
    assert.equal(phone.state.toolsListCount, before);
  });

  it('proxies tools/call results', async () => {
    const response = await bridge.request('tools/call', { name: 'echo', arguments: { text: 'hi' } });
    assert.equal(response.result.content[0].text, JSON.stringify({ text: 'hi' }));
  });

  it('passes tool execution errors through as isError results', async () => {
    const response = await bridge.request('tools/call', { name: 'fail_tool', arguments: {} });
    assert.equal(response.result.isError, true);
    assert.equal(response.result.content[0].text, 'boom');
  });

  it('surfaces unknown tools as JSON-RPC errors', async () => {
    const response = await bridge.request('tools/call', { name: 'does_not_exist', arguments: {} });
    assert.equal(response.error.code, -32001);
    assert.match(response.error.message, /does_not_exist/);
  });

  it('answers ping locally without touching the phone', async () => {
    const before = phone.state.requests.length;
    const response = await bridge.request('ping', {});
    assert.deepEqual(response.result, {});
    assert.equal(phone.state.requests.length, before);
  });

  it('rejects unknown methods with -32601', async () => {
    const response = await bridge.request('resources/list', {});
    assert.equal(response.error.code, -32601);
  });

  it('ignores notifications and keeps serving requests', async () => {
    bridge.send({ jsonrpc: '2.0', method: 'notifications/initialized' });
    const response = await bridge.request('ping', {});
    assert.deepEqual(response.result, {});
  });

  it('replies to malformed JSON with -32700', async () => {
    bridge.sendRaw('{not json');
    const message = await waitFor(() => bridge.unmatched.find((m) => m?.error?.code === -32700));
    assert.ok(message);
  });

  it('replies to batch messages with -32600', async () => {
    bridge.sendRaw('[{"jsonrpc":"2.0","id":1,"method":"ping"}]');
    const message = await waitFor(() => bridge.unmatched.find((m) => m?.error?.code === -32600));
    assert.ok(message);
  });
});

describe('bridge transport variants', () => {
  it('parses SSE (text/event-stream) responses', async () => {
    const phone = await startMockPhone({ sseToolsList: true });
    const bridge = new BridgeProcess({ args: ['--url', phone.url, '--api-key', 'test-key'] });
    try {
      const response = await bridge.request('tools/list', {});
      assert.equal(response.result.tools.length, 3);
    } finally {
      await bridge.stop();
      await phone.close();
    }
  });

  it('captures and reuses the Mcp-Session-Id from initialize', async () => {
    const phone = await startMockPhone({ requireSession: true });
    const bridge = new BridgeProcess({ args: ['--url', phone.url, '--api-key', 'test-key'] });
    try {
      const response = await bridge.request('tools/list', {});
      assert.equal(response.result.tools.length, 3);
      const nonInit = phone.state.requests.filter((request) => request.method !== 'initialize');
      assert.ok(nonInit.length > 0);
      assert.ok(nonInit.every((request) => request.session === phone.state.sessionId));
    } finally {
      await bridge.stop();
      await phone.close();
    }
  });

  it('retries transient 5xx failures for safe requests', async () => {
    const phone = await startMockPhone({ flakyToolsList: 1 });
    const bridge = new BridgeProcess({ args: ['--url', phone.url, '--api-key', 'test-key'] });
    try {
      const response = await bridge.request('tools/list', {});
      assert.equal(response.result.tools.length, 3);
      assert.equal(phone.state.toolsListCount, 2);
    } finally {
      await bridge.stop();
      await phone.close();
    }
  });

  it('reports wrong API keys with a helpful error and stays alive', async () => {
    const phone = await startMockPhone({ apiKey: 'right-key' });
    const bridge = new BridgeProcess({ args: ['--url', phone.url, '--api-key', 'wrong-key'] });
    try {
      const response = await bridge.request('tools/list', {});
      assert.ok(response.error);
      assert.match(response.error.message, /API key/i);
      const ping = await bridge.request('ping', {});
      assert.deepEqual(ping.result, {});
    } finally {
      await bridge.stop();
      await phone.close();
    }
  });

  it('accepts ws:// URLs by converting to the HTTP endpoint', async () => {
    const phone = await startMockPhone();
    const wsUrl = phone.url.replace(/^http:/, 'ws:').replace(/\/mcp$/, '/ws');
    const bridge = new BridgeProcess({ args: ['--url', wsUrl, '--api-key', 'test-key'] });
    try {
      const response = await bridge.request('tools/list', {});
      assert.equal(response.result.tools.length, 3);
    } finally {
      await bridge.stop();
      await phone.close();
    }
  });
});
