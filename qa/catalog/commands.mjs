// Seed suite: the /materione command surface (issue #38).
// These are auto tests — the runner judges them from console replies, server
// markers (harness/README.md contract) and client-side Groovy.

import { test } from './_lib.mjs';

const COMMAND = 'plugin/src/main/java/dev/materione/plugin/command/MaterioneCommand.java';

async function inventoryCount(ctx) {
  return Number(await ctx.exec(
    'def total = 0; for (int i = 0; i < 36; i++) { def s = player.getInventory().items.get(i); if (!s.isEmpty()) total += s.getCount() }; return total',
  ));
}

async function typedChat(ctx, command, shot) {
  const text = JSON.stringify(command);
  const parsed = await ctx.exec(
    `def p = mc.getConnection().getCommands().parse(${text}, mc.getConnection().getSuggestionsProvider()); return 'remaining=' + p.getReader().getRemaining() + ';exceptions=' + p.getExceptions().size()`,
  );
  ctx.expect(String(parsed)).toBe('remaining=;exceptions=0');
  await ctx.exec(
    `mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(${JSON.stringify('/' + command)}, false)); return 'typed'`,
  );
  await ctx.sleep(300);
  await ctx.shot(shot);
  ctx.expect(await ctx.exec(
    'return mc.screen.keyPressed(new net.minecraft.client.input.KeyEvent(257, 0, 0))',
  )).toBe(true);
}

