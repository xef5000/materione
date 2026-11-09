// The containers beyond the chest (roadmap M6, docs/architecture/gui.md section 12, row Q, issue 295): a hopper and a
// dispenser opened by the product on the real client. The GUIs are showcase:hopper and showcase:dispenser
// (fixtures/packs/showcase/guis), opened by name with `materione gui open`. The server announces each open, click and
// outside click on the console (`say MATERIONE-SHOWCASE-HOPPER-...` and `...-DISPENSER-...`), which these entries read as
// markers, and the client reports the menu it built, its slots and the title it drew.
//
// Written against the probe loop and NOT YET RUN on a real client: the blessing run (qa/README.md) happens on main, after
// merge. The auto entries judge what is observable (the menu, the slot count, the title origin, the slot ids and the
// dynamic title); the visual entries capture the screens for the verdict on the art placement.

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SERVER_LOG = path.join(ROOT, 'harness', 'build', 'probe', 'server', 'logs', 'latest.log');

const COVERS = [
  'fixtures/packs/showcase/guis/hopper.yml',
  'fixtures/packs/showcase/guis/dispenser.yml',
  'shared/gui/containers.json',
  'content/src/main/java/dev/materione/content/gui/**',
  'plugin/src/main/java/dev/materione/plugin/gui/**',
  'plugin/src/main/java/dev/materione/plugin/gui/event/**',
  'plugin/src/main/java/dev/materione/plugin/gui/input/**',
  'qa/catalog/guicontainers.mjs',
];

/** How many server-log lines match, since the server started (a marker is printed once per event). */
function logCount(regex) {
  return fs.readFileSync(SERVER_LOG, 'utf8').split('\n').filter((line) => regex.test(line)).length;
}

/** Waits until a marker has been printed one more time than `before` (an event may take a tick to reach the log). */
async function waitForMarker(ctx, regex, before) {
  for (let attempt = 0; attempt < 40; attempt++) {
    if (logCount(regex) > before) return;
    await ctx.sleep(150);
  }
  throw new Error(`no new line matching ${regex} within 6 s`);
}

/** Opens a showcase container by name, and waits for the server to announce it. */
async function openGui(ctx, id, marker) {
  await ctx.reset();
  await ctx.rcon(`materione gui open ${id} Probe`);
  await ctx.waitForServerLog(marker);
  await ctx.sleep(900); // the client finishes opening the window and loads the pack's fonts
}

async function closeWindow(ctx) {
  try {
    await ctx.exec('player.closeContainer(); return "closed"');
  } catch { /* nothing open */ }
}

/** The client's menu class and slot count: the menu the server's window type produced. */
async function menuOf(ctx) {
  return String(await ctx.exec(`return player.containerMenu.getClass().getSimpleName() + ' slots=' + player.containerMenu.slots.size()`));
}

/** The title the client drew for the open screen, as the client resolved it. */
async function screenTitle(ctx) {
  return String(await ctx.exec("return mc.screen == null ? '' : mc.screen.getTitle().getString()"));
}

