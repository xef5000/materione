// Browse suite: the in-game content browser (M6, /materione browse) on a real server and client. The browser is the GUI
// `materione:browser` of the plugin's own built-in pack, so these entries drive the generic GUI runtime: they open it,
// walk its packs and folders with real container clicks, search it by argument and by the chat prompt, take items, and
// read the result back from the window, the chat and the inventory. The visual entry captures the window for a human
// pass. The probe stages every fixture pack, so the root lists the packs that hold items (corpus, nested, probe,
// sample, world), and `nested` holds one folder, `weapons`, with one item, the Nested Blade.
//
// Not covered here: a take refused for lack of materione.browse.get. The probe player is either an operator (both
// nodes) or has neither, and no permission provider exists to grant only the open node. That path is covered on the
// real file, with the permission revoked between two clicks, by BuiltinBrowserPackTest; it is not verified in a client.

import { test } from './_lib.mjs';

const COVERS = [
  'plugin/src/main/resources/builtin-packs/**',
  'plugin/src/main/java/dev/materione/plugin/gui/list/**',
  'plugin/src/main/java/dev/materione/plugin/gui/GuiRuntime.java',
  'plugin/src/main/java/dev/materione/plugin/command/MaterioneCommand.java',
  'plugin/src/main/java/dev/materione/plugin/command/MaterioneCommandAdapter.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/GiveItemAction.java',
  'plugin/src/main/java/dev/materione/plugin/items/Give.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/PipelineService.java',
  'core/src/main/java/dev/materione/core/runtime/RuntimeContentIndex.java',
  'core/src/main/java/dev/materione/core/pipeline/BuiltinPacks.java',
  'core/src/main/java/dev/materione/core/pipeline/ParseStage.java',
  'fixtures/packs/corpus/items/**',
  'fixtures/packs/nested/**',
];

/** The display name of the client's open window at a slot, or 'empty'. */
function slotName(ctx, slot) {
  return ctx.exec(`def s = player.containerMenu.getSlot(${slot}).getItem()
return s.isEmpty() ? 'empty' : s.getHoverName().getString()`);
}

/** The first of the list's 45 slots that shows this name, or -1. */
async function slotOf(ctx, name) {
  for (let slot = 0; slot < 45; slot++) {
    if ((await slotName(ctx, slot)) === name) return slot;
  }
  return -1;
}

/** A real container click from the client: button 0 is a left click, 1 a right click. */
function clickSlot(ctx, slot, button = 0, input = 'PICKUP') {
  return ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, ${button}, net.minecraft.world.inventory.ContainerInput.${input}, player)
