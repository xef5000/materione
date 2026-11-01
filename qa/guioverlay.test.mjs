import assert from 'node:assert/strict';
import { test } from 'node:test';
import catalog from './catalog/guioverlay.mjs';

function fakeContext(changeId) {
  let visible = false;
  let cursor = false;
  let id = 42;
  return {
    reset: async () => {},
    rcon: async () => {},
    waitForServerLog: async () => {},
    sleep: async () => {},
    expect: (actual) => ({ toBe: (expected) => assert.equal(actual, expected) }),
    exec: async (script) => {
      if (script.includes('player.closeContainer()')) return 'closed';
      if (script.includes("mc.screen == null ? ''")) return visible ? 'ACTIVE Probe' : 'background';
      if (script.includes("player.containerMenu.slots.size()")) return `true:${id}:63`;
      if (script.includes('getHoverName()')) return cursor ? 'Red Gem:3' : 'empty';
      if (script.includes('containerId, 54,')) cursor = true;
      if (script.includes('containerId, 11,')) {
        visible = !visible;
        if (changeId) id++;
      }
      return 'clicked';
    },
  };
}

test('toggle-auto records the original container ID across both title changes', async () => {
  const entry = catalog.find((item) => item.id === 'guioverlay.toggle-auto');
  await entry.steps(fakeContext(false));
  await assert.rejects(entry.steps(fakeContext(true)), assert.AssertionError);
});
