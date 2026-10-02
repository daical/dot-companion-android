import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { constantTimeEqual, validConversationId } from './auth.mjs';
import { callbackUrl, DisabledCallbackTransport } from './callback-transport.mjs';
import { BridgeError, CallbackError, canonicalJson, forbidden, invalid, record, safeLog, unavailable } from './errors.mjs';
import { MemoryStore } from './store.mjs';
import { MAX_WEBHOOK_BYTES, signingKey, webhookHeaders } from './signatures.mjs';

export const PROTOCOL_VERSION = '2026-07-28';
export const EVENT_NAME = 'companion.message.created';
export const MAX_TEXT_BYTES = 8192;
const DEFAULT_TTL_MS = 3_600_000;
const VERIFY_CACHE_MS = 60_000;
const ROTATION_MS = 60_000;
const MAX_ATTEMPTS = 5;
const MAX_MESSAGES = 500;
const MAX_SUBSCRIPTIONS = 16;
const MAX_DELIVERIES = 2000;

function text(value) {
  if (typeof value !== 'string' || !value.trim() || Buffer.byteLength(value) > MAX_TEXT_BYTES
      || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value)) invalid('Text must contain 1–8192 UTF-8 bytes.');
  return value;
}

function requestId(value) {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)) {
    invalid('A canonical UUID requestId is required.');
  }
  return value;
}

function createdAt(value) {
  if (typeof value !== 'string'
      || !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.test(value)
      || !Number.isFinite(Date.parse(value))) invalid('createdAt must be an ISO-8601 timestamp with timezone.');
  const calendarDate = value.slice(0, 10);
  if (!new Date(`${calendarDate}T00:00:00Z`).toISOString().startsWith(calendarDate)) invalid('Invalid calendar date.');
  return value;
}

function publicMessage(message) {
  return Object.fromEntries(['requestId', 'conversationId', 'text', 'createdAt', 'status', 'reply']
    .map((key) => [key, message[key]]));
}

export function subscriptionIdentity(principalId, url, name, argumentsValue) {
  return `sub_${createHash('sha256').update(canonicalJson({
    principalId, url, name, arguments: argumentsValue,
  })).digest('hex')}`;
}

function refreshMessage(state, id) {
  const message = state.messages.find((entry) => entry.requestId === id);
  if (!message || message.status === 'replied') return;
  const deliveries = state.deliveries.filter((entry) => entry.requestId === id);
  if (deliveries.some((entry) => entry.status === 'accepted')) message.status = 'awaiting_reply';
  else if (deliveries.some((entry) => entry.status === 'pending')) message.status = 'queued';
  else if (deliveries.length) message.status = 'failed';
}

