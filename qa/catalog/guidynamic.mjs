// Dynamic GUI slots (roadmap M6, docs/architecture/logic.md §6.3, §24, issue 216): a conditional item, the
// viewer's own head and an animated slot, shown by a real client. The guilogic fixture pack holds the
// `guilogic:dynamic` GUI (fixtures/packs/guilogic/guis/dynamic.yml). Each entry reads the container the client
// shows (the slot's item type) and takes screenshots for the visual verdict; the server console marker
// `MATERIONE-GUILOGIC-DYNAMIC-OPEN` confirms which GUI and argument opened.

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SERVER_LOG = path.join(ROOT, 'harness', 'build', 'probe', 'server', 'logs', 'latest.log');

const COVERS = [
  'plugin/src/main/java/dev/materione/plugin/gui/**',
  'plugin/src/main/java/dev/materione/plugin/logic/item/**',
  'content/src/main/java/dev/materione/content/gui/**',
  'content/src/main/java/dev/materione/content/gui/item/**',
  'fixtures/packs/guilogic/guis/dynamic.yml',
];

/** The item type the open container shows in a slot, as the client holds it (for example DIAMOND, AIR). */
async function slotType(ctx, slot) {
  return String(await ctx.exec(
    `return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(` +
    `player.containerMenu.slots.get(${slot}).getItem().getItem()).toString()`));
}

async function openDynamic(ctx, args) {
  await ctx.reset();
  await ctx.rcon(`materione gui open guilogic:dynamic Probe ${args}`);
  await ctx.waitForServerLog(/MATERIONE-GUILOGIC-DYNAMIC-OPEN guilogic:dynamic/);
  await ctx.sleep(600); // the client finishes opening the window
}

async function closeWindow(ctx) {
  try {
    await ctx.exec('player.closeContainer(); return "closed"');
  } catch { /* nothing open */ }
}

/** How many server-log lines match, since the server started. */
function logCount(regex) {
  return fs.readFileSync(SERVER_LOG, 'utf8').split('\n').filter((line) => regex.test(line)).length;
}

export default [
  test({
    id: 'guidynamic.open-states',
    title: 'guilogic:dynamic opens with its three dynamic slots as the client shows them: the diamond in slot 11 only with on=1, the viewer\'s head in slot 13, and one of the two animation frames in slot 15; no barrier or condition failure is logged',
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openDynamic(ctx, 'on=0');
        ctx.expect(await slotType(ctx, 11)).toBe('minecraft:air');
        await openDynamic(ctx, 'on=1');
        ctx.expect(await slotType(ctx, 11)).toBe('minecraft:diamond');
        ctx.expect(await slotType(ctx, 13)).toBe('minecraft:player_head');
        ctx.expect(['minecraft:redstone_block', 'minecraft:emerald'].includes(await slotType(ctx, 15))).toBe(true);
        ctx.expect(logCount(/MTR-007[23]/)).toBe(0); // no unresolved source and no failed condition
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guidynamic.conditional-item',
    title: 'guilogic:dynamic opened with on=0 shows no diamond in slot 11, and reopened with on=1 shows one (visible_if on an item layer is evaluated when the window renders)',
    mode: 'visual',
    dependsOn: ['guidynamic.open-states'],
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openDynamic(ctx, 'on=0');
        ctx.expect(await slotType(ctx, 11)).toBe('minecraft:air');
        await ctx.shot('dynamic-conditional-hidden');

        await openDynamic(ctx, 'on=1');
        ctx.expect(await slotType(ctx, 11)).toBe('minecraft:diamond');
        await ctx.shot('dynamic-conditional-shown');
        ctx.expect(logCount(/MTR-0073/)).toBe(0); // a failing condition would have been reported
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guidynamic.viewer-head',
    title: 'guilogic:dynamic shows the viewer\'s own head in slot 13 (a head: {player: "${player.name}"} source resolves for the player who opened the window, and it is named after them)',
    mode: 'visual',
    dependsOn: ['guidynamic.open-states'],
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openDynamic(ctx, 'on=1');
        ctx.expect(await slotType(ctx, 13)).toBe('minecraft:player_head');
        ctx.expect(logCount(/MTR-0072/)).toBe(0); // the probe player is online, so the head resolves
        await ctx.shot('dynamic-viewer-head');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guidynamic.animated-slot',
    title: 'the animated slot of guilogic:dynamic in slot 15 changes item while the window is open (the frames of every 20 ticks, on the window\'s clock), first a redstone block, then an emerald',
    mode: 'visual',
    dependsOn: ['guidynamic.open-states'],
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openDynamic(ctx, 'on=1');
        const first = await slotType(ctx, 15);
        await ctx.shot('dynamic-animated-first');
        // two frames at 20 ticks each: the clock turns the frame over within a second or two
        let second = first;
        for (let attempt = 0; attempt < 8 && second === first; attempt++) {
          await ctx.sleep(500);
          second = await slotType(ctx, 15);
        }
        await ctx.shot('dynamic-animated-second');
        const frames = ['minecraft:redstone_block', 'minecraft:emerald'];
        ctx.expect(frames.includes(first) && frames.includes(second)).toBe(true);
        ctx.expect(second).not.toBe(first);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),
];
