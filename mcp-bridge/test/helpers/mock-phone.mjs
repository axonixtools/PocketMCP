import http from 'node:http';

export const MOCK_TOOLS = [
  { name: 'device_info', description: 'Get device information', inputSchema: { type: 'object', properties: {} } },
  { name: 'echo', description: 'Echo arguments back', inputSchema: { type: 'object', properties: { text: { type: 'string' } } } },
  { name: 'fail_tool', description: 'Always returns an error result', inputSchema: { type: 'object', properties: {} } },
];

/**
 * Minimal PocketMCP server double for bridge tests.
 * Implements /health and JSON-RPC over POST /mcp (JSON and SSE responses).
 */
export async function startMockPhone({
  apiKey = 'test-key',
  sseToolsList = false,
  flakyToolsList = 0,
  requireSession = false,
} = {}) {
  const state = {
    initialized: 0,
    toolsListCount: 0,
    requests: [],
    authSeen: null,
    sessionSeen: null,
    sessionId: 'mock-session-01',
  };

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');

    if (req.method === 'GET' && url.pathname === '/health') {
      respond(res, 200, { status: 'ok', name: 'PocketMCP', version: '0.0.0-test', tools: MOCK_TOOLS.length });
      return;
    }
    if (req.method !== 'POST' || url.pathname !== '/mcp') {
      respond(res, 404, { error: 'not found' });
      return;
    }

    const body = await readBody(req);
    const candidates = [
      req.headers['x-api-key'],
      typeof req.headers.authorization === 'string' ? req.headers.authorization.replace(/^Bearer\s+/i, '') : null,
      url.searchParams.get('key'),
      url.searchParams.get('api_key'),
    ].filter((value) => typeof value === 'string' && value.length > 0);
    const providedKey = candidates[0] ?? null;
    if (providedKey) state.authSeen = providedKey;
    if (apiKey && providedKey !== apiKey) {
      respond(res, 401, { error: 'Invalid API key' });
      return;
    }

    let message;
    try {
      message = JSON.parse(body || '{}');
    } catch {
      respond(res, 400, { error: 'bad json' });
      return;
    }

    const session = req.headers['mcp-session-id'] ?? null;
    if (session) state.sessionSeen = session;
    state.requests.push({ method: message.method, auth: providedKey, session });

    if (requireSession && message.method !== 'initialize' && session !== state.sessionId) {
      respond(res, 404, { jsonrpc: '2.0', id: message.id ?? null, error: { code: -32000, message: 'Invalid session' } });
      return;
    }

    switch (message.method) {
      case 'initialize':
        state.initialized += 1;
        res.setHeader('mcp-session-id', state.sessionId);
        respond(res, 200, {
          jsonrpc: '2.0',
          id: message.id ?? null,
          result: {
            protocolVersion: '2024-11-05',
            capabilities: { tools: { listChanged: false } },
            serverInfo: { name: 'PocketMCP', version: '0.0.0-test' },
          },
        });
        return;

      case 'notifications/initialized':
        respond(res, 202, null);
        return;

      case 'tools/list': {
        state.toolsListCount += 1;
        if (state.toolsListCount <= flakyToolsList) {
          respond(res, 500, { error: 'transient failure' });
          return;
        }
        const payload = { jsonrpc: '2.0', id: message.id ?? null, result: { tools: MOCK_TOOLS } };
        if (sseToolsList) {
          res.writeHead(200, { 'content-type': 'text/event-stream' });
          res.end(`event: message\ndata: ${JSON.stringify(payload)}\n\n`);
        } else {
          respond(res, 200, payload);
        }
        return;
      }

      case 'tools/call': {
        const name = message.params?.name;
        if (name === 'echo') {
          respond(res, 200, {
            jsonrpc: '2.0',
            id: message.id,
            result: { content: [{ type: 'text', text: JSON.stringify(message.params?.arguments ?? {}) }] },
          });
          return;
        }
        if (name === 'fail_tool') {
          respond(res, 200, {
            jsonrpc: '2.0',
            id: message.id,
            result: { content: [{ type: 'text', text: 'boom' }], isError: true },
          });
          return;
        }
        respond(res, 200, {
          jsonrpc: '2.0',
          id: message.id,
          error: { code: -32001, message: `Tool not found: ${name}` },
        });
        return;
      }

      default:
        respond(res, 200, {
          jsonrpc: '2.0',
          id: message.id ?? null,
          error: { code: -32601, message: 'Method not found' },
        });
    }
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return {
    url: `http://127.0.0.1:${port}/mcp`,
    port,
    state,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

function respond(res, status, body) {
  if (body === null) {
    res.writeHead(status);
    res.end();
    return;
  }
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
    });
    req.on('end', () => resolve(data));
  });
}
