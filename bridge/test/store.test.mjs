import test from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, linkSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { LocalBridge } from '../src/bridge.mjs';
import { PrivateFileStore, emptyState } from '../src/store.mjs';
import { syntheticFixture } from '../scripts/synthetic-fixtures.mjs';

function temporary(t) {
  const directory = mkdtempSync(join(tmpdir(), 'companion-store-test-'));
  t.after(() => rmSync(directory, { force: true, recursive: true }));
  return directory;
}

test('private store creates 0700 directory and atomically writes 0600 state', (t) => {
  const directory = temporary(t);
  const file = join(directory, 'runtime', 'state.json');
  const store = new PrivateFileStore(file);
  assert.deepEqual(store.load(), emptyState());
  store.save(emptyState());
  assert.equal(statSync(join(directory, 'runtime')).mode & 0o777, 0o700);
  assert.equal(statSync(file).mode & 0o777, 0o600);
  assert.deepEqual(store.load(), emptyState());
  assert.deepEqual(readdirSync(join(directory, 'runtime')), ['state.json']);
});

test('persisted messages, replies, subscriptions, and pending deliveries survive fresh instance', async (t) => {
  const file = join(temporary(t), 'state.json');
  const store = new PrivateFileStore(file);
  const f = syntheticFixture({ store });
  const subscription = await f.bridge.subscribe(f.mcp, f.subscription());
  const message = f.message(); f.bridge.createMessage(f.app, message);
  const restarted = new LocalBridge({ authorization: f.authorization, store: new PrivateFileStore(file),
    transport: f.receiver, clock: f.clock });
  await restarted.drain();
  assert.equal(restarted.listMessages(f.app, 'local-preview').messages[0].status, 'awaiting_reply');
  restarted.callTool(f.mcp, { name: 'send_reply', arguments: { requestId: message.requestId, text: 'Persisted synthetic reply.' } });
  const secondRestart = new LocalBridge({ authorization: f.authorization, store: new PrivateFileStore(file),
    transport: f.receiver, clock: f.clock });
  assert.equal(secondRestart.listMessages(f.app, 'local-preview').messages[0].reply, 'Persisted synthetic reply.');
  assert.equal((await secondRestart.subscribe(f.mcp, f.subscription())).id, subscription.id);
  const contents = readFileSync(file, 'utf8');
  assert.equal(contents.includes(f.tokens.appToken), false);
  assert.equal(contents.includes(f.tokens.mcpToken), false);
});

test('permissive runtime directory and symlink directory are refused', (t) => {
  const directory = temporary(t);
  const permissive = join(directory, 'public'); mkdirSync(permissive, { mode: 0o755 });
  assert.throws(() => new PrivateFileStore(join(permissive, 'state.json')), /private/);
  const actual = join(directory, 'actual'); mkdirSync(actual, { mode: 0o700 });
  const linked = join(directory, 'linked'); symlinkSync(actual, linked);
  assert.throws(() => new PrivateFileStore(join(linked, 'state.json')), /private/);
});

test('permissive state file is refused without modification', (t) => {
  const file = join(temporary(t), 'state.json');
  const bytes = JSON.stringify(emptyState());
  writeFileSync(file, bytes, { mode: 0o600 }); chmodSync(file, 0o644);
  const store = new PrivateFileStore(file);
  assert.throws(() => store.load(), /private regular file/);
  assert.equal(readFileSync(file, 'utf8'), bytes);
});

test('symlink and hard-link state files are refused without following them', (t) => {
  const directory = temporary(t);
  const actual = join(directory, 'actual.json'); writeFileSync(actual, JSON.stringify(emptyState()), { mode: 0o600 });
  const file = join(directory, 'state.json'); symlinkSync(actual, file);
  assert.throws(() => new PrivateFileStore(file).load(), /private regular file/);
  const linked = join(directory, 'hard.json'); linkSync(actual, linked);
  assert.throws(() => new PrivateFileStore(linked).load(), /private regular file/);
});

test('invalid JSON and unknown schema fail closed while preserving recovery bytes', (t) => {
  const file = join(temporary(t), 'state.json');
  writeFileSync(file, '{corrupted', { mode: 0o600 });
  assert.throws(() => new PrivateFileStore(file).load(), /manual recovery/);
  assert.equal(readFileSync(file, 'utf8'), '{corrupted');
  writeFileSync(file, '{"version":99}');
  const f = syntheticFixture();
  assert.throws(() => new LocalBridge({ authorization: f.authorization, store: new PrivateFileStore(file) }), /schema is invalid/);
  assert.equal(readFileSync(file, 'utf8'), '{"version":99}');
});

test('failed persistence leaves in-memory request queue unchanged', () => {
  const f = syntheticFixture();
  const store = { load: emptyState, save: () => { throw new Error('Synthetic disk full.'); } };
  const bridge = new LocalBridge({ authorization: f.authorization, store, transport: f.receiver, clock: f.clock });
  assert.throws(() => bridge.createMessage(f.app, f.message()), /Synthetic disk full/);
  assert.deepEqual(bridge.listMessages(f.app, 'local-preview').messages, []);
});

test('private store size limit refuses writes without replacing the previous valid state', (t) => {
  const file = join(temporary(t), 'state.json');
  const store = new PrivateFileStore(file); store.save(emptyState());
  const previous = readFileSync(file, 'utf8');
  assert.throws(() => store.save({ tooLarge: 'x'.repeat(16 * 1024 * 1024) }), /capacity/);
  assert.equal(readFileSync(file, 'utf8'), previous);
});
