// Itemvars suite (#333 step 1, logic.md section 38): variables held by the item stack a run is about, on a real server
// and client. The rules are the fixtures/packs/itemvars pack: the trained sword levels itself up on every hit
// (`set_var: {var: item.level, op: add}`), announcing the new level through the server console
// (`say MATERIONE-ITEMVARS-HIT ...`) and the player's chat (`CHECK hit level=N`); a rule document reads the level of the
// sword that made a kill. The value lives in the stack's own data, so the tests read it back through `/materione var
// get`, which addresses the stack the named player holds in the main hand. A player's hit is the console's
// `damage ... by Probe`, which raises the same EntityDamageByEntityEvent a swing does. The headless harness runs the
// same scenario on Paper 1.21.4 (PaperItemVarsIT, with the drop, the pick-up and the restart); this suite proves it on the
// 26.x server with a real client.

import { test } from './_lib.mjs';

const COVERS = [
  'plugin/src/main/java/dev/materione/plugin/logic/variable/PdcStackVariableStore.java',
  'plugin/src/main/java/dev/materione/plugin/logic/variable/BukkitStackRef.java',
  'plugin/src/main/java/dev/materione/plugin/logic/variable/RunVariables.java',
  'plugin/src/main/java/dev/materione/plugin/logic/variable/VariableService.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/behavior/BehaviorService.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/behavior/WorldEvents.java',
  'plugin/src/main/java/dev/materione/plugin/command/VarCommand.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/view/OpenViewAction.java',
  'plugin/src/main/java/dev/materione/plugin/gui/event/GuiContexts.java',
  'plugin/src/main/java/dev/materione/plugin/view/**',
  'fixtures/packs/itemvars/**',
];

// A zombie that stands still beside the player, tagged so a selector finds exactly it.
async function summon(ctx, tag) {
  await ctx.rcon(`kill @e[tag=${tag}]`);
  await ctx.rcon(`execute at Probe run summon zombie ~2 ~ ~ {NoAI:1b,Tags:["${tag}"]}`);
}

// The player strikes the tagged entity, as a player's attack: the damage event names Probe as the damager.
async function strike(ctx, tag, amount = 1) {
  return ctx.rcon(`damage @e[tag=${tag},limit=1] ${amount} minecraft:player_attack by Probe`);
}

// The level of the stack Probe holds in the main hand, as /materione var prints it.
async function heldLevel(ctx) {
  return ctx.rcon('materione var get Probe itemvars:level');
}

async function useHeldItem(ctx) {
  return ctx.exec(`mc.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND)
return 'used'`);
}

function clickContainer(ctx, slot) {
  return ctx.exec(`mc.gameMode.handleContainerInput(
  player.containerMenu.containerId, ${slot}, 0,
  net.minecraft.world.inventory.ContainerInput.PICKUP, player)
return 'clicked'`);
}

async function closeWindow(ctx) {
  try {
    await ctx.exec('player.closeContainer(); return "closed"');
  } catch { /* nothing open */ }
}

