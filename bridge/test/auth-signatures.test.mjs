import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { DevelopmentAuthorization, constantTimeEqual } from '../src/auth.mjs';
import { safeLog } from '../src/errors.mjs';
import { MAX_WEBHOOK_BYTES, signingKey, signWebhook, verifyWebhook, webhookHeaders } from '../src/signatures.mjs';
import { syntheticFixture, syntheticSecret } from '../scripts/synthetic-fixtures.mjs';

test('development roles and conversation scopes cannot be interchanged', () => {
  const f = syntheticFixture();
  assert.throws(() => f.authorization.authenticate(`Bearer ${f.tokens.appToken}`, 'mcp'), { httpStatus: 401 });
  assert.throws(() => f.authorization.authenticate(`Bearer ${f.tokens.mcpToken}`, 'app'), { httpStatus: 401 });
  assert.throws(() => f.authorization.require(f.app, 'app', 'other-preview'), { httpStatus: 403 });
});

test('malformed, missing, truncated, and unknown bearer credentials are denied', () => {
  const f = syntheticFixture();
  for (const candidate of [undefined, '', 'Basic abc', `bearer ${f.tokens.appToken}`, `Bearer ${f.tokens.appToken} `,
    `Bearer ${f.tokens.appToken.slice(0, -1)}`, 'Bearer unknown', `Bearer ${randomBytes(32).toString('base64url')}`]) {
    assert.throws(() => f.authorization.authenticate(candidate, 'app'), { httpStatus: 401 });
  }
});

test('revocation applies to both previously returned principals and tokens', () => {
  const f = syntheticFixture();
  f.authorization.revoke(f.mcp.id);
  assert.equal(f.authorization.isAuthorized(f.mcp.id, 'mcp', 'local-preview'), false);
  assert.throws(() => f.authorization.require(f.mcp, 'mcp', 'local-preview'), { httpStatus: 403 });
  assert.throws(() => f.authorization.authenticate(`Bearer ${f.tokens.mcpToken}`, 'mcp'), { httpStatus: 401 });
});

test('configuration rejects weak, duplicate, and invalid tokens or principal IDs', () => {
  const token = randomBytes(32).toString('base64url');
  const valid = { id: 'fixture:app', role: 'app', conversationId: 'local-preview', token };
  for (const replacement of [{ token: 'weak' }, { token: `space ${token}` }, { role: 'admin' },
    { conversationId: '../private' }, { id: '' }]) {
    assert.throws(() => new DevelopmentAuthorization([{ ...valid, ...replacement }]));
  }
  assert.throws(() => new DevelopmentAuthorization([valid, { ...valid, id: 'fixture:mcp', role: 'mcp' }]));
  assert.throws(() => new DevelopmentAuthorization([valid, { ...valid, token: randomBytes(32).toString('base64url') }]));
});

test('constant-time challenge comparison rejects mismatches and wrong types', () => {
  assert.equal(constantTimeEqual('challenge', 'challenge'), true);
  for (const value of ['challengE', 'challenge-longer', '', undefined, { challenge: 'challenge' }]) {
    assert.equal(constantTimeEqual('challenge', value), false);
  }
});

test('Standard Webhooks fixed fixture signs exact UTF-8 bytes', () => {
  const secret = `whsec_${Buffer.alloc(32, 66).toString('base64')}`;
  const signature = signWebhook(secret, 'evt_fixture', '1790942400', Buffer.from('{"hello":"world"}'));
  assert.equal(signature, 'v1,7nJ66TfvelNoP5GjKpCR/MAq/L+tqhiWK/YWBuR+tcE=');
});

test('Standard Webhooks accepts valid signatures and rejects body/id/timestamp tampering', () => {
  const secret = syntheticSecret();
  const body = Buffer.from('{"text":"Unicode: café 🌙"}');
  const now = 1_790_942_400_000;
  const headers = webhookHeaders({ id: 'sub_fixture', secret }, 'evt_fixture', body, now);
  assert.equal(verifyWebhook(secret, headers, body, now), true);
  assert.equal(verifyWebhook(secret, headers, Buffer.from('{ "text":"Unicode: café 🌙"}'), now), false);
  assert.equal(verifyWebhook(secret, { ...headers, 'webhook-id': 'evt_other' }, body, now), false);
  assert.equal(verifyWebhook(secret, { ...headers, 'webhook-timestamp': '1790942401' }, body, now), false);
  assert.equal(verifyWebhook(syntheticSecret(), headers, body, now), false);
});

