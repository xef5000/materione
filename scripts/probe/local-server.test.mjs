import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { localServerOverride } from './local-server.mjs';

const jar = path.resolve('harness', 'build', 'folia', 'server.jar');
const sha = 'a'.repeat(64);
const io = { isFile: () => true, sha256: () => sha };

test('default remains pinned Paper without a local override', () => {
  assert.equal(localServerOverride(undefined, undefined, io), null);
});

test('override requires both an absolute file and an exact SHA-256', () => {
  assert.throws(() => localServerOverride(jar, undefined, io), /requires/);
  assert.throws(() => localServerOverride(undefined, sha, io), /requires/);
  assert.throws(() => localServerOverride('relative.jar', sha, io), /requires/);
  assert.throws(() => localServerOverride(jar, 'invalid', io), /requires/);
  assert.throws(() => localServerOverride(jar, sha, { ...io, isFile: () => false }), /not a file/);
  assert.throws(() => localServerOverride(jar, 'b'.repeat(64), io), /mismatch/);
  assert.deepEqual(localServerOverride(jar, sha.toUpperCase(), io), { jar, sha256: sha });
});
