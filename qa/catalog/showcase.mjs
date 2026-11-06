// The GUI showcase (#227, logic.md §1, §34): the five GUIs of the showcase pack (fixtures/packs/showcase), driven
// through real container clicks and chat from the probe client. Each entry names the parity row it demonstrates in its
// title. The shop opens with /shop (the open command, with a typed category); the servers, the players, the settings and
// the confirm GUI open from it. The server and the client are judged by the console markers
// (`say MATERIONE-SHOWCASE-...`), the chat lines the flows send, and the slots the client shows. Server-scope and
// player-scope values are read and set with `/materione var`, so each test starts from a known stock and toggle.
//
// Written against the probe loop and NOT YET RUN on a real client: the 26.x probe was not available to the change
// that added them. The blessing run (qa/README.md) happens on main, after merge. Shop, settings, confirm and players
// read the same server-scope stock and the same player toggle, so they run in order (each test resets both).

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SERVER_LOG = path.join(ROOT, 'harness', 'build', 'probe', 'server', 'logs', 'latest.log');

const COVERS = [
  'plugin/src/main/java/dev/materione/plugin/gui/**',
  'plugin/src/main/java/dev/materione/plugin/logic/action/**',
  'plugin/src/main/java/dev/materione/plugin/logic/condition/**',
  'plugin/src/main/java/dev/materione/plugin/logic/currency/**',
  'plugin/src/main/java/dev/materione/plugin/logic/variable/**',
  'plugin/src/main/java/dev/materione/plugin/command/VarCommand.java',
  'content/src/main/java/dev/materione/content/gui/**',
  'content/src/main/java/dev/materione/content/currency/**',
  'behavior/src/main/java/dev/materione/behavior/flow/**',
  'fixtures/packs/showcase/**',
];

