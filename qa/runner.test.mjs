import assert from 'node:assert/strict';
import test from 'node:test';
import { makeExpect, QaAssertionError } from './runner.mjs';

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
