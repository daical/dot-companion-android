import test from 'node:test';
import assert from 'node:assert/strict';
import { request as httpRequest } from 'node:http';
import { randomUUID } from 'node:crypto';
import { DevelopmentAuthorization } from '../src/auth.mjs';
import { LocalBridge, PROTOCOL_VERSION } from '../src/bridge.mjs';
import { startLocalServer } from '../src/http.mjs';
import { syntheticFixture } from '../scripts/synthetic-fixtures.mjs';

async function serverFixture(t, defaultTransport = false) {
  const f = syntheticFixture();
  if (defaultTransport) f.bridge = new LocalBridge({ authorization: f.authorization, clock: f.clock });
  const server = await startLocalServer({ bridge: f.bridge, port: 0, pump: false,
    logger: (event, details) => f.logs.push({ event, ...details }) });
  t.after(() => server.close());
  f.server = server;
  f.rawGet = (path, overrides = {}) => new Promise((resolve, reject) => {
    const req = httpRequest(`${server.url}${path}`, { headers: {
      Authorization: `Bearer ${f.tokens.appToken}`, ...overrides,
    } }, (response) => {
      response.resume(); response.on('end', () => resolve({ status: response.statusCode }));
    });
    req.on('error', reject); req.end();
  });
  f.get = (path, overrides = {}) => fetch(`${server.url}${path}`, {
    headers: { Authorization: `Bearer ${f.tokens.appToken}`, ...overrides },
  });
  f.post = (path, value, overrides = {}) => fetch(`${server.url}${path}`, {
    method: 'POST', headers: { Authorization: `Bearer ${f.tokens.appToken}`,
      'Content-Type': 'application/json', ...overrides }, body: JSON.stringify(value),
  });
  f.rpc = (method, params = {}, overrides = {}) => f.post('/mcp', {
    jsonrpc: '2.0', id: 1, method, params,
  }, { Authorization: `Bearer ${f.tokens.mcpToken}`, 'MCP-Protocol-Version': PROTOCOL_VERSION, ...overrides });
  return f;
}

test('HTTP listener is IPv4 loopback and status never advertises a live dot', async (t) => {
  const f = await serverFixture(t);
  assert.equal(f.server.server.address().address, '127.0.0.1');
  const response = await f.get('/v1/status');
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { mode: 'local_development', liveDotConnected: false });
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(response.headers.get('access-control-allow-origin'), null);
});

test('HTTP endpoints require the correct scoped bearer token and do not accept URL credentials', async (t) => {
  const f = await serverFixture(t);
  const unauthenticated = await fetch(`${f.server.url}/v1/status`);
  assert.equal(unauthenticated.status, 401);
  assert.equal(unauthenticated.headers.get('www-authenticate'), 'Bearer realm="local-development"');
  assert.equal((await f.get('/v1/status', { Authorization: `Bearer ${f.tokens.mcpToken}` })).status, 401);
  assert.equal((await f.rpc('server/discover', {}, { Authorization: `Bearer ${f.tokens.appToken}` })).status, 401);
  assert.equal((await fetch(`${f.server.url}/v1/status?token=${f.tokens.appToken}`)).status, 400);
});

test('external Host, browser Origin, and forwarding headers are blocked', async (t) => {
  const f = await serverFixture(t);
  for (const headers of [{ Host: 'attacker.example.org' }, { Host: 'localhost' },
    { Origin: 'https://attacker.example.org' }, { Origin: 'null' },
    { Origin: f.server.url }, { Forwarded: 'for=127.0.0.1' }, { 'X-Forwarded-Host': 'localhost' }]) {
    assert.equal((await f.rawGet('/v1/status', headers)).status, 403);
  }
});

test('explicit emulator Host alias is permitted on the same local port', async (t) => {
  const f = await serverFixture(t);
  assert.equal((await f.rawGet('/v1/status', { Host: `10.0.2.2:${f.server.server.address().port}` })).status, 200);
});

test('duplicate Authorization headers are rejected', async (t) => {
  const f = await serverFixture(t);
  const status = await new Promise((resolve, reject) => {
    const req = httpRequest(f.server.url + '/v1/status', {
      headers: ['Authorization', `Bearer ${f.tokens.appToken}`, 'Authorization', `Bearer ${f.tokens.appToken}`,
        'Host', `127.0.0.1:${f.server.server.address().port}`],
    }, (response) => { response.resume(); response.on('end', () => resolve(response.statusCode)); });
    req.on('error', reject); req.end();
  });
  assert.equal(status, 400);
});

test('HTTP messages maintain contract, idempotent status, and conversation isolation', async (t) => {
  const f = await serverFixture(t);
  const message = f.message();
  assert.deepEqual(await (await f.post('/v1/messages', message)).json(), { requestId: message.requestId, status: 'queued' });
  const before = await (await f.get('/v1/messages?conversationId=local-preview')).json();
  assert.equal(before.messages[0].reply, null);
  await f.rpc('tools/call', { name: 'send_reply', arguments: { requestId: message.requestId, text: 'Synthetic HTTP reply.' } });
  assert.deepEqual(await (await f.post('/v1/messages', message)).json(), { requestId: message.requestId, status: 'replied' });
  assert.equal((await f.post('/v1/messages', { ...message, text: 'changed' })).status, 409);
  assert.equal((await f.get('/v1/messages?conversationId=other-preview')).status, 403);
});

