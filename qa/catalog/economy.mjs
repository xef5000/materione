// Money from a command argument (logic.md section 42, #464): the economy fixture pack (fixtures/packs/economy) with the
// coinpay-raw command, which takes its amount as text and hands it to take_money and give_money. The probe has no economy
// provider (the harness's test economy is in its durability probe only), so these tests cover the refusal the player can see
// without one: a bad amount is refused before any currency is needed, the run carries on, the server reports the refusal
// under MTR-0120, and nothing is paid. A payment that moves real balances is the harness's PaperEconomyIT (EconomyRunner).
// The QA client is the only player on its server, so the amounts it types are the ones the commands see.

import { test, typedChat } from './_lib.mjs';

const COVERS = [
  'fixtures/packs/economy/**',
  'api/src/main/java/dev/materione/api/logic/ActionOutcome.java',
  'api/src/main/java/dev/materione/api/logic/CodedRefusal.java',
  'api/src/main/java/dev/materione/api/diagnostics/DiagnosticCodes.java',
  'behavior/src/main/java/dev/materione/behavior/run/FlowContinuations.java',
  'behavior/src/main/java/dev/materione/behavior/run/FlowInterpreter.java',
  'behavior/src/main/java/dev/materione/behavior/run/RunReporter.java',
  'behavior/src/main/java/dev/materione/behavior/run/TargetedAction.java',
  'behavior/src/main/java/dev/materione/behavior/run/ControlSteps.java',
  'behavior/src/main/java/dev/materione/behavior/runtime/RuleEngine.java',
  'plugin/src/main/java/dev/materione/plugin/logic/ProviderParams.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/GiveMoneyAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/TakeMoneyAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/condition/HasMoneyCondition.java',
  'harness/src/durabilityProbe/java/dev/materione/harness/probe/EconomyStub.java',
  'qa/catalog/_lib.mjs',
];

export default [
  test({
    id: 'economy.raw-amount-refused',
    title: 'coinpay-raw Probe -5 (a negative amount, as text) is refused by the money code: the server prints "MATERIONE-ECON-RAW Probe to Probe amount -5" (the run carries on to its last step) and logs a diagnostic under MTR-0120',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await typedChat(ctx, 'coinpay-raw Probe -5', undefined, { waitForTree: true });
      await ctx.waitForServerLog(/MATERIONE-ECON-RAW Probe to Probe amount -5/, { timeoutMs: 20_000 });
      await ctx.waitForServerLog(/MTR-0120/, { timeoutMs: 20_000 });
    },
  }),

  test({
    id: 'economy.raw-text-refused',
    title: 'coinpay-raw Probe abc (a word, as text) is refused and the refusal names what it rendered to: the diagnostic under MTR-0120 says amount must be a number above 0 and quotes "abc"',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await typedChat(ctx, 'coinpay-raw Probe abc', undefined, { waitForTree: true });
      await ctx.waitForServerLog(/MATERIONE-ECON-RAW Probe to Probe amount abc/, { timeoutMs: 20_000 });
      await ctx.waitForServerLog(/MTR-0120.*amount must be a number above 0/, { timeoutMs: 20_000 });
    },
  }),
];
