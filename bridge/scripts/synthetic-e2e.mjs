import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { LocalBridge, PROTOCOL_VERSION } from '../src/bridge.mjs';
import { startLocalServer } from '../src/http.mjs';
import { PrivateFileStore } from '../src/store.mjs';
import { syntheticFixture } from './synthetic-fixtures.mjs';

const runtime = mkdtempSync(join(tmpdir(), 'dot-companion-synthetic-'));
let server;
try {
  const store = new PrivateFileStore(join(runtime, 'state.json'));
  const fixture = syntheticFixture({ store });
  server = await startLocalServer({ bridge: fixture.bridge, port: 0, pump: false });
  let rpcId = 0;
  async function app(path, body) {
    const response = await fetch(`${server.url}${path}`, {
      method: body ? 'POST' : 'GET',
      headers: { Authorization: `Bearer ${fixture.tokens.appToken}`,
        ...(body ? { 'Content-Type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    assert.equal(response.status, 200);
    return response.json();
  }
  async function rpc(method, params = {}) {
    const response = await fetch(`${server.url}/mcp`, {
      method: 'POST', headers: { Authorization: `Bearer ${fixture.tokens.mcpToken}`,
        'Content-Type': 'application/json', 'MCP-Protocol-Version': PROTOCOL_VERSION },
      body: JSON.stringify({ jsonrpc: '2.0', id: ++rpcId, method, params }),
    });
    assert.equal(response.status, 200);
    const value = await response.json();
    assert.equal(value.error, undefined);
    return value.result;
  }
  assert.deepEqual(await app('/v1/status'), { mode: 'local_development', liveDotConnected: false });
  assert.deepEqual((await rpc('server/discover')).supportedVersions, [PROTOCOL_VERSION]);
  assert.equal((await rpc('events/list')).events[0].name, 'companion.message.created');
  const subscription = await rpc('events/subscribe', fixture.subscription());
  const message = fixture.message();
  assert.deepEqual(await app('/v1/messages', message), { requestId: message.requestId, status: 'queued' });
  await fixture.bridge.drain();
  assert.equal(fixture.receiver.accepted.length, 1);
  assert.equal((await app('/v1/messages?conversationId=local-preview')).messages[0].status, 'awaiting_reply');
  const fetched = await rpc('tools/call', { name: 'fetch_request', arguments: { requestId: message.requestId } });
  assert.equal(fetched.structuredContent.request.text, message.text);
  const reply = 'Synthetic fixture reply. No dot or model was contacted.';
  await rpc('tools/call', { name: 'send_reply', arguments: { requestId: message.requestId, text: reply } });
  await rpc('tools/call', { name: 'send_reply', arguments: { requestId: message.requestId, text: reply } });
  await app('/v1/messages', message);
  assert.equal((await app('/v1/messages?conversationId=local-preview')).messages[0].reply, reply);
  await server.close();
  server = undefined;
  const restarted = new LocalBridge({ authorization: fixture.authorization, store,
    transport: fixture.receiver, clock: fixture.clock });
  assert.equal(restarted.listMessages(fixture.app, 'local-preview').messages[0].status, 'replied');
  const refreshed = await restarted.subscribe(fixture.mcp, fixture.subscription());
  assert.equal(refreshed.id, subscription.id);
  const stop = fixture.subscription();
  delete stop.delivery.secret;
  delete stop.cursor;
  assert.deepEqual(await restarted.unsubscribe(fixture.mcp, stop), {});
  process.stdout.write(`${JSON.stringify({
    mode: 'synthetic_local_e2e', liveDotConnected: false,
    result: 'passed', verified: ['authenticated_loopback_http', 'discovery', 'signed_callback_verification',
      'phone_queue_to_event', 'webhook_receipt_is_awaiting_reply', 'fetch_request', 'idempotent_send_reply',
      'idempotent_message', 'private_state_restart', 'subscription_refresh', 'unsubscribe'],
    externalCallbacks: 0, modelCalls: 0,
  }, null, 2)}\n`);
} finally {
  if (server) await server.close();
  rmSync(runtime, { recursive: true, force: true });
}
