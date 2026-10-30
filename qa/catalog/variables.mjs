// Declared persistent variables through /materione var (logic.md §3.3, issue #211). Auto tests: the
// runner judges from the RCON reply, which the command writes for a console sender. The corpus pack
// declares qa_coins (player, number) and qa_season (server, string) in variables/qa.yml.
//
// Not covered here: a set_var in a rule fired by a click. That needs a QA rule on a corpus item, which
// lands with the GUI runtime (#214) and its QA pass; the store and the command share the same path.

import { test } from './_lib.mjs';

const VAR_COMMAND = 'plugin/src/main/java/dev/materione/plugin/command/VarCommand.java';
const VARIABLE_SERVICE = 'plugin/src/main/java/dev/materione/plugin/logic/variable/VariableService.java';
const SET_VAR = 'plugin/src/main/java/dev/materione/plugin/logic/action/SetVarAction.java';
const COVERS = [VAR_COMMAND, VARIABLE_SERVICE, SET_VAR, 'fixtures/packs/corpus/variables/**'];

export default [
  test({
    id: 'variables.set-and-get-player',
    title: '/materione var set then get: corpus:qa_coins is 5 for Probe and reads back as 5',
    covers: COVERS,
    steps: async (ctx) => {
      const set = await ctx.rcon('materione var set Probe corpus:qa_coins 5');
      ctx.expect(set).toContain('corpus:qa_coins = 5');
      const get = await ctx.rcon('materione var get Probe corpus:qa_coins');
      ctx.expect(get).toContain('corpus:qa_coins = 5');
      ctx.expect(get).toContain('player: Probe');
    },
  }),

  test({
    id: 'variables.last-write-wins',
    title: 'corpus:qa_coins reads 12 after a second write of 12 (the stored value is replaced, not added to)',
    covers: COVERS,
    dependsOn: ['variables.set-and-get-player'],
    steps: async (ctx) => {
      await ctx.rcon('materione var set Probe corpus:qa_coins 12');
      const get = await ctx.rcon('materione var get Probe corpus:qa_coins');
      ctx.expect(get).toContain('corpus:qa_coins = 12');
    },
  }),

  test({
    id: 'variables.server-scope-ignores-the-player',
    title: 'corpus:qa_season is one value for the server: set with - reads back as summer',
    covers: COVERS,
    steps: async (ctx) => {
      const set = await ctx.rcon('materione var set - corpus:qa_season summer');
      ctx.expect(set).toContain('corpus:qa_season = summer');
      const get = await ctx.rcon('materione var get - corpus:qa_season');
      ctx.expect(get).toContain('corpus:qa_season = summer');
      ctx.expect(get).toContain('(server)');
    },
  }),

  test({
    id: 'variables.type-mismatch-is-refused',
    title: 'Writing text into the number corpus:qa_coins is refused and leaves the stored value unchanged',
    covers: COVERS,
    dependsOn: ['variables.set-and-get-player'],
    steps: async (ctx) => {
      await ctx.rcon('materione var set Probe corpus:qa_coins 7');
      const refused = await ctx.rcon('materione var set Probe corpus:qa_coins lots');
      ctx.expect(refused).toContain('is not a number');
      const get = await ctx.rcon('materione var get Probe corpus:qa_coins');
      ctx.expect(get).toContain('corpus:qa_coins = 7');
    },
  }),

  test({
    id: 'variables.undeclared-is-refused',
    title: 'Writing an undeclared corpus:qa_gems is refused: no key is created',
    covers: COVERS,
    steps: async (ctx) => {
      const refused = await ctx.rcon('materione var set Probe corpus:qa_gems 1');
      ctx.expect(refused).toContain('no declared variable named');
    },
  }),
];
