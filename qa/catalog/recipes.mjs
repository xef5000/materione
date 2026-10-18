// Recipes suite: pack-authored crafting recipes drive a real client through
// a real crafting table (menu opened by the client, grid filled by packet
// clicks, server recipe matching decides the result). Auto mode — the runner
// judges pass/fail from assertions.

import { test } from './_lib.mjs';

// The crafting-table flow: reset to the scene anchor, place a table two
// blocks east (the anchor teleport makes it reachable), give ingredients,
// open it through the client (useItemOn with a hand-built BlockHitResult —
// the same surface a real player's right-click drives) and wait for the
// server to bind the menu.
async function openCraftingTable(ctx, spot, ingredients) {
  await ctx.reset();
  await ctx.rcon(`setblock ${spot.x} ${spot.y} ${spot.z} minecraft:crafting_table`);
  for (const command of ingredients) {
    await ctx.rcon(command);
  }
  await ctx.exec(`def player = mc.player
def hit = new net.minecraft.world.phys.BlockHitResult(
    new net.minecraft.world.phys.Vec3(${spot.x}.0, ${spot.y + 0.5}, ${spot.z + 0.5}),
    net.minecraft.core.Direction.WEST,
    new net.minecraft.core.BlockPos(${spot.x}, ${spot.y}, ${spot.z}), false)
mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)
return 'opened'`, 15_000);
  await ctx.sleep(800); // the open packet round-trips before the menu is bound
  return ctx.exec(
    `return mc.player.containerMenu.@target.getClass().getSimpleName()`, 15_000);
}

// Grid moves: for each hotbar slot, pickup its stack and right-click one
// item into each listed grid slot (1..9, row-major). The hotbar lives at
// menu slots 37..45.
async function fillGrid(ctx, cid, moves) {
  const script = moves
    .map((m) => `gm.handleContainerInput(cid, ${37 + m.hotbar}, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
for (int slot : new int[] {${m.gridSlots.join(', ')}}) {
  gm.handleContainerInput(cid, slot, 1, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
}`)
    .join('\n');
  await ctx.exec(`def player = mc.player
def gm = mc.gameMode
int cid = ${cid}
${script}
return 'filled'`, 15_000);
}

// The inverse of fillGrid: pickup each filled grid slot and dump the cursor
// stack back onto its hotbar slot (same-item stacks merge).
async function clearGrid(ctx, cid, moves) {
  const script = moves
    .map((m) => `for (int slot : new int[] {${m.gridSlots.join(', ')}}) {
  gm.handleContainerInput(cid, slot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
  gm.handleContainerInput(cid, ${37 + m.hotbar}, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
}`)
    .join('\n');
  await ctx.exec(`def player = mc.player
def gm = mc.gameMode
int cid = ${cid}
${script}
return 'cleared'`, 15_000);
}

// The predicted result slot (the server re-matches on every grid change and
// sends the slot back — settle before reading).
async function resultSlotItem(ctx) {
  await ctx.sleep(500);
  return ctx.exec(`def st = mc.player.containerMenu.slots.get(0).getItem()
return st.isEmpty() ? 'EMPTY' : st.getHoverName().getString()`, 15_000);
}

// Take the crafted result into the inventory and list every non-empty
// inventory stack as "material xN -- name" for assertions.
async function takeResult(ctx, cid) {
  await ctx.exec(`def player = mc.player
def gm = mc.gameMode
gm.handleContainerInput(${cid}, 0, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player)
return 'taken'`, 15_000);
  return ctx.exec(`def out = []
def inv = mc.player.getInventory()
for (int i = 0; i < 46; i++) {
  def st = inv.getItem(i)
  if (!st.isEmpty()) out.add(st.getItem().toString() + ' x' + st.getCount() + ' -- ' + st.getHoverName().getString())
}
return out.join(' | ')`, 15_000);
}

export default [
  test({
    id: 'recipes.shaped',
    title: 'sample:ruby_sword (shaped: " R / R / S " — two sample:ruby above a minecraft:stick) crafts at a real crafting table: the wrong arrangement (rubies across the top row) yields nothing, the authored center-column shape crafts one Ruby Sword — the pack item (pack display name on the vanilla base material) — straight into the inventory',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/recipes/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/recipes/**',
      'fixtures/packs/sample/recipes/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const menuClass = await openCraftingTable(ctx, { x: 2, y: -60, z: 3 }, [
        'give Probe minecraft:stick 1',
        'materione give sample:ruby Probe 2',
      ]);
      ctx.expect(String(menuClass)).toBe('CraftingMenu');
      const cid = await ctx.exec('return mc.player.containerMenu.containerId', 15_000);

      // --- wrong arrangement: rubies across the top row, stick top-right —
      // no arrangement (mirrored included) matches the sword's column
      const wrong = [
        { hotbar: 1, gridSlots: [1, 2] },
        { hotbar: 0, gridSlots: [3] },
      ];
      await fillGrid(ctx, cid, wrong);
      ctx.expect(await resultSlotItem(ctx)).toBe('EMPTY');
      const nothing = await takeResult(ctx, cid);
      ctx.expect(nothing.includes('Ruby Sword')).toBe(false);
      await clearGrid(ctx, cid, wrong);

      // --- the authored shape: rubies at (0,1) and (1,1), stick at (2,1)
      const right = [
        { hotbar: 1, gridSlots: [2, 5] },
        { hotbar: 0, gridSlots: [8] },
      ];
      await fillGrid(ctx, cid, right);
      const inv = await takeResult(ctx, cid);
      // the crafted stack is the ruby_sword's vanilla base material carrying
      // the pack display name — the recipe's result went through the pack
      // item pipeline, not a vanilla output
      ctx.expect(inv).toContain('-- Ruby Sword');
      ctx.expect(await ctx.shot('shaped-crafted')).toBeTruthy();

      await ctx.exec("mc.player.closeContainer()\nreturn 'closed'", 15_000);
    },
  }),
];
