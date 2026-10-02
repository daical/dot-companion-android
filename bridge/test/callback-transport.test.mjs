import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { SafeHttpsCallbackTransport, DisabledCallbackTransport, callbackUrl, isPublicAddress } from '../src/callback-transport.mjs';
import { MAX_WEBHOOK_BYTES } from '../src/signatures.mjs';

function requestFixture({ status = 200, chunks = ['{"challenge":"fixture"}'], error, location } = {}) {
  const calls = [];
  function request(options, onResponse) {
    const req = new EventEmitter();
    req.destroy = () => {};
    req.end = (body) => {
      calls.push({ options, body: Buffer.from(body) });
      queueMicrotask(() => {
        if (error) { req.emit('error', Object.assign(new Error('redacted'), { code: error })); return; }
        const response = new EventEmitter();
        response.statusCode = status;
        response.headers = location ? { location } : {};
        response.destroy = () => {};
        onResponse(response);
        for (const chunk of chunks) response.emit('data', Buffer.from(chunk));
        response.emit('end');
      });
    };
    return req;
  }
  return { calls, request };
}

const requestBody = () => ({ body: Buffer.from('{}'), headers: { 'Content-Type': 'application/json' } });

test('private, loopback, link-local, documentation, multicast, and reserved IPv4 are blocked', () => {
  for (const address of ['0.0.0.0', '0.10.20.30', '10.0.0.1', '100.64.0.1', '100.127.255.255',
    '127.0.0.1', '169.254.169.254', '172.16.0.1', '172.31.255.255', '192.0.0.9', '192.0.2.1',
    '192.31.196.1', '192.52.193.1', '192.88.99.1', '192.168.1.1', '192.175.48.1', '198.18.0.1',
    '198.19.255.255', '198.51.100.1', '203.0.113.1', '224.0.0.1', '239.255.255.255', '240.0.0.1',
    '255.255.255.255']) assert.equal(isPublicAddress(address), false, address);
});

test('IPv4 range boundaries do not block ordinary public addresses', () => {
  for (const address of ['1.1.1.1', '8.8.8.8', '100.63.255.255', '100.128.0.0', '172.15.255.255',
    '172.32.0.0', '192.1.0.1', '198.17.255.255', '198.20.0.0', '223.255.255.255']) {
    assert.equal(isPublicAddress(address), true, address);
  }
});

test('all IPv6 and malformed textual addresses fail closed', () => {
  for (const address of ['::', '::1', '::ffff:127.0.0.1', '::ffff:8.8.8.8', 'fe80::1', 'fc00::1',
    'ff02::1', '2001:db8::1', '2001:4860:4860::8888', '::%eth0', '8.8.8', '010.0.0.1',
    '0x7f000001', '2130706433', 'not-an-address']) assert.equal(isPublicAddress(address), false, address);
});

test('callback URLs reject unsafe schemes, credentials, fragments, local names, and ports', () => {
  for (const url of ['http://receiver.example.org/', 'https://user:pass@receiver.example.org/',
    'https://receiver.example.org/#fragment', 'https://receiver.example.org:8443/', 'https://localhost/',
    'https://service.local/', 'https://service.internal/', 'https://service.onion/', 'https://private/',
    'https://receiver.example.org./', 'https://[::1]/', 'https://127.0.0.1/', 'https://2130706433/',
    'https://0x7f000001/', 'https://0177.0.0.1/', 'https://receiver.example.org\\@127.0.0.1/',
    'https://receiver.example.org/ has-space', 'invalid', null]) {
    assert.throws(() => callbackUrl(url), { rpcCode: -32015 }, String(url));
  }
});

test('callback URL canonicalization retains safe hostname, path, and callback query', () => {
  assert.equal(callbackUrl('https://RECEIVER.example.org:443/event?callback=opaque').href,
    'https://receiver.example.org/event?callback=opaque');
});

test('CLI default transport never makes an outbound request', async () => {
  await assert.rejects(new DisabledCallbackTransport().post(), { reason: 'outbound_disabled' });
});

