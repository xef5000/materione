import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

/**
 * An explicitly selected, checksum-verified local probe server. No override means the pinned Paper
 * download remains in use. Injected file operations let the selection policy be tested without IO.
 */
export function localServerOverride(jar, expected, io = {
  isFile: (file) => fs.statSync(file).isFile(),
  sha256: (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'),
}) {
  if (jar === undefined && expected === undefined) return null;
  if (typeof jar !== 'string' || !path.isAbsolute(jar) || typeof expected !== 'string'
      || !/^[a-f0-9]{64}$/i.test(expected)) {
    throw new Error('local server override requires --server-jar <absolute-path> and --server-sha256 <64 hex digits>');
  }
  if (!io.isFile(jar)) throw new Error(`local server jar is not a file: ${jar}`);
  const actual = io.sha256(jar).toLowerCase();
  if (actual !== expected.toLowerCase()) {
    throw new Error(`local server SHA-256 mismatch: expected ${expected}, actual ${actual}`);
  }
  return { jar, sha256: actual };
}
