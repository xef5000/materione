import { test } from './_lib.mjs';

const covers = [
  'content/src/main/java/dev/materione/content/gui/**',
  'plugin/src/main/java/dev/materione/plugin/gui/**',
  'pack/src/main/java/dev/materione/pack/allocation/**',
  'fixtures/packs/overlay/**',
];

async function click(ctx, slot = 11, kind = 'PICKUP') {
  await ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, 0,
    net.minecraft.world.inventory.ContainerInput.${kind}, player); return 'clicked'`);
}

async function title(ctx) {
  return String(await ctx.exec("return mc.screen == null ? '' : mc.screen.getTitle().getString()"));
}

async function windowState(ctx) {
  return String(await ctx.exec(`return (mc.screen != null) + ':' + player.containerMenu.containerId + ':' +
    player.containerMenu.slots.size()`));
}

async function assertOpen(ctx, slots) {
  const state = (await windowState(ctx)).split(':');
  ctx.expect(state[0]).toBe('true');
  ctx.expect(Number(state[1]) !== 0).toBe(true);
  ctx.expect(Number(state[2])).toBe(slots);
  return state[1];
}

async function stack(ctx, expression) {
  return String(await ctx.exec(`def s = ${expression}
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

async function assertNoDrops(ctx) {
  ctx.expect(await ctx.rcon('execute at Probe if entity @e[type=item,distance=..16]')).toBe('Test failed');
}

async function slotType(ctx, slot) {
  return String(await ctx.exec(`return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
    player.containerMenu.slots.get(${slot}).getItem().getItem()).toString()`));
}

async function waitForSlot(ctx, slot, expected) {
  for (let i = 0; i < 30; i++) {
    if (await slotType(ctx, slot) === expected) return;
    await ctx.sleep(200);
  }
  ctx.expect(await slotType(ctx, slot)).toBe(expected);
}

async function open(ctx) {
  await ctx.reset();
  await ctx.rcon('materione give corpus:gem_red Probe 3');
  await ctx.waitForServerLog(/MATERIONE-GIVE-OK id=corpus:gem_red player=Probe amount=3/);
  await ctx.rcon('materione gui open overlay:tabs Probe');
  await ctx.waitForServerLog(/MATERIONE-OVERLAY-OPEN/);
  await ctx.sleep(700);
}

async function waitForTitle(ctx, shown) {
  for (let i = 0; i < 30; i++) {
    const text = await title(ctx);
    if (text !== '' && text.includes('ACTIVE Probe') === shown) return;
    await ctx.sleep(200);
  }
  ctx.expect((await title(ctx)) !== '').toBe(true);
  ctx.expect((await title(ctx)).includes('ACTIVE Probe')).toBe(shown);
}

async function stableWindow(ctx, slots, cursor) {
  const id = await assertOpen(ctx, slots);
  // refresh: 10 means this covers at least two unchanged refresh periods.
  for (let i = 0; i < 8; i++) {
    await ctx.sleep(200);
    ctx.expect(await assertOpen(ctx, slots)).toBe(id);
    ctx.expect(await stack(ctx, 'player.containerMenu.getCarried()')).toBe(cursor);
  }
}

async function waitForDifferentTitle(ctx, previous) {
  for (let i = 0; i < 40; i++) {
    const text = await title(ctx);
    if (text !== '' && text !== previous) return text;
    await ctx.sleep(100);
  }
  const text = await title(ctx);
  ctx.expect(text !== '' && text !== previous).toBe(true);
  return text;
}

async function openAnimation(ctx) {
  await ctx.reset();
  await ctx.rcon('materione gui open overlay:animation Probe');
  await ctx.waitForServerLog(/MATERIONE-OVERLAY-ANIMATION-OPEN/);
  await ctx.sleep(600);
  await assertOpen(ctx, 63);
}

