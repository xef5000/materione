import { test } from './_lib.mjs';

const covers = [
  'content/src/main/java/dev/materione/content/gui/**',
  'plugin/src/main/java/dev/materione/plugin/gui/**',
  'pack/src/main/java/dev/materione/pack/allocation/**',
  'fixtures/packs/overlay/**',
];

async function click(ctx) {
  await ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, 11, 0,
    net.minecraft.world.inventory.ContainerInput.PICKUP, player); return 'clicked'`);
}

async function title(ctx) {
  return String(await ctx.exec("return mc.screen == null ? '' : mc.screen.getTitle().getString()"));
}

async function open(ctx) {
  await ctx.reset();
  await ctx.rcon('materione gui open overlay:tabs Probe');
  await ctx.waitForServerLog(/MATERIONE-OVERLAY-OPEN/);
  await ctx.sleep(700);
}

async function waitForTitle(ctx, shown) {
  for (let i = 0; i < 30; i++) {
    if ((await title(ctx)).includes('ACTIVE Probe') === shown) return;
    await ctx.sleep(200);
  }
  ctx.expect((await title(ctx)).includes('ACTIVE Probe')).toBe(shown);
}

export default [
  test({
    id: 'guioverlay.toggle-auto',
    title: 'overlay:tabs toggles conditional image and templated text in the same GUI session',
    covers,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await open(ctx);
        await waitForTitle(ctx, false);
        await click(ctx);
        await waitForTitle(ctx, true);
        await click(ctx);
        await waitForTitle(ctx, false);
      } finally {
        await ctx.exec("player.closeContainer(); return 'closed'");
      }
    },
  }),
  test({
    id: 'guioverlay.toggle-visual',
    title: 'overlay:tabs shows and hides the selected image and ACTIVE Probe label when toggled',
    mode: 'visual',
    dependsOn: ['guioverlay.toggle-auto'],
    covers,
    timeoutMs: 120_000,
    steps: async (ctx) => {
      try {
        await open(ctx);
        await waitForTitle(ctx, false);
        await ctx.shot('tab-hidden');
        await click(ctx);
        await waitForTitle(ctx, true);
        await ctx.shot('tab-shown');
        ctx.needsEyes('Compare screenshots: the selected image and ACTIVE Probe text are absent before and visible after the toggle; background and slot items remain stable.');
      } finally {
        await ctx.exec("player.closeContainer(); return 'closed'");
      }
    },
  }),
];
