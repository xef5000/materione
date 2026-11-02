import assert from 'node:assert/strict';
import { test } from 'node:test';
import fs from 'node:fs';
import catalog from './catalog/guioverlay.mjs';
import dynamicCatalog from './catalog/guidynamic.mjs';
import { slotType } from './catalog/_lib.mjs';

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

test('slot registry query unwraps the item before the static lookup (simulated bridge)', async () => {
  const calls = [];
  const ctx = { exec: async (script) => {
    calls.push(script);
    // Model the bridge failure mode; this is not a native Minecraft execution.
    return script.includes('getKey(item.@target)') ? 'minecraft:diamond' : 'minecraft:air';
  } };
  assert.equal(await slotType(ctx, 11), 'minecraft:diamond');
  assert.match(calls[0], /slots\.get\(11\)\.getItem\(\)\.getItem\(\)/);
  assert.match(calls[0], /BuiltInRegistries\.ITEM\.getKey\(item\.@target\)/);
  await assert.rejects(slotType(ctx, -1), /nonnegative integer/);
  await assert.rejects(slotType(ctx, '11'), /nonnegative integer/);
  assert.equal(calls.length, 1);
});

test('dynamic visual contracts capture their screenshots and request a verdict', async (t) => {
  t.mock.method(fs, 'readFileSync', () => '');
  const cases = [
    ['guidynamic.conditional-item', ['dynamic-conditional-hidden', 'dynamic-conditional-shown']],
    ['guidynamic.viewer-head', ['dynamic-viewer-head']],
    ['guidynamic.animated-slot', ['dynamic-animated-first', 'dynamic-animated-second']],
  ];
  for (const [id, expectedShots] of cases) {
    let on = false;
    let frame = 0;
    const shots = [];
    const prompts = [];
    const ctx = {
      reset: async () => {},
      rcon: async (command) => { on = command.includes('on=1'); },
      waitForServerLog: async () => {},
      sleep: async () => {},
      shot: async (name) => { shots.push(name); },
      needsEyes: (prompt) => { prompts.push(prompt); },
      expect: (actual) => ({
        toBe: (expected) => assert.equal(actual, expected),
        not: { toBe: (expected) => assert.notEqual(actual, expected) },
      }),
      exec: async (script) => {
        if (script.includes('closeContainer')) return 'closed';
        const slot = Number(script.match(/slots\.get\((\d+)\)/)[1]);
        if (slot === 11) return on ? 'minecraft:diamond' : 'minecraft:air';
        if (slot === 13) return 'minecraft:player_head';
        if (slot === 15) return frame++ === 0 ? 'minecraft:redstone_block' : 'minecraft:emerald';
        throw new Error(`Unexpected slot ${slot}`);
      },
    };
    const entry = dynamicCatalog.find((item) => item.id === id);
    assert.equal(entry.mode, 'visual');
    await entry.steps(ctx);
    assert.deepEqual(shots, expectedShots);
    assert.equal(prompts.length, 1);
    for (const name of expectedShots) assert.ok(prompts[0].includes(name), `${id}: missing ${name}`);
  }
});
