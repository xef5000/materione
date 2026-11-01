// GUI logic, the first slice (roadmap M6, docs/architecture/logic.md §6, issue 214): a GUI's rules answer
// real clicks from a real client. The guilogic fixture pack (fixtures/packs/guilogic) holds its GUIs; the
// trade GUI carries the input slots of logic.md §25 (issue 218). The first slice's menu has three buttons in its
// middle row (ping, open, close at slots 11, 13 and 15) and announces every
// open and close on the server console (`say MATERIONE-GUILOGIC-...`), which these tests read as markers
// next to what the client shows. Clicks are real container clicks sent by the client
// (`handleContainerInput`), so the whole path runs: the click packet, the policy table, the click router,
// the flow, the action. The auto entries judge markers, chat and the open container; the visual entry
// captures what the player sees.

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SERVER_LOG = path.join(ROOT, 'harness', 'build', 'probe', 'server', 'logs', 'latest.log');
const MENU_FILE = path.join(ROOT, 'harness', 'build', 'probe', 'server', 'plugins', 'Materione', 'content',
  'packs', 'guilogic', 'guis', 'menu.yml');

const COVERS = [
  'plugin/src/main/java/dev/materione/plugin/gui/**',
  'plugin/src/main/java/dev/materione/plugin/logic/action/gui/**',
  'plugin/src/main/java/dev/materione/plugin/runtime/PipelineService.java',
  'content/src/main/java/dev/materione/content/gui/**',
  'behavior/src/main/java/dev/materione/behavior/flow/TriggerFilters.java',
  'fixtures/packs/guilogic/**',
];

