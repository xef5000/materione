// Protection suite (#466, logic.md section 43, docs/architecture/protection.md): the protection port on a real server and client.
// The rules are the fixtures/packs/protection pack: each announces itself through the server console (`say
// MATERIONE-PROTECTION-...`, read from the server log). The probe server runs no protection plugin and registers no provider
// (the harness's stand-in provider is in its durability probe only), so these tests cover what a server with none shows: the
// default mode is `both`, the conditions are true because nothing protects the place, and the verbs ask an empty port and go on to the event path. That a provider's deny stops a verb is the harness's PaperProtectionIT (ProtectionRunner)
// and the unit tests; that a real WorldGuard answers is a server owner's check (see the adapter table in protection.md section 9).

import { test } from './_lib.mjs';

const COVERS = [
  'api/src/main/java/dev/materione/api/protection/**',
  'plugin/src/main/java/dev/materione/plugin/protection/**',
  'plugin/src/main/java/dev/materione/plugin/logic/condition/ProtectionCondition.java',
  'plugin/src/main/java/dev/materione/plugin/logic/condition/CanBuildCondition.java',
  'plugin/src/main/java/dev/materione/plugin/logic/condition/CanBreakCondition.java',
  'plugin/src/main/java/dev/materione/plugin/logic/condition/CanInteractCondition.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/BreakBlocksAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/SetBlockAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/world/**',
  'integrations/src/main/java/dev/materione/integrations/protection/**',
  'fixtures/packs/protection/**',
  'qa/catalog/_lib.mjs',
];

export default [
  test({
    id: 'protection.default-mode-is-both-with-no-provider',
    title: 'a server with no protection plugin boots in the default mode: the server log prints "MATERIONE-PROTECTION-OK mode=both providers=none" and no MTR-0122, MTR-0123 or MTR-0124 is reported',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.waitForServerLog(/MATERIONE-PROTECTION-OK mode=both providers=none/, { timeoutMs: 20_000 });
      ctx.expect(ctx.serverLogLine(/MTR-012[234]/)).toBeFalsy();
    },
  }),

  test({
    id: 'protection.conditions-are-true-where-nothing-protects',
    title: 'rejoining as Probe asks can_build and can_break about the block above the player: with no provider present both are true, so the server log prints "MATERIONE-PROTECTION-BUILD Probe yes" and "MATERIONE-PROTECTION-BREAK Probe yes"',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 600_000, // includes one full client relaunch (ctx.rejoin)
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rejoin();
      await ctx.waitForServerLog(/MATERIONE-PROTECTION-BUILD Probe yes/, { timeoutMs: 60_000 });
      await ctx.waitForServerLog(/MATERIONE-PROTECTION-BREAK Probe yes/, { timeoutMs: 60_000 });
    },
  }),
];
