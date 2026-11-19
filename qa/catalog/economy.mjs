// Economy-provider suite: one test per currency integration that needs a real third-party plugin (logic.md section
// 18, items 30-36). Each scenario is a probe item in fixtures/packs/probe/items/probe_money_*.yml whose right-click
// rule pays, charges and refuses against a currency file in fixtures/packs/probe/currencies/; the run reads Materione's
// view back from chat and then asks the plugin ITSELF, as the player, what the balance is, so a pass means the money
// really moved in the provider and not only in Materione's memory.
//
// These need the pinned optional plugin jars in the disposable probe server; see "Economy-provider tests" in
// qa/README.md. Each entry checks that its plugin is loaded before asserting anything.

import { test } from './_lib.mjs';

// One right-click in the air: aim at the sky, prove the block ray misses, then use the held item.
async function useInAir(ctx) {
  return ctx.exec(`def yaw = player.getYRot()
def pitch = player.getXRot()
player.setRot(yaw, -89.0F)
def blockHit = player.pick(20.0D, 1.0F, false)
if (blockHit.getType().toString() != 'MISS') {
  player.setRot(yaw, pitch)
  return 'not-air:' + blockHit.getType().toString()
}
mc.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND)
player.setRot(yaw, pitch)
return 'used-air'`, 15_000);
}

async function holdProbe(ctx, item) {
  await ctx.reset();
  await ctx.rcon(`materione give ${item} Probe`);
  await ctx.waitForChat(new RegExp(`Given ${item}`));
  await ctx.exec('player.getInventory().selected = 0');
}

const SERVER_PLUGINS = async (ctx) => String(await ctx.rcon('plugins'));

export default [
  test({
    id: 'economy.playerpoints-roundtrip',
    title: 'probe:probe_money_playerpoints right-click pays 25 and charges 10 through PlayerPoints, refuses a fraction and an overdraft without changing the balance, and PlayerPoints itself reports 15',
    mode: 'auto',
    covers: [
      'integrations/src/main/java/dev/materione/integrations/playerpoints/**',
      'integrations/src/main/java/dev/materione/integrations/currency/**',
      'integrations/src/main/java/dev/materione/integrations/Integrations.java',
      'plugin/src/main/java/dev/materione/plugin/logic/currency/**',
      'plugin/src/main/java/dev/materione/plugin/logic/action/GiveMoneyAction.java',
      'plugin/src/main/java/dev/materione/plugin/logic/action/TakeMoneyAction.java',
      'fixtures/packs/probe/currencies/qa_playerpoints.yml',
      'fixtures/packs/probe/items/probe_money_playerpoints.yml',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      ctx.expect(await SERVER_PLUGINS(ctx)).toContain('PlayerPoints');
      await ctx.rcon('points set Probe 0');
      await holdProbe(ctx, 'probe:probe_money_playerpoints');
      ctx.expect(await useInAir(ctx)).toBe('used-air');
      await ctx.waitForChat(/PP after-give=25(\.0+)?$/);
      await ctx.waitForChat(/PP after-take=15(\.0+)?$/);
      // 2.5 points cannot exist in PlayerPoints: refused, so the balance is still 15, and the reason is on record
      await ctx.waitForChat(/PP after-fraction=15(\.0+)?$/);
      await ctx.waitForServerLog(/PlayerPoints holds whole numbers only, so 2\.5 cannot be used/);
      await ctx.waitForChat(/PP overdraft-refused balance=15(\.0+)?$/);
      // the provider's own word, asked as the player: the money moved in PlayerPoints, not only in Materione
      await ctx.command('points me');
      await ctx.waitForChat(/\[PlayerPoints\] You have 15 Points\./);
    },
  }),

  test({
    id: 'economy.excellenteconomy-two-currencies',
    title: 'probe:probe_money_excellenteconomy right-click pays and charges two Materione currencies backed by ExcellentEconomy\'s coins (whole) and money (decimal), refuses a coin overdraft, and ExcellentEconomy itself reports coins 20 and money 10',
    mode: 'auto',
    covers: [
      'integrations-excellenteconomy/**',
      'integrations/src/main/java/dev/materione/integrations/Integrations.java',
      'plugin/src/main/java/dev/materione/plugin/logic/currency/**',
      'plugin/src/main/java/dev/materione/plugin/logic/action/GiveMoneyAction.java',
      'plugin/src/main/java/dev/materione/plugin/logic/action/TakeMoneyAction.java',
      'fixtures/packs/probe/currencies/qa_excellenteconomy_coins.yml',
      'fixtures/packs/probe/currencies/qa_excellenteconomy_money.yml',
      'fixtures/packs/probe/items/probe_money_excellenteconomy.yml',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      const plugins = await SERVER_PLUGINS(ctx);
      ctx.expect(plugins).toContain('ExcellentEconomy');
      await ctx.rcon('coins set Probe 0');
      await ctx.rcon('money set Probe 0');
      await holdProbe(ctx, 'probe:probe_money_excellenteconomy');
      ctx.expect(await useInAir(ctx)).toBe('used-air');
      await ctx.waitForChat(/EE after-give coins=30(\.0+)? money=12\.5(0)?$/);
      await ctx.waitForChat(/EE after-take coins=20(\.0+)? money=10(\.0+)?$/);
      await ctx.waitForChat(/EE overdraft-refused coins=20(\.0+)?$/);
      // the provider's own word, asked as the player, for each of the two currencies
      await ctx.command('coins balance');
      await ctx.waitForChat(/Coins » Balance: 20⛂\./);
      await ctx.command('money balance');
      await ctx.waitForChat(/Money » Balance: \$10(\.00)?\./);
    },
  }),
];
