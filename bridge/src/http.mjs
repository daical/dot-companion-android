import { createServer } from 'node:http';
import { BridgeError, safeLog } from './errors.mjs';
import { PROTOCOL_VERSION } from './bridge.mjs';

const MAX_REQUEST_BYTES = 32 * 1024;

function httpError(status, message) {
  throw new BridgeError(message, { httpStatus: status });
}

function checkSurface(request, port) {
  if (!['127.0.0.1', '::ffff:127.0.0.1'].includes(request.socket.remoteAddress)) {
    httpError(403, 'Loopback access required.');
  }
  const allowedHosts = ['127.0.0.1', 'localhost', '10.0.2.2'].map((host) => `${host}:${port}`);
  if (!allowedHosts.includes(request.headers.host)) httpError(403, 'Invalid local Host header.');
  // Native apps and local CLI clients have no Origin. Browser clients are not
  // supported and no CORS exception is provided.
  if (request.headers.origin !== undefined) httpError(403, 'Browser origins are not supported.');
  if (Object.keys(request.headers).some((name) => name === 'forwarded' || name.startsWith('x-forwarded-'))) {
    httpError(403, 'Proxy forwarding is not supported.');
  }
  const counts = new Map();
  for (let index = 0; index < request.rawHeaders.length; index += 2) {
    const name = request.rawHeaders[index].toLowerCase();
    counts.set(name, (counts.get(name) ?? 0) + 1);
  }
  if (['host', 'authorization', 'origin', 'content-type'].some((name) => (counts.get(name) ?? 0) > 1)) {
    httpError(400, 'Duplicate security headers are not supported.');
  }
  if (!request.url?.startsWith('/') || request.url.startsWith('//')) httpError(400, 'Invalid local path.');
}

async function readJson(request, mcp) {
  if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers['content-type'] ?? '')) {
    httpError(415, 'Use application/json.');
  }
  const length = request.headers['content-length'];
  if (length !== undefined && (!/^\d+$/.test(length) || Number(length) > MAX_REQUEST_BYTES)) {
    request.resume();
    httpError(413, 'Request body too large.');
  }
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_REQUEST_BYTES) httpError(413, 'Request body too large.');
    chunks.push(chunk);
  }
  try {
    // Fatal decoding rejects invalid UTF-8 rather than silently replacing bytes.
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
  } catch {
    throw new BridgeError('Invalid JSON.', { httpStatus: 400, rpcCode: mcp ? -32700 : -32602 });
  }
}

function send(response, status, value) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': String(Buffer.byteLength(body)),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  });
  response.end(body);
}

/** The listener is always IPv4 loopback. A public bind is not configurable. */
export async function startLocalServer({ bridge, port = 8787, logger, pump = true }) {
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('Invalid local port.');
  const server = createServer({ maxHeaderSize: 8192 }, async (request, response) => {
    let rpcId = null;
    let mcp = false;
    let authenticated = false;
    try {
      checkSurface(request, server.address().port);
      const url = new URL(request.url, `http://127.0.0.1:${server.address().port}`);
      if (url.pathname === '/mcp') {
        mcp = true;
        if (request.method !== 'POST') httpError(405, 'Only JSON-RPC POST is supported.');
        if (url.search) httpError(400, 'MCP query parameters are not supported.');
        const principal = bridge.authorization.authenticate(request.headers.authorization, 'mcp');
        authenticated = true;
        const protocol = request.headers['mcp-protocol-version'];
        if (protocol !== undefined && protocol !== PROTOCOL_VERSION) httpError(400, 'Unsupported MCP protocol version.');
        const input = await readJson(request, true);
        rpcId = typeof input?.id === 'string' || Number.isSafeInteger(input?.id) ? input.id : null;
        send(response, 200, await bridge.rpc(principal, input));
      } else if (url.pathname === '/v1/status' && request.method === 'GET') {
        if (url.search) httpError(400, 'Status query parameters are not supported.');
        const principal = bridge.authorization.authenticate(request.headers.authorization, 'app');
        send(response, 200, bridge.status(principal));
      } else if (url.pathname === '/v1/messages') {
        const principal = bridge.authorization.authenticate(request.headers.authorization, 'app');
        if (request.method === 'POST') {
          if (url.search) httpError(400, 'Message POST query parameters are not supported.');
          send(response, 200, bridge.createMessage(principal, await readJson(request, false)));
        } else if (request.method === 'GET') {
          const keys = [...url.searchParams.keys()];
          if (keys.length !== 1 || keys[0] !== 'conversationId') httpError(400, 'One conversationId is required.');
          send(response, 200, bridge.listMessages(principal, url.searchParams.get('conversationId')));
        } else httpError(405, 'Use GET or POST.');
      } else httpError(404, 'Local endpoint not found.');
    } catch (error) {
      const known = error instanceof BridgeError;
      safeLog(logger, 'request_rejected', { status: known ? error.httpStatus : 500, reason: error.reason });
      if (response.headersSent || response.destroyed) return;
      if (mcp && authenticated && (!known || ![413, 415, 405].includes(error.httpStatus))) {
        const rpcError = { code: known ? error.rpcCode : -32603,
          message: known ? error.message : 'Internal local bridge error.' };
        if (known && error.reason) rpcError.data = { reason: error.reason };
        send(response, 200, { jsonrpc: '2.0', id: rpcId, error: rpcError });
      } else {
        const status = known ? error.httpStatus : 500;
        const value = { error: known ? error.message : 'Internal local bridge error.' };
        if (status === 401) response.setHeader('WWW-Authenticate', 'Bearer realm="local-development"');
        send(response, status, value);
      }
    }
  });
  server.headersTimeout = 5000;
  server.requestTimeout = 15_000;
  server.keepAliveTimeout = 1000;
  server.maxRequestsPerSocket = 100;
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen({ host: '127.0.0.1', port }, resolve);
  });
  const timer = pump ? setInterval(() => {
    bridge.drain().catch(() => safeLog(logger, 'delivery_pump_failed'));
  }, 500) : undefined;
  timer?.unref();
  return {
    server,
    url: `http://127.0.0.1:${server.address().port}`,
    async close() {
      if (timer) clearInterval(timer);
      await new Promise((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
        server.closeIdleConnections();
      });
    },
  };
}
