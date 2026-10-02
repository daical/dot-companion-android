import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { DevelopmentAuthorization } from './auth.mjs';
import { LocalBridge } from './bridge.mjs';
import { startLocalServer } from './http.mjs';
import { PrivateFileStore } from './store.mjs';

async function main() {
  const conversationId = process.env.DOT_BRIDGE_CONVERSATION_ID ?? 'local-preview';
  const appToken = process.env.DOT_BRIDGE_APP_TOKEN;
  const mcpToken = process.env.DOT_BRIDGE_MCP_TOKEN;
  if (!appToken || !mcpToken) {
    throw new Error('Set separate DOT_BRIDGE_APP_TOKEN and DOT_BRIDGE_MCP_TOKEN development values (at least 32 base64url characters).');
  }
  const authorization = new DevelopmentAuthorization([
    { id: `dev:app:${conversationId}`, role: 'app', conversationId, token: appToken },
    { id: `dev:mcp:${conversationId}`, role: 'mcp', conversationId, token: mcpToken },
  ]);
  const runtime = fileURLToPath(new URL('../.runtime/', import.meta.url));
  const store = new PrivateFileStore(join(runtime, 'state.json'));
  const logger = (event, details) => process.stderr.write(`${JSON.stringify({ event, ...details })}\n`);
  // Outbound callback delivery remains disabled. Only test runners inject it.
  const bridge = new LocalBridge({ authorization, store, logger });
  const port = process.env.DOT_BRIDGE_PORT === undefined ? 8787 : Number(process.env.DOT_BRIDGE_PORT);
  const server = await startLocalServer({ bridge, port, logger });
  process.stdout.write(`Local development bridge: ${server.url}\nLive dot connected: false. Outbound callbacks disabled.\n`);
  let closing = false;
  const close = async () => {
    if (closing) return;
    closing = true;
    await server.close();
  };
  process.once('SIGINT', () => close().catch(() => { process.exitCode = 1; }));
  process.once('SIGTERM', () => close().catch(() => { process.exitCode = 1; }));
}

main().catch((error) => {
  // All configuration errors are fixed messages; never dump objects or stacks.
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
});