export default [
  test({
    id: 'guioverlay.toggle-auto',
    title: 'overlay:tabs toggles conditional image and templated text in the same GUI session',
    covers,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await open(ctx);
        await waitForTitle(ctx, false);
        const containerId = await assertOpen(ctx, 63);
        await click(ctx, 54); // three-row chest: first hotbar slot
        await ctx.sleep(400);
        ctx.expect(await stack(ctx, 'player.containerMenu.getCarried()')).toBe('Red Gem:3');
        await click(ctx);
        await waitForTitle(ctx, true);
        ctx.expect(await assertOpen(ctx, 63)).toBe(containerId);
        await stableWindow(ctx, 63, 'Red Gem:3');
        await click(ctx);
        await waitForTitle(ctx, false);
        ctx.expect(await assertOpen(ctx, 63)).toBe(containerId);
        await stableWindow(ctx, 63, 'Red Gem:3');
      } finally {
        await ctx.exec("player.closeContainer(); return 'closed'");
      }
    },
  }),
  test({
    id: 'guioverlay.toggle-visual',
    title: 'overlay:tabs shows and hides the selected image and ACTIVE Probe label when toggled',
    mode: 'visual',
    dependsOn: ['guioverlay.toggle-auto'],
    covers,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await open(ctx);
        await waitForTitle(ctx, false);
        await assertOpen(ctx, 63);
        await ctx.shot('tab-hidden');
        await click(ctx);
        await waitForTitle(ctx, true);
        await assertOpen(ctx, 63);
        await ctx.shot('tab-shown');
        ctx.needsEyes('Compare screenshots: the selected image and ACTIVE Probe text are absent before and visible after the toggle; background and slot items remain stable.');
      } finally {
        await ctx.exec("player.closeContainer(); return 'closed'");
      }
    },
  }),
  test({
    id: 'guioverlay.input-ownership',
    title: 'overlay:ownership conserves gems when a pre-change refresh covers a shift-out, including cancellation and uncover',
    covers: [...covers, 'fixtures/packs/corpus/items/gem_red.yml'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        for (const cancelled of [false, true]) {
          await ctx.reset();
          await ctx.rcon('materione give corpus:gem_red Probe 3');
          await ctx.waitForServerLog(/MATERIONE-GIVE-OK id=corpus:gem_red player=Probe amount=3/);
          await ctx.rcon(`materione gui open overlay:ownership Probe cancel=${cancelled ? '1' : '0'}`);
          await ctx.waitForServerLog(/MATERIONE-OVERLAY-OWNERSHIP-OPEN/);
          await ctx.sleep(600);
          await assertOpen(ctx, 45);
          await click(ctx, 36);
          await ctx.sleep(300);
          ctx.expect(await stack(ctx, 'player.containerMenu.getCarried()')).toBe('Red Gem:3');
          await click(ctx, 4);
          await ctx.sleep(400);
          ctx.expect(await stack(ctx, 'player.containerMenu.slots.get(4).getItem()')).toBe('Red Gem:3');
          await click(ctx, 4, 'QUICK_MOVE');
          await waitForSlot(ctx, 4, 'minecraft:barrier');
          await ctx.sleep(400);
          await assertOpen(ctx, 45);
          if (cancelled) await ctx.waitForServerLog(/MATERIONE-OVERLAY-OWNERSHIP-CANCEL/);
          ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(3);
          ctx.expect(await inventoryCount(ctx, 'Barrier')).toBe(0);
          ctx.expect(await stack(ctx, 'player.containerMenu.getCarried()')).toBe('empty');
          await assertNoDrops(ctx);

          // Uncovering restores the input; the display item must never enter the player's inventory.
          await click(ctx, 8);
          await waitForSlot(ctx, 4, 'minecraft:air');
          ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(3);
          ctx.expect(await inventoryCount(ctx, 'Barrier')).toBe(0);
          // Shift-out uses the main inventory first; cancellation returns via Bukkit's first free slot.
          const returnedSlot = cancelled ? 36 : 9;
          ctx.expect(await stack(ctx, `player.containerMenu.slots.get(${returnedSlot}).getItem()`)).toBe('Red Gem:3');
          await click(ctx, returnedSlot);
          await ctx.sleep(300);
          await click(ctx, 4);
          await ctx.sleep(400);
          ctx.expect(await stack(ctx, 'player.containerMenu.slots.get(4).getItem()')).toBe('Red Gem:3');
          ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(0);
          // Normal covering returns the occupied input once, not the widget.
          await click(ctx, 8);
          await waitForSlot(ctx, 4, 'minecraft:barrier');
          await ctx.sleep(400);
          ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(3);
          ctx.expect(await inventoryCount(ctx, 'Barrier')).toBe(0);
          await assertNoDrops(ctx);
          await ctx.exec("player.closeContainer(); return 'closed'");
          await ctx.sleep(400);
          ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(3);
          ctx.expect(await inventoryCount(ctx, 'Barrier')).toBe(0);
          await assertNoDrops(ctx);
        }
      } finally {
        await ctx.exec("player.closeContainer(); return 'closed'");
      }
    },
  }),
  test({
    id: 'guioverlay.animation-auto',
    title: 'overlay:animation changes raster frames repeatedly without resetting the window clock',
    covers,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openAnimation(ctx);
        const containerId = await assertOpen(ctx, 63);
        const first = await title(ctx);
        const second = await waitForDifferentTitle(ctx, first);
        ctx.expect(await assertOpen(ctx, 63)).toBe(containerId);
        const third = await waitForDifferentTitle(ctx, second);
        ctx.expect(await assertOpen(ctx, 63)).toBe(containerId);
        const fourth = await waitForDifferentTitle(ctx, third);
        ctx.expect(third).toBe(first);
        ctx.expect(fourth).toBe(second);
        ctx.expect(await assertOpen(ctx, 63)).toBe(containerId);
      } finally {
        await ctx.exec("player.closeContainer(); return 'closed'");
      }
    },
  }),
  test({
    id: 'guioverlay.animation-visual',
    title: 'overlay:animation displays its image at the two authored raster-frame positions',
    mode: 'visual',
    dependsOn: ['guioverlay.animation-auto'],
    covers,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await openAnimation(ctx);
        const first = await title(ctx);
        await ctx.shot('animation-first');
        await waitForDifferentTitle(ctx, first);
        await assertOpen(ctx, 63);
        await ctx.shot('animation-second');
        ctx.needsEyes('The banner moves between the two positions; the background and container remain stable.');
      } finally {
        await ctx.exec("player.closeContainer(); return 'closed'");
      }
    },
  }),
  test({
    id: 'guioverlay.animation-quick-craft',
    title: 'overlay:animation_input conserves gems across a frame change during packet-driven quick-craft and later shift-out',
    covers: [...covers, 'fixtures/packs/corpus/items/gem_red.yml'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await ctx.reset();
        await ctx.rcon('materione give corpus:gem_red Probe 3');
        await ctx.waitForServerLog(/MATERIONE-GIVE-OK id=corpus:gem_red player=Probe amount=3/);
        await ctx.rcon('materione gui open overlay:animation_input Probe');
        await ctx.waitForServerLog(/MATERIONE-OVERLAY-INTERACTION-OPEN/);
        await ctx.sleep(600);
        const id = await assertOpen(ctx, 45);
        await click(ctx, 36);
        await ctx.sleep(300);
        ctx.expect(await stack(ctx, 'player.containerMenu.getCarried()')).toBe('Red Gem:3');
        const first = await title(ctx);
        await ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, -999, 0,
          net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT, player)
mc.gameMode.handleContainerInput(player.containerMenu.containerId, 0, 1,
          net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT, player)
return 'quick-craft started; screen=' + System.identityHashCode(mc.screen) +
  ' client-menu=' + System.identityHashCode(player.containerMenu)`);
        await waitForDifferentTitle(ctx, first);
        ctx.expect(await assertOpen(ctx, 45)).toBe(id);
        await ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, -999, 2,
          net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT, player)
return 'quick-craft ended; screen=' + System.identityHashCode(mc.screen) +
  ' client-menu=' + System.identityHashCode(player.containerMenu)`);
        await ctx.sleep(500);
        const total = Number(await ctx.exec(`def total = 0
def stacks = [player.containerMenu.getCarried()]
for (int i = 0; i < player.containerMenu.slots.size(); i++) stacks.add(player.containerMenu.slots.get(i).getItem())
for (def s : stacks) if (!s.isEmpty() && s.getHoverName().getString() == 'Red Gem') total += s.getCount()
return total`));
        ctx.expect(total).toBe(3);
        await assertNoDrops(ctx);
        // A fresh quick move after the transition must still use the same open container.
        if (await stack(ctx, 'player.containerMenu.getCarried()') === 'Red Gem:3') {
          await click(ctx, 0);
          await ctx.sleep(300);
        }
        ctx.expect(await stack(ctx, 'player.containerMenu.slots.get(0).getItem()')).toBe('Red Gem:3');
        await click(ctx, 0, 'QUICK_MOVE');
        await ctx.sleep(500);
        ctx.expect(await assertOpen(ctx, 45)).toBe(id);
        ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(3);
        ctx.expect(await stack(ctx, 'player.containerMenu.getCarried()')).toBe('empty');
        await assertNoDrops(ctx);
      } finally {
        await ctx.exec("player.closeContainer(); return 'closed'");
      }
    },
  }),
];
