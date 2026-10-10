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
];
