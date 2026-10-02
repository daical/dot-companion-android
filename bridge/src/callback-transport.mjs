import { lookup as dnsLookup } from 'node:dns/promises';
import { request as httpsRequest } from 'node:https';
import { isIP } from 'node:net';
import { CallbackError } from './errors.mjs';
import { MAX_WEBHOOK_BYTES } from './signatures.mjs';

// Fail closed for all IANA special-purpose IPv4 blocks and all IPv6. IPv6
// interoperability is intentionally outside this local harness's tested scope.
const BLOCKED_V4 = [
  [0, 8], [0x0a000000, 8], [0x64400000, 10], [0x7f000000, 8],
  [0xa9fe0000, 16], [0xac100000, 12], [0xc0000000, 24],
  [0xc0000200, 24], [0xc01fc400, 24], [0xc034c100, 24],
  [0xc0586300, 24], [0xc0a80000, 16], [0xc0af3000, 24],
  [0xc6120000, 15], [0xc6336400, 24], [0xcb007100, 24],
  [0xe0000000, 4], [0xf0000000, 4],
];

export function isPublicAddress(address) {
  if (isIP(address) !== 4) return false;
  const value = address.split('.').reduce((sum, octet) => (sum * 256 + Number(octet)) >>> 0, 0);
  return !BLOCKED_V4.some(([base, prefix]) => {
    const mask = (0xffffffff << (32 - prefix)) >>> 0;
    return (value & mask) >>> 0 === (base & mask) >>> 0;
  });
}

export function callbackUrl(rawUrl) {
  if (typeof rawUrl !== 'string' || rawUrl.length > 2048 || /[\s\\]/.test(rawUrl)) {
    throw new CallbackError('invalid_url');
  }
  let url;
  try { url = new URL(rawUrl); } catch { throw new CallbackError('invalid_url'); }
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== 'https:' || url.username || url.password || url.hash
      || (url.port && url.port !== '443') || !hostname || hostname.endsWith('.')
      || hostname === 'localhost' || /\.(localhost|local|internal|home|lan|test|invalid|onion|example)$/.test(hostname)
      || hostname.includes(':') || hostname.includes('[')
      || (!isIP(hostname) && (!hostname.includes('.')
        || !hostname.split('.').every((label) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))))) {
    throw new CallbackError('invalid_url');
  }
  if (isIP(hostname) && !isPublicAddress(hostname)) throw new CallbackError('blocked_address');
  return url;
}

/** Default CLI transport: no external callbacks, tunnels, or dot subscriptions. */
export class DisabledCallbackTransport {
  async post() { throw new CallbackError('outbound_disabled'); }
}

/**
 * Isolated transport for future review and injected tests. This is not a public
 * deployment switch: the CLI never selects it. Resolve on every attempt, reject
 * an answer set containing any blocked address, pin that one address to the
 * socket, and retain the original hostname for TLS certificate validation.
 */
export class SafeHttpsCallbackTransport {
  constructor({ resolver = dnsLookup, request = httpsRequest, timeoutMs = 10_000 } = {}) {
    this.resolver = resolver;
    this.request = request;
    this.timeoutMs = timeoutMs;
  }

  async post(rawUrl, { headers, body }) {
    const url = callbackUrl(rawUrl);
    if (!Buffer.isBuffer(body) || body.length > MAX_WEBHOOK_BYTES) throw new CallbackError('payload_too_large');
    const hostname = url.hostname;
    let addresses;
    const lookupDeadline = AbortSignal.timeout(this.timeoutMs);
    try {
      const lookup = isIP(hostname)
        ? Promise.resolve([{ address: hostname, family: 4 }])
        : this.resolver(hostname, { all: true, verbatim: true });
      addresses = await new Promise((resolve, reject) => {
        const abort = () => reject(new CallbackError('timeout'));
        lookupDeadline.addEventListener('abort', abort, { once: true });
        Promise.resolve(lookup).then(resolve, reject).finally(() => lookupDeadline.removeEventListener('abort', abort));
      });
    } catch (error) {
      throw error instanceof CallbackError ? error : new CallbackError('dns_failed');
    }
    if (!Array.isArray(addresses) || !addresses.length
        || addresses.some(({ address, family }) => family !== 4 || !isPublicAddress(address))) {
      throw new CallbackError('blocked_address');
    }
    const pinned = addresses[0];
    return new Promise((resolve, reject) => {
      let finished = false;
      const finish = (error, value) => {
        if (finished) return;
        finished = true;
        if (error) reject(error); else resolve(value);
      };
      const signal = AbortSignal.timeout(this.timeoutMs);
      const options = {
        protocol: 'https:', hostname, port: 443,
        path: `${url.pathname}${url.search}`,
        method: 'POST', agent: false, family: 4,
        // Node still verifies the certificate against options.hostname.
        servername: isIP(hostname) ? undefined : hostname,
        rejectUnauthorized: true, minVersion: 'TLSv1.2', signal,
        lookup: (_hostname, lookupOptions, callback) => {
          if (lookupOptions?.all) callback(null, [{ ...pinned }]);
          else callback(null, pinned.address, pinned.family);
        },
        headers: { ...headers, 'Content-Length': String(body.length) },
      };
      let req;
      try {
        req = this.request(options, (response) => {
          const status = response.statusCode;
          if (status >= 300 && status < 400) {
            finish(new CallbackError('redirect_forbidden'));
            response.destroy();
            return;
          }
          const chunks = [];
          let size = 0;
          response.on('data', (chunk) => {
            size += chunk.length;
            if (size > 16 * 1024) {
              finish(new CallbackError('response_too_large'));
              response.destroy();
            } else chunks.push(chunk);
          });
          response.on('end', () => finish(null, { status, body: Buffer.concat(chunks).toString('utf8') }));
          response.on('error', () => finish(new CallbackError('connection_failed')));
          response.on('aborted', () => finish(new CallbackError('connection_failed')));
        });
        req.on('error', (error) => finish(new CallbackError(
          signal.aborted || error.code === 'ABORT_ERR' ? 'timeout' : 'connection_failed',
        )));
        req.end(body);
      } catch {
        finish(new CallbackError('connection_failed'));
        req?.destroy();
      }
    });
  }
}