test('receiver enforces timestamp replay tolerance in both directions', () => {
  const secret = syntheticSecret();
  const now = 1_790_942_400_000;
  const body = Buffer.from('{}');
  const headers = webhookHeaders({ id: 'sub_fixture', secret }, 'evt_fixture', body, now);
  assert.equal(verifyWebhook(secret, headers, body, now + 300_000), true);
  assert.equal(verifyWebhook(secret, headers, body, now + 300_001), false);
  assert.equal(verifyWebhook(secret, headers, body, now - 300_001), false);
});

test('signing secret requires canonical base64 and 24–64 decoded bytes', () => {
  assert.equal(signingKey(`whsec_${Buffer.alloc(24).toString('base64')}`).length, 24);
  assert.equal(signingKey(`whsec_${Buffer.alloc(64).toString('base64')}`).length, 64);
  for (const secret of [null, 'raw-key', 'whsec_!!!!', 'whsec_YQ==',
    `whsec_${Buffer.alloc(23).toString('base64')}`, `whsec_${Buffer.alloc(65).toString('base64')}`,
    `whsec_${Buffer.alloc(32).toString('base64').replace(/=$/, '')}`]) {
    assert.throws(() => signingKey(secret));
  }
});

test('signature metadata and payload limits fail closed', () => {
  const secret = syntheticSecret();
  for (const id of ['event.with.dot', 'id\r\nheader', '', undefined]) {
    assert.throws(() => signWebhook(secret, id, '1790942400', Buffer.from('{}')));
  }
  for (const timestamp of ['not-time', '1.1', '-1', '9007199254740992']) {
    assert.throws(() => signWebhook(secret, 'evt_fixture', timestamp, Buffer.from('{}')));
  }
  assert.throws(() => signWebhook(secret, 'evt_fixture', '1790942400', Buffer.alloc(MAX_WEBHOOK_BYTES + 1)));
});

test('rotation adds space-separated signatures only inside the rotation window', () => {
  const secret = syntheticSecret();
  const previousSecret = syntheticSecret();
  const now = 1_790_942_400_000;
  const body = Buffer.from('{}');
  const sub = { id: 'sub_fixture', secret, previousSecret, rotationUntil: now + 60_000 };
  const rotating = webhookHeaders(sub, 'evt_fixture', body, now);
  assert.equal(rotating['webhook-signature'].split(' ').length, 2);
  assert.equal(verifyWebhook(secret, rotating, body, now), true);
  assert.equal(verifyWebhook(previousSecret, rotating, body, now), true);
  const rotated = webhookHeaders(sub, 'evt_fixture', body, now + 60_000);
  assert.equal(rotated['webhook-signature'].split(' ').length, 1);
  assert.equal(verifyWebhook(previousSecret, rotated, body, now + 60_000), false);
});

test('receiver rejects malformed or unknown signature versions without throwing', () => {
  const secret = syntheticSecret();
  const now = 1_790_942_400_000;
  const body = Buffer.from('{}');
  const headers = webhookHeaders({ id: 'sub_fixture', secret }, 'evt_fixture', body, now);
  for (const signature of [undefined, '', 'v2,abc', 'v1,!!!!', 'v1,abc,extra', 'v1,' + 'x'.repeat(1025)]) {
    assert.equal(verifyWebhook(secret, { ...headers, 'webhook-signature': signature }, body, now), false);
  }
});

test('safe logger strips tokens, callbacks, request text, and uncontrolled reasons', () => {
  const entries = [];
  safeLog((event, value) => entries.push({ event, ...value }), 'test', {
    status: 401, attempt: 2, reason: 'Bearer SECRET', url: 'https://secret.invalid/',
    text: 'PRIVATE', secret: 'whsec_PRIVATE', headers: { Authorization: 'Bearer PRIVATE' },
  });
  assert.deepEqual(entries, [{ event: 'test', status: 401, attempt: 2 }]);
});
