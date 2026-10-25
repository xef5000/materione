// Paintings suite: pack-authored paintings/ definitions verified end to end on
// a real client — the variants are composed through Paper's registry events at
// bootstrap (registry mode: nothing is deployed to the world), sync to the
// client's painting-variant registry with their authored properties, place
// through the dev driver (whose direction had the NMS contract backwards,
// #101) and render the pack's own atlas sprites. The placeable tag is probed
// through vanilla item placement: non-placeable variants must never appear in
// the random pool.
//
// Client-side access (Mojmap 26.x): the level's RegistryAccess resolves the
// PAINTING_VARIANT registry; entries carry width/height/assetId/author/title.
// mc.hitResult is a stale render-frame cache — aim verification uses a fresh
// mc.player.pick(...) instead.

import { test } from './_lib.mjs';

// Registry read: id → the variant's authored properties, straight off the
// client's synced registry. Unknown ids resolve as MISSING (a miss must stay
// a miss — registration is real, not "anything resolves").
const REGISTRY_READ = `def rk = Class.forName('net.minecraft.core.registries.Registries').getField('PAINTING_VARIANT').get(null)
def reg = mc.level.registryAccess().@target.lookupOrThrow(rk)
def read = { ns, name ->
  def idc = Class.forName('net.minecraft.resources.Identifier').getMethod('fromNamespaceAndPath', String, String).invoke(null, ns, name)
  def opt = reg.get(idc)
  if (opt.isEmpty()) return ns + ':' + name + '=MISSING'
  def v = opt.get().value()
  def text = { o -> o.isPresent() ? o.get().getString() : 'none' }
  return reg.getKey(v).toString() + ' size=' + v.width() + 'x' + v.height() + ' asset=' + v.assetId() + ' author=' + text(v.author()) + ' title=' + text(v.title())
}
return read('sample', 'sunrise') + ' | ' + read('sample', 'moonrise') + ' | ' + read('sample', 'panorama') + ' | ' + read('sample', 'nope') + ' | ' + read('minecraft', 'alban')`;

// Aim the camera and prove the crosshair really holds the wanted cell before
// any placement command — the server raytraces from the synced rotation, so a
// stale or drifted aim would place (or fail) somewhere else entirely.
async function aimAt(ctx, yaw, pitch, cell, face) {
  await ctx.exec(`player.setRot(${yaw}F, ${pitch}F)
Thread.sleep(450)
def picked = mc.player.pick(20.0D, 1.0F, false)
if (picked.getType().toString() != 'BLOCK') return 'MISS'
def pos = picked.getBlockPos()
def dir = picked.getDirection().toString()
return pos.getX() + ',' + pos.getY() + ',' + pos.getZ() + ' ' + dir`, 15_000)
    .then((hit) => {
      ctx.expect(String(hit)).toBe(`${cell[0]},${cell[1]},${cell[2]} ${face}`);
    });
}

// The dev driver travels as a raw command packet: the client's synced tree
// collapses everything after /materione into one quoted-string argument, so
// the chat GUI would reject the namespaced id before it is ever sent (#96).
async function devPainting(ctx, key) {
  await ctx.exec(`mc.getConnection().@target.sendCommand('materione dev painting ${key}')
return 'sent'`, 15_000);
}

async function paintingFacingId(ctx, selector) {
  const output = await ctx.rcon(`data get entity ${selector} facing`);
  const match = /entity data:\s*(-?\d+)b\s*$/.exec(output);
  if (!match) throw new Error(`expected a painting facing byte, got ${output}`);
  return Number(match[1]);
}

const PAINTING_SCENE = { playerY: 31, floorY: 30, wallZ: 8 };

async function clearPaintingScene(ctx) {
  await ctx.rcon('kill @e[type=painting]');
  await ctx.rcon(
    `fill -3 ${PAINTING_SCENE.floorY} 2 3 ${PAINTING_SCENE.floorY + 4} 8 minecraft:air`);
}

async function stageAimedWall(ctx) {
  await clearPaintingScene(ctx);
  await ctx.rcon(
    `fill -2 ${PAINTING_SCENE.floorY} 2 2 ${PAINTING_SCENE.floorY} 6 minecraft:smooth_stone`);
  await ctx.rcon(
    `fill -2 ${PAINTING_SCENE.floorY} ${PAINTING_SCENE.wallZ} 2 ${PAINTING_SCENE.floorY + 3} ${PAINTING_SCENE.wallZ} minecraft:smooth_stone`);
  // The elevated scene has its own floor target and never clears the
  // superflat terrain used by the other suites.
  await ctx.rcon(`tp Probe 0 ${PAINTING_SCENE.playerY} 2`);
  await ctx.sleep(1_000);
}

const PLACEABLE_PROBE_TRIALS = 25;