test('HTTPS transport pins validated DNS address while retaining hostname and certificate checks', async () => {
  const network = requestFixture();
  const resolverCalls = [];
  const transport = new SafeHttpsCallbackTransport({ request: network.request,
    resolver: async (hostname, options) => { resolverCalls.push({ hostname, options }); return [{ address: '8.8.8.8', family: 4 }]; } });
  const payload = requestBody();
  assert.equal((await transport.post('https://receiver.example.org/callback?opaque=1', payload)).status, 200);
  assert.deepEqual(resolverCalls, [{ hostname: 'receiver.example.org', options: { all: true, verbatim: true } }]);
  const { options, body } = network.calls[0];
  assert.equal(options.hostname, 'receiver.example.org');
  assert.equal(options.servername, 'receiver.example.org');
  assert.equal(options.path, '/callback?opaque=1');
  assert.equal(options.rejectUnauthorized, true);
  assert.equal(options.agent, false);
  assert.equal(options.minVersion, 'TLSv1.2');
  assert.equal(options.headers['Content-Length'], '2');
  assert.deepEqual(body, payload.body);
  options.lookup('receiver.example.org', {}, (error, address, family) => {
    assert.equal(error, null); assert.equal(address, '8.8.8.8'); assert.equal(family, 4);
  });
  options.lookup('receiver.example.org', { all: true }, (error, answers) => {
    assert.equal(error, null); assert.deepEqual(answers, [{ address: '8.8.8.8', family: 4 }]);
  });
});

test('DNS rebinding between attempts is blocked before creating the second socket', async () => {
  const network = requestFixture();
  let queries = 0;
  const transport = new SafeHttpsCallbackTransport({ request: network.request,
    resolver: async () => [{ address: ++queries === 1 ? '8.8.8.8' : '127.0.0.1', family: 4 }] });
  await transport.post('https://receiver.example.org/', requestBody());
  await assert.rejects(transport.post('https://receiver.example.org/', requestBody()), { reason: 'blocked_address' });
  assert.equal(queries, 2);
  assert.equal(network.calls.length, 1);
});

test('mixed public/private and dual-stack DNS answers are rejected in full', async () => {
  for (const answers of [[{ address: '8.8.8.8', family: 4 }, { address: '10.0.0.1', family: 4 }],
    [{ address: '8.8.8.8', family: 4 }, { address: '2001:4860::1', family: 6 }], [],
    [{ address: '8.8.8.8', family: 6 }]]) {
    const network = requestFixture();
    const transport = new SafeHttpsCallbackTransport({ request: network.request, resolver: async () => answers });
    await assert.rejects(transport.post('https://receiver.example.org/', requestBody()), { reason: 'blocked_address' });
    assert.equal(network.calls.length, 0);
  }
});

test('redirects are rejected without following the supplied Location', async () => {
  const network = requestFixture({ status: 302, location: 'https://127.0.0.1/secrets' });
  const transport = new SafeHttpsCallbackTransport({ request: network.request,
    resolver: async () => [{ address: '8.8.8.8', family: 4 }] });
  await assert.rejects(transport.post('https://receiver.example.org/', requestBody()), { reason: 'redirect_forbidden' });
  assert.equal(network.calls.length, 1);
});

test('callback response size is bounded', async () => {
  const network = requestFixture({ chunks: ['x'.repeat(16 * 1024 + 1)] });
  const transport = new SafeHttpsCallbackTransport({ request: network.request,
    resolver: async () => [{ address: '8.8.8.8', family: 4 }] });
  await assert.rejects(transport.post('https://receiver.example.org/', requestBody()), { reason: 'response_too_large' });
});

test('DNS and connection failures are categorized without exposing their messages', async () => {
  const network = requestFixture({ error: 'ECONNRESET' });
  const transport = new SafeHttpsCallbackTransport({ request: network.request,
    resolver: async () => [{ address: '8.8.8.8', family: 4 }] });
  await assert.rejects(transport.post('https://receiver.example.org/', requestBody()), { reason: 'connection_failed' });
  const failedDns = new SafeHttpsCallbackTransport({ request: network.request,
    resolver: async () => { throw new Error('PRIVATE DNS DETAILS'); } });
  await assert.rejects(failedDns.post('https://receiver.example.org/', requestBody()), (error) => {
    assert.equal(error.reason, 'dns_failed'); assert.equal(error.message.includes('PRIVATE'), false); return true;
  });
});

test('transport rejects oversized event bytes before any DNS or socket activity', async () => {
  let lookedUp = false;
  const network = requestFixture();
  const transport = new SafeHttpsCallbackTransport({ request: network.request,
    resolver: async () => { lookedUp = true; return [{ address: '8.8.8.8', family: 4 }]; } });
  await assert.rejects(transport.post('https://receiver.example.org/', {
    headers: {}, body: Buffer.alloc(MAX_WEBHOOK_BYTES + 1),
  }), { reason: 'payload_too_large' });
  assert.equal(lookedUp, false); assert.equal(network.calls.length, 0);
});
