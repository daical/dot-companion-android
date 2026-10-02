// Synthetic in-memory callback receiver. This module is never loaded by start.
import { createHash, randomBytes } from 'node:crypto';
import { DevelopmentAuthorization, constantTimeEqual } from '../src/auth.mjs';
import { LocalBridge, EVENT_NAME } from '../src/bridge.mjs';
import { callbackUrl } from '../src/callback-transport.mjs';
import { BridgeError, record } from '../src/errors.mjs';
import { verifyWebhook } from '../src/signatures.mjs';

export function syntheticSecret() { return `whsec_${randomBytes(32).toString('base64')}`; }

export class SyntheticReceiver {
  constructor({ clock, secret = syntheticSecret(), url = 'https://receiver.example.org/companion' } = {}) {
    this.clock = clock;
    this.secret = secret;
    this.url = callbackUrl(url).href;
    this.attempts = [];
    this.accepted = [];
    this.receipts = new Map();
    this.registrations = new Map();
    this.challenges = new Set();
    this.eventResponse = undefined;
    this.verificationResponse = undefined;
  }

  async post(url, request) {
    // Validate the same URL restrictions, but do not resolve DNS or open a socket.
    if (callbackUrl(url).href !== this.url) throw new Error('Unexpected synthetic receiver URL.');
    const { headers, body } = request;
    this.attempts.push({ url, headers: { ...headers }, body: Buffer.from(body) });
    const subscriptionId = headers['X-MCP-Subscription-Id'];
    if (typeof subscriptionId !== 'string' || !/^sub_[0-9a-f]{64}$/.test(subscriptionId)) {
      return { status: 401, body: '' };
    }
    const secret = this.secret;
    if (!verifyWebhook(secret, headers, body, this.clock())) return { status: 401, body: '' };
    const keyId = createHash('sha256').update(secret).digest('hex');
    const value = JSON.parse(body);
    if (value.type === 'verification') {
      record(value, ['type', 'challenge']);
      if (typeof value.challenge !== 'string' || this.challenges.has(value.challenge)) return { status: 400, body: '' };
      this.challenges.add(value.challenge);
      const response = this.verificationResponse
        ? await this.verificationResponse(value, request)
        : { status: 200, body: JSON.stringify({ challenge: value.challenge }) };
      if (response.status >= 200 && response.status < 300) {
        let echoed;
        try { echoed = JSON.parse(response.body); } catch { /* A failed echo cannot register. */ }
        if (constantTimeEqual(echoed?.challenge, value.challenge)) {
          this.registrations.set(subscriptionId, { url: this.url, keyId });
        }
      }
      return response;
    }
    // The subscription header is outside the Standard Webhooks signature. It
    // can select only a binding established by a successful signed challenge,
    // with the same callback and key; it cannot invent a receipt namespace.
    const registration = this.registrations.get(subscriptionId);
    if (!registration || registration.url !== this.url || registration.keyId !== keyId) {
      return { status: 401, body: '' };
    }
    record(value, ['eventId', 'name', 'timestamp', 'data', 'cursor']);
    record(value.data, ['request_id', 'conversation_id', 'created_at']);
    if (value.name !== EVENT_NAME || value.eventId !== headers['webhook-id'] || value.cursor !== null
        || typeof headers['X-MCP-Subscription-Id'] !== 'string') return { status: 400, body: '' };
    if (this.eventResponse) {
      const response = await this.eventResponse(value, request);
      if (response.status < 200 || response.status >= 300) return response;
    }
    // One receiver instance owns one callback. Deduplicate by signed event ID,
    // independently of the unsigned subscription header, including rotation.
    const identity = value.eventId;
    const hash = createHash('sha256').update(body).digest('hex');
    if (this.receipts.has(identity)) {
      if (this.receipts.get(identity) !== hash) throw new BridgeError('Synthetic duplicate ID conflict.');
      return { status: 200, body: '' };
    }
    this.receipts.set(identity, hash);
    this.accepted.push(value);
    return { status: 200, body: '' };
  }
}

export function syntheticFixture({ store, now = Date.parse('2026-10-02T12:00:00Z') } = {}) {
  let currentTime = now;
  const clock = () => currentTime;
  const appToken = randomBytes(32).toString('base64url');
  const mcpToken = randomBytes(32).toString('base64url');
  const otherAppToken = randomBytes(32).toString('base64url');
  const otherMcpToken = randomBytes(32).toString('base64url');
  const authorization = new DevelopmentAuthorization([
    { id: 'synthetic:app', role: 'app', conversationId: 'local-preview', token: appToken },
    { id: 'synthetic:mcp', role: 'mcp', conversationId: 'local-preview', token: mcpToken },
    { id: 'synthetic:other-app', role: 'app', conversationId: 'other-preview', token: otherAppToken },
    { id: 'synthetic:other-mcp', role: 'mcp', conversationId: 'other-preview', token: otherMcpToken },
  ]);
  const receiver = new SyntheticReceiver({ clock });
  const logs = [];
  const bridge = new LocalBridge({ authorization, store, transport: receiver, clock,
    logger: (event, details) => logs.push({ event, ...details }) });
  const app = authorization.authenticate(`Bearer ${appToken}`, 'app');
  const mcp = authorization.authenticate(`Bearer ${mcpToken}`, 'mcp');
  const otherApp = authorization.authenticate(`Bearer ${otherAppToken}`, 'app');
  const otherMcp = authorization.authenticate(`Bearer ${otherMcpToken}`, 'mcp');
  return { bridge, receiver, clock, logs, authorization, app, mcp, otherApp, otherMcp,
    tokens: { appToken, mcpToken, otherAppToken, otherMcpToken },
    advance(ms) { currentTime += ms; },
    subscription(overrides = {}) {
      return { name: EVENT_NAME, arguments: { conversation_id: 'local-preview' },
        delivery: { mode: 'webhook', url: receiver.url, secret: receiver.secret }, cursor: null, ...overrides };
    },
    message(overrides = {}) {
      return { requestId: '3e5bd3d6-6641-46d9-b5d8-ecb3de2a4c04', conversationId: 'local-preview',
        text: 'Synthetic fixture: hello from the phone queue.', createdAt: new Date(clock()).toISOString(), ...overrides };
    },
  };
}