/** A real container click from the client: button 0 is a left click, 1 a right click. */
function click(ctx, slot, button = 0) {
  return ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, ${button}, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
return 'clicked'`);
}

/** How many slots the open container has (the shop and the players are 3 rows: 27 + 36; settings, servers and confirm 1 row). */
async function openSlots(ctx) {
  return Number(await ctx.exec('return player.containerMenu.slots.size()'));
}

async function containerOpen(ctx) {
  return Number(await ctx.exec('return player.containerMenu.containerId')) !== 0;
}

/** What a container slot shows: the item's name, or "empty". */
async function slotName(ctx, slot) {
  return String(await ctx.exec(`def s = player.containerMenu.slots.get(${slot}).getItem()
return s.isEmpty() ? 'empty' : s.getHoverName().getString()`));
}

/** How many emeralds (the item, not its name) the viewer's inventory holds. */
async function emeraldCount(ctx) {
  return Number(await ctx.exec(`def total = 0
for (int i = 0; i < 36; i++) {
  def s = player.getInventory().items.get(i)
  if (!s.isEmpty() && s.getItem() == net.minecraft.world.item.Items.EMERALD) total += s.getCount()
}
return total`));
}

/** How many server-log lines match, since the server started (a marker is printed once per event). */
function logCount(regex) {
  return fs.readFileSync(SERVER_LOG, 'utf8').split('\n').filter((line) => regex.test(line)).length;
}

async function closeWindow(ctx) {
  try {
    await ctx.exec('player.closeContainer(); return "closed"');
  } catch { /* nothing open */ }
}

/** Resets the viewer, sets the shared values the flows read, and opens showcase:shop through its open command. */
async function openShop(ctx, { points = 20, stock = 3, tips = false, category = '' } = {}) {
  await ctx.reset();
  await ctx.rcon(`xp set Probe ${points} points`);
  await ctx.rcon(`materione var set - showcase:stock ${stock}`);
  await ctx.rcon(`materione var set Probe showcase:show_tips ${tips}`);
  await ctx.rcon(`execute as Probe run shop${category ? ` ${category}` : ''}`);
  await ctx.waitForServerLog(new RegExp(`MATERIONE-SHOWCASE-OPEN shop category=${category || 'all'}`));
  await ctx.sleep(900); // the client finishes opening the window
}

/** The viewer's answer to a chat prompt, sent through the client's own connection (as the search entry does). */
async function answerChat(ctx, text) {
  await ctx.exec(`mc.player.connection.sendChat('${text}'); return 'sent'`);
  await ctx.sleep(900);
}

export default [
  // --- the shop: the open command, the typed argument, the paywall, conditional stock ------------------------------

  test({
    id: 'showcase.shop-open-command',
    title: 'the /shop open command opens showcase:shop with a typed category: "shop gems" announces category=gems on the console, the window is the three-row shop (63 slots), and the emerald shows its lore with the stock',
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openShop(ctx, { category: 'gems' });
        ctx.expect(await containerOpen(ctx)).toBe(true);
        ctx.expect(await openSlots(ctx)).toBe(63);
        ctx.expect(await slotName(ctx, 11)).toBe('Emerald');
        ctx.expect(await slotName(ctx, 13)).toBe('Servers');
        ctx.expect(await slotName(ctx, 15)).toBe('Players');
        ctx.expect(await slotName(ctx, 22)).toBe('Settings');
        ctx.expect(await slotName(ctx, 26)).toBe('Close');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'showcase.shop-buy-with-points',
    title: 'in showcase:shop with 20 points and a stock of 3, a click on the emerald (slot 11) takes 5 points, gives one emerald, says "Bought an emerald." and lowers the server stock to 2 (materione var get reads showcase:stock = 2)',
    covers: COVERS,
    dependsOn: ['showcase.shop-open-command'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openShop(ctx, { points: 20, stock: 3 });
        const buysBefore = logCount(/MATERIONE-SHOWCASE-BUY/);

        await click(ctx, 11);
        await ctx.waitForChat(/Bought an emerald/);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-BUY/);
        await ctx.sleep(600);
        ctx.expect(logCount(/MATERIONE-SHOWCASE-BUY/)).toBe(buysBefore + 1);
        ctx.expect(await emeraldCount(ctx)).toBe(1);
        ctx.expect(await ctx.rcon('materione var get - showcase:stock')).toMatch(/= 2/);
      } finally {
        await closeWindow(ctx);
        await ctx.rcon('materione var set - showcase:stock 3');
      }
    },
  }),

  test({
    id: 'showcase.shop-buy-refused-without-points',
    title: 'in showcase:shop with 2 points, a click on the emerald says "You need 5 points.", gives no emerald and does not lower the stock (the require takes nothing when the payment fails)',
    covers: COVERS,
    dependsOn: ['showcase.shop-buy-with-points'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openShop(ctx, { points: 2, stock: 3 });
        const buysBefore = logCount(/MATERIONE-SHOWCASE-BUY/);

        await click(ctx, 11);
        await ctx.waitForChat(/You need 5 points/);
        await ctx.sleep(400);
        ctx.expect(logCount(/MATERIONE-SHOWCASE-BUY/)).toBe(buysBefore);
        ctx.expect(await emeraldCount(ctx)).toBe(0);
        ctx.expect(await ctx.rcon('materione var get - showcase:stock')).toMatch(/= 3/);
      } finally {
        await closeWindow(ctx);
        await ctx.rcon('materione var set - showcase:stock 3');
      }
    },
  }),

  test({
    id: 'showcase.shop-sold-out',
    title: 'with the server stock at 0, showcase:shop shows the barrier "Sold out" over the emerald in slot 11, and a click there says "Sold out." (the click reaches the emerald rule, which refuses the sale) and takes no points or emerald',
    covers: COVERS,
    dependsOn: ['showcase.shop-buy-refused-without-points'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openShop(ctx, { points: 20, stock: 0 });
        ctx.expect(await slotName(ctx, 11)).toBe('Sold out');

        await click(ctx, 11);
        await ctx.waitForChat(/Sold out\./);
        await ctx.sleep(400);
        ctx.expect(await emeraldCount(ctx)).toBe(0);
        ctx.expect(await ctx.rcon('materione var get - showcase:stock')).toMatch(/= 0/);
      } finally {
        await closeWindow(ctx);
        await ctx.rcon('materione var set - showcase:stock 3');
      }
    },
  }),

  // --- the server selector: a templated online count, a live window, connect that fails without a transfer ---------

  test({
    id: 'showcase.servers',
    title: 'showcase:servers opens from the shop\'s servers button (slot 13) and shows the lobby compass in slot 4; a click says "Connecting to lobby..." and, with no transfer configured in the probe, "The lobby cannot be reached from here."; the back arrow (slot 8) returns to the shop',
    covers: COVERS,
    dependsOn: ['showcase.shop-open-command'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openShop(ctx);
        await click(ctx, 13);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN servers/);
        await ctx.sleep(700);
        ctx.expect(await openSlots(ctx)).toBe(45); // one row
        ctx.expect(await slotName(ctx, 4)).toBe('Lobby');

        await click(ctx, 4);
        await ctx.waitForChat(/Connecting to lobby/);
        await ctx.waitForChat(/cannot be reached from here/);

        const opensBefore = logCount(/MATERIONE-SHOWCASE-OPEN shop/);
        await click(ctx, 8);
        await ctx.sleep(900);
        ctx.expect(logCount(/MATERIONE-SHOWCASE-OPEN shop/)).toBe(opensBefore + 1);
        ctx.expect(await openSlots(ctx)).toBe(63);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  // --- the paged player list with search -----------------------------------------------------------------------------

  test({
    id: 'showcase.players-search',
    title: 'showcase:players opens from the shop (slot 15), lists the online players sorted by name with the viewer among them, and shows "Page 1 of 1"; the search button asks in chat, a typed "pro" keeps the viewer, a typed "zzz" leaves the list empty, and the clear button shows the viewer again; a click on the entry says "Picked Probe"',
    covers: COVERS,
    dependsOn: ['showcase.shop-open-command'],
    timeoutMs: 300_000,
    steps: async (ctx) => {
      try {
        await openShop(ctx);
        await click(ctx, 15);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN players/);
        await ctx.sleep(900);
        ctx.expect(await slotName(ctx, 10)).toBe('Probe');
        ctx.expect(await slotName(ctx, 4)).toBe('Page 1 of 1');

        await click(ctx, 10);
        await ctx.waitForChat(/Picked Probe/);

        await click(ctx, 20);
        await ctx.waitForChat(/Type a player name to search/);
        await ctx.sleep(400);
        ctx.expect(await containerOpen(ctx)).toBe(false); // the window is hidden for the question
        await answerChat(ctx, 'pro');
        await ctx.sleep(600);
        ctx.expect(await containerOpen(ctx)).toBe(true); // the window came back for the same session
        ctx.expect(await slotName(ctx, 10)).toBe('Probe');

        await click(ctx, 20);
        await ctx.waitForChat(/Type a player name to search/);
        await ctx.sleep(400);
        await answerChat(ctx, 'zzz');
        await ctx.sleep(600);
        ctx.expect(await slotName(ctx, 10)).toBe('empty');

        await click(ctx, 22);
        await ctx.sleep(600);
        ctx.expect(await slotName(ctx, 10)).toBe('Probe');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  // --- the settings toggles and the confirm dialog -------------------------------------------------------------------

  test({
    id: 'showcase.settings-toggle',
    title: 'showcase:settings (from the shop\'s settings button, slot 22) shows "Tips: OFF" while the player\'s show_tips is false; a left click on it says "Tips are on." and shows "Tips: ON" (the variable reads true), a second left click turns it off again; a right click only explains the button',
    covers: COVERS,
    dependsOn: ['showcase.shop-open-command'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openShop(ctx, { tips: false });
        await click(ctx, 22);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN settings/);
        await ctx.sleep(700);
        ctx.expect(await slotName(ctx, 4)).toBe('Tips: OFF');

        await click(ctx, 4);
        await ctx.waitForChat(/Tips are on/);
        await ctx.sleep(600);
        ctx.expect(await slotName(ctx, 4)).toBe('Tips: ON');
        ctx.expect(await ctx.rcon('materione var get Probe showcase:show_tips')).toMatch(/= true/);

        await click(ctx, 4, 1);
        await ctx.waitForChat(/Left click flips this setting/);
        ctx.expect(await slotName(ctx, 4)).toBe('Tips: ON');

        await click(ctx, 4);
        await ctx.waitForChat(/Tips are off/);
        await ctx.sleep(600);
        ctx.expect(await slotName(ctx, 4)).toBe('Tips: OFF');
      } finally {
        await closeWindow(ctx);
        await ctx.rcon('materione var set Probe showcase:show_tips false');
      }
    },
  }),

  test({
    id: 'showcase.confirm-dialog',
    title: 'the reset button in showcase:settings (slot 6) opens showcase:confirm with its question; "No" (slot 5) says "Kept as it was." and goes back to the settings with show_tips still true; "Yes" (slot 3) says "Tips are off.", goes back to the settings, and leaves show_tips false',
    covers: COVERS,
    dependsOn: ['showcase.settings-toggle'],
    timeoutMs: 300_000,
    steps: async (ctx) => {
      try {
        await openShop(ctx, { tips: true });
        await click(ctx, 22);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN settings/);
        await ctx.sleep(700);

        await click(ctx, 6);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN confirm/);
        await ctx.sleep(700);
        ctx.expect(await openSlots(ctx)).toBe(45);
        ctx.expect(await slotName(ctx, 3)).toBe('Yes');
        ctx.expect(await slotName(ctx, 5)).toBe('No');

        await click(ctx, 5);
        await ctx.waitForChat(/Kept as it was/);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-CONFIRM no/);
        await ctx.sleep(900);
        ctx.expect(await slotName(ctx, 4)).toBe('Tips: ON'); // back at the settings, unchanged
        ctx.expect(await ctx.rcon('materione var get Probe showcase:show_tips')).toMatch(/= true/);

        await click(ctx, 6);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN confirm/);
        await ctx.sleep(700);
        await click(ctx, 3);
        await ctx.waitForChat(/Tips are off/);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-CONFIRM yes/);
        await ctx.sleep(900);
        ctx.expect(await slotName(ctx, 4)).toBe('Tips: OFF');
        ctx.expect(await ctx.rcon('materione var get Probe showcase:show_tips')).toMatch(/= false/);
      } finally {
        await closeWindow(ctx);
        await ctx.rcon('materione var set Probe showcase:show_tips false');
      }
    },
  }),

  test({
    id: 'showcase.confirm-click-outside',
    title: 'a click outside the showcase:confirm window (the cursor leaves the chest, slot -999) runs its gui.click_outside rule: it goes back to the settings, with no answer given and show_tips still true',
    covers: COVERS,
    dependsOn: ['showcase.confirm-dialog'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openShop(ctx, { tips: true });
        await click(ctx, 22);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN settings/);
        await ctx.sleep(700);
        await click(ctx, 6);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN confirm/);
        await ctx.sleep(700);

        const answersBefore = logCount(/MATERIONE-SHOWCASE-CONFIRM/);
        await ctx.exec("mc.gameMode.handleContainerInput(player.containerMenu.containerId, -999, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player); return 'clicked'");
        await ctx.sleep(900);
        ctx.expect(logCount(/MATERIONE-SHOWCASE-CONFIRM/)).toBe(answersBefore); // no yes or no was given
        ctx.expect(await slotName(ctx, 4)).toBe('Tips: ON'); // back at the settings
        ctx.expect(await ctx.rcon('materione var get Probe showcase:show_tips')).toMatch(/= true/);
      } finally {
        await closeWindow(ctx);
        await ctx.rcon('materione var set Probe showcase:show_tips false');
      }
    },
  }),

  test({
    id: 'showcase.tip-line-follows-the-toggle',
    title: 'the shop\'s "Click to buy" tip line is shown only while the player\'s show_tips is true: the same shop is captured with tips off and with tips on',
    covers: COVERS,
    dependsOn: ['showcase.settings-toggle'],
    mode: 'visual',
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openShop(ctx, { tips: false });
        await ctx.shot('showcase-shop-tips-off');
        await ctx.rcon('materione var set Probe showcase:show_tips true');
        await ctx.rcon('execute as Probe run shop');
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN shop category=all/);
        await ctx.sleep(900);
        await ctx.shot('showcase-shop-tips-on');
        ctx.needsEyes(
          'The first capture (showcase-shop-tips-off) shows the shop with no tip line under the SHOP heading; the second '
            + '(showcase-shop-tips-on) shows the grey "Click to buy" line at the top of the window beside the heading. The '
            + 'emerald in the middle row is the same in both.',
        );
      } finally {
        await closeWindow(ctx);
        await ctx.rcon('materione var set Probe showcase:show_tips false');
      }
    },
  }),

  // --- the visual verdict ---------------------------------------------------------------------------------------------

  test({
    id: 'showcase.visual',
    title: 'the five showcase GUIs as a player sees them: the shop with an emerald in stock, the same shop sold out, the servers window, the players window with the viewer listed, the settings window with the tips on, and the confirm question',
    mode: 'visual',
    covers: COVERS,
    dependsOn: ['showcase.shop-open-command', 'showcase.confirm-dialog', 'showcase.players-search', 'showcase.servers'],
    timeoutMs: 360_000,
    steps: async (ctx) => {
      try {
        await openShop(ctx, { stock: 3, tips: true });
        await ctx.shot('showcase-shop-in-stock');

        await ctx.rcon('materione var set - showcase:stock 0');
        await ctx.rcon('execute as Probe run shop');
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN shop category=all/);
        await ctx.sleep(900);
        await ctx.shot('showcase-shop-sold-out');
        await ctx.rcon('materione var set - showcase:stock 3');

        await click(ctx, 13);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN servers/);
        await ctx.sleep(700);
        await ctx.shot('showcase-servers');

        await click(ctx, 8);
        await ctx.sleep(800);
        await click(ctx, 15);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN players/);
        await ctx.sleep(900);
        await ctx.shot('showcase-players');

        await click(ctx, 8);
        await ctx.sleep(800);
        await click(ctx, 22);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN settings/);
        await ctx.sleep(700);
        await ctx.shot('showcase-settings');

        await click(ctx, 6);
        await ctx.waitForServerLog(/MATERIONE-SHOWCASE-OPEN confirm/);
        await ctx.sleep(700);
        await ctx.shot('showcase-confirm');
        ctx.needsEyes(
          'showcase-shop-in-stock: a three-row chest titled SHOP with the tip line "Click to buy" and an emerald in the '
            + 'middle row with the lore "5 points", "Category: all" and "In stock: 3". showcase-shop-sold-out: the same '
            + 'shop with a barrier named "Sold out" covering the emerald. showcase-servers: a one-row chest headed SERVERS '
            + 'with a compass named "Lobby" in the 5th slot and a back arrow in the last slot. showcase-players: a three-row '
            + 'chest headed PLAYERS, the viewer\'s head in the first slot of the list (second row, first column), "Page 1 of 1" on the paper in the top '
            + 'middle, and the search compass, clear barrier and arrows in the bottom row. showcase-settings: a one-row '
            + 'chest headed SETTINGS with a lime dye named "Tips: ON" in the 5th slot, a red TNT named "Turn tips off" in '
            + 'the 7th and a back arrow in the 9th. showcase-confirm: a one-row chest with the question "Turn the tips off?" '
            + 'at the top left and a lime concrete "Yes" and a red concrete "No" in the 4th and 6th slots.',
        );
      } finally {
        await closeWindow(ctx);
        await ctx.rcon('materione var set - showcase:stock 3');
        await ctx.rcon('materione var set Probe showcase:show_tips false');
      }
    },
  }),
];
