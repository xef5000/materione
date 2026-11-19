// Economy-provider suite: one test per currency integration that needs a real third-party plugin (logic.md section
// 18, items 30-36). Each scenario is a probe item in fixtures/packs/probe/items/probe_money_*.yml whose right-click
// rule pays, charges and refuses against a currency file in fixtures/packs/probe/currencies/; the run reads Materione's
// view back from chat and then asks the plugin ITSELF, as the player, what the balance is, so a pass means the money
// really moved in the provider and not only in Materione's memory.
//
// These need the pinned optional plugin jars in the disposable probe server; see "Economy-provider tests" in
// qa/README.md. Each entry checks that its plugin is loaded before asserting anything.

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
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

// RedisEconomy keeps its money in Redis, so the independent cross-check reads Redis itself. The container name is
// the one qa/README.md starts; MATERIONE_QA_REDIS_CONTAINER overrides it.
const REDIS_CONTAINER = process.env.MATERIONE_QA_REDIS_CONTAINER || 'materione-qa-redis';

/** The UUID an offline-mode server gives a name (Java's UUID.nameUUIDFromBytes of "OfflinePlayer:<name>"). */
function offlineUuid(name) {
  const bytes = createHash('md5').update(`OfflinePlayer:${name}`).digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** A player's balance of one RedisEconomy currency, read from the sorted set RedisEconomy stores it in. */
function redisBalance(currency, name) {
  const out = execFileSync('docker', ['exec', REDIS_CONTAINER, 'redis-cli', 'ZSCORE', `rediseco:balances_${currency}`,
    offlineUuid(name)], { encoding: 'utf8', timeout: 15_000 }).trim();
  return Number(out);
}

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

  test({
    id: 'economy.rediseconomy-two-currencies',
    title: 'probe:probe_money_rediseconomy right-click pays and charges two Materione currencies backed by RedisEconomy\'s default and dollar currencies, refuses an overdraft, and Redis itself holds the changed balances',
    mode: 'auto',
    covers: [
      'integrations/src/main/java/dev/materione/integrations/rediseconomy/**',
      'integrations/src/main/java/dev/materione/integrations/Integrations.java',
      'plugin/src/main/java/dev/materione/plugin/logic/currency/**',
      'plugin/src/main/java/dev/materione/plugin/logic/action/GiveMoneyAction.java',
      'plugin/src/main/java/dev/materione/plugin/logic/action/TakeMoneyAction.java',
      'fixtures/packs/probe/currencies/qa_rediseconomy_default.yml',
      'fixtures/packs/probe/currencies/qa_rediseconomy_dollar.yml',
      'fixtures/packs/probe/items/probe_money_rediseconomy.yml',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      ctx.expect(await SERVER_PLUGINS(ctx)).toContain('RedisEconomy');
      await holdProbe(ctx, 'probe:probe_money_rediseconomy');
      ctx.expect(await useInAir(ctx)).toBe('used-air');
      // the balances live in Redis and outlast a run, so the item reports where each started and this works in changes
      const before = String(await ctx.waitForChat(/RE before default=\S+ dollar=\S+$/));
      const [startDefault, startDollar] = [...before.matchAll(/=(\S+)/g)].map((m) => Number(m[1]));
      ctx.expect(Number.isFinite(startDefault) && Number.isFinite(startDollar)).toBe(true);
      await ctx.waitForChat(new RegExp(`RE after-give default=${startDefault + 25}(\\.0+)? dollar=${startDollar + 12.5}(0)?$`));
      await ctx.waitForChat(new RegExp(`RE after-take default=${startDefault + 15}(\\.0+)? dollar=${startDollar + 10}(\\.0+)?$`));
      await ctx.waitForChat(new RegExp(`RE overdraft-refused default=${startDefault + 15}(\\.0+)?$`));
      // the provider's own store, read independently of Materione: the money moved in RedisEconomy
      ctx.expect(redisBalance('vault', 'Probe')).toBe(startDefault + 15);
      ctx.expect(redisBalance('dollar', 'Probe')).toBe(startDollar + 10);
    },
  }),
];
