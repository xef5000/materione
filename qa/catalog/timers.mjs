import { test } from './_lib.mjs';

const COVERS = [
  'api/src/main/java/dev/materione/api/scheduling/TimerInterval.java',
  'platform/src/main/java/dev/materione/platform/scheduling/**',
  'plugin/src/main/java/dev/materione/plugin/runtime/behavior/ScheduledTrigger*',
  'plugin/src/main/java/dev/materione/plugin/logic/trigger/WorldTriggers.java',
  'fixtures/packs/timers/**',
];

export default [
  test({
    id: 'timers.server-variable-increments',
    title: 'timers:clock runs every second on the server, increments timers:count, and broadcasts "Timer 1" to Probe',
    mode: 'auto',
    covers: COVERS,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione var set - timers:count 0');
      await ctx.rcon('materione var set - timers:enabled true');
      try {
        await ctx.waitForChat(/Timer 1/, { timeoutMs: 20_000 });
        await ctx.waitForServerLog(/MATERIONE-TIMERS-TICK 1/, { timeoutMs: 20_000 });
        const value = String(await ctx.rcon('materione var get - timers:count'));
        ctx.expect(value).toMatch(/timers:count = [1-9][0-9]*/);
      } finally {
        await ctx.rcon('materione var set - timers:enabled false');
      }
    },
  }),
];