export default [
  test({
    id: 'itemvars.two-swords-two-levels',
    title: 'two trained swords keep two levels: two hits take the first sword to level 3, the second sword (selected in another slot) still reads the default 1, one hit takes it to 2, and the first is still 3',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('clear Probe');
      await ctx.rcon('materione give itemvars:trained_sword Probe');
      await ctx.waitForChat(/Given itemvars:trained_sword/);
      await ctx.exec('player.getInventory().selected = 0');
      await summon(ctx, 'iv_a');
      await strike(ctx, 'iv_a');
      await ctx.waitForChat(/CHECK hit level=2/);
      await strike(ctx, 'iv_a');
      await ctx.waitForChat(/CHECK hit level=3/);
      await ctx.waitForServerLog(/MATERIONE-ITEMVARS-HIT Probe level=3/, { timeoutMs: 20_000 });
      ctx.expect(await heldLevel(ctx)).toContain('itemvars:level = 3');

      // a second sword in the next slot has its own state: it was never hit
      await ctx.rcon('materione give itemvars:trained_sword Probe');
      await ctx.exec('player.getInventory().selected = 1');
      await ctx.sleep(500);
      ctx.expect(await heldLevel(ctx)).toContain('itemvars:level = 1');
      await strike(ctx, 'iv_a');
      await ctx.sleep(1_000);
      ctx.expect(await heldLevel(ctx)).toContain('itemvars:level = 2');

      await ctx.exec('player.getInventory().selected = 0');
      await ctx.sleep(500);
      ctx.expect(await heldLevel(ctx)).toContain('itemvars:level = 3');
      await ctx.rcon('kill @e[tag=iv_a]');
    },
  }),

  test({
    id: 'itemvars.survives-a-rejoin',
    title: 'the level is the stack\'s own data, so it survives the player leaving and rejoining: after a hit and a full client relaunch the held sword still reads level 2',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 600_000, // includes one full client relaunch (ctx.rejoin)
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('clear Probe');
      await ctx.rcon('materione give itemvars:trained_sword Probe');
      await ctx.waitForChat(/Given itemvars:trained_sword/);
      await ctx.exec('player.getInventory().selected = 0');
      await summon(ctx, 'iv_b');
      await strike(ctx, 'iv_b');
      await ctx.waitForChat(/CHECK hit level=2/);
      await ctx.rejoin();
      await ctx.exec('player.getInventory().selected = 0');
      await ctx.sleep(1_000);
      ctx.expect(await heldLevel(ctx)).toContain('itemvars:level = 2');
      await ctx.rcon('kill @e[tag=iv_b]');
    },
  }),

  test({
    id: 'itemvars.rule-document-reads-the-held-stack',
    title: 'a rule document on entity.kill reads the item variable of the sword in the main hand: after one hit and a kill, the client sees "CHECK kill level=3" (the kill is a second hit away), and with an empty hand the same rule reads the default',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('clear Probe');
      await ctx.rcon('materione give itemvars:trained_sword Probe');
      await ctx.waitForChat(/Given itemvars:trained_sword/);
      await ctx.exec('player.getInventory().selected = 0');
      await summon(ctx, 'iv_c');
      await strike(ctx, 'iv_c'); // level 2
      await ctx.waitForChat(/CHECK hit level=2/);
      await strike(ctx, 'iv_c', 1000); // the kill is a hit too: level 3, then the document reads it
      await ctx.waitForChat(/CHECK kill level=3/);

      // an empty hand is no stack: the document reads the default and the plugin says why (MTR-0065, rate-limited)
      await ctx.rcon('clear Probe');
      await summon(ctx, 'iv_c');
      await strike(ctx, 'iv_c', 1000);
      await ctx.waitForChat(/CHECK kill level=1/);
    },
  }),

  test({
    id: 'itemvars.gui-open-is-about-the-sword',
    title: 'itemvars:view_sword right-click opens itemvars:level_gui about that exact stack while a byte-identical second sword sits in the next slot: the rendered upgrade item says Level 1, two clicks write the subject sword to level 2 then 3 and re-render, and afterwards the subject sword reads level 3 while the identical sword still reads level 1',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        await ctx.reset();
        await ctx.rcon('gamemode survival Probe');
        await ctx.rcon('clear Probe');
        await ctx.rcon('materione give itemvars:view_sword Probe');
        await ctx.waitForChat(/Given itemvars:view_sword/);
        // a second fresh sword: equal bytes to the first, so only physical identity tells them apart
        await ctx.rcon('materione give itemvars:view_sword Probe');
        await ctx.sleep(500);
        await ctx.exec('player.getInventory().selected = 0');

        await useHeldItem(ctx);
        await ctx.waitForServerLog(/MATERIONE-GUI-OPENED id=itemvars:level_gui player=Probe/);
        await ctx.sleep(500);
        const name = String(await ctx.exec(`return player.containerMenu.slots.get(11)
  .getItem().getHoverName().getString()`));
        ctx.expect(name).toBe('Level 1');

        await clickContainer(ctx, 11);
        await ctx.waitForChat(/CHECK gui level=2/);
        await ctx.waitForServerLog(/MATERIONE-ITEMVARS-GUI Probe level=2/);
        await ctx.sleep(1500);
        const rerendered = String(await ctx.exec(`return player.containerMenu.slots.get(11)
  .getItem().getHoverName().getString()`));
        ctx.expect(rerendered).toBe('Level 2');

        // a second write through the same bound handle: the subject must still be live
        await clickContainer(ctx, 11);
        await ctx.waitForChat(/CHECK gui level=3/);
        await ctx.waitForServerLog(/MATERIONE-ITEMVARS-GUI Probe level=3/);
        await ctx.sleep(1500);
        const again = String(await ctx.exec(`return player.containerMenu.slots.get(11)
  .getItem().getHoverName().getString()`));
        ctx.expect(again).toBe('Level 3');

        await closeWindow(ctx);
        ctx.expect(await heldLevel(ctx)).toContain('itemvars:level = 3');
        await ctx.exec('player.getInventory().selected = 1');
        await ctx.sleep(500);
        ctx.expect(await heldLevel(ctx)).toContain('itemvars:level = 1');
      } finally {
        await closeWindow(ctx);
      }
    },
  }),
];
