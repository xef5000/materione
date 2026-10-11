// Mechanics suite: item behavior as seen by a real player on a real server
// (issue #38). Auto mode — the runner judges pass/fail from assertions.

import { test } from './_lib.mjs';

export default [
  test({
    id: 'mechanics.commands-and-cooldown',
    title: 'storm_wand left-click runs console + player commands on every swing; the behavior rule is suppressed inside its cooldown window',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/runtime/mechanics/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/behavior/**',
      'behavior/src/main/java/dev/materione/behavior/**',
      'fixtures/packs/world/**',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione give world:storm_wand Probe');
      await ctx.waitForChat(/Given world:storm_wand/);
      await ctx.exec('player.getInventory().selected = 0');
      const held = await ctx.exec(
        'return player.getInventory().items.get(0).getHoverName().getString()');
      ctx.expect(String(held)).toBe('Storm Wand');

      // A client-side swing sends the swing packet the server turns into
      // LEFT_CLICK_AIR — no input injection needed. One swing drives three
      // surfaces: the console tellraw (commands mechanic, %player%
      // substituted), the player-executed version command (as_player — the
      // MATERIONE-VERSION log marker proves the dispatch), and the behavior
      // rule whose message is gated by its 3s cooldown.
      const counts = async () => {
        const lines = await ctx.chat(60);
        return {
          sparks: lines.filter((l) => l.includes('The wand sparks.')).length,
          crackles: lines.filter((l) => l.includes('The wand crackles.')).length,
        };
      };
      const waitForCount = async (name, want, timeoutMs = 8000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const c = await counts();
          if (c[name] >= want) {
            return c;
          }
          if (Date.now() > deadline) {
            // assertion failure carries both counters into the dossier
            ctx.expect(c[name]).toBe(want);
          }
          await ctx.sleep(300);
        }
      };

      const baseline = await counts();

      // swing 1: cooldown fresh — every surface fires
      await ctx.exec('player.swing(player.getUsedItemHand())');
      await waitForCount('sparks', baseline.sparks + 1);
      await ctx.waitForServerLog(/MATERIONE-VERSION/);
      await waitForCount('crackles', baseline.crackles + 1, 3000);

      // swing 2 immediately: the mechanics fire again, the rule is suppressed
      await ctx.exec('player.swing(player.getUsedItemHand())');
      await waitForCount('sparks', baseline.sparks + 2);
      const suppressed = await counts();
      ctx.expect(suppressed.crackles - baseline.crackles).toBe(1);

      // after the 3s window the rule fires again
      await ctx.sleep(3300);
      await ctx.exec('player.swing(player.getUsedItemHand())');
      await waitForCount('sparks', baseline.sparks + 3);
      await waitForCount('crackles', baseline.crackles + 2, 3000);
    },
  }),

  test({
    id: 'mechanics.custom-durability',
    title: 'crucible_blade counts vanilla uses in stack PDC while the vanilla damage bar stays at zero, the wear counter survives materione reload, and the third use breaks the blade',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/runtime/mechanics/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/behavior/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/ItemAutoUpdater.java',
      'behavior/src/main/java/dev/materione/behavior/**',
      'fixtures/packs/world/**',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      // Vanilla item wear only happens outside creative; clear any targets a
      // previous run left near the anchor before summoning a fresh one.
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('execute as Probe at @s run kill @e[type=villager,distance=..8]');
      await ctx.rcon('materione give world:crucible_blade Probe');
      await ctx.waitForChat(/Given world:crucible_blade/);
      await ctx.exec('player.getInventory().selected = 0');
      const held = await ctx.exec(
        'return player.getInventory().items.get(0).getHoverName().getString()');
      ctx.expect(String(held)).toBe('Crucible Blade');
      // Baseline floor: an undamaged iron sword — the mechanic must keep it
      // exactly here (event.setCancelled) while its own counter advances.
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getDamageValue()'))).toBe('0');
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getMaxDamage()'))).toBe('250');

      // Melee target: a static NoAI villager two blocks down the player's
      // look vector (armor stands refuse the hit on 26.1.2). The client's
      // crosshair picks it up and a real attack-key click drives the whole
      // vanilla pipeline — ServerboundAttackPacket → player.attack →
      // PlayerItemDamageEvent — exactly what a human click produces.
      await ctx.rcon(
        'execute as Probe at @s run summon minecraft:villager ^ ^ ^2 '
        + '{NoAI:1b,AbsorptionAmount:40f}');
      await ctx.sleep(800); // entity tracker + crosshair pick settle
      const aim = () => ctx.exec("return mc.hitResult.getEntity().getType().toString()");
      ctx.expect(String(await aim())).toBe('entity.minecraft.villager');

      // The wear probe: right-click air fires the click:right rule whose
      // message interpolates the live PDC counters (${item.*} surface).
      const probe = () => ctx.exec(
        'mc.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND); return "probed"');
      const wearCount = async () =>
        (await ctx.chat(60)).filter((l) => l.includes('blade-wear')).length;
      const baseline = await wearCount();
      const waitForWear = async (want, timeoutMs = 10_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          if ((await wearCount()) >= want) return;
          if (Date.now() > deadline) {
            ctx.expect((await wearCount()) - baseline).toBe(want - baseline);
          }
          await ctx.sleep(300);
        }
      };
      const waitForTwos = async (want, timeoutMs = 10_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const twos = (await ctx.chat(60)).filter((l) => l.includes('blade-wear 2/3')).length;
          if (twos >= want) return;
          if (Date.now() > deadline) ctx.expect(twos).toBe(want);
          await ctx.sleep(300);
        }
      };

      const hit = async () => {
        ctx.expect(String(await aim())).toBe('entity.minecraft.villager');
        await ctx.exec(`mc.options.keyAttack.click(mc.options.keyAttack.getDefaultKey())
return 'clicked'`);
        // past the sword's full attack cooldown — 26.1 gates swings on
        // minimum attack charge, and a sub-charge swing is dropped silently
        await ctx.sleep(1700);
      };

      // use 1: the counter appears in PDC (1/3), vanilla bar untouched
      await hit();
      ctx.expect(String(await probe())).toBe('probed');
      await waitForWear(baseline + 1);
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getDamageValue()'))).toBe('0');

      // use 2: counter advances (2/3) — the probe re-reads it from chat
      await hit();
      ctx.expect(String(await probe())).toBe('probed');
      await waitForWear(baseline + 2);

      // reload: the auto-updater refreshes held stacks but must carry the
      // per-instance counters across — a second wear probe still reads 2/3.
      const preTwos = (await ctx.chat(60)).filter((l) => l.includes('blade-wear 2/3')).length;
      await ctx.rcon('materione reload');
      await ctx.waitForServerLog(/MATERIONE-RELOAD-OK items=\d+/);
      ctx.expect(String(await probe())).toBe('probed');
      await waitForTwos(preTwos + 1);

      // use 3 hits max: the blade breaks — the slot empties and no third
      // wear value can come (the PDC was cleared with the stack; the three
      // counted lines are 1/3, 2/3, and the post-reload re-read of 2/3)
      await hit();
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().isEmpty()'))).toBe('true');
      ctx.expect((await wearCount()) - baseline).toBe(3);
      ctx.expect(
        (await ctx.chat(60)).filter((l) => l.includes('blade-wear 3/3')).length,
      ).toBe(0);

      await ctx.rcon('execute as Probe at @s run kill @e[type=villager,distance=..8]');
    },
  }),
];