return 'clicked'`);
}

async function containerOpen(ctx) {
  return Number(await ctx.exec('return player.containerMenu.containerId')) !== 0;
}

/** Waits for the six-row window (54 slots and the player's 36). */
async function waitForWindow(ctx) {
  for (let attempt = 0; attempt < 40; attempt++) {
    if (Number(await ctx.exec('return player.containerMenu.slots.size()')) === 90) {
      await ctx.sleep(700); // the first render, and the search rule that follows an open with arguments
      return;
    }
    await ctx.sleep(250);
  }
  ctx.expect(await ctx.exec('return player.containerMenu.slots.size()')).toBe(90);
}

async function inventoryCount(ctx) {
  return Number(await ctx.exec(
    'def total = 0; for (int i = 0; i < 36; i++) { def s = player.getInventory().items.get(i); if (!s.isEmpty()) total += s.getCount() }; return total',
  ));
}

async function prepare(ctx) {
  await ctx.reset();
  await ctx.exec('player.closeContainer(); return "closed"');
  await ctx.sleep(400);
}

export default [
  test({
    id: 'browse.walks-packs-and-folders',
    title: '/materione browse opens at the packs; opening nested shows its folder first, opening the folder shows the Nested Blade, and Back climbs one step at a time to the packs',
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const granted = (await ctx.rcon('op Probe')).includes('Made Probe a server operator');
      try {
        await prepare(ctx);
        await ctx.rcon('execute as Probe run materione browse');
        await waitForWindow(ctx);
        const nested = await slotOf(ctx, 'nested');
        ctx.expect(nested).toBeGreaterThan(-1);
        ctx.expect(await slotOf(ctx, 'corpus')).toBeGreaterThan(-1);
        ctx.expect(await slotName(ctx, 45)).toBe('empty'); // no Back at the top

        await clickSlot(ctx, nested);
        await ctx.sleep(900);
        ctx.expect(await slotName(ctx, 0)).toBe('weapons');
        ctx.expect(await slotName(ctx, 45)).toBe('Back');

        await clickSlot(ctx, 0);
        await ctx.sleep(900);
        ctx.expect(await slotName(ctx, 0)).toBe('Nested Blade');
        ctx.expect(await slotName(ctx, 1)).toBe('empty');

        await clickSlot(ctx, 45);
        await ctx.sleep(900);
        ctx.expect(await slotName(ctx, 0)).toBe('weapons');
        await clickSlot(ctx, 45);
        await ctx.sleep(900);
        ctx.expect(await slotOf(ctx, 'nested')).toBe(nested);
        ctx.expect(await slotName(ctx, 45)).toBe('empty');
      } finally {
        await ctx.exec('player.closeContainer(); return "closed"');
        if (granted) await ctx.rcon('deop Probe');
      }
    },
  }),

  test({
    id: 'browse.search-finds-and-gives',
    title: 'typed /materione browse with the words "red gem" opens the browser already searching, with the one matching item; a left click in the client hands one Red Gem into the inventory',
    covers: COVERS,
    dependsOn: ['browse.walks-packs-and-folders'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const granted = (await ctx.rcon('op Probe')).includes('Made Probe a server operator');
      try {
        await prepare(ctx);
        await ctx.rcon('execute as Probe run materione browse red gem');
        await waitForWindow(ctx);
        ctx.expect(await slotName(ctx, 0)).toBe('Red Gem');
        ctx.expect(await slotName(ctx, 1)).toBe('empty');
        const before = await inventoryCount(ctx);
        await clickSlot(ctx, 0);
        await ctx.sleep(700);
        ctx.expect(await inventoryCount(ctx)).toBe(before + 1);
        await ctx.shot('browse-search-gave-one');
      } finally {
        await ctx.exec('player.closeContainer(); return "closed"');
        if (granted) await ctx.rcon('deop Probe');
      }
    },
  }),

  test({
    id: 'browse.shift-click-takes-a-stack',
    title: 'a shift-click on the Red Gem hands a full stack (64: its material is a quartz) and takes nothing from the window',
    covers: COVERS,
    dependsOn: ['browse.search-finds-and-gives'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const granted = (await ctx.rcon('op Probe')).includes('Made Probe a server operator');
      try {
        await prepare(ctx);
        await ctx.rcon('execute as Probe run materione browse red gem');
        await waitForWindow(ctx);
        ctx.expect(await slotName(ctx, 0)).toBe('Red Gem');
        const before = await inventoryCount(ctx);
        await clickSlot(ctx, 0, 0, 'QUICK_MOVE');
        await ctx.sleep(700);
        ctx.expect(await inventoryCount(ctx)).toBe(before + 64);
        ctx.expect(await slotName(ctx, 0)).toBe('Red Gem'); // the window still shows it: nothing was moved out of it
      } finally {
        await ctx.exec('player.closeContainer(); return "closed"');
        if (granted) await ctx.rcon('deop Probe');
      }
    },
  }),

  test({
    id: 'browse.search-prompt-in-chat',
    title: 'the Search compass asks in chat, the window closes for the question, the typed words "red gem" come back as the answer, the window returns with the one match, and Clear brings the packs back',
    covers: COVERS,
    dependsOn: ['browse.walks-packs-and-folders'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const granted = (await ctx.rcon('op Probe')).includes('Made Probe a server operator');
      try {
        await prepare(ctx);
        await ctx.rcon('execute as Probe run materione browse');
        await waitForWindow(ctx);
        ctx.expect(await slotOf(ctx, 'corpus')).toBeGreaterThan(-1);

        await clickSlot(ctx, 48);
        await ctx.waitForChat(/Type the words to search for/);
        await ctx.sleep(400);
        ctx.expect(await containerOpen(ctx)).toBe(false); // the window is hidden for the question
        const before = (await ctx.chat()).length;
        await ctx.exec(`mc.player.connection.sendChat('red gem'); return 'sent'`);
        await ctx.sleep(900);
        ctx.expect((await ctx.chat()).length).toBe(before); // the answer is not broadcast

        await ctx.sleep(600);
        ctx.expect(await containerOpen(ctx)).toBe(true); // the window came back for the same session
        ctx.expect(await slotName(ctx, 0)).toBe('Red Gem');
        ctx.expect(await slotName(ctx, 1)).toBe('empty');
        ctx.expect(await slotName(ctx, 50)).toBe('Clear the search');

        await clickSlot(ctx, 50);
        await ctx.sleep(900);
        ctx.expect(await slotOf(ctx, 'corpus')).toBeGreaterThan(-1);
        ctx.expect(await slotName(ctx, 50)).toBe('empty');
      } finally {
        await ctx.exec('player.closeContainer(); return "closed"');
        if (granted) await ctx.rcon('deop Probe');
      }
    },
  }),

  test({
    id: 'browse.refuses-without-the-open-permission',
    title: 'a player without materione.browse is refused by the GUI\'s own open block ("no permission (materione.browse)") and no window opens',
    covers: COVERS,
    dependsOn: ['browse.walks-packs-and-folders'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const restoreOperator = (await ctx.rcon('deop Probe')).includes('Made Probe no longer a server operator');
      try {
        await prepare(ctx);
        await ctx.rcon('execute as Probe run materione browse');
        await ctx.waitForChat(/no permission \(materione\.browse\)/);
        await ctx.sleep(400);
        ctx.expect(await containerOpen(ctx)).toBe(false);
      } finally {
        if (restoreOperator) await ctx.rcon('op Probe');
      }
    },
  }),

  test({
    id: 'browse.visual',
    title: 'the content browser as a player sees it: the packs, a pack with its folder, a search that finds the Red Gem, and a search that finds nothing',
    mode: 'visual',
    covers: COVERS,
    dependsOn: ['browse.walks-packs-and-folders', 'browse.search-finds-and-gives'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const granted = (await ctx.rcon('op Probe')).includes('Made Probe a server operator');
      try {
        await prepare(ctx);
        await ctx.rcon('execute as Probe run materione browse');
        await waitForWindow(ctx);
        await ctx.shot('browse-packs');

        await clickSlot(ctx, await slotOf(ctx, 'nested'));
        await ctx.sleep(900);
        await ctx.shot('browse-pack-nested');

        await ctx.exec('player.closeContainer(); return "closed"');
        await ctx.sleep(400);
        await ctx.rcon('execute as Probe run materione browse red gem');
        await waitForWindow(ctx);
        await ctx.shot('browse-search-red-gem');

        await ctx.exec('player.closeContainer(); return "closed"');
        await ctx.sleep(400);
        await ctx.rcon('execute as Probe run materione browse zzz no such thing');
        await waitForWindow(ctx);
        await ctx.shot('browse-search-empty');

        ctx.needsEyes(
          'browse-packs: a 54-slot window titled CONTENT BROWSER in small capitals, PAGE 1 OF 1 at the top right; one tile per pack '
          + '(a bookshelf, the pack name in yellow, "N items" in dark grey and "Click to open." in grey); the bottom row has the '
          + 'previous and next arrows, a Search compass, a paper "Where you are" reading "All packs", and a Close door; no Back '
          + 'arrow. browse-pack-nested: a chest named weapons (yellow); a Back arrow bottom left; the paper reads nested. '
          + 'browse-search-red-gem: the Red Gem itself, with its own model and name, its id and "Click: take one. Shift-click: '
          + 'take a stack." under it; a Clear the search barrier. browse-search-empty: no tile, NO RESULTS in grey in the middle, '
          + 'the Clear barrier present, PAGE 1 OF 1.',
        );
      } finally {
        await ctx.exec('player.closeContainer(); return "closed"');
        if (granted) await ctx.rcon('deop Probe');
      }
    },
  }),
];
