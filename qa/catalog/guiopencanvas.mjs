// The GUI open canvas (roadmap M6, docs/architecture/gui.md section 11, issues 274 and 276): art drawn outside
// the vanilla screen, and a GUI with no art at all. The `opencanvas` fixture pack holds both
// (fixtures/packs/opencanvas/guis): `offscreen` paints a panel left, right, above and below a 3-row chest plus
// a strip on the highest row the client draws (-243), so some tiles are padded and some start at negative
// rows; `plain` has no layer, so its pack output carries no PNG, no font provider and no glyph.
//
// Blessing is pending: these entries were authored with the compiler change and have not been run against a
// client (the probe is local-only, docs/probe.md). The auto entries read what the real client shows; the visual
// ones capture screenshots for the verdict.

import { test } from './_lib.mjs';

const covers = [
  'content/src/main/java/dev/materione/content/gui/**',
  'plugin/src/main/java/dev/materione/plugin/gui/**',
  'plugin/src/main/java/dev/materione/plugin/runtime/PipelineService.java',
  'fixtures/packs/opencanvas/**',
  'qa/catalog/_lib.mjs',
];

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

/** The container's title as the client resolved it, and how many private-use glyphs (art, shifts) it holds. */
async function screenTitle(ctx) {
  return String(await ctx.exec("return mc.screen == null ? '' : mc.screen.getTitle().getString()"));
}

function privateUseCount(text) {
  return [...text].filter((ch) => ch.codePointAt(0) >= 0xE000 && ch.codePointAt(0) <= 0xF8FF).length;
}

async function slotCount(ctx) {
  return Number(await ctx.exec('return player.containerMenu.slots.size()'));
}

export default [
  test({
    id: 'guiopencanvas.untextured-auto',
    title: 'opencanvas:plain (no layers) opens as a plain 2-row chest: 54 slots and the plain title with no glyph in it, because a GUI that paints nothing emits no background glyph',
    covers,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'opencanvas:plain', /MATERIONE-OPENCANVAS-PLAIN-OPEN/);
        ctx.expect(await slotCount(ctx)).toBe(54);
        const title = await screenTitle(ctx);
        ctx.expect(title).toBe('Plain chest');
        ctx.expect(privateUseCount(title)).toBe(0);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),
  test({
    id: 'guiopencanvas.untextured-visual',
    title: 'opencanvas:plain looks exactly like the client\'s own 2-row chest under the title "Plain chest": no flat grey, no missing glyph, the player inventory untouched',
    mode: 'visual',
    dependsOn: ['guiopencanvas.untextured-auto'],
    covers,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'opencanvas:plain', /MATERIONE-OPENCANVAS-PLAIN-OPEN/);
        await ctx.shot('plain-chest');
        ctx.needsEyes('A vanilla double-row chest with rounded corners and the title "Plain chest" in the usual dark grey; the player inventory below shows its normal slot grid. Nothing is drawn over the screen and no tofu box appears.');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),
  test({
    id: 'guiopencanvas.offscreen-auto',
    title: 'opencanvas:offscreen opens a 3-row chest (63 slots) whose title carries the art glyphs and the shifts that place them, and the server logs no GUI diagnostic for it',
    covers,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'opencanvas:offscreen', /MATERIONE-OPENCANVAS-OFFSCREEN-OPEN/);
        ctx.expect(await slotCount(ctx)).toBe(63);
        const title = await screenTitle(ctx);
        // at least one art tile and the shift characters between tiles and back to the origin
        ctx.expect(privateUseCount(title)).toBeGreaterThan(4);
        ctx.expect(ctx.serverLogText(/MTR-00(52|53|90|91|92)/)).toBe(null);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),
  test({
    id: 'guiopencanvas.offscreen-visual',
    title: 'opencanvas:offscreen draws a red panel left of the chest, a blue one right, a green one and a purple strip above, a yellow one below - outside the screen, not clipped - and the chest itself is untouched',
    mode: 'visual',
    dependsOn: ['guiopencanvas.offscreen-auto'],
    covers,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'opencanvas:offscreen', /MATERIONE-OPENCANVAS-OFFSCREEN-OPEN/);
        await ctx.shot('offscreen-art');
        ctx.needsEyes('Five beveled panels around a vanilla 3-row chest: red to its left, blue to its right, green and a thin purple strip above (the strip near the top of the window), yellow below. Each is whole (nothing cut at the screen edge), with sharp pixels and no seam where a panel crosses a 256 px tile boundary. The chest and the player inventory are the client\'s own and are not covered. No tofu boxes. If the window is small, art past the window edge is cut by the window, which is expected.');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),
];
