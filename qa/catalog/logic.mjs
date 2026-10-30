// Logic providers suite (logic.md §4.5, issue #213). Auto mode: the MATERIONE-LOGIC-OK marker is the
// contract, logged on every content reload with the state of the economy, transfer and provider actions.

import { test } from './_lib.mjs';

export default [
  test({
    id: 'logic.providers-unavailable-without-vault',
    title: 'with no Vault and no transfer host, reload reports materione:money and the money and connect actions unavailable',
    mode: 'auto',
    covers: [
      'api/src/main/java/dev/materione/api/logic/provider/**',
      'plugin/src/main/java/dev/materione/plugin/logic/**',
      'plugin/src/main/java/dev/materione/plugin/MaterionePlugin.java',
      'integrations/src/main/java/dev/materione/integrations/**',
    ],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione reload');
      await ctx.waitForServerLog(/MATERIONE-RELOAD-OK items=\d+/);
      await ctx.waitForServerLog(/MATERIONE-LOGIC-OK currency=materione:money available=false transfer=none unavailable=give_money,take_money,connect/);
    },
  }),
];