/** A real container click from the client: button 0 is a left click, 1 a right click. */
function click(ctx, slot, button = 0) {
  return ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, ${button}, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
return 'clicked'`);
}

/** A click outside the window (slot -999, the cursor leaves the container) on the server's gui.click_outside. */
function clickOutside(ctx) {
  return ctx.exec("mc.gameMode.handleContainerInput(player.containerMenu.containerId, -999, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player); return 'clicked'");
}

export default [
  // --- the hopper: five plain slots, the dynamic title ------------------------------------------------------------------

  test({
    id: 'guicontainers.hopper-open-auto',
    title: 'showcase:hopper opens as a hopper: the client builds a HopperMenu of five container slots (41 slots with the player inventory), and the server announces the open',
    covers: COVERS,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'showcase:hopper', /MATERIONE-SHOWCASE-HOPPER-OPEN/);
        ctx.expect(await menuOf(ctx)).toBe('HopperMenu slots=41');
        ctx.expect(Number(await ctx.exec('return player.containerMenu.containerId'))).not.toBe(0);
        // the title is composed from the layers (art glyphs and the stock text), so only its presence is judged here
        ctx.expect(await screenTitle(ctx)).not.toBe('');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guicontainers.hopper-click-slots-auto',
    title: 'showcase:hopper: a click on each slot id (0 to 4) runs the rule of that slot once, and the server names the slot it was clicked on (slot=N, one line per click)',
    covers: COVERS,
    dependsOn: ['guicontainers.hopper-open-auto'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'showcase:hopper', /MATERIONE-SHOWCASE-HOPPER-OPEN/);
        for (let slot = 0; slot <= 4; slot++) {
          const marker = new RegExp(`MATERIONE-SHOWCASE-HOPPER-CLICK slot=${slot}$`);
          const before = logCount(marker);
          await click(ctx, slot);
          await waitForMarker(ctx, marker, before);
          ctx.expect(logCount(marker)).toBe(before + 1);
        }
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guicontainers.hopper-click-outside-auto',
    title: 'showcase:hopper: a click outside the window (slot -999) runs its gui.click_outside rule once, and the window stays open',
    covers: COVERS,
    dependsOn: ['guicontainers.hopper-open-auto'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'showcase:hopper', /MATERIONE-SHOWCASE-HOPPER-OPEN/);
        const marker = /MATERIONE-SHOWCASE-HOPPER-OUTSIDE left$/;
        const before = logCount(marker);
        await clickOutside(ctx);
        await waitForMarker(ctx, marker, before);
        ctx.expect(logCount(marker)).toBe(before + 1);
        ctx.expect(Number(await ctx.exec('return player.containerMenu.containerId'))).not.toBe(0);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guicontainers.hopper-dynamic-title-auto',
    title: 'showcase:hopper: the title text "Stock N" follows the server stock on the open hopper (refresh 20): setting the stock to 1 changes the client title with no reopen (same menu, no second open line)',
    covers: COVERS,
    dependsOn: ['guicontainers.hopper-open-auto'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'showcase:hopper', /MATERIONE-SHOWCASE-HOPPER-OPEN/);
        const before = await screenTitle(ctx);
        const containerBefore = await ctx.exec('return player.containerMenu.containerId');
        const opens = logCount(/MATERIONE-SHOWCASE-HOPPER-OPEN/);
        await ctx.rcon('materione var set - showcase:stock 1');
        let after = before;
        for (let attempt = 0; attempt < 20 && after === before; attempt++) {
          await ctx.sleep(300);
          after = await screenTitle(ctx);
        }
        ctx.expect(after).not.toBe(before);
        ctx.expect(await ctx.exec('return player.containerMenu.containerId')).toBe(containerBefore);
        ctx.expect(logCount(/MATERIONE-SHOWCASE-HOPPER-OPEN/)).toBe(opens);
      } finally {
        await ctx.rcon('materione var set - showcase:stock 3');
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guicontainers.hopper-visual',
    title: 'showcase:hopper draws as a hopper: the framed panel 176 x 133 with the five slot items in one row, the title "Stock 3" in the title row, and the client\'s own inventory below',
    mode: 'visual',
    dependsOn: ['guicontainers.hopper-open-auto'],
    covers: COVERS,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'showcase:hopper', /MATERIONE-SHOWCASE-HOPPER-OPEN/);
        await ctx.shot('hopper-art');
        ctx.needsEyes('A vanilla hopper window: five slots in one row near the top with the emerald, gold ingot, iron ingot, redstone and lapis lazuli in slots 1 to 5 (the emerald is slot 0 on the left), the framed panel behind them, and the title row reading "Stock 3" in the usual dark grey at the top-left. The player inventory is drawn below by the client, unobstructed by the panel. The window is not cut at its edges, and no tofu box or missing glyph appears.');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  // --- the dispenser: nine slots in a 3 x 3 grid, the centred title ---------------------------------------------------

  test({
    id: 'guicontainers.dispenser-open-auto',
    title: 'showcase:dispenser opens as a dispenser: the client builds a DispenserMenu of nine container slots (45 slots with the player inventory), and the title origin is 8 (the compiled title keeps the pen at x 8 with its trailing shift, gui.md section 12.5.3)',
    covers: COVERS,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'showcase:dispenser', /MATERIONE-SHOWCASE-DISPENSER-OPEN/);
        ctx.expect(await menuOf(ctx)).toBe('DispenserMenu slots=45');
        ctx.expect(Number(await ctx.exec('return mc.screen.titleLabelX'))).toBe(8);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guicontainers.dispenser-click-slots-auto',
    title: 'showcase:dispenser: a click on each slot id (0 to 8) runs the rule of that slot once, and the server names the slot it was clicked on (slot=N, one line per click)',
    covers: COVERS,
    dependsOn: ['guicontainers.dispenser-open-auto'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'showcase:dispenser', /MATERIONE-SHOWCASE-DISPENSER-OPEN/);
        for (let slot = 0; slot <= 8; slot++) {
          const marker = new RegExp(`MATERIONE-SHOWCASE-DISPENSER-CLICK slot=${slot}$`);
          const before = logCount(marker);
          await click(ctx, slot);
          await waitForMarker(ctx, marker, before);
          ctx.expect(logCount(marker)).toBe(before + 1);
        }
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guicontainers.dispenser-click-outside-auto',
    title: 'showcase:dispenser: a click outside the window (slot -999) runs its gui.click_outside rule once, and the window stays open',
    covers: COVERS,
    dependsOn: ['guicontainers.dispenser-open-auto'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'showcase:dispenser', /MATERIONE-SHOWCASE-DISPENSER-OPEN/);
        const marker = /MATERIONE-SHOWCASE-DISPENSER-OUTSIDE left$/;
        const before = logCount(marker);
        await clickOutside(ctx);
        await waitForMarker(ctx, marker, before);
        ctx.expect(logCount(marker)).toBe(before + 1);
        ctx.expect(Number(await ctx.exec('return player.containerMenu.containerId'))).not.toBe(0);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guicontainers.dispenser-visual',
    title: 'showcase:dispenser draws as a dispenser: the framed panel 176 x 166, the nine slot items in a 3 x 3 grid, and the title "DISPENSER" at the title row, with the client\'s own inventory below',
    mode: 'visual',
    dependsOn: ['guicontainers.dispenser-open-auto'],
    covers: COVERS,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'showcase:dispenser', /MATERIONE-SHOWCASE-DISPENSER-OPEN/);
        await ctx.shot('dispenser-art');
        ctx.needsEyes('A vanilla dispenser window: a 3 x 3 grid of nine slots in the middle (items in every slot, slot 0 top-left and slot 8 bottom-right) on the framed panel, and the title "DISPENSER" at the top-left in the usual dark grey (not centred, the compiled title keeps its origin). The player inventory is drawn below, unobstructed. The window is not cut at its edges, and no tofu box or missing glyph appears.');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),
];
