// Worldverbs suite (#329, logic.md section 41): the entity and world verbs, on a real server and client. The rules are the
// fixtures/packs/worldverbs pack: each announces itself through the server console (`say MATERIONE-WORLDVERBS-...`, read
// from the server log). The headless harness runs the excavator's 3 by 3 break and the dash with fake players on Paper
// 1.21.4 (PaperWorldVerbsIT); this suite proves the parts only a client sees or feels: the dash moves the player, the
// particle trail is drawn, and a lifesteal hit heals. The particle test is `visual`: particles are packets, and the verdict
// needs eyes.

import { test } from './_lib.mjs';

const COVERS = [
  'plugin/src/main/java/dev/materione/plugin/logic/action/HealAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/DamageAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/VelocityAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/ParticleAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/LaunchAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/BreakBlocksAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/SetBlockAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/DropItemAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/StackEditAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/world/**',
  'plugin/src/main/java/dev/materione/plugin/runtime/behavior/WorldEventListener.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/behavior/ItemBehaviorListener.java',
  'fixtures/packs/worldverbs/**',
];

// Aims level at yaw 0 (facing +Z) and uses the held item once, as a right click in the air.
async function useHeld(ctx) {
  await ctx.exec('player.getInventory().selected = 0');
  await ctx.exec('player.setRot(0F, 0F)\nreturn \'aimed\'');
  await ctx.sleep(300);
  await ctx.exec('mc.options.keyUse.click(mc.options.keyUse.getDefaultKey())\nreturn \'used\'');
}

export default [
  test({
    id: 'worldverbs.dash-moves-the-player',
    title: 'worldverbs:dash_blade pushes the player forward: a right click prints "MATERIONE-WORLDVERBS-DASH Probe" and the player ends up more than two blocks further along +Z than where they stood',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('materione give worldverbs:dash_blade Probe');
      await ctx.waitForChat(/Given worldverbs:dash_blade/);
      const before = Number(await ctx.exec('return player.getZ()'));
      await useHeld(ctx);
      await ctx.waitForServerLog(/MATERIONE-WORLDVERBS-DASH Probe/, { timeoutMs: 20_000 });
      await ctx.sleep(1500);
      const after = Number(await ctx.exec('return player.getZ()'));
      ctx.expect(after - before > 2).toBe(true);
    },
  }),

  test({
    id: 'worldverbs.particle-trail',
    title: 'worldverbs:dash_blade leaves a trail of white cloud particles around the player as they dash (20 particles, a little spread)',
    mode: 'visual',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('materione give worldverbs:dash_blade Probe');
      await ctx.waitForChat(/Given worldverbs:dash_blade/);
      await ctx.exec('mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK)\nreturn \'third-person\'');
      await useHeld(ctx);
      await ctx.waitForServerLog(/MATERIONE-WORLDVERBS-DASH Probe/, { timeoutMs: 20_000 });
      await ctx.sleep(150);
      await ctx.shot('dash-start');
      await ctx.sleep(250);
      await ctx.shot('dash-trail');
      await ctx.exec('mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON)\nreturn \'first-person\'');
      ctx.needsEyes('a puff of white cloud particles is visible around the player in the first screenshot and drifting in the second; nothing is drawn at the world origin or far from the player');
    },
  }),

  test({
    id: 'worldverbs.lifesteal-heals-the-wielder',
    title: 'worldverbs:leech_dagger heals the wielder for 20% of the damage dealt: striking a zombie for 10 as Probe prints "MATERIONE-WORLDVERBS-LEECH Probe 10" and Probe\'s health rises by 2',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('materione give worldverbs:leech_dagger Probe');
      await ctx.waitForChat(/Given worldverbs:leech_dagger/);
      await ctx.exec('player.getInventory().selected = 0');
      await ctx.rcon('kill @e[tag=wv_target]');
      await ctx.rcon('execute at Probe run summon zombie ~2 ~ ~ {NoAI:1b,Tags:["wv_target"]}');
      await ctx.sleep(4000); // a player who has just joined is invulnerable for a moment
      await ctx.rcon('damage Probe 6 minecraft:generic');
      const hurt = String(await ctx.rcon('data get entity Probe Health'));
      ctx.expect(hurt).toContain('14.0f');
      await ctx.rcon('damage @e[tag=wv_target,limit=1] 10 minecraft:player_attack by Probe');
      await ctx.waitForServerLog(/MATERIONE-WORLDVERBS-LEECH Probe 10/, { timeoutMs: 20_000 });
      const healed = String(await ctx.rcon('data get entity Probe Health'));
      ctx.expect(healed).toContain('16.0f');
      await ctx.rcon('kill @e[tag=wv_target]');
    },
  }),
];
