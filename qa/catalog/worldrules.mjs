// Worldrules suite (#327, logic.md section 37): rules that no item, block, furniture or GUI holds, answering world
// events on a real server and client. The rules are the fixtures/packs/worldrules pack: each one says what fired
// through the server console (`say MATERIONE-WORLDRULES-...`, read from the server log) and through the player's chat
// (`CHECK ...`, read from the client). A player's hit is the console's `damage ... by Probe`, which raises the same
// EntityDamageByEntityEvent a swing does and credits a kill to the player; the headless harness runs the same
// scenario on Paper 1.21.4 (PaperWorldEventsIT), this suite proves it on the 26.x server with a real client.

import { test } from './_lib.mjs';

const COVERS = [
  'plugin/src/main/java/dev/materione/plugin/runtime/behavior/WorldEventListener.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/behavior/WorldEvents.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/behavior/WorldRules.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/behavior/RuleDocuments.java',
  'plugin/src/main/java/dev/materione/plugin/logic/trigger/WorldTriggers.java',
  'content/src/main/java/dev/materione/content/rules/**',
  'fixtures/packs/worldrules/**',
];

// A zombie that stands still beside the player, tagged so a selector finds exactly it.
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
    id: 'worldrules.entity-kill-counts',
    title: 'worldrules:world answers entity.kill with no host: killing two zombies as Probe prints "CHECK kill zombie is_player=false" each time, and the second prints "CHECK quest-done" (a player variable counts)',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('materione var set Probe worldrules:zombie_kills 0');
      await summon(ctx, 'zombie', 'wr_kill');
      await strike(ctx, 'wr_kill', 1000);
      await ctx.waitForChat(/CHECK kill zombie is_player=false/);
      await ctx.waitForServerLog(/MATERIONE-WORLDRULES-KILL zombie killer=Probe is_player=false/, { timeoutMs: 20_000 });
      await summon(ctx, 'zombie', 'wr_kill');
      await strike(ctx, 'wr_kill', 1000);
      await ctx.waitForChat(/CHECK quest-done/);
    },
  }),

  test({
    id: 'worldrules.entity-hit-document-and-item',
    title: 'a hit on a zombie prints "CHECK hit zombie cause=entity_attack" from the rule document, and "CHECK blade worldrules:rule_blade hit zombie" from the rule on the held item, both on one trigger',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('materione give worldrules:rule_blade Probe');
      await ctx.waitForChat(/Given worldrules:rule_blade/);
      await ctx.exec('player.getInventory().selected = 0');
      await summon(ctx, 'zombie', 'wr_hit');
      await strike(ctx, 'wr_hit', 1);
      await ctx.waitForChat(/CHECK hit zombie cause=entity_attack/);
      await ctx.waitForChat(/CHECK blade worldrules:rule_blade hit zombie/);
      await ctx.rcon('kill @e[tag=wr_hit]');
    },
  }),

  test({
    id: 'worldrules.entity-hit-cancel',
    title: 'the entity.hit rule for husks cancels the damage: a 5 point hit leaves the husk at 20.0 health, and the rule still ran (MATERIONE-WORLDRULES-HUSK-CANCELLED)',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await summon(ctx, 'husk', 'wr_cancel');
      await strike(ctx, 'wr_cancel', 5);
      await ctx.waitForServerLog(/MATERIONE-WORLDRULES-HUSK-CANCELLED/, { timeoutMs: 20_000 });
      ctx.expect(await ctx.rcon('data get entity @e[tag=wr_cancel,limit=1] Health')).toContain('20.0f');
      await ctx.rcon('kill @e[tag=wr_cancel]');
    },
  }),

  test({
    id: 'worldrules.entity-filter-excludes-other-types',
    title: 'the zombie-only rules do not answer a skeleton: striking and killing one prints no "CHECK hit" and no "CHECK kill"',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      const before = (await ctx.chat(200)).length;
      await summon(ctx, 'skeleton', 'wr_skel');
      await strike(ctx, 'wr_skel', 1000);
      await ctx.sleep(2_500);
      const lines = (await ctx.chat(200)).join('\n');
      ctx.expect(lines).not.toContain('CHECK kill skeleton');
      ctx.expect(lines).not.toContain('CHECK hit skeleton');
      ctx.log(`chat lines before: ${before}`);
    },
  }),

  test({
    id: 'worldrules.player-join-greets',
    title: 'rejoining fires player.join: the server log prints "MATERIONE-WORLDRULES-JOIN Probe first=false" (the player has played before) and the client sees "CHECK join first=false"',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 600_000, // includes one full client relaunch (ctx.rejoin)
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rejoin();
      await ctx.waitForServerLog(/MATERIONE-WORLDRULES-JOIN Probe first=false/, { timeoutMs: 60_000 });
      await ctx.waitForChat(/CHECK join first=false/);
    },
  }),

  test({
    id: 'worldrules.player-death-and-respawn',
    title: 'dying fires death (the existing trigger, now also hosted by rule documents) with the cause and respawning fires player.respawn: the server log prints "MATERIONE-WORLDRULES-DEATH Probe cause=kill" then "MATERIONE-WORLDRULES-RESPAWN Probe"',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('kill Probe');
      await ctx.waitForServerLog(/MATERIONE-WORLDRULES-DEATH Probe cause=kill/, { timeoutMs: 20_000 });
      await ctx.sleep(800);
      ctx.expect(String(await ctx.exec(`player.respawn()
return 'respawned'`))).toBe('respawned');
      await ctx.waitForServerLog(/MATERIONE-WORLDRULES-RESPAWN Probe/, { timeoutMs: 20_000 });
    },
  }),

];
