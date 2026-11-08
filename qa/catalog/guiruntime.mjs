// The GUI runtime (roadmap M6, docs/architecture/gui.md §5, §7, issue 185): a compiled GUI opens as a real
// container window for a player, the open window follows the editor's saves, and a GUI that stops
// compiling closes for its viewers. The auto entries judge the server's MATERIONE-GUI-* markers and the
// open window read through the client; the visual entry captures what the player sees, which only eyes
// can judge. The GUI is written into the probe's own sample pack, so nothing is left behind.

import { test } from './_lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SERVER_SAMPLE = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)), '..', '..',
  'harness', 'build', 'probe', 'server', 'plugins', 'Materione', 'content', 'packs', 'sample');
const GUI_FILE = path.join(SERVER_SAMPLE, 'guis', 'qa_runtime.yml');

const RUNTIME_COVERS = [
  'plugin/src/main/java/dev/materione/plugin/gui/**',
  'plugin/src/main/java/dev/materione/plugin/command/GuiCommands.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/PipelineService.java',
  'plugin/src/main/java/dev/materione/plugin/runtime/api/RuntimeApiImpl.java',
  'content/src/main/java/dev/materione/content/gui/**',
  'core/src/main/java/dev/materione/core/runtime/RuntimeRegistries.java',
];

/** The GUI the probe pack holds: three rows, a panel, the wells, a heading drawn on the window, one gem. */
function guiYaml(heading, rows = 3) {
  return [
    'qa_runtime:',
    '  title: QA Runtime',
    `  container: chest_${rows}`,
    '  layers:',
    '    - id: frame',
    '      type: panel',
    '      width: 176',
    '      height: 168',
    '    - id: wells',
    '      type: slots',
    '      region: container',
    '    - id: heading',
    '      type: text',
    '      x: 8',
    '      y: 6',
    `      text: "${heading}"`,
    '      font: smallcaps',
    '      color: "#404040"',
    '    - id: sword',
    '      type: item',
    '      slot: 13',
    '      item: corpus:gem_red',
    '      amount: 1',
    '',
  ].join('\n');
}

function writeGui(text) {
  fs.mkdirSync(path.dirname(GUI_FILE), { recursive: true });
  fs.writeFileSync(GUI_FILE, text);
}

function removeGui() {
  fs.rmSync(GUI_FILE, { force: true });
}

