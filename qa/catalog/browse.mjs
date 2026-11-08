// Browse suite: the in-game content browser (M6, /materione browse) on a real server and client. The auto entries
// open the browser (typed as the probe player), search it, take an item with real container clicks, and read the
// result back from the window, the chat and the inventory. The visual entry captures the window for a human pass.

import { test } from './_lib.mjs';

const COVERS = [
  'plugin/src/main/java/dev/materione/plugin/gui/browse/**',
  'plugin/src/main/java/dev/materione/plugin/command/MaterioneCommand.java',
  'plugin/src/main/java/dev/materione/plugin/command/MaterioneCommandAdapter.java',
  'plugin/src/main/java/dev/materione/plugin/gui/GuiListener.java',
  'plugin/src/main/java/dev/materione/plugin/items/Give.java',
  'content/src/main/java/dev/materione/content/items/ItemType.java',
  'fixtures/packs/corpus/items/**',
];

/** The display name of the client's open window at a slot, or 'empty'. */
function slotName(ctx, slot) {
  return ctx.exec(`def s = player.containerMenu.getSlot(${slot}).getItem()
return s.isEmpty() ? 'empty' : s.getHoverName().getString()`);
}

/** A real container click from the client: button 0 is a left click, 1 a right click. */
function clickSlot(ctx, slot, button = 0, input = 'PICKUP') {
  return ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, ${button}, net.minecraft.world.inventory.ContainerInput.${input}, player)
return 'clicked'`);
}

async function inventoryCount(ctx) {
  return Number(await ctx.exec(
    'def total = 0; for (int i = 0; i < 36; i++) { def s = player.getInventory().items.get(i); if (!s.isEmpty()) total += s.getCount() }; return total',
  ));
}

export default [
  test({
    id: 'browse.search-finds-and-gives',
    title: 'typed /materione browse with the words "red gem" opens the browser filtered to the one matching item; a left click in the client hands one Red Gem into the inventory',
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const granted = (await ctx.rcon('op Probe')).includes('Made Probe a server operator');
      try {
        await ctx.reset();
        await ctx.rcon('execute as Probe run materione browse red gem');
        await ctx.waitForServerLog(/MATERIONE-BROWSE-OPEN rows=\d+ searched=true/);
        await ctx.sleep(900);
        ctx.expect(await slotName(ctx, 0)).toBe('Red Gem');
        ctx.expect(await slotName(ctx, 1)).toBe('empty');
        const before = await inventoryCount(ctx);
        await clickSlot(ctx, 0);
        await ctx.waitForChat(/Given corpus:gem_red ×1/);
        await ctx.sleep(400);
        ctx.expect(await inventoryCount(ctx)).toBe(before + 1);
        await ctx.shot('browse-search-gave-one');
      } finally {
        if (granted) await ctx.rcon('deop Probe');
      }
    },
  }),

  test({
    id: 'browse.search-without-a-match',
    title: 'a search for words no item carries opens the browser with the "Nothing matches" message and no entries',
    covers: COVERS,
    dependsOn: ['browse.search-finds-and-gives'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const granted = (await ctx.rcon('op Probe')).includes('Made Probe a server operator');
      try {
        await ctx.reset();
        await ctx.rcon('execute as Probe run materione browse zzz no such thing');
        await ctx.waitForServerLog(/MATERIONE-BROWSE-OPEN rows=\d+ searched=true/);
        await ctx.sleep(900);
        ctx.expect(await slotName(ctx, 0)).toBe('empty');
        ctx.expect(String(await slotName(ctx, 22))).toContain('Nothing matches');
      } finally {
        if (granted) await ctx.rcon('deop Probe');
      }
    },
  }),

  test({
    id: 'browse.visual',
    title: 'the content browser as a player sees it: the categories screen, a search that finds the Red Gem, and a search that finds nothing',
    mode: 'visual',
    covers: COVERS,
    dependsOn: ['browse.search-finds-and-gives', 'browse.search-without-a-match'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const granted = (await ctx.rcon('op Probe')).includes('Made Probe a server operator');
      try {
        await ctx.reset();
        await ctx.rcon('execute as Probe run materione browse');
        await ctx.waitForServerLog(/MATERIONE-BROWSE-OPEN rows=\d+ searched=false/);
        await ctx.sleep(900);
        await ctx.shot('browse-categories');

        await ctx.rcon('execute as Probe run materione browse red gem');
        await ctx.waitForServerLog(/MATERIONE-BROWSE-OPEN rows=\d+ searched=true/);
        await ctx.sleep(900);
        await ctx.shot('browse-search-red-gem');

        await ctx.rcon('execute as Probe run materione browse zzz no such thing');
        await ctx.waitForServerLog(/MATERIONE-BROWSE-OPEN rows=\d+ searched=true/);
        await ctx.sleep(900);
        await ctx.shot('browse-search-empty');

        ctx.needsEyes(
          'browse-categories: a 54-slot window titled "Materione · Browse"; one tile per category (its first item as the icon, '
          + 'the label in aqua, the count in grey); the bottom row has a Search compass, a page indicator naming the category count, '
          + 'and Close. browse-search-red-gem: one tile, the Red Gem itself, with its id, its category and the take hint in '
          + 'the lore; a Back book in the bottom row. browse-search-empty: a barrier named Nothing matches "zzz no such thing" '
          + 'in the middle, with the page indicator reading 0 found.',
        );
      } finally {
        if (granted) await ctx.rcon('deop Probe');
      }
    },
  }),
];
