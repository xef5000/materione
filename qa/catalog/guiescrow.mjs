// Crash-safe input slots, row P (roadmap M6, docs/architecture/gui-input-durability.md section 12, issue 310): a
// `durable: required` input layer takes the escrow path. The fixture GUI guilogic:escrow (fixtures/packs/guilogic/guis/
// escrow.yml) is a one-row chest with such a layer. The player's real container clicks (the client's own
// `handleContainerInput`) run the whole path: the click packet, the policy table, the manual click, the one tier-1
// step with the account. Nothing here claims crash-safety: a kill-boundary run is row H (#313), and these entries are
// not blessed (blessing runs happen on main).
//
// Not covered here, by design, because the probe loop cannot do them yet: `guiescrow.refused-unsupported` (a server
// with player-data saving disabled or an unwritable storage/escrow/), `guiescrow.recovery-message` and the legacy
// `gui_inputs` migration on a real join after a kill (both need the probe.mjs server-restart verb: row Q, #312). The
// unit tests (Tier1LifecycleTest, EscrowCapabilityTest) hold their logic.

import { test } from './_lib.mjs';

const COVERS = [
  'plugin/src/main/java/dev/materione/plugin/escrow/**',
  'plugin/src/main/java/dev/materione/plugin/gui/input/**',
  'plugin/src/main/java/dev/materione/plugin/gui/window/BukkitWindowHost.java',
  'plugin/src/main/java/dev/materione/plugin/gui/GuiListener.java',
  'plugin/src/main/java/dev/materione/plugin/command/MaterioneCommand.java',
  'fixtures/packs/guilogic/guis/escrow.yml',
  'fixtures/packs/guilogic/guis/trash.yml',
  'fixtures/packs/guilogic/guis/consume.yml',
  'plugin/src/main/java/dev/materione/plugin/logic/action/ConsumeCustodyAction.java',
  'plugin/src/main/resources/recipes/trash_slot.yml',
  'qa/catalog/guiescrow.mjs',
];

/** A real container click from the client: button 0 is a left click, 1 a right click. */
function click(ctx, slot, button = 0) {
  return ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, ${button}, net.minecraft.world.inventory.ContainerInput.PICKUP, player)