function validateState(state) {
  try {
    record(state, ['version', 'messages', 'subscriptions', 'deliveries']);
    if (state.version !== 1 || !Array.isArray(state.messages) || !Array.isArray(state.subscriptions)
        || !Array.isArray(state.deliveries) || state.messages.length > MAX_MESSAGES
        || state.subscriptions.length > MAX_SUBSCRIPTIONS || state.deliveries.length > MAX_DELIVERIES) invalid();
    const ids = new Set();
    for (const message of state.messages) {
      record(message, ['requestId', 'conversationId', 'text', 'createdAt', 'status', 'reply', 'appPrincipalId']);
      requestId(message.requestId); text(message.text); createdAt(message.createdAt);
      if (!validConversationId(message.conversationId) || ids.has(message.requestId)
          || typeof message.appPrincipalId !== 'string' || !message.appPrincipalId
          || !['queued', 'awaiting_reply', 'replied', 'failed'].includes(message.status)
          || (message.status === 'replied' ? typeof message.reply !== 'string' : message.reply !== null)) invalid();
      if (message.reply !== null) text(message.reply);
      ids.add(message.requestId);
    }
    const subscriptionIds = new Set();
    for (const subscription of state.subscriptions) {
      record(subscription, ['id', 'principalId', 'name', 'arguments', 'url', 'secret', 'expiresAt',
        'active', 'previousSecret', 'rotationUntil'],
      ['id', 'principalId', 'name', 'arguments', 'url', 'secret', 'expiresAt', 'active']);
      record(subscription.arguments, ['conversation_id']);
      const url = callbackUrl(subscription.url).href;
      signingKey(subscription.secret);
      if (subscription.previousSecret) signingKey(subscription.previousSecret);
      if (subscription.name !== EVENT_NAME || !validConversationId(subscription.arguments.conversation_id)
          || subscription.id !== subscriptionIdentity(subscription.principalId, url, subscription.name, subscription.arguments)
          || subscriptionIds.has(subscription.id) || typeof subscription.active !== 'boolean'
          || !Number.isSafeInteger(subscription.expiresAt) || subscription.expiresAt <= 0
          || (subscription.rotationUntil !== undefined && !Number.isSafeInteger(subscription.rotationUntil))) invalid();
      subscriptionIds.add(subscription.id);
    }
    const deliveryIds = new Set();
    for (const delivery of state.deliveries) {
      record(delivery, ['id', 'eventId', 'requestId', 'subscriptionId', 'body', 'attempts', 'nextAttemptAt', 'status']);
      if (!ids.has(delivery.requestId) || typeof delivery.body !== 'string'
          || Buffer.byteLength(delivery.body) > MAX_WEBHOOK_BYTES || deliveryIds.has(delivery.id)
          || delivery.id !== `${delivery.subscriptionId}:${delivery.eventId}`
          || !Number.isSafeInteger(delivery.attempts) || delivery.attempts < 0 || delivery.attempts > MAX_ATTEMPTS
          || !Number.isSafeInteger(delivery.nextAttemptAt) || delivery.nextAttemptAt < 0
          || !['pending', 'accepted', 'failed', 'stopped'].includes(delivery.status)) invalid();
      const event = JSON.parse(delivery.body);
      record(event, ['eventId', 'name', 'timestamp', 'data', 'cursor']);
      record(event.data, ['request_id', 'conversation_id', 'created_at']);
      const message = state.messages.find((entry) => entry.requestId === delivery.requestId);
      if (event.eventId !== delivery.eventId || !/^evt_[0-9a-f-]{36}$/.test(event.eventId)
          || event.name !== EVENT_NAME || event.cursor !== null || event.timestamp !== message.createdAt
          || event.data.request_id !== message.requestId || event.data.conversation_id !== message.conversationId
          || event.data.created_at !== message.createdAt) invalid();
      deliveryIds.add(delivery.id);
    }
  } catch {
    throw new Error('Local state schema is invalid; preserve it for manual recovery.');
  }
}

/** MCP Events wire harness, not a certified MCP server or live dot connection. */
export class LocalBridge {
  #state;
  #locks = new Map();
  #verification = new Map();
  #draining;

