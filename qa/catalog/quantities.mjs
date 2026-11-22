// Quantities that take an expression (logic.md section 42, #470): a potion duration, a title fade-in and a sound volume
// come from a typed command argument (fixtures/packs/commands/commands/qty.yml). A value in range does the thing and
// announces it; a value out of range is refused under MTR-0120 before the effect happens, and the server logs the refusal.
// The QA client is the only player on its server, so the numbers it types are the ones the commands see. Auto entries:
// the runner reads the server log, and no screen is judged.

import { test, typedChat } from './_lib.mjs';

const COVERS = [
  'fixtures/packs/commands/**',
  'api/src/main/java/dev/materione/api/logic/Quantity.java',
  'api/src/main/java/dev/materione/api/diagnostics/DiagnosticCodes.java',
  'behavior/src/main/java/dev/materione/behavior/flow/ActionCompiler.java',
  'behavior/src/main/java/dev/materione/behavior/run/FlowContinuations.java',
  'behavior/src/main/java/dev/materione/behavior/run/ControlSteps.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/PotionAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/TitleAction.java',
  'plugin/src/main/java/dev/materione/plugin/logic/action/SoundAction.java',
  'qa/catalog/_lib.mjs',
];

export default [
  test({
    id: 'quantities.potion-duration-applied',
    title: 'qty-potion 200 (a potion whose duration is a typed argument, in range) applies the effect and the flow reaches its last step: the server prints "MATERIONE-QTY-POTION Probe ticks 200"',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await typedChat(ctx, 'qty-potion 200', undefined, { waitForTree: true });
      await ctx.waitForServerLog(/MATERIONE-QTY-POTION Probe ticks 200/, { timeoutMs: 20_000 });
    },
  }),

  test({
    id: 'quantities.potion-duration-refused',
    title: 'qty-potion 0 (a duration below the one tick the potion takes) is refused under MTR-0120: the server logs the code and names the value, and the else steps run ("MATERIONE-QTY-POTION-REFUSED Probe ticks 0"), with no effect',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await typedChat(ctx, 'qty-potion 0', undefined, { waitForTree: true });
      await ctx.waitForServerLog(/MATERIONE-QTY-POTION-REFUSED Probe ticks 0/, { timeoutMs: 20_000 });
      await ctx.waitForServerLog(/MTR-0120.*duration must be a whole number in 1\.\.2147483647/, { timeoutMs: 20_000 });
    },
  }),

  test({
    id: 'quantities.title-fade-in-refused',
    title: 'qty-title -1 (a negative fade-in) is refused under MTR-0120 and no title is shown: the server logs the code, names fade_in and quotes "-1"',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await typedChat(ctx, 'qty-title -1', undefined, { waitForTree: true });
      await ctx.waitForServerLog(/MTR-0120.*fade_in must be a whole number in 0\.\.72000, not "-1"/, { timeoutMs: 20_000 });
    },
  }),

  test({
    id: 'quantities.sound-volume-applied',
    title: 'qty-sound 0.5 (a sound whose volume is a typed argument, in range) plays and the flow reaches its last step: the server prints "MATERIONE-QTY-SOUND Probe volume 0.5"',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await typedChat(ctx, 'qty-sound 0.5', undefined, { waitForTree: true });
      await ctx.waitForServerLog(/MATERIONE-QTY-SOUND Probe volume 0\.5/, { timeoutMs: 20_000 });
    },
  }),

  test({
    id: 'quantities.sound-volume-refused',
    title: 'qty-sound -1 (a negative volume) is refused under MTR-0120 and no sound plays: the server logs the code, names volume and quotes "-1"',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await typedChat(ctx, 'qty-sound -1', undefined, { waitForTree: true });
      await ctx.waitForServerLog(/MTR-0120.*volume must be a number in 0\.0\.\.1000\.0, not "-1"/, { timeoutMs: 20_000 });
    },
  }),
];