export default [
  test({
    id: 'commands.typed-player-chat',
    title: 'normal client chat accepts a namespaced item and multi-token /materione give tail without quoting, then delivers exactly five gems',
    covers: [COMMAND, 'plugin/src/main/java/dev/materione/plugin/command/MaterioneCommandAdapter.java',
      'plugin/src/main/java/dev/materione/plugin/MaterionePlugin.java',
      'fixtures/packs/corpus/items/**'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      const granted = (await ctx.rcon('op Probe')).includes('Made Probe a server operator');
      try {
        await ctx.sleep(400);
        await typedChat(ctx, 'materione give corpus:gem_red Probe 5', 'typed-root-input');
        await ctx.waitForChat(/Given corpus:gem_red.*5/);
        const stack = await ctx.exec(
          "def s = player.getInventory().items.get(0); return s.getHoverName().getString() + ':' + s.getCount()",
        );
        ctx.expect(String(stack)).toBe('Red Gem:5');
        ctx.expect(await inventoryCount(ctx)).toBe(5);
        await ctx.shot('typed-root-result');
      } finally {
        if (granted) await ctx.rcon('deop Probe');
      }
    },
  }),

  test({
    id: 'commands.typed-gui-alias',
    title: 'normal client chat opens /opentest tools, reports malformed choice usage, and runs the food denial without opening another window',
    covers: ['plugin/src/main/java/dev/materione/plugin/gui/open/**',
      'fixtures/packs/guilogic/guis/opener.yml'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      const restoreOperator = (await ctx.rcon('deop Probe')).includes('Made Probe no longer a server operator');
      try {
        await ctx.sleep(400);
        await typedChat(ctx, 'opentest tools', 'typed-alias-input');
        await ctx.waitForServerLog(/MATERIONE-OPEN-COMMAND alias=opentest outcome=opened/);
        await ctx.sleep(600);
        ctx.expect(Number(await ctx.exec('return player.containerMenu.slots.size()'))).toBe(45);
        ctx.expect(Number(await ctx.exec('return player.containerMenu.containerId'))).not.toBe(0);
        await ctx.shot('typed-alias-window');
        await ctx.exec('player.closeContainer(); return "closed"');
        await typedChat(ctx, 'opentest nope', 'typed-alias-bad-argument');
        await ctx.waitForServerLog(/MATERIONE-OPEN-COMMAND alias=opentest outcome=usage/);
        await ctx.waitForChat(/usage: \/opentest/);
        ctx.expect(Number(await ctx.exec('return player.containerMenu.containerId'))).toBe(0);
        await typedChat(ctx, 'opentest food', 'typed-alias-denial');
        await ctx.waitForServerLog(/MATERIONE-OPEN-COMMAND alias=opentest outcome=refused/);
        await ctx.waitForChat(/No food here, pick another category\./);
        ctx.expect(Number(await ctx.exec('return player.containerMenu.containerId'))).toBe(0);
      } finally {
        await ctx.exec('player.closeContainer(); return "closed"');
        if (restoreOperator) await ctx.rcon('op Probe');
      }
    },
  }),

  test({
    id: 'commands.version',
    title: '/materione version reports the running plugin version to the console',
    covers: [COMMAND, 'plugin/src/main/java/dev/materione/plugin/MaterioneVersion.java'],
    steps: async (ctx) => {
      const reply = await ctx.rcon('materione version');
      ctx.expect(reply).toMatch(/^Materione \S+/);
      // The MATERIONE-VERSION marker is logged on every version command, so
      // it lands inside this test's log window (unlike boot markers, which
      // precede it).
      ctx.expect(await ctx.serverLogLine(/MATERIONE-VERSION /)).toBeTruthy();
    },
  }),

  test({
    id: 'commands.unknown-subcommand',
    title: '/materione <unknown> answers with the usage line (fail-soft command surface)',
    covers: [COMMAND],
    steps: async (ctx) => {
      const reply = await ctx.rcon('materione definitely-not-real');
      ctx.expect(reply).toContain('usage: /materione');
    },
  }),

  test({
    id: 'commands.doctor',
    title: '/materione doctor prints the system report and completes (MATERIONE-DOCTOR-OK)',
    covers: [COMMAND],
    dependsOn: ['commands.version'],
    steps: async (ctx) => {
      const reply = await ctx.rcon('materione doctor');
      ctx.expect(reply).toContain('Materione doctor');
      ctx.expect(reply).toContain('platform:');
      ctx.expect(reply).toContain('runtime:');
      ctx.expect(reply).toContain('delivery:');
      await ctx.waitForServerLog(/MATERIONE-DOCTOR-OK/);
    },
  }),

  test({
    id: 'commands.give-basic',
    title: 'corpus:gem_red is handed to Probe: chat line, MATERIONE-GIVE-OK marker, item in inventory',
    covers: [
      COMMAND,
      'plugin/src/main/java/dev/materione/plugin/items/**',
      'fixtures/packs/corpus/items/**',
    ],
    dependsOn: ['commands.version'],
    steps: async (ctx) => {
      await ctx.reset();
      // The give reply is empty by contract: delivery hops to the target's
      // region thread, feedback goes to the player as chat (asserted below)
      // and to the server log as MATERIONE-GIVE-OK.
      await ctx.rcon('materione give corpus:gem_red Probe');
      const chatLine = await ctx.waitForChat(/Given corpus:gem_red/);
      ctx.expect(chatLine).toContain('×1');
      await ctx.waitForServerLog(/MATERIONE-GIVE-OK id=corpus:gem_red player=Probe amount=1/);
      // The stack must actually be in the (just-cleared) player inventory
      // with its pack-defined display name. DebugBridge wraps collections, so
      // use indexed access + method calls — no Groovy closures.
      const displayName = await ctx.exec(
        'return player.getInventory().items.get(0).getHoverName().getString()',
      );
      ctx.expect(String(displayName)).toBe('Red Gem');
    },
  }),

  test({
    id: 'commands.give-amount',
    title: '/materione give honors the amount argument (5 stacks of corpus:gem_red)',
    covers: [COMMAND, 'plugin/src/main/java/dev/materione/plugin/items/Give.java'],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione give corpus:gem_red Probe 5');
      const chatLine = await ctx.waitForChat(/Given corpus:gem_red/);
      ctx.expect(chatLine).toContain('×5');
      await ctx.waitForServerLog(/MATERIONE-GIVE-OK id=corpus:gem_red player=Probe amount=5/);
      // Sum every non-empty stack in the player inventory (indexed form —
      // DebugBridge wraps collections; closures do not resolve).
      ctx.expect(await inventoryCount(ctx)).toBe(5);
    },
  }),

  test({
    id: 'commands.give-unknown-item',
    title: '/materione give corpus:does_not_exist fails soft: friendly message + MATERIONE-GIVE-FAILED reason=unknown-item',
    covers: [COMMAND, 'plugin/src/main/java/dev/materione/plugin/items/ItemIdResolver.java'],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      const reply = await ctx.rcon('materione give corpus:does_not_exist Probe');
      ctx.expect(reply).toContain("no live item matches 'corpus:does_not_exist'");
      await ctx.waitForServerLog(/MATERIONE-GIVE-FAILED reason=unknown-item/);
    },
  }),

  test({
    id: 'commands.give-unknown-player',
    title: '/materione give with an offline target fails soft: friendly message + MATERIONE-GIVE-FAILED reason=no-such-player',
    covers: [COMMAND],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      const reply = await ctx.rcon('materione give corpus:gem_red Nobody_Here');
      ctx.expect(reply).toContain("no online player named 'Nobody_Here'");
      await ctx.waitForServerLog(/MATERIONE-GIVE-FAILED reason=no-such-player player=Nobody_Here/);
    },
  }),
];
