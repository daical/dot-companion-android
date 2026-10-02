import { createHmac, timingSafeEqual } from 'node:crypto';
import { invalid } from './errors.mjs';

export const MAX_WEBHOOK_BYTES = 256 * 1024;

export function signingKey(secret) {
  if (typeof secret !== 'string' || !secret.startsWith('whsec_')) invalid('Invalid signing secret.');
  const encoded = secret.slice(6);
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) invalid('Invalid signing secret.');
  const key = Buffer.from(encoded, 'base64');
  if (key.length < 24 || key.length > 64 || key.toString('base64') !== encoded) {
    invalid('Invalid signing secret.');
  }
  return key;
}

function metadata(id, timestamp, body) {
  if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,180}$/.test(id)
      || !/^[0-9]{1,16}$/.test(String(timestamp)) || !Number.isSafeInteger(Number(timestamp))
      || !Buffer.isBuffer(body) || body.length > MAX_WEBHOOK_BYTES) invalid('Invalid webhook metadata.');
  return Buffer.concat([Buffer.from(`${id}.${timestamp}.`, 'utf8'), body]);
}

/** Standard Webhooks v1: HMAC-SHA256 over id.timestamp.exact-body-bytes. */
export function signWebhook(secret, id, timestamp, body) {
  return `v1,${createHmac('sha256', signingKey(secret)).update(metadata(id, timestamp, body)).digest('base64')}`;
}

export function webhookHeaders(subscription, id, body, now) {
  const timestamp = String(Math.floor(now / 1000));
  const secrets = [subscription.secret];
  if (subscription.previousSecret && subscription.rotationUntil > now) secrets.push(subscription.previousSecret);
  return {
    'Content-Type': 'application/json',
    'webhook-id': id,
    'webhook-timestamp': timestamp,
    'webhook-signature': secrets.map((secret) => signWebhook(secret, id, timestamp, body)).join(' '),
    'X-MCP-Subscription-Id': subscription.id,
  };
}

/** Receiver utility for synthetic tests, with five-minute replay tolerance. */
export function verifyWebhook(secret, headers, body, now, toleranceMs = 300_000) {
  try {
    const normalized = Object.fromEntries(Object.entries(headers).map(([key, value]) => [key.toLowerCase(), value]));
    const id = normalized['webhook-id'];
    const timestamp = normalized['webhook-timestamp'];
    if (typeof timestamp !== 'string' || Math.abs(Number(timestamp) * 1000 - now) > toleranceMs) return false;
    const expected = signWebhook(secret, id, timestamp, body).slice(3);
    const expectedBytes = Buffer.from(expected, 'base64');
    const signatures = normalized['webhook-signature'];
    if (typeof signatures !== 'string' || signatures.length > 1024) return false;
    let accepted = false;
    for (const entry of signatures.split(' ')) {
      const [version, encoded, ...rest] = entry.split(',');
      if (version !== 'v1' || rest.length || !encoded) continue;
      const bytes = Buffer.from(encoded, 'base64');
      if (bytes.length === expectedBytes.length && bytes.toString('base64') === encoded
          && timingSafeEqual(bytes, expectedBytes)) accepted = true;
    }
    return accepted;
  } catch {
    return false;
  }
}
