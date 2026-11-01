// GUI open commands (roadmap M6, logic.md §6.1, issues #215 and #96): a GUI that declares `open: commands:` gets a
// Brigadier literal the client types, and the typed argument reaches the server as a typed value. The guilogic
// fixture pack's `opener` GUI declares `opentest` with a choice argument (all, tools, food) and a requirement that
// refuses food. The auto entries judge the server-log markers the handler prints (`MATERIONE-OPEN-COMMAND`, the
// contract in harness/README.md), the chat the player sees, and whether the container opened; the visual entry
// captures the window.

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ALIAS_FILE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..',
  'harness', 'build', 'probe', 'server', 'plugins', 'Materione', 'content', 'packs',
  'sample', 'guis', 'qa_auto_alias.yml');

const COVERS = [
  'plugin/src/main/java/dev/materione/plugin/gui/**',
  'plugin/src/main/java/dev/materione/plugin/gui/open/**',
  'plugin/src/main/java/dev/materione/plugin/MaterionePlugin.java',
  'behavior/src/main/java/dev/materione/behavior/flow/FlowCompiler.java',
  'content/src/main/java/dev/materione/content/gui/**',
  'fixtures/packs/guilogic/**',
];

/** How many slots the open container has: a one-row GUI has 9 chest slots and the 36 of the player's inventory. */
async function openSlots(ctx) {
  return Number(await ctx.exec('return player.containerMenu.slots.size()'));
}

async function containerOpen(ctx) {
  return Number(await ctx.exec('return player.containerMenu.containerId')) !== 0;
}

export default [
  test({
    id: 'opencommands.deferred-alias-applies-automatically',
    title: 'sample:qa_auto_alias changes its command twice inside five seconds; qa_auto_second opens it after the scheduled retry without a third content reload',
    covers: [...COVERS, 'core/src/main/java/dev/materione/core/scheduling/**'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const original = fs.existsSync(ALIAS_FILE) ? fs.readFileSync(ALIAS_FILE, 'utf8') : null;
      const yaml = (alias) => `qa_auto_alias:\n  title: Automatic alias\n  rows: 1\n  layers: []\n  open:\n    commands: [${alias}]\n`;
      try {
        await ctx.reset();
        fs.mkdirSync(path.dirname(ALIAS_FILE), { recursive: true });
        fs.writeFileSync(ALIAS_FILE, yaml('qa_auto_first'));
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-OPEN-TREE reload aliases=.*qa_auto_first/);
        fs.writeFileSync(ALIAS_FILE, yaml('qa_auto_second'));
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-OPEN-TREE reload-deferred aliases=.*qa_auto_second/);
        await ctx.waitForServerLog(/MATERIONE-OPEN-TREE reload aliases=.*qa_auto_second/);
        await ctx.command('qa_auto_second');
        await ctx.waitForServerLog(/MATERIONE-OPEN-COMMAND alias=qa_auto_second outcome=opened/);
        ctx.expect(await containerOpen(ctx)).toBeTruthy();
        ctx.expect(await openSlots(ctx)).toBe(45);
      } finally {
        await ctx.exec('player.closeContainer(); return "closed"');
        if (original === null) fs.rmSync(ALIAS_FILE, { force: true });
        else fs.writeFileSync(ALIAS_FILE, original);
        await ctx.rcon('materione reload');
      }
    },
  }),

  test({
    id: 'opencommands.typed-command-opens-the-gui',
    title: 'the open command /opentest with the choice argument tools opens guilogic:opener (one row, 45 slots counting the player inventory) and the server answers outcome=opened',
    covers: COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.command('opentest tools');
      await ctx.waitForServerLog(/MATERIONE-OPEN-COMMAND alias=opentest outcome=opened/);
      await ctx.sleep(600); // the client finishes opening the window
      ctx.expect(await containerOpen(ctx)).toBeTruthy();
      ctx.expect(await openSlots(ctx)).toBe(45);
    },
  }),

  test({
    id: 'opencommands.bad-argument-answers-with-usage',
    title: 'the open command /opentest with the value nope is answered in chat with its usage line ("usage: /opentest" with the choice values), the server logs outcome=usage, and no window opens',
    covers: COVERS,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.command('opentest nope');
      await ctx.waitForServerLog(/MATERIONE-OPEN-COMMAND alias=opentest outcome=usage/);
      await ctx.waitForChat(/usage: \/opentest/);
      ctx.expect(await containerOpen(ctx)).toBe(false);
    },
  }),

  test({
    id: 'opencommands.failed-requirement-denies-with-its-message',
    title: 'the open command /opentest with the choice food fails the requirement of guilogic:opener: the deny steps send "No food here, pick another category." in chat, the server logs outcome=refused, and no window opens',
    covers: COVERS,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();
      const restoreOperator = (await ctx.rcon('deop Probe')).includes('Made Probe no longer a server operator');
      try {
        await ctx.command('opentest food');
        await ctx.waitForServerLog(/MATERIONE-OPEN-COMMAND alias=opentest outcome=refused/);
        await ctx.waitForChat(/No food here, pick another category\./);
        ctx.expect(await containerOpen(ctx)).toBe(false);
      } finally {
        if (restoreOperator) await ctx.rcon('op Probe');
      }
    },
  }),

  test({
    id: 'opencommands.window-matches-the-open-block',
    title: 'guilogic:opener opened by /opentest tools is a one-row chest titled "Opener" with the heading "OPENER" in small caps at the top left and its container slots empty',
    mode: 'visual',
    covers: COVERS,
    dependsOn: ['opencommands.typed-command-opens-the-gui'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.command('opentest tools');
      await ctx.waitForServerLog(/MATERIONE-OPEN-COMMAND alias=opentest outcome=opened/);
      await ctx.sleep(600);
      await ctx.shot('opencommands-opener');
      ctx.needsEyes(
        'the window is a one-row chest titled "Opener", with the heading "OPENER" in small caps at the top left '
          + 'and an empty row of container slots, opened by typing /opentest tools',
      );
    },
  }),
];