  constructor({ authorization, store = new MemoryStore(), transport = new DisabledCallbackTransport(),
    clock = () => Date.now(), logger } = {}) {
    if (!authorization) throw new Error('Development authorization is required.');
    this.authorization = authorization;
    this.store = store;
    this.transport = transport;
    this.clock = clock;
    this.logger = logger;
    this.#state = store.load();
    validateState(this.#state);
  }

  #commit(change) {
    const next = structuredClone(this.#state);
    const value = change(next);
    this.store.save(next);
    this.#state = next;
    return value;
  }

  status(principal) {
    this.authorization.require(principal, 'app', principal.conversationId);
    return { mode: 'local_development', liveDotConnected: false };
  }

  createMessage(principal, input) {
    record(input, ['requestId', 'conversationId', 'text', 'createdAt']);
    requestId(input.requestId); text(input.text); createdAt(input.createdAt);
    if (!validConversationId(input.conversationId)) invalid();
    this.authorization.require(principal, 'app', input.conversationId);
    const existing = this.#state.messages.find((message) => message.requestId === input.requestId);
    if (existing) {
      if (existing.appPrincipalId !== principal.id || existing.conversationId !== input.conversationId) forbidden();
      if (existing.text !== input.text || existing.createdAt !== input.createdAt) {
        throw new BridgeError('Request ID conflicts with an existing message.', { httpStatus: 409, rpcCode: -32009 });
      }
      return { requestId: input.requestId, status: existing.status };
    }
    const now = this.clock();
    const subscriptions = this.#state.subscriptions.filter((entry) => entry.active && entry.expiresAt > now
      && entry.arguments.conversation_id === input.conversationId
      && this.authorization.isAuthorized(entry.principalId, 'mcp', input.conversationId));
    if (this.#state.messages.length >= MAX_MESSAGES
        || this.#state.messages.filter((entry) => entry.conversationId === input.conversationId).length >= 100
        || this.#state.deliveries.length + subscriptions.length > MAX_DELIVERIES) unavailable();
    const event = { eventId: `evt_${randomUUID()}`, name: EVENT_NAME, timestamp: input.createdAt,
      data: { request_id: input.requestId, conversation_id: input.conversationId, created_at: input.createdAt }, cursor: null };
    const body = JSON.stringify(event);
    if (Buffer.byteLength(body) > MAX_WEBHOOK_BYTES) invalid('Event payload exceeds the local limit.');
    this.#commit((state) => {
      state.messages.push({ ...input, appPrincipalId: principal.id, status: 'queued', reply: null });
      for (const subscription of subscriptions) {
        state.deliveries.push({ id: `${subscription.id}:${event.eventId}`, eventId: event.eventId,
          requestId: input.requestId, subscriptionId: subscription.id, body,
          attempts: 0, nextAttemptAt: now, status: 'pending' });
      }
    });
    safeLog(this.logger, 'message_queued', { count: 1 });
    return { requestId: input.requestId, status: 'queued' };
  }

  listMessages(principal, conversationId) {
    if (!validConversationId(conversationId)) invalid();
    this.authorization.require(principal, 'app', conversationId);
    return { messages: this.#state.messages.filter((entry) => entry.conversationId === conversationId
      && entry.appPrincipalId === principal.id).map(publicMessage) };
  }

  #findRequest(principal, id) {
    requestId(id);
    const message = this.#state.messages.find((entry) => entry.requestId === id);
    if (!message || !this.authorization.isAuthorized(principal.id, 'mcp', message.conversationId)) {
      // Unknown IDs and other principals' resources have the same response.
      forbidden();
    }
    return message;
  }

  callTool(principal, params) {
    record(params, ['name', 'arguments']);
    if (params.name === 'fetch_request') {
      record(params.arguments, ['requestId']);
      const message = this.#findRequest(principal, params.arguments.requestId);
      return this.#toolResult({ request: publicMessage(message) });
    }
    if (params.name === 'send_reply') {
      record(params.arguments, ['requestId', 'text']);
      text(params.arguments.text);
      const message = this.#findRequest(principal, params.arguments.requestId);
      if (message.reply !== null && message.reply !== params.arguments.text) {
        throw new BridgeError('Reply conflicts with an existing reply.', { httpStatus: 409, rpcCode: -32009 });
      }
      if (message.reply === null) {
        this.#commit((state) => {
          const target = state.messages.find((entry) => entry.requestId === message.requestId);
          target.reply = params.arguments.text;
          target.status = 'replied';
          // Replies do not produce message.created events or retry old wakeups.
          for (const delivery of state.deliveries.filter((entry) => entry.requestId === target.requestId
              && entry.status === 'pending')) delivery.status = 'stopped';
        });
      }
      return this.#toolResult({ requestId: message.requestId, status: 'replied' });
    }
    throw new BridgeError('Unknown tool.', { rpcCode: -32602 });
  }

  #toolResult(value) {
    return { content: [{ type: 'text', text: JSON.stringify(value) }], structuredContent: value, isError: false };
  }

  #validateSubscription(principal, params, subscribe) {
    record(params, subscribe ? ['name', 'arguments', 'delivery', 'cursor', 'ttlMs']
      : ['name', 'arguments', 'delivery'], ['name', 'arguments', 'delivery']);
    record(params.arguments, ['conversation_id']);
    if (params.name !== EVENT_NAME || !validConversationId(params.arguments.conversation_id)) invalid();
    this.authorization.require(principal, 'mcp', params.arguments.conversation_id);
    record(params.delivery, subscribe ? ['mode', 'url', 'secret'] : ['mode', 'url']);
    if (params.delivery.mode !== 'webhook' || (params.cursor !== undefined && params.cursor !== null)) invalid();
    const url = callbackUrl(params.delivery.url).href;
    if (subscribe) {
      signingKey(params.delivery.secret);
      if (params.ttlMs !== undefined && params.ttlMs !== null
          && (!Number.isSafeInteger(params.ttlMs) || params.ttlMs <= 0)) invalid('Invalid ttlMs.');
    }
    const id = subscriptionIdentity(principal.id, url, params.name, params.arguments);
    return { id, url };
  }

  async #lock(id, work) {
    const earlier = this.#locks.get(id) ?? Promise.resolve();
    const current = earlier.catch(() => {}).then(work);
    this.#locks.set(id, current);
    try { return await current; } finally {
      if (this.#locks.get(id) === current) this.#locks.delete(id);
    }
  }

  async subscribe(principal, params) {
    const { id, url } = this.#validateSubscription(principal, params, true);
    return this.#lock(id, async () => {
      // TTL is bounded to one hour; null requests still receive finite lifetime.
      const ttl = params.ttlMs == null ? DEFAULT_TTL_MS
        : Math.max(1000, Math.min(params.ttlMs, DEFAULT_TTL_MS));
      const secretDigest = createHash('sha256').update(params.delivery.secret).digest('hex');
      const cacheKey = `${principal.id}:${url}`;
      const cached = this.#verification.get(cacheKey);
      const now = this.clock();
      if (!cached || cached.until <= now || cached.secretDigest !== secretDigest) {
        const challenge = randomBytes(32).toString('base64url');
        const body = Buffer.from(JSON.stringify({ type: 'verification', challenge }));
        const verificationId = `msg_verification_${randomUUID()}`;
        const verification = { id, secret: params.delivery.secret };
        let response;
        try {
          response = await this.transport.post(url, { body,
            headers: webhookHeaders(verification, verificationId, body, now) });
        } catch (error) {
          throw error instanceof CallbackError ? error : new CallbackError('connection_failed');
        }
        if (this.clock() - now >= 10_000) throw new CallbackError('timeout');
        if (response.status < 200 || response.status >= 300) throw new CallbackError('http_error');
        let echoed;
        try {
          if (typeof response.body !== 'string' || Buffer.byteLength(response.body) > 4096) throw new Error();
          echoed = JSON.parse(response.body);
          record(echoed, ['challenge']);
        } catch { throw new CallbackError('challenge_failed'); }
        if (!constantTimeEqual(echoed.challenge, challenge)) throw new CallbackError('challenge_failed');
        if (this.#verification.size >= 128) this.#verification.delete(this.#verification.keys().next().value);
        this.#verification.set(cacheKey, { until: this.clock() + VERIFY_CACHE_MS, secretDigest });
      }
      // Access may have been revoked while callback verification was in flight.
      this.authorization.require(principal, 'mcp', params.arguments.conversation_id);
      const existing = this.#state.subscriptions.find((entry) => entry.id === id);
      if (!existing && this.#state.subscriptions.length >= MAX_SUBSCRIPTIONS) unavailable();
      const activatedAt = this.clock();
      const expiresAt = activatedAt + ttl;
      this.#commit((state) => {
        const old = state.subscriptions.find((entry) => entry.id === id);
        const subscription = { id, principalId: principal.id, name: params.name,
          arguments: structuredClone(params.arguments), url, secret: params.delivery.secret,
          expiresAt, active: true };
        if (old && old.secret !== subscription.secret) {
          subscription.previousSecret = old.secret;
          subscription.rotationUntil = activatedAt + ROTATION_MS;
        } else if (old?.previousSecret && old.rotationUntil > activatedAt) {
          subscription.previousSecret = old.previousSecret;
          subscription.rotationUntil = old.rotationUntil;
        }
        if (old) state.subscriptions[state.subscriptions.indexOf(old)] = subscription;
        else state.subscriptions.push(subscription);
      });
      return { id, refreshBefore: new Date(expiresAt).toISOString(), cursor: null, truncated: false };
    });
  }

  async unsubscribe(principal, params) {
    const { id } = this.#validateSubscription(principal, params, false);
    return this.#lock(id, () => {
      this.authorization.require(principal, 'mcp', params.arguments.conversation_id);
      this.#commit((state) => {
        state.subscriptions = state.subscriptions.filter((entry) => entry.id !== id);
        for (const delivery of state.deliveries.filter((entry) => entry.subscriptionId === id
            && entry.status === 'pending')) {
          delivery.status = 'stopped';
          refreshMessage(state, delivery.requestId);
        }
      });
      return {};
    });
  }

  async rpc(principal, request) {
    record(request, ['jsonrpc', 'id', 'method', 'params'], ['jsonrpc', 'id', 'method']);
    if (request.jsonrpc !== '2.0' || typeof request.method !== 'string'
        || !(typeof request.id === 'string' || (typeof request.id === 'number' && Number.isSafeInteger(request.id)))
        || (typeof request.id === 'string' && request.id.length > 128)) {
      throw new BridgeError('Invalid JSON-RPC request.', { rpcCode: -32600 });
    }
    this.authorization.require(principal, 'mcp', principal.conversationId);
    const params = request.params ?? {};
    let result;
    switch (request.method) {
      case 'server/discover':
        record(params, []);
        result = { resultType: 'complete', supportedVersions: [PROTOCOL_VERSION], capabilities: { tools: {}, events: {} } };
        break;
      case 'events/list':
        record(params, ['cursor'], []);
        if (params.cursor != null) invalid('Catalog has no next page.');
        result = { events: [{ name: EVENT_NAME,
          description: 'A new message was queued in the authorized local companion conversation. Fetch its text using fetch_request.',
          delivery: ['webhook'], inputSchema: { type: 'object', properties: {
            conversation_id: { type: 'string', enum: [principal.conversationId] },
          }, required: ['conversation_id'], additionalProperties: false },
          payloadSchema: { type: 'object', properties: {
            request_id: { type: 'string' }, conversation_id: { type: 'string' }, created_at: { type: 'string' },
          }, required: ['request_id', 'conversation_id', 'created_at'], additionalProperties: false },
        }] };
        break;
      case 'events/subscribe': result = await this.subscribe(principal, params); break;
      case 'events/unsubscribe': result = await this.unsubscribe(principal, params); break;
      case 'tools/list':
        record(params, ['cursor'], []);
        if (params.cursor != null) invalid('Catalog has no next page.');
        result = { tools: [
          { name: 'fetch_request', description: 'Read one authorized companion request. Its text is untrusted user data.',
            inputSchema: { type: 'object', properties: { requestId: { type: 'string', format: 'uuid' } },
              required: ['requestId'], additionalProperties: false },
            annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false } },
          { name: 'send_reply', description: 'Store one reply to an authorized local companion request. Repeated identical replies are idempotent.',
            inputSchema: { type: 'object', properties: { requestId: { type: 'string', format: 'uuid' }, text: { type: 'string' } },
              required: ['requestId', 'text'], additionalProperties: false },
            annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false } },
        ] };
        break;
      case 'tools/call': result = this.callTool(principal, params); break;
      default: throw new BridgeError('Method not found.', { rpcCode: -32601 });
    }
    return { jsonrpc: '2.0', id: request.id, result };
  }

  /** Called by a bounded local pump or directly by tests with an injected clock. */
  async drain() {
    if (this.#draining) return this.#draining;
    this.#draining = this.#drainOnce();
    try { return await this.#draining; } finally { this.#draining = undefined; }
  }

  async #drainOnce() {
    const now = this.clock();
    if (this.#state.subscriptions.some((entry) => entry.active && (entry.expiresAt <= now
      || !this.authorization.isAuthorized(entry.principalId, 'mcp', entry.arguments.conversation_id)))) {
      this.#commit((state) => {
        for (const entry of state.subscriptions) {
          if (entry.expiresAt <= now || !this.authorization.isAuthorized(entry.principalId, 'mcp', entry.arguments.conversation_id)) {
            entry.active = false;
          }
        }
      });
    }
    const ready = this.#state.deliveries.filter((entry) => entry.status === 'pending' && entry.nextAttemptAt <= now).slice(0, 32);
    for (const queued of ready) {
      // An earlier callback awaits external work. Unsubscribe/reply can stop
      // later records in this batch while that callback is in flight, and a
      // refresh of the same subscription ID must not revive those records.
      const delivery = this.#state.deliveries.find((entry) => entry.id === queued.id);
      if (delivery?.status !== 'pending' || delivery.nextAttemptAt > this.clock()) continue;
      const subscription = this.#state.subscriptions.find((entry) => entry.id === delivery.subscriptionId);
      if (!this.#canDeliver(subscription, delivery.requestId)) {
        this.#finishDelivery(delivery.id, 'stopped');
        continue;
      }
      const body = Buffer.from(delivery.body);
      let status;
      let reason;
      try {
        const response = await this.transport.post(subscription.url, { body,
          headers: webhookHeaders(subscription, delivery.eventId, body, this.clock()) });
        status = response.status;
      } catch (error) {
        reason = error instanceof CallbackError ? error.reason : 'connection_failed';
      }
      // Unsubscription, expiry, revocation, or reply can occur during an attempt.
      const current = this.#state.deliveries.find((entry) => entry.id === delivery.id);
      const currentSubscription = this.#state.subscriptions.find((entry) => entry.id === delivery.subscriptionId);
      if (current?.status !== 'pending' || !this.#canDeliver(currentSubscription, delivery.requestId)) {
        if (current?.status === 'pending') this.#finishDelivery(delivery.id, 'stopped');
        continue;
      }
      if (Number.isInteger(status) && status >= 200 && status < 300) {
        this.#finishDelivery(delivery.id, 'accepted', true);
      } else {
        const retryable = status === 408 || status === 429 || (status >= 500 && status <= 599)
          || ['timeout', 'dns_failed', 'connection_failed'].includes(reason);
        this.#commit((state) => {
          const entry = state.deliveries.find((item) => item.id === delivery.id);
          entry.attempts += 1;
          if (status === 410) {
            const sub = state.subscriptions.find((item) => item.id === entry.subscriptionId);
            if (sub) sub.active = false;
            for (const job of state.deliveries.filter((item) => item.subscriptionId === entry.subscriptionId
                && item.status === 'pending')) {
              job.status = 'stopped';
              refreshMessage(state, job.requestId);
            }
          } else if (retryable && entry.attempts < MAX_ATTEMPTS) {
            entry.nextAttemptAt = this.clock() + Math.min(30_000, 1000 * 2 ** (entry.attempts - 1));
          } else entry.status = 'failed';
          refreshMessage(state, entry.requestId);
        });
        safeLog(this.logger, 'delivery_failed', { status, reason, attempt: delivery.attempts + 1 });
      }
    }
    return { processed: ready.length };
  }

  #canDeliver(subscription, id) {
    const message = this.#state.messages.find((entry) => entry.requestId === id);
    return Boolean(subscription?.active && subscription.expiresAt > this.clock() && message && message.reply === null
      && this.authorization.isAuthorized(subscription.principalId, 'mcp', message.conversationId)
      && subscription.arguments.conversation_id === message.conversationId);
  }

  #finishDelivery(id, status, attempted = false) {
    this.#commit((state) => {
      const entry = state.deliveries.find((item) => item.id === id);
      if (!entry || entry.status !== 'pending') return;
      entry.status = status;
      if (attempted) entry.attempts += 1;
      refreshMessage(state, entry.requestId);
    });
  }
}
