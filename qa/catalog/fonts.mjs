// Fonts suite: what a player sees in unstyled text once a pack ships GUI text
// (issue 198). A GUI's ASCII offset fonts must stay out of minecraft:default, so
// vanilla labels keep vanilla glyphs. The probe server serves
// fixtures/packs/guitext (a glyph and a GUI with smallcaps and default text)
// next to the sample pack, so the vanilla-text check runs against a live pack
// that has both. Visual mode: the screenshot is judged by eyes, the entry
// records needs-eyes until a pass is marked.

import { test } from './_lib.mjs';

const FONT_COVERS = [
  'api/src/main/java/dev/materione/api/content/PackEmitter.java',
  'content/src/main/java/dev/materione/content/gui/**',
  'content/src/main/java/dev/materione/content/glyphs/**',
  'pack/src/main/java/dev/materione/pack/compile/PackCompiler.java',
  'fixtures/packs/guitext/**',
];

export default [
  test({
    id: 'fonts.guitext-pack-live',
    title: 'the guitext fixture pack (a glyph and a GUI with smallcaps and default text) compiles into the live pack with zero quarantined content, so the vanilla-text check below runs against a pack that has both glyph and GUI fonts',
    mode: 'auto',
    covers: FONT_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      // stage: a reload compiles the pack again inside this test's log window
      await ctx.rcon('materione reload');
      await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/, { timeoutMs: 90_000 });
      // no skipped GUI layer (MTR-0052) and no exhausted GUI background (MTR-0053);
      // serverLogLine throws when nothing matches, which is the passing case here
      let gui = null;
      try {
        gui = await ctx.serverLogLine(/MTR-005[23]/);
      } catch {
        gui = null;
      }
      ctx.expect(gui).toBe(null);
    },
  }),

  test({
    id: 'fonts.gui-text-leaves-vanilla-text',
    title: 'with the guitext pack live, the survival inventory keeps vanilla text: its "Crafting" label is drawn in the vanilla default font on the row above the crafting grid, not in GUI smallcaps glyphs and not shifted down onto the slot row',
    mode: 'visual',
    covers: FONT_COVERS,
    dependsOn: ['fonts.guitext-pack-live'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.exec('mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player.@target)); return mc.screen.getClass().getName()');
      ctx.expect(await ctx.exec('return mc.screen.getTitle().getString()')).toBe('Crafting');
      // join-time toasts cover the label for a few seconds
      await ctx.sleep(8000);
      await ctx.shot('vanilla-inventory-text');
      await ctx.exec('mc.setScreen(null)');
      ctx.needsEyes(
        'the "Crafting" label of the survival inventory is drawn as vanilla text: tall capitals and descenders, '
          + 'above the crafting grid at the panel top. Not the 3x5 smallcaps style, and not shifted down onto the top slot row. '
          + 'The toast text ("Move with W, A, S and D" and similar) renders as vanilla too.',
      );
    },
  }),
];
