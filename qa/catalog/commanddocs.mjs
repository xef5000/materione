// Command documents suite (#330, logic.md section 40): a command a pack declares with no GUI to open, typed by a real client.
// The commands are the fixtures/packs/commands pack: each announces itself through the server console
// (`say MATERIONE-COMMANDS-...`, read from the server log) and the executor logs its own `MATERIONE-COMMAND alias=...
// outcome=...` line for what a refusal is (the contract is in harness/README.md). The headless harness runs the same
// scenario with fake players on Paper 1.21.4 (PaperCommandsIT); this suite proves it on the 26.x server with a real
// client, where the answer is a chat line the client can read and the player argument is completed by the client's tree.
// The QA client is the only player on its server, so it pays and messages itself.

import { test, typedChat } from './_lib.mjs';

const COVERS = [
  'content/src/main/java/dev/materione/content/commands/**',
  'behavior/src/main/java/dev/materione/behavior/flow/FlowCompiler.java',
  'plugin/src/main/java/dev/materione/plugin/commands/**',
  'plugin/src/main/java/dev/materione/plugin/logic/trigger/CommandTriggers.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/PipelineService.java',
  'fixtures/packs/commands/**',
  'qa/catalog/_lib.mjs',
  'scripts/probe/command.mjs',
];

export default [
  test({
    id: 'commanddocs.discord-prints-a-link',
    title: 'commands:discord (T27, no arguments, no gate) runs when Probe types /discord: the chat shows "Join us: discord.gg/example", the server log prints "MATERIONE-COMMANDS-DISCORD Probe label discord" and the executor answers outcome=ran',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await typedChat(ctx, 'discord', undefined, { waitForTree: true });
      await ctx.waitForServerLog(/MATERIONE-COMMANDS-DISCORD Probe label discord/, { timeoutMs: 20_000 });
      await ctx.waitForServerLog(/MATERIONE-COMMAND alias=discord command=commands:discord outcome=ran/);
      await ctx.waitForChat(/Join us: discord\.gg\/example/);
    },
  }),

  test({
    id: 'commanddocs.pay-sends-to-a-player',
    title: 'commands:pay (T21) takes a typed player and a typed number: /pay Probe 5 prints "MATERIONE-COMMANDS-PAY Probe to Probe amount 5 doubled 10 label pay name pay" (the number is a number, args.to is the player) and the player named by the argument reads "Probe sent you 5" in chat',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await typedChat(ctx, 'pay Probe 5', undefined, { waitForTree: true });
      await ctx.waitForServerLog(
        /MATERIONE-COMMANDS-PAY Probe to Probe amount 5 doubled 10 label pay name pay/, { timeoutMs: 20_000 });
      await ctx.waitForChat(/Probe sent you 5/);
    },
  }),

  test({
    id: 'commanddocs.pay-refuses-a-bad-amount',
    title: 'the requirement of commands:pay refuses /pay Probe -3 before the flow runs: the deny steps tell Probe "Pay a positive amount." in chat, the server prints "MATERIONE-COMMANDS-PAY-DENIED Probe amount -3" and the executor answers outcome=refused',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      // an operator holds every node nobody registered, the bypass node among them: Probe must not be one
      const restoreOperator = (await ctx.rcon('deop Probe')).includes('Made Probe no longer a server operator');
      try {
        await typedChat(ctx, 'pay Probe -3', undefined, { waitForTree: true });
        await ctx.waitForServerLog(/MATERIONE-COMMANDS-PAY-DENIED Probe amount -3/, { timeoutMs: 20_000 });
        await ctx.waitForServerLog(/MATERIONE-COMMAND alias=pay command=commands:pay outcome=refused/);
        await ctx.waitForChat(/Pay a positive amount\./);
        const log = String(await ctx.serverLogText());
        ctx.expect(log.includes('MATERIONE-COMMANDS-PAY Probe to Probe amount -3')).toBe(false);
      } finally {
        if (restoreOperator) await ctx.rcon('op Probe');
      }
    },
  }),

  test({
    id: 'commanddocs.permission-denied',
    title: 'a player without the permission of commands:staff is refused before anything else: after "deop Probe", /staff tells Probe "no permission (commands.staff)", the executor answers outcome=denied and the flow never prints',
    mode: 'auto',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      const restoreOperator = (await ctx.rcon('deop Probe')).includes('Made Probe no longer a server operator');
      try {
        await typedChat(ctx, 'staff', undefined, { waitForTree: true });
        await ctx.waitForServerLog(/MATERIONE-COMMAND alias=staff command=commands:staff outcome=denied/, { timeoutMs: 20_000 });
        await ctx.waitForChat(/no permission \(commands\.staff\)/);
        const log = String(await ctx.serverLogText());
        ctx.expect(log.includes('MATERIONE-COMMANDS-STAFF Probe')).toBe(false);
      } finally {
        if (restoreOperator) await ctx.rcon('op Probe');
      }
    },
  }),

  test({
    id: 'commanddocs.suggestions-offer-players',
    title: 'the client completes the player argument of /pay from the synced tree: typing "/pay " offers Probe, the only player online, in the completion popup',
    mode: 'visual',
    covers: COVERS,
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.exec('mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("/pay ", false)); return "typed"');
      await ctx.sleep(1200); // the client asks the server for completions
      await ctx.shot('commanddocs-pay-suggestions');
      ctx.needsEyes('the chat box holds "/pay " and the completion popup above it lists "Probe", the player argument of commands:pay');
      await ctx.exec('mc.setScreen(null); return "closed"');
    },
  }),
];