test('message query shape is strict and does not reflect query values', async (t) => {
  const f = await serverFixture(t);
  for (const query of ['', '?conversationId=local-preview&conversationId=other-preview', '?conversationId=local-preview&token=secret',
    '?unknown=private-data', '?conversationId=../private']) {
    const response = await f.get(`/v1/messages${query}`);
    assert.equal(response.status, 400);
    assert.equal((await response.text()).includes('private-data'), false);
  }
});

test('unknown routes, wrong methods, and unsupported content types are rejected', async (t) => {
  const f = await serverFixture(t);
  assert.equal((await f.get('/private')).status, 404);
  assert.equal((await f.get('/mcp')).status, 405);
  assert.equal((await f.post('/v1/messages', f.message(), { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await fetch(`${f.server.url}/v1/messages`, {
    method: 'DELETE', headers: { Authorization: `Bearer ${f.tokens.appToken}` },
  })).status, 405);
});

test('JSON body byte limit is enforced before parsing or storing', async (t) => {
  const f = await serverFixture(t);
  const response = await f.post('/v1/messages', { ...f.message(), text: 'x'.repeat(33 * 1024) });
  assert.equal(response.status, 413);
  assert.equal(f.bridge.listMessages(f.app, 'local-preview').messages.length, 0);
});

test('invalid UTF-8, malformed JSON, and unexpected message fields are rejected', async (t) => {
  const f = await serverFixture(t);
  for (const body of ['{', Buffer.from([0xff, 0xfe]), JSON.stringify({ ...f.message(), token: 'private' })]) {
    const response = await fetch(`${f.server.url}/v1/messages`, { method: 'POST',
      headers: { Authorization: `Bearer ${f.tokens.appToken}`, 'Content-Type': 'application/json' }, body });
    assert.equal(response.status, 400);
  }
});

test('MCP exposes JSON-RPC errors without leaking callback secrets or raw exceptions', async (t) => {
  const f = await serverFixture(t, true);
  const response = await f.rpc('events/subscribe', f.subscription());
  assert.equal(response.status, 200);
  const value = await response.json();
  assert.deepEqual(value.error, { code: -32015, message: 'Callback endpoint could not be verified or reached.',
    data: { reason: 'outbound_disabled' } });
  assert.equal(JSON.stringify(value).includes(f.receiver.secret), false);
});

test('MCP parse error, invalid envelopes, unknown methods, and unsupported versions are handled', async (t) => {
  const f = await serverFixture(t);
  const invalidJson = await fetch(`${f.server.url}/mcp`, { method: 'POST',
    headers: { Authorization: `Bearer ${f.tokens.mcpToken}`, 'Content-Type': 'application/json' }, body: '{' });
  assert.equal((await invalidJson.json()).error.code, -32700);
  const badEnvelope = await f.post('/mcp', { jsonrpc: '1.0', id: 1, method: 'server/discover' }, {
    Authorization: `Bearer ${f.tokens.mcpToken}`,
  });
  assert.equal((await badEnvelope.json()).error.code, -32600);
  assert.equal((await (await f.rpc('unknown.method')).json()).error.code, -32601);
  assert.equal((await (await f.rpc('server/discover', {}, { 'MCP-Protocol-Version': '2025-03-26' })).json()).error.code, -32602);
});

test('MCP revoked access and unknown request IDs have no data leakage', async (t) => {
  const f = await serverFixture(t);
  const unknown = await f.rpc('tools/call', { name: 'fetch_request', arguments: { requestId: randomUUID() } });
  assert.equal((await unknown.json()).error.code, -32003);
  f.authorization.revoke(f.mcp.id);
  assert.equal((await f.rpc('tools/list')).status, 401);
});

test('HTTP logs redact input text and bearer/signing credentials', async (t) => {
  const f = await serverFixture(t);
  await f.post('/v1/messages', f.message({ text: 'PRIVATE message', instruction: 'bad' }));
  await f.get('/v1/status', { Authorization: `Bearer ${f.tokens.mcpToken}` });
  const logs = JSON.stringify(f.logs);
  for (const value of ['PRIVATE message', f.tokens.appToken, f.tokens.mcpToken, f.receiver.secret]) {
    assert.equal(logs.includes(value), false);
  }
});

test('listener configuration rejects invalid ports and provides no bind-host override', async () => {
  const authorization = new DevelopmentAuthorization([]);
  const bridge = new LocalBridge({ authorization });
  for (const port of [-1, 65536, 1.5, NaN, '8787']) {
    await assert.rejects(startLocalServer({ bridge, port }), /Invalid local port/);
  }
});
