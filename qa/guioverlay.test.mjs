import assert from 'node:assert/strict';
import { test } from 'node:test';
import catalog from './catalog/guioverlay.mjs';

function fakeContext(changeId) {
  let visible = false;
  let requested = false;
  let cursor = false;
  let id = 42;
  function apply() {
    if (!cursor && visible !== requested) {
      visible = requested;
      if (changeId) id++;
    }
  }
  return {
    reset: async () => {},
    rcon: async () => {},
    waitForServerLog: async () => {},
    sleep: async () => {},
    expect: (actual) => ({ toBe: (expected) => assert.equal(actual, expected) }),
    exec: async (script) => {
      if (script.includes('player.closeContainer()')) return 'closed';
      if (script.includes("mc.screen == null ? ''")) return visible ? 'ACTIVE Probe' : 'background';
      if (script.includes("player.containerMenu.slots.size()")) {
        assert.match(script, /String\.valueOf\(mc\.screen != null\)/);
        return `true:${id}:63`;
      }
      if (script.includes('getHoverName()')) return cursor ? 'Red Gem:3' : 'empty';
      if (script.includes('containerId, 54,')) {
        cursor = !cursor;
        apply();
      }
      if (script.includes('containerId, 11,')) {
        requested = !requested;
        apply();
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

test('quick-craft conservation query counts carried and indexed menu stacks', () => {
  const entry = catalog.find((item) => item.id === 'guioverlay.animation-quick-craft');
  const source = entry.steps.toString();
  assert.match(source, /def carried = player\.containerMenu\.getCarried\(\)/);
  assert.match(source, /for \(int i = 0; i < player\.containerMenu\.slots\.size\(\); i\+\+\)/);
  assert.match(source, /player\.containerMenu\.slots\.get\(i\)\.getItem\(\)/);
  assert.doesNotMatch(source, /for \(def s : stacks\)/);
});
