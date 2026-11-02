import assert from 'node:assert/strict';
import fs from 'node:fs';
import { test } from 'node:test';
import { commandBody } from '../scripts/probe/command.mjs';
import { typedChat } from './catalog/_lib.mjs';

test('bridge command normalization accepts one optional slash and rejects malformed input', () => {
  for (const input of ['opentest tools', '/opentest tools', ' /opentest tools ']) {
    assert.equal(commandBody(input), 'opentest tools');
  }
  assert.equal(commandBody('test --option'), 'test --option');
  for (const input of ['', ' ', '/', '//opentest', '/ tools', 'test\nother', null, 2]) {
    assert.throws(() => commandBody(input), /command must/);
  }
});

function fakeContext(results) {
  const scripts = [];
  const shots = [];
  let sleeps = 0;
  return {
    scripts, shots,
    get sleeps() { return sleeps; },
    exec: async (script) => {
      scripts.push(script);
      if (script.includes('.parse(')) return results.shift() ?? 'remaining=;exceptions=0';
      if (script.includes('keyPressed(')) return true;
      return 'typed';
    },
    sleep: async () => { sleeps++; },
    shot: async (name) => { shots.push(name); },
    expect: (actual) => ({ toBe: (expected) => assert.equal(actual, expected) }),
  };
}

test('typed chat waits for the dynamic tree, then submits once through ChatScreen', async () => {
  const ctx = fakeContext(['remaining=qa_auto_second;exceptions=1', 'remaining=;exceptions=0']);
  await typedChat(ctx, '/qa_auto_second', 'input', { waitForTree: true });
  assert.equal(ctx.scripts.filter((s) => s.includes('.parse(')).length, 2);
  assert.match(ctx.scripts[0], /\.parse\("qa_auto_second",/);
  assert.match(ctx.scripts[2], /ChatScreen\("\/qa_auto_second", false\)/);
  assert.match(ctx.scripts[3], /keyPressed\(new net\.minecraft\.client\.input\.KeyEvent\(257, 0, 0\)\)/);
  assert.deepEqual(ctx.shots, ['input']);
  assert.equal(ctx.sleeps, 2);
});

test('typed chat refuses an unparseable command without submitting it', async () => {
  const ctx = fakeContext(['remaining=bad;exceptions=1']);
  await assert.rejects(typedChat(ctx, 'bad'), assert.AssertionError);
  assert.equal(ctx.scripts.length, 1);
});

test('typed chat stops waiting when a refreshed alias never reaches the client', async () => {
  const ctx = fakeContext(Array(40).fill('remaining=qa_auto_second;exceptions=1'));
  await assert.rejects(typedChat(ctx, 'qa_auto_second', undefined, { waitForTree: true }), assert.AssertionError);
  assert.equal(ctx.scripts.length, 40);
  assert.equal(ctx.sleeps, 39);
});

test('detail fixture declares the close marker that the back contract observes', () => {
  const detail = fs.readFileSync(new URL('../fixtures/packs/guilogic/guis/detail.yml', import.meta.url), 'utf8');
  assert.match(detail, /    - on: gui\.close\r?\n      do:\r?\n        - run_command:\r?\n            command: "say MATERIONE-GUILOGIC-CLOSE \$\{gui\.id\} reason=\$\{close\.reason\}"\r?\n            as: console/);
  assert.match(detail, /    - on: gui\.click\r?\n      layer: up\r?\n      do:\r?\n        - gui\.back/);
});
