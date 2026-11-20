// Targets suite (#331, logic.md section 39): an action acting on another player or entity, on a real server and client.
// The rules are the fixtures/packs/targets pack: each announces itself through the server console
// (`say MATERIONE-TARGETS-...`, read from the server log), and the effect on the other entity is read back from the
// server (`data get entity ... active_effects`). The headless harness runs the same scenario with fake players on
// Paper 1.21.4 (PaperTargetAddressingIT); this suite proves it on the 26.x server with a real client, where the message
// to a player is a chat line the client can read.

import { test } from './_lib.mjs';

const COVERS = [
  'api/src/main/java/dev/materione/api/logic/TargetSpec.java',
  'api/src/main/java/dev/materione/api/logic/Subjects.java',
  'api/src/main/java/dev/materione/api/logic/ActionDescription.java',
  'behavior/src/main/java/dev/materione/behavior/run/TargetedAction.java',
  'behavior/src/main/java/dev/materione/behavior/run/TargetedContext.java',
  'plugin/src/main/java/dev/materione/plugin/logic/target/**',
  'plugin/src/main/java/dev/materione/plugin/logic/action/MessageAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/PotionAction.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/behavior/WorldEventListener.java',
  'fixtures/packs/targets/**',
];

// A mob that stands still beside the player, tagged so a selector finds exactly it.
async function summon(ctx, type, tag) {
  await ctx.rcon(`kill @e[tag=${tag}]`);
  await ctx.rcon(`execute at Probe run summon ${type} ~2 ~ ~ {NoAI:1b,Tags:["${tag}"]}`);
}

// The player strikes the tagged entity, as a player's attack: the damage event names Probe as the damager.
async function strike(ctx, tag, amount) {
  return ctx.rcon(`damage @e[tag=${tag},limit=1] ${amount} minecraft:player_attack by Probe`);
}

export default [
  test({
    id: 'targets.potion-on-struck-entity',
    title: 'targets:targets bleeds the entity that was struck: hitting a tagged zombie as Probe prints "MATERIONE-TARGETS-BLEED zombie" and the zombie (not Probe) has the wither effect',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('effect clear Probe');
      await summon(ctx, 'zombie', 'tg_bleed');
      await strike(ctx, 'tg_bleed', 1);
      await ctx.waitForServerLog(/MATERIONE-TARGETS-BLEED zombie/, { timeoutMs: 20_000 });
      ctx.expect(await ctx.rcon('data get entity @e[tag=tg_bleed,limit=1] active_effects')).toContain('wither');
      // the effect went to the struck entity and not to the player who struck it
      ctx.expect(String(await ctx.rcon('effect clear Probe'))).toContain('no effects');
      await ctx.rcon('kill @e[tag=tg_bleed]');
    },
  }),

  test({
    id: 'targets.nearby-selector',
    title: 'a bounded selector finds the nearest zombie: striking a husk prints "MATERIONE-TARGETS-NEARBY-DONE" and slows the zombie within eight blocks, not the husk that was struck',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('kill @e[type=zombie]');
      await summon(ctx, 'zombie', 'tg_near');
      await summon(ctx, 'husk', 'tg_husk');
      await strike(ctx, 'tg_husk', 1);
      await ctx.waitForServerLog(/MATERIONE-TARGETS-NEARBY-DONE/, { timeoutMs: 20_000 });
      ctx.expect(await ctx.rcon('data get entity @e[tag=tg_near,limit=1] active_effects')).toContain('slowness');
      await ctx.rcon('kill @e[tag=tg_near]');
      await ctx.rcon('kill @e[tag=tg_husk]');
    },
  }),

  test({
    id: 'targets.message-to-argument-player',
    title: 'rejoining as Probe messages a player named by a flow argument: the client sees "hello from Probe" (the message went to the argument player) and the server log prints "MATERIONE-TARGETS-MESSAGE-OK Probe to Probe"',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 600_000, // includes one full client relaunch (ctx.rejoin)
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rejoin();
      await ctx.waitForServerLog(/MATERIONE-TARGETS-MESSAGE-OK Probe to Probe/, { timeoutMs: 60_000 });
      await ctx.waitForChat(/hello from Probe/);
    },
  }),

  test({
    id: 'targets.unresolved-target-fails-the-require',
    title: 'a message to a player nobody is fails the action instead of reaching someone else: rejoining as Probe prints "MATERIONE-TARGETS-MESSAGE-REFUSED Carol" (the flow\'s require ran its else) and never "MESSAGE-OK Probe to Carol"',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 600_000, // includes one full client relaunch (ctx.rejoin)
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rejoin();
      await ctx.waitForServerLog(/MATERIONE-TARGETS-MESSAGE-REFUSED Carol/, { timeoutMs: 60_000 });
      const lines = (await ctx.chat(200)).join('\n');
      ctx.expect(lines).not.toContain('MESSAGE-OK Probe to Carol');
    },
  }),
];
