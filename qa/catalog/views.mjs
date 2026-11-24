// Views, row V0 (docs/architecture/views.md, issue 455): the container GUI is the first view kind, and one verb set
// serves it: view.open, view.close and view.back replace gui.open, gui.close and gui.back, the viewer record that
// replaced GuiSession answers the placeholders %materione_view_open% and %materione_view_last%, and view.id is the
// one root for view facts. The guilogic fixture pack (fixtures/packs/guilogic) holds the GUIs; its menu and detail
// announce every open and close on the server console (`say MATERIONE-GUILOGIC-...`, whose open marker prints
// ${view.id} and ${view.args.*}), which these tests read as markers beside what the client shows.
//
// Not run when written (needs the probe); the entries are auto assertions blessed on main.

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SERVER_LOG = path.join(ROOT, 'harness', 'build', 'probe', 'server', 'logs', 'latest.log');

/** How many server-log lines match, since the server started (a marker is printed once per event). */
function logCount(regex) {
  return fs.readFileSync(SERVER_LOG, 'utf8').split('\n').filter((line) => regex.test(line)).length;
}

/** The name of the stack in a container slot, or 'empty'. */
async function slotName(ctx, slot) {
  return String(await ctx.exec(`def s = player.containerMenu.slots.get(${slot}).getItem()
return s.isEmpty() ? 'empty' : s.getHoverName().getString()`));
}

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

  test({
    id: 'views.session.declared-and-held-by-one-window',
    title: 'overlay:tab is a declared session variable (fixtures/packs/overlay/variables/windows.yml): /materione var list shows it as per open view, /materione var get and var set refuse it because it lives in one open window, and the open overlay:tabs window\'s own toggle button (slot 11, set_var session.tab through the session store) still runs its rule to the console marker',
    covers: [...COVERS, 'fixtures/packs/overlay/**', 'plugin/src/main/java/dev/materione/plugin/command/VarCommand.java'],
    dependsOn: ['guilogic.open-and-back'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await ctx.reset();
        ctx.expect(await ctx.rcon('materione var list')).toContain('overlay:tab');
        ctx.expect(await ctx.rcon('materione var list')).toContain('per open view');
        ctx.expect(await ctx.rcon('materione var get - overlay:tab')).toContain('session variable');
        ctx.expect(await ctx.rcon('materione var set - overlay:tab true')).toContain('session variable');

        await ctx.rcon('materione gui open overlay:tabs Probe');
        await ctx.waitForServerLog(/MATERIONE-OVERLAY-OPEN/);
        await ctx.sleep(600);
        await click(ctx, 11); // set_var {var: session.tab, op: toggle}
        await ctx.waitForServerLog(/MATERIONE-OVERLAY-TOGGLED/);
        ctx.expect(await containerOpen(ctx)).toBe(true); // the window survived its own session write
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'views.server-var.live-single',
    title: 'guilogic:live has no refresh clock and no gui.refresh rule: with it open, slot 11 reads "Wave calm"; the console command `materione var set - guilogic:wave storm` changes slot 11 to "Wave storm" with no click and no reopen (the open marker prints once); resetting the variable changes it back',
    covers: [...COVERS, 'fixtures/packs/guilogic/**'],
    dependsOn: ['guilogic.open-and-back'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        await ctx.reset();
        await ctx.rcon('materione var set - guilogic:wave calm');
        await ctx.rcon('materione gui open guilogic:live Probe');
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-LIVE-OPEN/);
        await ctx.sleep(600);
        ctx.expect(await slotName(ctx, 11)).toBe('Wave calm');

        const opens = logCount(/MATERIONE-GUILOGIC-LIVE-OPEN/);
        await ctx.rcon('materione var set - guilogic:wave storm');
        let name = await slotName(ctx, 11);
        for (let attempt = 0; attempt < 20 && name !== 'Wave storm'; attempt++) {
          await ctx.sleep(150);
          name = await slotName(ctx, 11);
        }
        ctx.expect(name).toBe('Wave storm');
        ctx.expect(logCount(/MATERIONE-GUILOGIC-LIVE-OPEN/)).toBe(opens);
        ctx.expect(await containerOpen(ctx)).toBe(true);
      } finally {
        await ctx.rcon('materione var set - guilogic:wave calm');
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'views.session-toggle.rerenders-window',
    title: 'guilogic:live: slot 13 is empty at first; a click on the lever (slot 15) toggles the window\'s own session variable (the toggle marker prints once) and the diamond appears in slot 13 with no gui.refresh in the rule; a second click hides it again',
    covers: [...COVERS, 'fixtures/packs/guilogic/**'],
    dependsOn: ['guilogic.open-and-back'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        await ctx.reset();
        await ctx.rcon('materione gui open guilogic:live Probe');
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-LIVE-OPEN/);
        await ctx.sleep(600);
        ctx.expect(await slotName(ctx, 13)).toBe('empty');

        const toggles = logCount(/MATERIONE-GUILOGIC-LIVE-TOGGLED/);
        await click(ctx, 15);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-LIVE-TOGGLED/);
        let name = await slotName(ctx, 13);
        for (let attempt = 0; attempt < 20 && name !== 'Shown'; attempt++) {
          await ctx.sleep(150);
          name = await slotName(ctx, 13);
        }
        ctx.expect(name).toBe('Shown');
        ctx.expect(logCount(/MATERIONE-GUILOGIC-LIVE-TOGGLED/)).toBe(toggles + 1);

        await click(ctx, 15);
        name = await slotName(ctx, 13);
        for (let attempt = 0; attempt < 20 && name !== 'empty'; attempt++) {
          await ctx.sleep(150);
          name = await slotName(ctx, 13);
        }
        ctx.expect(name).toBe('empty');
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
