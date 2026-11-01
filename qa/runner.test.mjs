import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { environmentFingerprint, makeExpect, QaAssertionError, visualVerdict } from './runner.mjs';

const pins = {
  'paper-probe-version': '26.1.2',
  'paper-probe-build': '74',
  'paper-probe-url': 'https://example.test/paper.jar',
  'paper-probe-sha256': 'server-hash',
  'probe-debugbridge-url': 'https://example.test/bridge.jar',
  'probe-debugbridge-sha1': 'bridge-hash',
  'probe-fabric-loader': '0.19.5',
};

function scratch(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'materione-qa-env-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}

test('structured equality ignores mapping order but preserves values, keys and array order', () => {
  const actual = { do: [{ message: 'saved', node: { y: 80, x: 60 } }], on: 'gui.open' };
  makeExpect(actual).toEqual({ on: 'gui.open', do: [{ node: { x: 60, y: 80 }, message: 'saved' }] });
  for (const expected of [
    { ...actual, on: 'gui.close' },
    { ...actual, extra: true },
    { on: 'gui.open' },
  ]) {
    assert.throws(() => makeExpect(actual).toEqual(expected), QaAssertionError);
  }
  assert.throws(() => makeExpect([1, 2]).toEqual([2, 1]), QaAssertionError);
  assert.throws(() => makeExpect({ value: 1 }).toEqual({ value: '1' }), QaAssertionError);
});

test('negative identity assertions distinguish the default and open container', () => {
  makeExpect(1).not.toBe(0);
  assert.throws(() => makeExpect(0).not.toBe(0), {
    constructor: QaAssertionError, operator: 'not.toBe', expected: 0, actual: 0,
  });
  assert.throws(() => makeExpect(1).toBe(0), QaAssertionError);
});

test('negative structured equality uses the same mapping semantics', () => {
  makeExpect([0, 0, 0, 0]).not.toEqual([255, 0, 0, 255]);
  assert.throws(() => makeExpect({ x: 1, y: 2 }).not.toEqual({ y: 2, x: 1 }), {
    constructor: QaAssertionError, operator: 'not.toEqual',
  });
});

test('default environment keeps the pin-only key and excludes the rebuilt Materione jar', (t) => {
  const dir = scratch(t);
  const expected = crypto.createHash('sha256').update(JSON.stringify(pins)).digest('hex').slice(0, 12);
  assert.deepEqual(environmentFingerprint(pins, dir), { key: expected, plugins: [] });
  fs.writeFileSync(path.join(dir, 'Materione.jar'), 'first build');
  assert.equal(environmentFingerprint(pins, dir).key, expected);
  fs.writeFileSync(path.join(dir, 'Materione.jar'), 'second build');
  fs.writeFileSync(path.join(dir, 'config.yml'), 'not a runtime jar');
  fs.mkdirSync(path.join(dir, 'directory.jar'));
  assert.equal(environmentFingerprint(pins, dir).key, expected);
  assert.equal(environmentFingerprint(pins, path.join(dir, 'not-prepared')).key, expected);
});

test('adding, replacing and removing an optional runtime jar invalidates the environment', (t) => {
  const dir = scratch(t);
  const baseline = environmentFingerprint(pins, dir).key;
  const jar = path.join(dir, 'PlaceholderAPI.jar');
  fs.writeFileSync(jar, 'version one');
  const snapshot = environmentFingerprint(pins, dir);
  const installed = snapshot.key;
  assert.deepEqual(snapshot.plugins, [{
    name: 'PlaceholderAPI.jar',
    sha256: crypto.createHash('sha256').update('version one').digest('hex'),
  }]);
  assert.notEqual(installed, baseline);
  fs.writeFileSync(jar, 'version two');
  assert.notEqual(environmentFingerprint(pins, dir).key, installed);
  assert.equal(snapshot.key, installed);
  assert.equal(snapshot.plugins[0].sha256, crypto.createHash('sha256').update('version one').digest('hex'));
  fs.rmSync(jar);
  assert.equal(environmentFingerprint(pins, dir).key, baseline);
});

test('optional jar fingerprints are deterministic regardless of creation order', (t) => {
  const first = scratch(t);
  const second = scratch(t);
  for (const name of ['z.jar', 'a.jar']) fs.writeFileSync(path.join(first, name), name);
  for (const name of ['a.jar', 'z.jar']) fs.writeFileSync(path.join(second, name), name);
  assert.deepEqual(environmentFingerprint(pins, first), environmentFingerprint(pins, second));
  assert.throws(() => environmentFingerprint(pins, path.join(first, 'a.jar')), { code: 'ENOTDIR' });
  assert.throws(() => environmentFingerprint({}, first), /missing.*probe pin/);
});

test('visual verdict preserves the tested commit, environment, definition, time and dirty flag', () => {
  const captured = {
    sha: 'tested-commit', dirty: false, env: 'tested-environment', def: 'tested-definition',
    at: '2026-01-01T00:00:00.000Z', run: 'captured-run', result: 'visual', eyes: 'Inspect the title',
    runtimePlugins: [{ name: 'PlaceholderAPI.jar', sha256: 'captured-jar' }],
  };
  const marked = visualVerdict(captured, 'pass', {
    by: 'reviewer', note: 'Title inspected', at: '2026-01-02T00:00:00.000Z',
  });
  assert.deepEqual(marked, {
    ...captured, result: 'pass', markedAt: '2026-01-02T00:00:00.000Z',
    markedBy: 'reviewer', note: 'Title inspected',
  });
  assert.equal(captured.result, 'visual');
  assert.equal(visualVerdict({ ...captured, dirty: true }, 'pass', { by: 'reviewer' }).dirty, true);
});