/** A real container click from the client: button 0 is a left click, 1 a right click. */
function click(ctx, slot, button = 0) {
  return ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, ${button}, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
return 'clicked'`);
}

/** How many slots the open container has (the menu is 3 rows: 27 + 36; the detail is 1 row: 9 + 36). */
async function openSlots(ctx) {
  return Number(await ctx.exec('return player.containerMenu.slots.size()'));
}

async function containerOpen(ctx) {
  return Number(await ctx.exec('return player.containerMenu.containerId')) !== 0;
}

/** How many server-log lines match, since the server started (a marker is printed once per event). */
function logCount(regex) {
  return fs.readFileSync(SERVER_LOG, 'utf8').split('\n').filter((line) => regex.test(line)).length;
}

async function slotStack(ctx, slot) {
  return String(await ctx.exec(`def s = player.containerMenu.slots.get(${slot}).getItem()
return s.isEmpty() ? 'empty' : s.getHoverName().getString() + ':' + s.getCount()`));
}

async function cursorStack(ctx) {
  return String(await ctx.exec(`def s = player.containerMenu.getCarried()
return s.isEmpty() ? 'empty' : s.getHoverName().getString() + ':' + s.getCount()`));
}

async function inventoryCount(ctx, name) {
  return Number(await ctx.exec(`def total = 0
for (int i = 0; i < 36; i++) {
  def s = player.getInventory().items.get(i)
  if (!s.isEmpty() && s.getHoverName().getString() == '${name}') total += s.getCount()
}
return total`));
}

async function openMenu(ctx, args = 'page=2') {
  await ctx.reset();
  await ctx.rcon(`materione gui open guilogic:menu Probe ${args}`);
  await ctx.waitForServerLog(/MATERIONE-GUILOGIC-OPEN guilogic:menu/);
  await ctx.sleep(600); // the client finishes opening the window
}

async function closeWindow(ctx) {
  try {
    await ctx.exec('player.closeContainer(); return "closed"');
  } catch { /* nothing open */ }
}

export default [
  test({
    id: 'guilogic.click-message-sound',
    title: 'guilogic:menu opened with page=2: gui.open fires with the argument, a left click on the ping button (slot 11) runs its flow in order (the chat line "Pong from ping (slot 11, left)", the sound step, then the console marker), a right click runs the other rule, and neither click moves an item or leaves one on the cursor',
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openMenu(ctx);
        ctx.expect(await openSlots(ctx)).toBe(63);
        ctx.expect(ctx.serverLogLine(/MATERIONE-GUILOGIC-OPEN guilogic:menu page=2/)).toContain('page=2');

        await click(ctx, 11);
        await ctx.waitForChat(/Pong from ping \(slot 11, left\)/);
        // the marker is the last step of the flow, after the message and the sound: the flow ran to its end
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-PONG slot=11/);

        await click(ctx, 11, 1);
        await ctx.waitForChat(/Right click on ping/);
        ctx.expect(logCount(/MATERIONE-GUILOGIC-PONG/)).toBe(1); // the right click did not run the left rule

        // the click was cancelled: the GUI took nothing and the cursor is empty
        ctx.expect(String(await ctx.exec('return player.containerMenu.getCarried().isEmpty()'))).toBe('true');

        // a click on a slot no layer covers does nothing at all
        const before = (await ctx.chat()).length;
        await click(ctx, 0);
        await ctx.sleep(600);
        ctx.expect((await ctx.chat()).length).toBe(before);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guilogic.close',
    title: 'on guilogic:menu the close button (slot 15) says "Closing" and closes the window with gui.close reason=action, while closing the window by hand (Esc) fires the same trigger with reason=player',
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openMenu(ctx);
        await click(ctx, 15);
        await ctx.waitForChat(/Closing/);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-CLOSE guilogic:menu reason=action/);
        await ctx.sleep(400);
        ctx.expect(await containerOpen(ctx)).toBe(false);

        await openMenu(ctx, 'page=5');
        ctx.expect(await containerOpen(ctx)).toBe(true);
        await ctx.exec('player.closeContainer(); return "closed"');
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-CLOSE guilogic:menu reason=player/);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guilogic.open-and-back',
    title: 'on guilogic:menu (page=2) the open button (slot 13) replaces the window with guilogic:detail carrying item=sword and from=Probe (and fires the menu\'s close with reason=replaced), and the detail\'s up button (slot 4) runs gui.back: the menu is back with the arguments it was opened with',
    covers: COVERS,
    dependsOn: ['guilogic.click-message-sound'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openMenu(ctx, 'page=2');
        const opensBefore = logCount(/MATERIONE-GUILOGIC-OPEN guilogic:menu page=2/);

        await click(ctx, 13);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-OPEN guilogic:detail item=sword from=Probe/);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-CLOSE guilogic:menu reason=replaced/);
        await ctx.waitForChat(/Detail of sword/);
        await ctx.sleep(600);
        ctx.expect(await openSlots(ctx)).toBe(45); // the detail: one row

        await click(ctx, 4);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-CLOSE guilogic:detail reason=replaced/);
        await ctx.sleep(800);
        ctx.expect(await openSlots(ctx)).toBe(63); // the menu again
        // gui.open fired a second time for the menu, with the arguments the first one had
        ctx.expect(logCount(/MATERIONE-GUILOGIC-OPEN guilogic:menu page=2/)).toBe(opensBefore + 1);

        // and it is a live session: the ping button still answers
        await click(ctx, 11);
        await ctx.waitForChat(/Pong from ping/);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guilogic.live-refresh-keeps-session',
    title: 'an editor save that retitles guilogic:menu re-renders the open window and the session carries on: the arguments (page=3) survive the reload, so going detail and back still restores page=3 and the buttons still answer',
    covers: COVERS,
    dependsOn: ['guilogic.open-and-back'],
    timeoutMs: 300_000,
    steps: async (ctx) => {
      const original = fs.readFileSync(MENU_FILE, 'utf8');
      try {
        await openMenu(ctx, 'page=3');
        fs.writeFileSync(MENU_FILE, original.replace('text: "MENU"', 'text: "MENU TWO"'));
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-GUI-REFRESH changed=1 removed=0/);
        await ctx.sleep(800);

        await click(ctx, 11);
        await ctx.waitForChat(/Pong from ping/);

        const opensBefore = logCount(/MATERIONE-GUILOGIC-OPEN guilogic:menu page=3/);
        await click(ctx, 13);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-OPEN guilogic:detail item=sword/);
        await ctx.sleep(600);
        await click(ctx, 4);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-CLOSE guilogic:detail reason=replaced/);
        await ctx.sleep(800);
        ctx.expect(logCount(/MATERIONE-GUILOGIC-OPEN guilogic:menu page=3/)).toBe(opensBefore + 1);
      } finally {
        await closeWindow(ctx);
        fs.writeFileSync(MENU_FILE, original);
        await ctx.rcon('materione reload');
      }
    },
  }),

  test({
    id: 'guilogic.visual',
    title: 'guilogic:menu as a player sees it: the three buttons in the middle row, the window after the ping click (the chat line), and the detail GUI the open button leads to',
    mode: 'visual',
    covers: COVERS,
    dependsOn: ['guilogic.click-message-sound', 'guilogic.open-and-back'],
    timeoutMs: 300_000,
    steps: async (ctx) => {
      try {
        await openMenu(ctx);
        await ctx.shot('guilogic-menu');
        await click(ctx, 11);
        await ctx.waitForChat(/Pong from ping/);
        await ctx.shot('guilogic-after-ping');
        await click(ctx, 13);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-OPEN guilogic:detail/);
        await ctx.sleep(700);
        await ctx.shot('guilogic-detail');
        ctx.needsEyes(
          'guilogic:menu is a three-row chest with the heading "MENU" in small caps at the top left and three raised '
            + 'button squares in the middle row at the 3rd, 5th and 7th slots; the window did not change when the ping '
            + 'button was clicked and the chat shows "Pong from ping (slot 11, left)"; the detail GUI is a one-row chest '
            + 'headed "DETAIL" with one raised button in its middle slot and the chat shows "Detail of sword".',
        );
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guilogic.input-slots',
    title: 'guilogic:trade accepts and returns corpus:gem_red, rejects dirt without moving it, conserves cursor and inventory items on player close and reopen, and leaves an empty click as a no-op',
    covers: [...COVERS, 'fixtures/packs/corpus/items/gem_red.yml'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await ctx.reset();
        await ctx.rcon('materione give corpus:gem_red Probe 3');
        await ctx.waitForServerLog(/MATERIONE-GIVE-OK id=corpus:gem_red player=Probe amount=3/);
        await ctx.rcon('give Probe minecraft:dirt 2');
        await ctx.sleep(600);
        ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(3);
        ctx.expect(await inventoryCount(ctx, 'Dirt')).toBe(2);
        await ctx.rcon('materione gui open guilogic:trade Probe');
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-OPEN guilogic:trade/);
        await ctx.sleep(600); // the client finishes opening the window
        ctx.expect(await openSlots(ctx)).toBe(45);

        // an empty click on an input slot is no change: the server applies nothing and no rule runs
        const changesBefore = logCount(/MATERIONE-GUILOGIC-SLOT/);
        await click(ctx, 4);
        await ctx.sleep(600);
        ctx.expect(logCount(/MATERIONE-GUILOGIC-SLOT/)).toBe(changesBefore);
        ctx.expect(await slotStack(ctx, 4)).toBe('empty');
        ctx.expect(await cursorStack(ctx)).toBe('empty');
        ctx.expect(await containerOpen(ctx)).toBe(true);

        // The nine-slot chest's player hotbar occupies raw slots 36..44.
        ctx.expect(await slotStack(ctx, 36)).toBe('Red Gem:3');
        ctx.expect(await slotStack(ctx, 37)).toBe('Dirt:2');
        await click(ctx, 36);
        await ctx.sleep(400);
        ctx.expect(await cursorStack(ctx)).toBe('Red Gem:3');
        ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(0);
        await click(ctx, 4);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-SLOT slot=4 item=corpus:gem_red amount=3/);
        await ctx.sleep(400);
        ctx.expect(await slotStack(ctx, 4)).toBe('Red Gem:3');
        ctx.expect(await cursorStack(ctx)).toBe('empty');

        await click(ctx, 4, 1);
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-SLOT slot=4 item=corpus:gem_red amount=1/);
        await ctx.sleep(400);
        ctx.expect(await slotStack(ctx, 4)).toBe('Red Gem:1');
        ctx.expect(await cursorStack(ctx)).toBe('Red Gem:2');
        await click(ctx, 36);
        await ctx.sleep(400);
        ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(2);
        ctx.expect(await cursorStack(ctx)).toBe('empty');

        await click(ctx, 37);
        await ctx.sleep(400);
        ctx.expect(await cursorStack(ctx)).toBe('Dirt:2');
        const beforeReject = logCount(/MATERIONE-GUILOGIC-SLOT/);
        await click(ctx, 5);
        await ctx.sleep(600);
        ctx.expect(await slotStack(ctx, 5)).toBe('empty');
        ctx.expect(await slotStack(ctx, 4)).toBe('Red Gem:1');
        ctx.expect(await cursorStack(ctx)).toBe('Dirt:2');
        ctx.expect(logCount(/MATERIONE-GUILOGIC-SLOT/)).toBe(beforeReject);
        await click(ctx, 37);
        await ctx.sleep(400);
        ctx.expect(await inventoryCount(ctx, 'Dirt')).toBe(2);
        ctx.expect(await cursorStack(ctx)).toBe('empty');

        const closesBefore = logCount(/MATERIONE-GUILOGIC-TRADE-CLOSED/);
        await ctx.exec('player.closeContainer(); return "closed"');
        await ctx.waitForServerLog(/MATERIONE-GUILOGIC-TRADE-CLOSED/);
        await ctx.sleep(600);
        ctx.expect(logCount(/MATERIONE-GUILOGIC-TRADE-CLOSED/)).toBe(closesBefore + 1);
        ctx.expect(await containerOpen(ctx)).toBe(false);
        ctx.expect(await cursorStack(ctx)).toBe('empty');
        ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(3);
        ctx.expect(await inventoryCount(ctx, 'Dirt')).toBe(2);

        await ctx.rcon('materione gui open guilogic:trade Probe');
        await ctx.sleep(600);
        ctx.expect(await openSlots(ctx)).toBe(45);
        ctx.expect(await slotStack(ctx, 4)).toBe('empty');
        ctx.expect(await slotStack(ctx, 5)).toBe('empty');
        ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(3);
        await ctx.exec('player.closeContainer(); return "closed"');
        await ctx.sleep(600);
        ctx.expect(logCount(/MATERIONE-GUILOGIC-TRADE-CLOSED/)).toBe(closesBefore + 2);
        ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(3);
        ctx.expect(await inventoryCount(ctx, 'Dirt')).toBe(2);
        ctx.expect(await cursorStack(ctx)).toBe('empty');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),
];