export default [
  test({
    id: 'guiruntime.open-command',
    title: '/materione gui open sample:qa_runtime Probe opens the live GUI in the window: 27 slots, the gem in slot 13, MATERIONE-GUI-OPENED',
    covers: RUNTIME_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        writeGui(guiYaml('Shop'));
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        await ctx.reset();
        await ctx.rcon('materione gui open sample:qa_runtime Probe');
        await ctx.waitForServerLog(/MATERIONE-GUI-OPENED id=sample:qa_runtime player=Probe container=chest_3 slots=1/);
        ctx.expect(Number(await ctx.exec('return player.getOpenInventory().getTopInventory().getSize()'))).toBe(27);
        const gem = await ctx.exec(
          'return player.getOpenInventory().getTopInventory().getItem(13).getHoverName().getString()',
        );
        ctx.expect(String(gem)).toBe('Red Gem');
      } finally {
        removeGui();
        await ctx.rcon('materione reload');
      }
    },
  }),

  test({
    id: 'guiruntime.live-refresh',
    title: 'an editor save that changes sample:qa_runtime re-renders the open window in place: the new heading shows, and the cursor item stays in hand',
    dependsOn: ['guiruntime.open-command'],
    covers: RUNTIME_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        writeGui(guiYaml('Shop'));
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        await ctx.reset();
        await ctx.rcon('materione gui open sample:qa_runtime Probe');
        await ctx.waitForServerLog(/MATERIONE-GUI-OPENED id=sample:qa_runtime player=Probe/);
        // a stack in hand: a re-opened window must hand it back, not return it to the inventory
        await ctx.exec('player.setItemOnCursor(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIRT, 3)); return "held"');

        writeGui(guiYaml('Market'));
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-GUI-REFRESH changed=1 removed=0/);
        ctx.expect(String(await ctx.exec('return player.getOpenInventory().getTitle()'))).toContain('Market');
        ctx.expect(String(await ctx.exec('return player.getItemOnCursor().getType().toString()'))).toBe('DIRT');
        ctx.expect(Number(await ctx.exec('return player.getItemOnCursor().getAmount()'))).toBe(3);
      } finally {
        removeGui();
        await ctx.rcon('materione reload');
      }
    },
  }),

  test({
    id: 'guiruntime.linked-item-follows-base',
    title: 'a GUI slot showing a customized item that is a template link to a base item (Customize, #203) shows the base\'s name, follows the base when the base is renamed, and a loop between two such items quarantines only those items',
    dependsOn: ['guiruntime.open-command'],
    covers: [
      ...RUNTIME_COVERS,
      'core/src/main/java/dev/materione/core/pipeline/**',
      'content/src/main/java/dev/materione/content/items/ItemType.java',
      'content/src/main/java/dev/materione/content/Templates.java',
    ],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      const base = path.join(SERVER_SAMPLE, 'items', 'qa_linked_base.yml');
      const linkedDir = path.join(SERVER_SAMPLE, 'items', 'gui', 'qa_runtime');
      const loopA = path.join(SERVER_SAMPLE, 'items', 'qa_loop_a.yml');
      const loopB = path.join(SERVER_SAMPLE, 'items', 'qa_loop_b.yml');
      const writeBase = (name) => fs.writeFileSync(base, `qa_linked_base:\n  material: PAPER\n  display_name: "${name}"\n`);
      try {
        // what Customize writes for a Materione base: a link and nothing else
        fs.mkdirSync(linkedDir, { recursive: true });
        fs.writeFileSync(path.join(linkedDir, 'sword.yml'), 'sword:\n  template: qa_linked_base\n');
        writeBase('Linked Base');
        writeGui(guiYaml('Shop').replace('item: corpus:gem_red', 'item: sample:sword'));
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        await ctx.reset();
        await ctx.rcon('materione gui open sample:qa_runtime Probe');
        await ctx.waitForServerLog(/MATERIONE-GUI-OPENED id=sample:qa_runtime player=Probe/);
        const name = () => ctx.exec(
          'return player.getOpenInventory().getTopInventory().getItem(13).getHoverName().getString()');
        ctx.expect(String(await name())).toBe('Linked Base');

        // the base changes, the linked item is not touched: the GUI follows
        writeBase('Linked Base Two');
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        await ctx.rcon('materione gui open sample:qa_runtime Probe');
        await ctx.waitForServerLog(/MATERIONE-GUI-OPENED id=sample:qa_runtime player=Probe/);
        ctx.expect(String(await name())).toBe('Linked Base Two');

        // a loop between two items quarantines those two (MTR-0093) and nothing else: the GUI still opens
        fs.writeFileSync(loopA, 'qa_loop_a:\n  template: qa_loop_b\n');
        fs.writeFileSync(loopB, 'qa_loop_b:\n  template: qa_loop_a\n');
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        await ctx.rcon('materione gui open sample:qa_runtime Probe');
        await ctx.waitForServerLog(/MATERIONE-GUI-OPENED id=sample:qa_runtime player=Probe/);
        ctx.expect(String(await name())).toBe('Linked Base Two');
      } finally {
        for (const file of [base, loopA, loopB]) fs.rmSync(file, { force: true });
        fs.rmSync(linkedDir, { recursive: true, force: true });
        removeGui();
        await ctx.rcon('materione reload');
      }
    },
  }),

  test({
    id: 'guiruntime.removed-closes-viewer',
    title: 'a save that stops sample:qa_runtime compiling closes the window of its viewer with a note, and the GUI stops resolving',
    dependsOn: ['guiruntime.open-command'],
    covers: RUNTIME_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        writeGui(guiYaml('Shop'));
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        await ctx.reset();
        await ctx.rcon('materione gui open sample:qa_runtime Probe');
        await ctx.waitForServerLog(/MATERIONE-GUI-OPENED id=sample:qa_runtime player=Probe/);

        writeGui(guiYaml('Shop', 9)); // rows outside 1..6: the GUI no longer parses
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-GUI-REFRESH changed=0 removed=1/);
        await ctx.waitForChat(/is no longer available/);
        ctx.expect(Number(await ctx.exec('return player.getOpenInventory().getTopInventory().getSize()'))).not.toBe(27);
      } finally {
        removeGui();
        await ctx.rcon('materione reload');
      }
    },
  }),

  test({
    id: 'guiruntime.visual',
    title: 'the sample:qa_runtime window as a player sees it: the chest with its heading and gem, then the same window after an editor save',
    mode: 'visual',
    dependsOn: ['guiruntime.open-command', 'guiruntime.live-refresh'],
    covers: RUNTIME_COVERS,
    timeoutMs: 180_000,
    steps: async (ctx) => {
      try {
        writeGui(guiYaml('Shop'));
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-RELOAD-OK/);
        await ctx.reset();
        await ctx.rcon('materione gui open sample:qa_runtime Probe');
        await ctx.waitForServerLog(/MATERIONE-GUI-OPENED id=sample:qa_runtime player=Probe/);
        await ctx.shot('gui-runtime-open');

        writeGui(guiYaml('Market'));
        await ctx.rcon('materione reload');
        await ctx.waitForServerLog(/MATERIONE-GUI-REFRESH changed=1 removed=0/);
        await ctx.shot('gui-runtime-refreshed');
        ctx.needsEyes(
          'the sample:qa_runtime window is a three-row vanilla chest: the bevelled panel and the slot wells read like the '
            + 'container screen, the heading "Shop" sits at the top-left in the small caps font in dark grey, and the gem '
            + 'in the 14th slot is the corpus Red Gem. In the refreshed shot the heading reads "Market" and the window is the '
            + 'same window (same position, same size, no flicker of the inventory behind it); the player inventory below '
            + 'is untouched and any item the player holds on the cursor is still shown there.',
        );
      } finally {
        removeGui();
        await ctx.rcon('materione reload');
      }
    },
  }),
];