export default [
  test({
    id: 'paintings.registration',
    title: "the pack's three painting variants are composed through Paper's registry events at bootstrap (boot marker paintings=3 mode=registry, doctor reports registry content current with nothing deployed) and reach the client's synced painting-variant registry with their authored size, asset_id, author and title — sunrise 2×1, moonrise 1×2, panorama 3×2, an unregistered id resolves to nothing and a vanilla variant stays vanilla; /materione dev painting places the custom variants facing the wall, refuses a variant that doesn't fit, reports unknown variants and answers a floor aim with the wall hint",
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/paintings/**',
      'modern/src/main/java/dev/materione/modern/**',
      'plugin/src/main/java/dev/materione/plugin/command/**',
      'fixtures/packs/sample/paintings/**',
    ],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      await ctx.reset();

      // --- registry mode is the live seam: composed at bootstrap, nothing
      // deployed, doctor honest about it
      ctx.expect(
        await ctx.serverLogLine(/MATERIONE-REGISTRY-OK paintings=3 songs=\d+ mode=registry/, { sinceTest: false }),
      ).toBeTruthy();
      const doctor = await ctx.rcon('materione doctor');
      ctx.expect(doctor).toContain('registry: paintings/jukebox registered at boot (content ');
      ctx.expect(doctor).toContain('— current');
      ctx.expect(doctor.includes('RESTART REQUIRED')).toBe(false);

      // --- the client's synced registry carries the authored properties
      const read = await ctx.exec(REGISTRY_READ, 20_000);
      ctx.expect(read).toContain(
        'sample:sunrise size=2x1 asset=sample:sunrise author=Materione title=Sunrise');
      ctx.expect(read).toContain(
        'sample:moonrise size=1x2 asset=sample:moonrise author=Materione title=Moonrise');
      ctx.expect(read).toContain(
        'sample:panorama size=3x2 asset=sample:panorama author=Materione title=Panorama');
      ctx.expect(read).toContain('sample:nope=MISSING'); // registration is real
      ctx.expect(read).toMatch(/minecraft:alban size=1x1 asset=minecraft:alban/); // vanilla stays vanilla

      // --- own an elevated approach and wall: ctx.reset does not clear blocks,
      // and this scene must not clear the superflat floor other suites use.
      await stageAimedWall(ctx);

      // --- dev painting places each custom variant; the placed entity's
      // variant and facing read back through the server
      await aimAt(ctx, 0, 12, [0, PAINTING_SCENE.playerY, PAINTING_SCENE.wallZ], 'north');
      await devPainting(ctx, 'sample:sunrise');
      await ctx.waitForChat(/dev painting: placed sample:sunrise/);
      ctx.expect(await ctx.serverLogLine(/MATERIONE-DEV-PAINTING ok=true key=sample:sunrise/)).toBeTruthy();
      ctx.expect(await ctx.rcon(`data get entity @e[type=painting,x=0.5,y=${PAINTING_SCENE.playerY + 0.5},z=7.5,distance=..2,limit=1] variant`))
        .toContain('sample:sunrise');
      ctx.expect(await paintingFacingId(
        ctx, `@e[type=painting,x=0.5,y=${PAINTING_SCENE.playerY + 0.5},z=7.5,distance=..2,limit=1]`))
        .toBe(2); // Paper's standard painting-facing id for north

      await aimAt(ctx, -20, 11, [2, PAINTING_SCENE.playerY, PAINTING_SCENE.wallZ], 'north');
      await devPainting(ctx, 'sample:moonrise');
      await ctx.waitForChat(/dev painting: placed sample:moonrise/);
      ctx.expect(await ctx.serverLogLine(/MATERIONE-DEV-PAINTING ok=true key=sample:moonrise/)).toBeTruthy();
      ctx.expect(await ctx.rcon(`data get entity @e[type=painting,x=2.5,y=${PAINTING_SCENE.playerY + 0.5},z=7.5,distance=..2,limit=1] variant`))
        .toContain('sample:moonrise');
      ctx.expect(await paintingFacingId(
        ctx, `@e[type=painting,x=2.5,y=${PAINTING_SCENE.playerY + 0.5},z=7.5,distance=..2,limit=1]`))
        .toBe(2);

      // --- a variant that doesn't fit the aim: the sunrise painting already
      // occupies the moonrise aim's hanging cell
      await aimAt(ctx, 0, 12, [0, PAINTING_SCENE.playerY, PAINTING_SCENE.wallZ], 'north');
      await devPainting(ctx, 'sample:panorama');
      await ctx.waitForChat(/doesn't fit on that wall/);
      ctx.expect(await ctx.serverLogLine(/MATERIONE-DEV-PAINTING failed reason=no-space key=sample:panorama/))
        .toBeTruthy();

      // --- an unregistered id answers with the restart honesty, not a crash
      await devPainting(ctx, 'sample:nope');
      await ctx.waitForChat(/is not registered/);
      ctx.expect(await ctx.serverLogLine(/MATERIONE-DEV-PAINTING failed reason=unknown-variant key=sample:nope/))
        .toBeTruthy();

      // --- floor/ceiling aims are refused with the wall hint (a non-
      // horizontal face used to crash the command outright, #101)
      await aimAt(ctx, 0, 28, [0, PAINTING_SCENE.floorY, 5], 'up');
      await devPainting(ctx, 'sample:sunrise');
      await ctx.waitForChat(/look at a wall \(not the floor or ceiling\)/);

      await ctx.rcon('tp Probe 0 -60 3');
      await clearPaintingScene(ctx);
    },
  }),

  test({
    id: 'paintings.placeable-pool',
    title: "the placeable composition shows in vanilla behavior: with a painting item on a bare 3×2 wall slab, item placements always succeed, never select the non-placeable sample:moonrise, and spread across several distinct vanilla variants — the random pool is the composed placeable tag, not the full registry and not ours alone",
    mode: 'auto',
    covers: [
      'modern/src/main/java/dev/materione/modern/**',
      'fixtures/packs/sample/paintings/**',
    ],
    dependsOn: ['paintings.registration'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      await ctx.reset();

      // --- scene: an isolated 3×2 slab (the only support around) with clear
      // air on every face and a separate pad for the player
      await clearPaintingScene(ctx);
      await ctx.rcon(`setblock 0 ${PAINTING_SCENE.floorY} 3 minecraft:smooth_stone`);
      await ctx.rcon(`fill 0 ${PAINTING_SCENE.floorY} 7 2 ${PAINTING_SCENE.floorY + 1} 7 minecraft:smooth_stone`);
      await ctx.rcon(`tp Probe 0 ${PAINTING_SCENE.playerY} 3`);
      await ctx.sleep(600);
      await ctx.rcon('give Probe minecraft:painting 64');
      const hand = await ctx.exec('return mc.player.getMainHandItem().toString()', 15_000);
      ctx.expect(String(hand)).toContain('minecraft:painting');

      // --- the click surface the driver uses, same as a real player's
      // right-click; the slab's north face hangs paintings at z=6
      const CLICK = `def hit = new net.minecraft.world.phys.BlockHitResult(
    new net.minecraft.world.phys.Vec3(1.5, ${PAINTING_SCENE.floorY + 0.5}, 7.0),
    net.minecraft.core.Direction.NORTH,
    new net.minecraft.core.BlockPos(0, ${PAINTING_SCENE.floorY}, 7), false)
mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)
return 'clicked'`;
      const seen = new Set();
      for (let i = 0; i < PLACEABLE_PROBE_TRIALS; i++) {
        await ctx.rcon('kill @e[type=painting]');
        await ctx.exec(CLICK, 15_000);
        await ctx.sleep(400); // the placement round-trips and the entity spawns
        const reply = await ctx.rcon(
          `data get entity @e[type=painting,x=1.5,y=${PAINTING_SCENE.floorY + 0.5},z=6.5,distance=..3,limit=1] variant`);
        if (!/data: "(sample|minecraft):/.test(reply)) {
          ctx.expect(reply).toMatch(/data: "(sample|minecraft):/); // fails with the rcon reply as evidence
        }
        seen.add(String(reply).match(/data: "([^"]+)"/)[1]);
      }
      ctx.expect(seen.has('sample:moonrise')).toBe(false); // non-placeable: never in the pool
      ctx.expect(seen.size).toBeGreaterThan(1); // a real random pool, not one pinned variant

      await ctx.rcon('tp Probe 0 -60 3');
      await clearPaintingScene(ctx);
      await ctx.rcon('clear Probe');
    },
  }),

  test({
    id: 'paintings.rendering',
    title: 'the placed variants render the pack atlas sprites on the client — sunrise as its authored warm gradient, moonrise as the night-sky panel with the pale moon — not checkerboard placeholders or vanilla art',
    mode: 'visual',
    covers: [
      'fixtures/packs/sample/assets/textures/painting/**',
    ],
    dependsOn: ['paintings.registration'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(3_000); // fresh-boot region-thread queue drain before crosshair work

      await stageAimedWall(ctx);

      await aimAt(ctx, 0, 12, [0, PAINTING_SCENE.playerY, PAINTING_SCENE.wallZ], 'north');
      await devPainting(ctx, 'sample:sunrise');
      await ctx.waitForChat(/dev painting: placed sample:sunrise/);
      await aimAt(ctx, -20, 11, [2, PAINTING_SCENE.playerY, PAINTING_SCENE.wallZ], 'north');
      await devPainting(ctx, 'sample:moonrise');
      await ctx.waitForChat(/dev painting: placed sample:moonrise/);
      await ctx.sleep(1_500); // the entity sync and atlas sprites settle

      await ctx.shot('paintings-render');
      await ctx.needsEyes(
        'the 2×1 sunrise art (warm rainbow gradient) hangs left of the 1×2 moonrise (night-blue panel, pale moon, star) on the stone wall — pack textures, not checkerboard or vanilla placeholders');
      await ctx.rcon('tp Probe 0 -60 3');
      await clearPaintingScene(ctx);
    },
  }),
];
