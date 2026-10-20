// Recipes suite: pack-authored recipes drive a real client through real
// containers (crafting table, furnace) — menus opened by the client, slots
// filled by packet clicks, the server's recipe matching decides the result.
// Auto mode — the runner judges pass/fail from assertions.

import { test } from './_lib.mjs';

// Menu-opener flow shared by every container this suite drives: reset to the
// scene anchor, clear whatever block a crashed earlier run left there (its
// container contents would spill onto the reset-clean ground and shift the
// hotbar layout when the player picks them up), place the block two blocks
// east (the anchor teleport makes it reachable), give ingredients, open it
// through the client (useItemOn with a hand-built BlockHitResult — the same
// surface a real player's right-click drives) and wait for the server to
// bind the menu. Returns the open menu's class and an inventory dump — the
// tests assert their ingredients from the dump, never from the give's reply
// (an rcon reply can come back empty even when the give landed).
async function openMenu(ctx, spot, block, ingredients) {
  await ctx.reset();
  await ctx.rcon(`setblock ${spot.x} ${spot.y} ${spot.z} minecraft:air`);
  await ctx.rcon(`kill @e[type=item,x=${spot.x},y=${spot.y},z=${spot.z},distance=..8]`);
  await ctx.rcon(`setblock ${spot.x} ${spot.y} ${spot.z} ${block}`);
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
  const menuClass = await ctx.exec(
    `return mc.player.containerMenu.@target.getClass().getSimpleName()`, 15_000);
  const inventory = await ctx.exec(`def out = []
def inv = mc.player.getInventory()
for (int i = 0; i < 46; i++) {
  def st = inv.getItem(i)
  if (!st.isEmpty()) out.add(st.getItem().toString() + ' x' + st.getCount() + ' -- ' + st.getHoverName().getString())
}
return out.join(' | ')`, 15_000);
  return { menuClass, inventory };
}

