// Views, row V0 (docs/architecture/views.md, issue 455): the container GUI is the first view kind, and one verb set
// serves it: view.open, view.close and view.back replace gui.open, gui.close and gui.back, the viewer record that
// replaced GuiSession answers the placeholders %materione_view_open% and %materione_view_last%, and view.id is the
// one root for view facts. The guilogic fixture pack (fixtures/packs/guilogic) holds the GUIs; its menu and detail
// announce every open and close on the server console (`say MATERIONE-GUILOGIC-...`, whose open marker prints
// ${view.id} and ${view.args.*}), which these tests read as markers beside what the client shows.
//
// Not run when written (needs the probe); the entries are auto assertions blessed on main.

import { test } from './_lib.mjs';

const COVERS = [
  'plugin/src/main/java/dev/materione/plugin/view/**',
  'plugin/src/main/java/dev/materione/plugin/gui/**',
  'plugin/src/main/java/dev/materione/plugin/logic/action/view/**',
  'plugin/src/main/java/dev/materione/plugin/logic/variable/MaterionePlaceholders.java',
  'integrations/src/main/java/dev/materione/integrations/variables/VariablePlaceholders.java',
  'fixtures/packs/guilogic/**',
];

/** A real container click from the client: button 0 is a left click. */
function click(ctx, slot, button = 0) {
  return ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, ${button}, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
return 'clicked'`);
}

async function containerOpen(ctx) {
  return Number(await ctx.exec('return player.containerMenu.containerId')) !== 0;
}

async function openSlots(ctx) {
  return Number(await ctx.exec('return player.containerMenu.slots.size()'));
}

async function closeWindow(ctx) {
  try {
    await ctx.exec('player.closeContainer(); return "closed"');
  } catch { /* nothing open */ }
}

export default [
  test({
    id: 'views.verbs.open-replace-back-close',
    title: 'on guilogic:menu (page=2) the open button (slot 13) runs view.open and replaces the window with guilogic:detail (view.id and view.args read item=sword from=Probe in its open marker), the detail\'s up button (slot 4) runs view.back and the menu returns with page=2, and the menu\'s close button (slot 15) runs view.close: the window closes with reason=action',
    covers: COVERS,
    dependsOn: ['guilogic.open-and-back'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await ctx.reset();
        await ctx.rcon('materione gui open guilogic:menu Probe page=2');
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-OPEN guilogic:menu page=2/);
        await ctx.sleep(600);

        await click(ctx, 13); // view.open: {view: detail, args: {...}}
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-OPEN guilogic:detail item=sword from=Probe/);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-CLOSE guilogic:menu reason=replaced/);
        await ctx.sleep(600);
        ctx.expect(await openSlots(ctx)).toBe(45); // the detail: one row

        await click(ctx, 4); // view.back
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-CLOSE guilogic:detail reason=replaced/);
        await ctx.sleep(800);
        ctx.expect(await openSlots(ctx)).toBe(63); // the menu again, with the arguments it was opened with

        await click(ctx, 15); // view.close
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-CLOSE guilogic:menu reason=action/);
        await ctx.sleep(400);
        ctx.expect(await containerOpen(ctx)).toBe(false);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  // Requires the public PlaceholderAPI plugin installed in the disposable probe server.
  test({
    id: 'views.placeholders.open-and-last',
    title: 'with guilogic:menu open, %materione_view_open% reads gui:guilogic:menu; after the open button replaces it with guilogic:detail, it reads gui:guilogic:detail and %materione_view_last% reads gui:guilogic:menu; with nothing open it reads empty',
    covers: COVERS,
    dependsOn: ['guilogic.open-and-back'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        ctx.expect(await ctx.rcon('plugins')).toContain('PlaceholderAPI');
        await ctx.reset();
        await ctx.rcon('materione gui open guilogic:menu Probe page=2');
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-OPEN guilogic:menu page=2/);
        await ctx.sleep(600);
        ctx.expect(await ctx.rcon('papi parse Probe %materione_view_open%')).toContain('gui:guilogic:menu');

        await click(ctx, 13);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-OPEN guilogic:detail item=sword from=Probe/);
        await ctx.sleep(600);
        ctx.expect(await ctx.rcon('papi parse Probe %materione_view_open%')).toContain('gui:guilogic:detail');
        ctx.expect(await ctx.rcon('papi parse Probe %materione_view_last%')).toContain('gui:guilogic:menu');
      } finally {
        await closeWindow(ctx);
      }
      await ctx.sleep(600);
      ctx.expect((await ctx.rcon('papi parse Probe %materione_view_open%')).trim()).not.toContain('gui:');
    },
  }),
];