return 'clicked'`);
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

async function openEscrow(ctx) {
  await ctx.reset();
  await ctx.rcon('materione gui open guilogic:escrow Probe');
  await ctx.waitForServerLog(/MATERIONE-GUIESCROW-OPEN guilogic:escrow/);
  await ctx.sleep(600); // the client finishes opening the window
}

/** Opens a guilogic GUI for the probe player and waits for its open marker; the runner reset the baseline at the test's start. */
async function openGui(ctx, gui, marker) {
  await ctx.rcon(`materione gui open ${gui} Probe`);
  await ctx.waitForServerLog(marker);
  await ctx.sleep(600); // the client finishes opening the window
}

async function closeWindow(ctx) {
  try {
    await ctx.exec('player.closeContainer(); return "closed"');
  } catch { /* nothing open */ }
}

/** Places three Red Gems in slot 4 and lifts them to the cursor; `shot` captures the two states when set. */
async function placeAndLift(ctx, shot) {
  await ctx.rcon('materione give corpus:gem_red Probe 3');
  await openEscrow(ctx);
  // the nine-slot chest's player hotbar occupies raw slots 36..44
  await click(ctx, 36);
  await ctx.sleep(400);
  ctx.expect(await cursorStack(ctx)).toBe('Red Gem:3');
  await click(ctx, 4);
  await ctx.sleep(400);
  ctx.expect(await slotStack(ctx, 4)).toBe('Red Gem:3');
  ctx.expect(await cursorStack(ctx)).toBe('empty');
  if (shot) {
    await ctx.shot('guiescrow-placed');
  }
  await click(ctx, 4);
  await ctx.sleep(400);
  ctx.expect(await slotStack(ctx, 4)).toBe('empty');
  ctx.expect(await cursorStack(ctx)).toBe('Red Gem:3');
  if (shot) {
    await ctx.shot('guiescrow-lifted');
  }
}

export default [
  test({
    id: 'guiescrow.pickup-lifts-to-cursor-auto',
    title: 'guilogic:escrow: a stack placed in a durable: required slot stays there, and a pickup lifts it to the cursor as in vanilla (the account loses it in the same step: the cursor is outside the guarantee)',
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await placeAndLift(ctx, false);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guiescrow.pickup-lifts-to-cursor',
    title: 'guilogic:escrow: the stack placed in a durable: required slot, then lifted to the cursor, as the player sees it',
    mode: 'visual',
    dependsOn: ['guiescrow.pickup-lifts-to-cursor-auto'],
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await placeAndLift(ctx, true);
        ctx.needsEyes('First shot: three Red Gems sit in the fifth slot of the one-row Escrow chest and the cursor is empty. Second shot: the slot is empty and the three Red Gems are on the cursor, exactly as a vanilla chest pickup looks, with no refusal line on the actionbar.');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guiescrow.owed-claim',
    title: 'guilogic:escrow: closing with a full inventory keeps the stack owed (told on the actionbar, nothing dropped in the world), and /materione claim returns it once there is room',
    covers: COVERS,
    timeoutMs: 300_000,
    steps: async (ctx) => {
      try {
        await ctx.rcon('materione give corpus:gem_red Probe 3');
        await openEscrow(ctx);
        await click(ctx, 36);
        await click(ctx, 4);
        await ctx.sleep(400);
        ctx.expect(await slotStack(ctx, 4)).toBe('Red Gem:3');
        // fill every free storage slot, then close: nothing fits
        for (let i = 0; i < 4; i++) {
          await ctx.rcon('give Probe minecraft:dirt 64');
        }
        await ctx.rcon('give Probe minecraft:stone 64');
        await ctx.rcon('give Probe minecraft:cobblestone 64');
        await ctx.rcon('give Probe minecraft:sand 64');
        await ctx.rcon('give Probe minecraft:gravel 64');
        for (const block of ['oak_log', 'birch_log', 'spruce_log', 'jungle_log', 'acacia_log', 'dark_oak_log', 'glass', 'clay',
          'bricks', 'netherrack', 'obsidian', 'sandstone', 'granite', 'diorite', 'andesite', 'oak_planks', 'birch_planks',
          'spruce_planks', 'jungle_planks', 'acacia_planks', 'dark_oak_planks', 'coal_block', 'iron_block', 'gold_block',
          'lapis_block', 'redstone_block', 'tuff', 'deepslate', 'calcite', 'dripstone_block', 'moss_block']) {
          await ctx.rcon(`give Probe minecraft:${block} 64`);
        }
        await ctx.sleep(600);
        await closeWindow(ctx);
        await ctx.sleep(800);
        ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(0);
        const owed = (await ctx.chat(40)).join('\n');
        ctx.expect(owed).toContain('owed to you');
        // never dropped into the world: no item entity near the player carries the gem
        const drops = String(await ctx.rcon('execute as Probe at @s run data get entity @e[type=item,distance=..8,limit=1] Item.id'));
        ctx.expect(drops).not.toContain('gem_red');

        await ctx.rcon('clear Probe minecraft:dirt');
        await ctx.sleep(400);
        await ctx.command('materione claim');
        await ctx.sleep(800);
        ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(3);
        ctx.expect((await ctx.chat(40)).join('\n')).toContain('You received');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guiescrow.death-release',
    title: 'guilogic:escrow: dying with a stack in a durable: required slot drops it with the death drops when keepInventory is off, and keeps it in the inventory when it is on',
    covers: COVERS,
    timeoutMs: 300_000,
    steps: async (ctx) => {
      try {
        for (const keep of ['false', 'true']) {
          await ctx.rcon(`gamerule keepInventory ${keep}`);
          await ctx.rcon('materione give corpus:gem_red Probe 3');
          await openEscrow(ctx);
          await click(ctx, 36);
          await click(ctx, 4);
          await ctx.sleep(400);
          ctx.expect(await slotStack(ctx, 4)).toBe('Red Gem:3');
          await ctx.rcon('kill Probe');
          await ctx.sleep(800);
          const held = String(await ctx.rcon('data get entity Probe Inventory'));
          const ground = String(await ctx.rcon('execute as Probe at @s run data get entity @e[type=item,distance=..8,limit=1,sort=nearest] Item.id'));
          if (keep === 'true') {
            ctx.expect(held).toContain('gem_red');
          } else {
            ctx.expect(held).not.toContain('gem_red');
            ctx.expect(ground).toContain('gem_red'); // dropped at the death location with the rest of the drops
          }
          await ctx.rcon('kill @e[type=item]');
          await ctx.exec('mc.player.respawn(); return "respawned"');
          await ctx.sleep(1200);
        }
      } finally {
        await ctx.rcon('gamerule keepInventory false');
        await closeWindow(ctx);
      }
    },
  }),

  // ---- the custody consume primitive (#248, logic.md section 36) -----------------------------------------------------
  // guilogic:trash is the built-in recipe `trash_slot` expanded: an item put into the durable: required layer is consumed
  // by the flow (a one-tick wait, then custody.consume). guilogic:consume has two buttons: slot 7 consumes what slot 0
  // holds and the flow goes on once it is decided, slot 8 does the same with `await: saved`, so its effect (a console
  // marker) runs only after a saved snapshot containing the consume was read back from disk. Not blessed here.

  test({
    id: 'guiescrow.trash-consumes',
    title: 'guilogic:trash (the trash_slot recipe): an item put into the trash slot is consumed by the flow, atomically through the tier-2 exit, and does not come back to the cursor, the inventory or the world',
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await ctx.rcon('materione give corpus:gem_red Probe 3');
        await openGui(ctx, 'guilogic:trash', /MATERIONE-GUITRASH-OPEN guilogic:trash/);
        await click(ctx, 36);
        await click(ctx, 4);
        await ctx.waitForServerLog(/MATERIONE-GUITRASH-DONE slot=4 item=corpus:gem_red amount=3/, { timeoutMs: 30_000 });
        await ctx.sleep(600);
        ctx.expect(await slotStack(ctx, 4)).toBe('empty');
        ctx.expect(await cursorStack(ctx)).toBe('empty');
        ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(0);
        ctx.expect(ctx.serverLogText(/MATERIONE-GUITRASH-REFUSED/)).toBe(null);
        const drops = String(await ctx.rcon('execute as Probe at @s run data get entity @e[type=item,distance=..8,limit=1] Item.id'));
        ctx.expect(drops).not.toContain('gem_red');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guiescrow.trash-feedback',
    title: 'guilogic:trash: after the item is trashed the slot is empty and the player sees the recipe\'s "Trashed." line on the actionbar',
    mode: 'visual',
    dependsOn: ['guiescrow.trash-consumes'],
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await ctx.rcon('materione give corpus:gem_red Probe 3');
        await openGui(ctx, 'guilogic:trash', /MATERIONE-GUITRASH-OPEN guilogic:trash/);
        await click(ctx, 36);
        await click(ctx, 4);
        await ctx.waitForServerLog(/MATERIONE-GUITRASH-DONE slot=4/, { timeoutMs: 30_000 });
        await ctx.sleep(250);
        await ctx.shot('guiescrow-trashed');
        ctx.needsEyes('The one-row Trash chest is open with every slot empty (the Red Gems are gone, nothing on the cursor) and the grey line "Trashed." shows on the actionbar above the hotbar.');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guiescrow.consume-effect-after-save',
    title: 'guilogic:consume: custody.consume continues the flow once decided (plain), and with await: saved runs its effect only after the consume was saved and read back; the held stack is gone each time',
    covers: COVERS,
    timeoutMs: 300_000,
    steps: async (ctx) => {
      try {
        await ctx.rcon('materione give corpus:gem_red Probe 3');
        await openGui(ctx, 'guilogic:consume', /MATERIONE-GUICONSUME-OPEN guilogic:consume/);
        await click(ctx, 36);
        await click(ctx, 0);
        await ctx.sleep(400);
        ctx.expect(await slotStack(ctx, 0)).toBe('Red Gem:3');
        await click(ctx, 7); // custody.consume {slot: 0}: no save is asked for
        await ctx.waitForServerLog(/MATERIONE-GUICONSUME-DONE/, { timeoutMs: 30_000 });
        await ctx.sleep(400);
        ctx.expect(await slotStack(ctx, 0)).toBe('empty');
        ctx.expect(ctx.serverLogText(/MATERIONE-GUICONSUME-PAID/)).toBe(null);

        await ctx.rcon('materione give corpus:gem_red Probe 2');
        await ctx.sleep(400);
        await click(ctx, 36);
        await click(ctx, 0);
        await ctx.sleep(400);
        ctx.expect(await slotStack(ctx, 0)).toBe('Red Gem:2');
        await click(ctx, 8); // custody.consume {slot: 0, await: saved}, then the effect
        await ctx.waitForServerLog(/MATERIONE-GUICONSUME-PAID/, { timeoutMs: 60_000 });
        ctx.expect(await slotStack(ctx, 0)).toBe('empty');
        ctx.expect(await inventoryCount(ctx, 'Red Gem')).toBe(0);
        ctx.expect(ctx.serverLogText(/MATERIONE-GUICONSUME-REFUSED/)).toBe(null);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),

  test({
    id: 'guiescrow.consume-refusal-runs-else',
    title: 'guilogic:consume: a consume of an empty slot fails, its else: runs and the step after it (the "done" marker) never does',
    covers: COVERS,
    timeoutMs: 240_000,
    steps: async (ctx) => {
      try {
        await openGui(ctx, 'guilogic:consume', /MATERIONE-GUICONSUME-OPEN guilogic:consume/);
        await click(ctx, 7);
        await ctx.waitForServerLog(/MATERIONE-GUICONSUME-REFUSED/, { timeoutMs: 30_000 });
        await ctx.sleep(600);
        ctx.expect(ctx.serverLogText(/MATERIONE-GUICONSUME-DONE/)).toBe(null);
      } finally {
        await closeWindow(ctx);
      }
    },
  }),
];