// Grid moves: for each hotbar slot, pickup its stack and right-click one
// item into each listed grid slot (1..9, row-major); the cursor remainder
// goes back onto the hotbar slot so the next fill picks it up again. The
// hotbar lives at menu slots 37..45.
async function fillGrid(ctx, cid, moves) {
  const script = moves
    .map((m) => `gm.handleContainerInput(cid, ${37 + m.hotbar}, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
for (int slot : new int[] {${m.gridSlots.join(', ')}}) {
  gm.handleContainerInput(cid, slot, 1, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
}
gm.handleContainerInput(cid, ${37 + m.hotbar}, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player)`)
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

// One furnace-menu interaction: pickup a stack (a hotbar slot by default, or
// `from` for an explicit menu slot) and place it — button 0 places the whole
// stack, button 1 a single item. Every move ends with the cursor empty
// (`returnTo` deposits the remainder somewhere specific; the default is the
// slot the move picked up from, where same-item stacks merge). Furnace
// slots: 0 input, 1 fuel, 2 result, 3..29 main inventory, and the hotbar at
// 30+ (unlike the crafting menu's 37+, this menu has no 3×3 grid in front).
async function furnaceMove(ctx, cid, moves) {
  const script = moves
    .map((m) => {
      const from = m.from !== undefined ? m.from : 30 + m.hotbar;
      const back = m.returnTo !== undefined ? m.returnTo : from;
      const lines = [`gm.handleContainerInput(cid, ${from}, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player)`];
      for (const slot of m.slots) {
        lines.push(`gm.handleContainerInput(cid, ${slot}, ${m.button ?? 0}, net.minecraft.world.inventory.ContainerInput.PICKUP, player)`);
      }
      if (back !== null) {
        lines.push(`gm.handleContainerInput(cid, ${back}, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player)`);
      }
      return lines.join('\n');
    })
    .join('\n');
  await ctx.exec(`def player = mc.player
def gm = mc.gameMode
int cid = ${cid}
${script}
return 'moved'`, 15_000);
}

// The furnace's live data slots — [litProgress, burnDuration,
// cookingProgress, cookingTotalTime] — read off the client's bound menu
// (server-synced). The ContainerData lives in an AbstractFurnaceMenu field
// this client's mappings name `data`; reflection, because the bridge's
// direct-field access does not walk superclasses.
async function furnaceData(ctx) {
  const s = await ctx.exec(`def m = mc.player.containerMenu.@target
def f = m.getClass().getSuperclass().getDeclaredField('data')
f.setAccessible(true)
def d = f.get(m)
return '' + d.get(0) + ',' + d.get(1) + ',' + d.get(2) + ',' + d.get(3)`, 15_000);
  return String(s).split(',').map((n) => parseInt(n, 10));
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
      const { menuClass } = await openMenu(
        ctx, { x: 2, y: -60, z: 3 }, 'minecraft:crafting_table', [
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

  test({
    id: 'recipes.shapeless',
    title: 'shapeless recipes craft at a real crafting table regardless of arrangement: sample:templated_ruby (ruby + minecraft:stick, distinct ingredients scattered in the grid) crafts one Templated Ruby, and sample:ruby_fuse (two rubies — duplicate identical ExactChoice ingredients, scattered differently) crafts a second that stacks into the inventory; a recipe whose duplicate custom ingredients Paper\'s stacked-contents cannot consume (corpus:amulet_blue, two identical AMETHYST_SHARD-material choices) never matches — #90, upstream PaperMC#10971',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/recipes/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/recipes/**',
      'fixtures/packs/sample/recipes/**',
    ],
    dependsOn: ['recipes.shaped'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const { menuClass, inventory } = await openMenu(
        ctx, { x: 2, y: -60, z: 3 }, 'minecraft:crafting_table', [
          'give Probe minecraft:stick 1',
          'materione give sample:ruby Probe 3',
        ]);
      ctx.expect(String(menuClass)).toBe('CraftingMenu');
      ctx.expect(inventory).toContain('x3 -- Ruby'); // the ingredients landed
      const cid = await ctx.exec('return mc.player.containerMenu.containerId', 15_000);

      // --- distinct ingredients, scattered: ruby top-left, stick mid-right
      const scattered = [
        { hotbar: 1, gridSlots: [1] },
        { hotbar: 0, gridSlots: [6] },
      ];
      await fillGrid(ctx, cid, scattered);
      ctx.expect(await resultSlotItem(ctx)).toBe('Templated Ruby'); // predicted per click
      const first = await takeResult(ctx, cid);
      ctx.expect(first).toContain('-- Templated Ruby');

      // --- duplicate identical custom ingredients (ruby + ruby), placed
      // differently again — the shapeless kind cares about the multiset
      const fused = [{ hotbar: 1, gridSlots: [3, 7] }];
      await fillGrid(ctx, cid, fused);
      ctx.expect(await resultSlotItem(ctx)).toBe('Templated Ruby');
      const second = await takeResult(ctx, cid);
      ctx.expect(second).toContain('x2 -- Templated Ruby');
      ctx.expect(await ctx.shot('shapeless-crafted')).toBeTruthy();

      await ctx.exec("mc.player.closeContainer()\nreturn 'closed'", 15_000);
    },
  }),

  test({
    id: 'recipes.furnace',
    title: 'furnace recipes smelt in a real furnace across all three ingredient shapes: sample:ruby_smelt (input sample:ruby — an ExactChoice on a PDC-tagged stack — cookingtime 100, experience 7) refuses a wrong input outright (nothing cooks, the furnace never lights), genuinely ignites for the ruby (litTime moves — the burnTotal/cookTotal data slots are sticky and lie), reads cookingTotalTime 100 off the live menu, cooks one ruby into a Diamond, consumes the input, and grants the recipe\'s experience on take (the player levels up); the corpus pack\'s gem_white → gem_black smelts custom-in/custom-out; sample:flesh_smelt smelts a vanilla MaterialChoice input (rotten_flesh → Leather)',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/recipes/**',
      'platform/src/main/java/dev/materione/platform/recipes/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/recipes/**',
      'fixtures/packs/sample/recipes/**',
    ],
    dependsOn: ['recipes.shapeless'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const spot = { x: 2, y: -60, z: 3 };
      const { menuClass } = await openMenu(
        ctx, spot, 'minecraft:furnace', [
          'give Probe minecraft:stick 1',
          'give Probe minecraft:coal 1',
          'materione give sample:ruby Probe 1',
          'materione give corpus:gem_white Probe 1',
        ]);
      ctx.expect(String(menuClass)).toBe('FurnaceMenu');
      const cid = await ctx.exec('return mc.player.containerMenu.containerId', 15_000);
      await ctx.rcon('xp set Probe 0 points'); // the experience grant is read off a zeroed bar

      // --- wrong input: a stick can never smelt here — the result slot
      // stays empty and the furnace never even lights (no fuel burns)
      await furnaceMove(ctx, cid, [
        { hotbar: 0, slots: [0] },            // whole stick into the input
        { hotbar: 1, slots: [1], button: 1 }, // one coal into the fuel
      ]);
      await ctx.sleep(1_500);
      const [wrongLit] = await furnaceData(ctx);
      ctx.expect(await ctx.exec(`def st = mc.player.containerMenu.slots.get(2).getItem()
return st.isEmpty() ? 'EMPTY' : st.getHoverName().getString()`, 15_000)).toBe('EMPTY');
      ctx.expect(wrongLit).toBe(0);
      // stick back to its hotbar slot — the move ends here (returnTo: null):
      // a trailing re-pickup would strand the stick on the cursor
      await furnaceMove(ctx, cid, [{ from: 0, slots: [30], returnTo: null }]);

      // --- the authored input: one ruby into the input, fuel already in
      // place. Ignition + recipe binding, polled — and read off the two
      // trustworthy slots: burnTotal/cookTotal stick at their last synced
      // values once set (a never-lit furnace still reports the fuel's
      // 1600), while litTime only moves when the furnace really burns.
      await furnaceMove(ctx, cid, [{ hotbar: 2, slots: [0] }]);
      const litDeadline = Date.now() + 5_000;
      let lit = 0;
      let total = 0;
      for (;;) {
        await ctx.sleep(400);
        [lit, , , total] = await furnaceData(ctx);
        if ((lit > 0 && total === 100) || Date.now() > litDeadline) break;
      }
      ctx.expect(lit).toBeGreaterThan(0); // the furnace genuinely lit for the ruby
      ctx.expect(total).toBe(100); // the authored cookingtime reached the live menu
      // --- real smelting: 100 ticks ≈ 5s, poll the result slot until the
      // diamond lands. Under a loaded runner session the server tick (and
      // the menu sync riding it) can lag far behind wall-clock — the diamond
      // provably finishes cooking well after an impatient window would give
      // up — so the poll keeps a generous deadline, and every pass pokes a
      // no-op PICKUP on an empty main-inventory slot first: the click makes
      // the server re-broadcast the container (stateId reconciliation), so
      // a stale client view cannot outlive the poll.
      let smelted = 'EMPTY';
      const deadline = Date.now() + 30_000;
      for (;;) {
        await ctx.sleep(700);
        await ctx.exec(`def player = mc.player
def gm = mc.gameMode
gm.handleContainerInput(${cid}, 10, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
return 'resynced'`, 15_000);
        smelted = await ctx.exec(`def st = mc.player.containerMenu.slots.get(2).getItem()
return st.isEmpty() ? 'EMPTY' : st.getHoverName().getString()`, 15_000);
        if (smelted !== 'EMPTY' || Date.now() > deadline) break;
      }
      if (smelted === 'EMPTY') {
        // diagnostic for a stalled cook: what does the SERVER-side block
        // entity hold, and what does the client menu hold after the resync?
        const serverNbt = await ctx.rcon(`data get block ${spot.x} ${spot.y} ${spot.z} Items`);
        const slots = await ctx.exec(`def d = mc.player.containerMenu.@target
def f0 = d.slots.get(0).getItem()
def f2 = d.slots.get(2).getItem()
def carried = d.carried
return 'input=' + (f0.isEmpty() ? 'EMPTY' : f0.getItem().toString() + ' x' + f0.getCount()) + ' result=' + (f2.isEmpty() ? 'EMPTY' : f2.getItem().toString() + ' x' + f2.getCount()) + ' carried=' + (carried.isEmpty() ? 'EMPTY' : carried.getItem().toString())`, 15_000);
        ctx.log('stalled-cook server Items: ' + serverNbt);
        ctx.log('stalled-cook menu: ' + slots);
      }
      ctx.expect(smelted).toBe('Diamond');

      // --- take it: the input is consumed, the result lands in the
      // inventory, and the recipe's experience pays out (0 → level 1 at 7 points)
      await ctx.exec(`def player = mc.player
def gm = mc.gameMode
gm.handleContainerInput(${cid}, 2, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player)
return 'taken'`, 15_000);
      const inputLeft = await ctx.exec(`return mc.player.containerMenu.slots.get(0).getItem().isEmpty()`, 15_000);
      ctx.expect(String(inputLeft)).toBe('true');
      await ctx.sleep(2_500); // the experience orb's pickup delay
      const level = await ctx.exec('return mc.player.experienceLevel', 15_000);
      ctx.expect(parseInt(level, 10)).toBeGreaterThan(0);

      // --- the corpus pack's furnace recipe: custom in (gem_white), custom
      // out — same resync-poked poll as the ruby cook
      await furnaceMove(ctx, cid, [{ hotbar: 3, slots: [0] }]);
      let corpusResult = 'EMPTY';
      const corpusDeadline = Date.now() + 30_000;
      for (;;) {
        await ctx.sleep(700);
        await ctx.exec(`def player = mc.player
def gm = mc.gameMode
gm.handleContainerInput(${cid}, 10, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
return 'resynced'`, 15_000);
        corpusResult = await ctx.exec(`def st = mc.player.containerMenu.slots.get(2).getItem()
return st.isEmpty() ? 'EMPTY' : st.getHoverName().getString()`, 15_000);
        if (corpusResult !== 'EMPTY' || Date.now() > corpusDeadline) break;
      }
      ctx.expect(corpusResult).toBe('Black Gem');
      await ctx.exec(`def player = mc.player
def gm = mc.gameMode
gm.handleContainerInput(${cid}, 2, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player)
return 'taken'`, 15_000);
      const corpusInv = await ctx.exec(`def out = []
def inv = mc.player.getInventory()
for (int i = 0; i < 46; i++) {
  def st = inv.getItem(i)
  if (!st.isEmpty()) out.add(st.getItem().toString() + ' x' + st.getCount() + ' -- ' + st.getHoverName().getString())
}
return out.join(' | ')`, 15_000);
      ctx.expect(corpusInv).toContain('-- Black Gem');

      // --- the vanilla-input path: a MaterialChoice furnace recipe (the
      // pack's flesh_smelt, no custom stack in sight); the coal's 80s burn
      // is still going — only ~25s of it has been spent
      await ctx.rcon('give Probe minecraft:rotten_flesh 1'); // lands on the first free hotbar slot (1)
      await furnaceMove(ctx, cid, [{ hotbar: 1, slots: [0] }]);
      let vanillaResult = 'EMPTY';
      const vanillaDeadline = Date.now() + 30_000;
      for (;;) {
        await ctx.sleep(700);
        await ctx.exec(`def player = mc.player
def gm = mc.gameMode
gm.handleContainerInput(${cid}, 10, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
return 'resynced'`, 15_000);
        vanillaResult = await ctx.exec(`def st = mc.player.containerMenu.slots.get(2).getItem()
return st.isEmpty() ? 'EMPTY' : st.getHoverName().getString()`, 15_000);
        if (vanillaResult !== 'EMPTY' || Date.now() > vanillaDeadline) break;
      }
      ctx.expect(vanillaResult).toBe('Leather');
      await ctx.exec(`def player = mc.player
def gm = mc.gameMode
gm.handleContainerInput(${cid}, 2, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player)
return 'taken'`, 15_000);
      const vanillaInv = await ctx.exec(`def out = []
def inv = mc.player.getInventory()
for (int i = 0; i < 46; i++) {
  def st = inv.getItem(i)
  if (!st.isEmpty()) out.add(st.getItem().toString() + ' x' + st.getCount() + ' -- ' + st.getHoverName().getString())
}
return out.join(' | ')`, 15_000);
      ctx.expect(vanillaInv).toContain('-- Leather');
      ctx.expect(await ctx.shot('furnace-smelted')).toBeTruthy();

      await ctx.exec("mc.player.closeContainer()\nreturn 'closed'", 15_000);
    },
  }),
];
