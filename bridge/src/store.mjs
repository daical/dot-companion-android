import {
  closeSync, fsyncSync, lstatSync, mkdirSync, openSync, readFileSync,
  renameSync, unlinkSync, writeFileSync,
} from 'node:fs';
import { dirname, join } from 'node:path';
import { randomUUID } from 'node:crypto';

export const emptyState = () => ({ version: 1, messages: [], subscriptions: [], deliveries: [] });

export class MemoryStore {
  #state;
  constructor(state = emptyState()) { this.#state = structuredClone(state); }
  load() { return structuredClone(this.#state); }
  save(state) { this.#state = structuredClone(state); }
}

/** A single-process development store. Multi-process access is unsupported. */
export class PrivateFileStore {
  constructor(file) {
    this.file = file;
    this.directory = dirname(file);
    mkdirSync(this.directory, { recursive: true, mode: 0o700 });
    const dir = lstatSync(this.directory);
    if (!dir.isDirectory() || dir.isSymbolicLink() || (dir.mode & 0o077)) {
      throw new Error('Runtime directory must be private (0700), with no symlink.');
    }
  }

  load() {
    let stat;
    try { stat = lstatSync(this.file); } catch (error) {
      if (error.code === 'ENOENT') return emptyState();
      throw new Error('Unable to inspect local state.');
    }
    if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1
        || (stat.mode & 0o077) || stat.size > 16 * 1024 * 1024) {
      throw new Error('Local state must be a private regular file (0600).');
    }
    try { return JSON.parse(readFileSync(this.file, 'utf8')); } catch {
      throw new Error('Local state is invalid; preserve it for manual recovery.');
    }
  }

  save(state) {
    const bytes = Buffer.from(JSON.stringify(state));
    if (bytes.length > 16 * 1024 * 1024) throw new Error('Local state capacity reached.');
    const temporary = join(this.directory, `.state-${randomUUID()}.tmp`);
    let descriptor;
    try {
      descriptor = openSync(temporary, 'wx', 0o600);
      writeFileSync(descriptor, bytes);
      fsyncSync(descriptor);
      closeSync(descriptor);
      descriptor = undefined;
      renameSync(temporary, this.file);
      // Make the rename durable as well as the file contents.
      const directoryDescriptor = openSync(this.directory, 'r');
      try { fsyncSync(directoryDescriptor); } finally { closeSync(directoryDescriptor); }
    } catch {
      if (descriptor !== undefined) closeSync(descriptor);
      try { unlinkSync(temporary); } catch { /* Preserve the existing state. */ }
      throw new Error('Unable to persist local state.');
    }
  }
}
