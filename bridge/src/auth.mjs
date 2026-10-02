import { createHash, timingSafeEqual } from 'node:crypto';
import { BridgeError, forbidden } from './errors.mjs';

const digest = (value) => createHash('sha256').update(value).digest();

export function constantTimeEqual(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string') return false;
  return timingSafeEqual(digest(left), digest(right));
}

export function validConversationId(value) {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value);
}

/** Synthetic development principals only. This is not OAuth or an identity provider. */
export class DevelopmentAuthorization {
  #principals = new Map();
  #tokens = [];

  constructor(principals) {
    for (const principal of principals) {
      const { id, role, conversationId, token } = principal;
      if (typeof id !== 'string' || !/^[A-Za-z0-9:_-]{1,160}$/.test(id)
          || !['app', 'mcp'].includes(role) || !validConversationId(conversationId)
          || typeof token !== 'string' || !/^[A-Za-z0-9_-]{32,256}$/.test(token)
          || this.#principals.has(id)) {
        throw new Error('Invalid local development principal configuration.');
      }
      const tokenHash = digest(token);
      if (this.#tokens.some((entry) => timingSafeEqual(entry.hash, tokenHash))) {
        throw new Error('Local app and MCP tokens must be distinct.');
      }
      this.#principals.set(id, { id, role, conversationId, active: true });
      this.#tokens.push({ hash: tokenHash, id });
    }
  }

  authenticate(authorization, expectedRole) {
    const match = typeof authorization === 'string'
      ? /^Bearer ([A-Za-z0-9_-]{32,256})$/.exec(authorization) : null;
    // Hash malformed input too; do not return token or principal details in errors.
    const candidate = digest(match?.[1] ?? 'invalid');
    let matched;
    for (const entry of this.#tokens) {
      if (timingSafeEqual(entry.hash, candidate)) matched = entry.id;
    }
    const principal = this.#principals.get(matched);
    if (!match || !principal?.active || principal.role !== expectedRole) {
      throw new BridgeError('Authentication required.', { httpStatus: 401, rpcCode: -32001 });
    }
    return { ...principal };
  }

  isAuthorized(principalId, role, conversationId) {
    const principal = this.#principals.get(principalId);
    return Boolean(principal?.active && principal.role === role
      && principal.conversationId === conversationId);
  }

  require(principal, role, conversationId) {
    if (!this.isAuthorized(principal.id, role, conversationId)) forbidden();
  }

  revoke(principalId) {
    const principal = this.#principals.get(principalId);
    if (principal) principal.active = false;
  }
}
