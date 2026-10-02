export class BridgeError extends Error {
  constructor(message, { httpStatus = 400, rpcCode = -32602, reason } = {}) {
    super(message);
    this.name = 'BridgeError';
    this.httpStatus = httpStatus;
    this.rpcCode = rpcCode;
    this.reason = reason;
  }
}

export class CallbackError extends BridgeError {
  constructor(reason) {
    super('Callback endpoint could not be verified or reached.', {
      httpStatus: 400, rpcCode: -32015, reason,
    });
  }
}

export function invalid(message = 'Invalid parameters.') {
  throw new BridgeError(message);
}

export function forbidden() {
  throw new BridgeError('Access denied.', { httpStatus: 403, rpcCode: -32003 });
}

export function unavailable() {
  throw new BridgeError('Local capacity reached.', { httpStatus: 429, rpcCode: -32029 });
}

export function record(value, allowedKeys, requiredKeys = allowedKeys) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || Object.keys(value).some((key) => !allowedKeys.includes(key))
      || requiredKeys.some((key) => !Object.hasOwn(value, key))) invalid();
  return value;
}

export function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

// Never log request bodies, headers, callback URLs, exception messages, or secrets.
export function safeLog(logger, event, details = {}) {
  const safe = {};
  for (const key of ['attempt', 'status', 'count']) {
    if (Number.isSafeInteger(details[key])) safe[key] = details[key];
  }
  if (typeof details.reason === 'string' && /^[a-z_]{1,40}$/.test(details.reason)) {
    safe.reason = details.reason;
  }
  logger?.(event, safe);
}
