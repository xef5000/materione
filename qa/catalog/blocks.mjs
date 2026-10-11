// Blocks suite: NoteBlock-based custom blocks as seen by a real player on a
// real server. Auto mode — the runner judges pass/fail from assertions.
//
// Server-side truth for a placed custom block: `materione dev inspect` (the
// MATERIONE-DEV-INSPECT marker carries id + technique + slot + rawSlot) plus
// `execute if block <pos> minecraft:note_block[instrument=…,note=…,powered=…]`
// state predicates. `data get block` does NOT work here — note blocks are not
// block entities on 26.1.2 ("The target block is not a block entity").
//
// Aiming is crosshair-driven and the physical desktop can nudge the camera
// between Groovy calls, so every action aims, verifies the hit and acts
// inside ONE atomic exec call, with re-aim retries. Rotation set via
// player.setRot persists between calls; block reach is 4.5 (survival).

import { test } from './_lib.mjs';

// Mirror of BlockStateSpaces' note-block instrument table (core/src/main/java/
// dev/materione/core/blocks/BlockStateSpaces.java): slot = idx*50 + note +
// (powered ? 25 : 0). The runtime's dev inspect reports the raw slot; these
// tables turn it back into the state the pack must render.
const NOTE_INSTRUMENTS = [
  'harp', 'basedrum', 'snare', 'hat', 'bass', 'flute', 'bell', 'chime',
  'xylophone', 'iron_xylophone', 'cow_bell', 'didgeridoo', 'bit', 'banjo',
  'pling', 'zombie', 'skeleton', 'creeper', 'dragon', 'wither_skeleton',
  'piglin', 'custom_head',
];

function noteParts(slot) {
  const rest = slot % 50;
  return {
    instrument: NOTE_INSTRUMENTS[Math.floor(slot / 50)],
    note: rest % 25,
    powered: rest >= 25,
  };
}

const stateKey = (slot) => {
  const p = noteParts(slot);
  return `minecraft:note_block[instrument=${p.instrument},note=${p.note},powered=${p.powered}]`;
};

const parseHit = (s) => {
  const m = /^(\w+) (-?\d+),(-?\d+),(-?\d+) (\w+)$/.exec(String(s));
  if (!m) return null;
  return { type: m[1], x: Number(m[2]), y: Number(m[3]), z: Number(m[4]), dir: m[5] };
};

const onTarget = (hit, cell) => hit && hit.type === 'BLOCK'
  && hit.x === cell[0] && hit.y === cell[1] && hit.z === cell[2];

const aimRead = (ctx, yaw, pitch) => ctx.exec(`player.setRot(${yaw}F, ${pitch}F)
Thread.sleep(500)
def hit = mc.hitResult
return hit.getType().toString() + ' ' + hit.getBlockPos().getX() + ',' + hit.getBlockPos().getY() + ',' + hit.getBlockPos().getZ() + ' ' + hit.getDirection().toString()`, 15_000);

// Aims at `cell` and clicks a use/attack key — all in one atomic exec so the
// crosshair cannot drift between verify and act. Retries against desktop-
// mouse nudges; the final failure names the last observed hit.
const aimedClick = async (ctx, cell, yaw, pitch, key, marker, tries = 3) => {
  let last = '(no attempt)';
  for (let i = 0; i < tries; i++) {
    const hit = parseHit(await aimRead(ctx, yaw, pitch));
    last = hit;
    if (onTarget(hit, cell)) {
      const reply = await ctx.exec(`def hit = mc.hitResult
if (hit.getType().toString() != 'BLOCK' || hit.getBlockPos().getX() != ${cell[0]} || hit.getBlockPos().getY() != ${cell[1]} || hit.getBlockPos().getZ() != ${cell[2]}) return 'AIM-DRIFT'
mc.options.${key}.click(mc.options.${key}.getDefaultKey())
return '${marker}'`, 15_000);
      if (String(reply) === marker) return;
      last = String(reply);
    }
    await ctx.sleep(700);
  }
  ctx.expect(JSON.stringify(last)).toBe(`${marker} on ${JSON.stringify(cell)}`);
};

// Aims at `cell` and HOLDS attack (mining is hold-driven, not click-driven),
// waits `holdMs`, releases, then requires the cell to read as air — retrying
// the whole hold when the crosshair drifted off mid-hold.
const mineAway = async (ctx, cell, holdMs, tries = 3) => {
  let last = '(no attempt)';
  const release = () => ctx.exec('mc.options.keyAttack.setDown(false)\nreturn \'released\'').catch(() => {});
  for (let i = 0; i < tries; i++) {
    try {
      const hit = parseHit(await aimRead(ctx, 0, 20));
      last = hit;
      if (onTarget(hit, cell)) {
        const started = await ctx.exec(`def hit = mc.hitResult
if (hit.getType().toString() != 'BLOCK' || hit.getBlockPos().getX() != ${cell[0]} || hit.getBlockPos().getY() != ${cell[1]} || hit.getBlockPos().getZ() != ${cell[2]}) return 'AIM-DRIFT'
mc.options.keyAttack.setDown(true)
return 'mining'`, 15_000);
        if (String(started) === 'mining') {
          try {
            await ctx.sleep(holdMs);
          } finally {
            await release(); // release before polling: a drifted crosshair must not strip the ground
          }
          await inspectCell(ctx, cell, /found=false none material=air/);
          return; // the inspect marker confirmed the teardown
        }
        last = String(started);
      }
    } catch (e) {
      await release();
      last = e;
    }
    await ctx.sleep(700);
  }
  ctx.expect(JSON.stringify(last)).toBe(`mined ${JSON.stringify(cell)}`);
};

// Polls `materione dev inspect <cell>` until a log line for the cell matches
// `pattern`. The placement packet and the region-thread status replies land a
// beat behind a fresh boot, so a single inspect can read the world before the
// click landed — re-send until the world settles or time runs out.
const inspectCell = async (ctx, cell, pattern, { timeoutMs = 120_000, every = 2500 } = {}) => {
  const deadline = Date.now() + timeoutMs;
  // status line shape: "… MATERIONE-DEV-INSPECT <status> world <x> <y> <z>"
  const cellRe = new RegExp(`MATERIONE-DEV-INSPECT .* world ${cell[0]} ${cell[1]} ${cell[2]}`);
  let line = null;
  for (;;) {
    await ctx.rcon(`materione dev inspect ${cell[0]} ${cell[1]} ${cell[2]} world`);
    line = ctx.serverLogText(cellRe);
    const readDeadline = Math.min(Date.now() + every, deadline);
    while (!line && Date.now() < readDeadline) {
      await ctx.sleep(500);
      line = ctx.serverLogText(cellRe);
    }
    if (line && (!pattern || pattern.test(line))) return line;
    if (Date.now() > deadline) {
      ctx.expect(line ?? '(no inspect line)').toMatch(pattern ?? cellRe);
    }
  }
};

// One remove takes the custom block, a second takes a furniture cluster
// anchored in the same cell — both fail soft ("not-custom" / "none").
const cleanCell = async (ctx, cell) => {
  await ctx.rcon(`materione dev remove ${cell.join(' ')} world`);
  await ctx.rcon(`materione dev remove ${cell.join(' ')} world`);
};

export default [
  test({
    id: 'blocks.noteblock-placement',
    title: 'sample:amethyst_lamp places from a real client through its authored sample:ruby placement item as exactly its allocated note-block state, right-click (empty hand) fires its ECA interact rule instead of the host use, and mining it leaves air with no dropped item entities',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/blocks/**',
      'core/src/main/java/dev/materione/core/blocks/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/blocks/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/behavior/**',
      'behavior/src/main/java/dev/materione/behavior/**',
      'fixtures/packs/sample/blocks/**',
      'fixtures/packs/sample/items/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 300_000, // fresh-boot region-thread queues can hold packets/replies for ~1min under load
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.sleep(15_000); // let the fresh boot's region-thread queue drain before crosshair work

      // --- scene A: place the block from the client -------------------------
      // The lamp links sample:ruby as its placement item (PAPER material, not
      // a block): the use click rides the PlayerInteractEvent fallback, which
      // encodes the allocated state slot and records the chunk-PDC identity.
      // Known fixture collision, deliberately not asserted here: ruby is ALSO
      // the chair furniture's placement item, so one ruby click co-places a
      // chair cluster in the lamp cell and consumes the whole stack (block vs
      // furniture double-claim — tracked as its own issue; a fixed runtime
      // that consumes exactly one ruby changes nothing this test asserts).
      const hit = parseHit(await aimRead(ctx, 0, 50));
      if (!hit || hit.type !== 'BLOCK' || hit.dir !== 'up') {
        ctx.expect(JSON.stringify(hit)).toBe('ground hit on its top face');
      }
      const cell = [hit.x, hit.y + 1, hit.z]; // top face → the cell above
      const above = [cell[0], cell[1] + 1, cell[2]];
      await cleanCell(ctx, cell);
      await cleanCell(ctx, above); // a stray stacked placement from a retry
      ctx.expect(await ctx.rcon(`execute if block ${cell.join(' ')} minecraft:air`))
        .toBe('Test passed');

      await ctx.rcon('materione give sample:ruby Probe');
      await ctx.waitForChat(/Given sample:ruby/);
      await ctx.exec('player.getInventory().selected = 0');
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getHoverName().getString()'))).toBe('Ruby');

      // aim + verify + click atomically (pitch 50: the ground ~1.3 blocks south)
      await aimedClick(ctx, [hit.x, hit.y, hit.z], 0, 50, 'keyUse', 'use-clicked');

      // identity: the state decodes through the allocation table AND agrees
      // with the chunk-PDC record (identify confirms both; a mismatch is
      // MTR-0024 and inspect would report found=false)
      const line = await inspectCell(ctx, cell,
        /found=true sample:amethyst_lamp technique=note_block/);
      const slot = Number(/slot=(\d+)/.exec(line)[1]);
      const rawSlot = Number(/rawSlot=(\d+)/.exec(line)[1]);
      ctx.expect(rawSlot).toBe(slot);

      // state: the placed host state is exactly the allocated slot's
      // instrument/note/powered mapping — the vanilla default state fails
      ctx.expect(await ctx.rcon(`execute if block ${cell.join(' ')} ${stateKey(slot)}`))
        .toBe('Test passed');
      const p = noteParts(slot);
      ctx.expect(await ctx.rcon(
        `execute if block ${cell.join(' ')} minecraft:note_block[instrument=${p.instrument},note=${p.note},powered=${!p.powered}]`,
      )).toBe('Test failed');

      // consumption: the single ruby is gone from the server-side inventory
      ctx.expect(await ctx.rcon('data get entity Probe Inventory')).toContain('[]');

      // teardown of the client-placed lamp (operator path, no drops)
      await ctx.rcon(`materione dev remove ${cell.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-REMOVE ok=true removed sample:amethyst_lamp world ${cell[0]} ${cell[1]} ${cell[2]}`),
        { timeoutMs: 45_000 });
      await cleanCell(ctx, cell);
      await cleanCell(ctx, above);

      // --- scene B: interact + break on a dev-placed lamp -------------------
      // Same runtime encode path as client placement, minus the placement
      // item (whose chair collision would put an interaction hitbox in front
      // of the crosshair). Three-and-a-half blocks south of the anchor;
      // pitch 20 puts the crosshair on its north face.
      const lamp = [0, -60, 7];
      await cleanCell(ctx, lamp); // idempotent reruns
      await ctx.rcon(`materione dev place sample:amethyst_lamp ${lamp.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-PLACE ok=true placed sample:amethyst_lamp world ${lamp.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${lamp.join(' ')} ${stateKey(slot)}`))
        .toBe('Test passed');

      // interact: right-click with an empty main hand — the runtime denies
      // the host's vanilla use (no note tuning/play) and fires the ECA
      // interact rule once. Chat history is newest-first: count, don't index.
      const hums = async () =>
        (await ctx.chat(80)).filter((l) => l.includes('The lamp hums softly.')).length;
      const baseline = await hums();
      await aimedClick(ctx, lamp, 0, 20, 'keyUse', 'interact-clicked');
      const deadline = Date.now() + 8000;
      for (;;) {
        if ((await hums()) > baseline) break;
        if (Date.now() > deadline) ctx.expect(await hums()).toBeGreaterThan(baseline);
        await ctx.sleep(300);
      }

      // break: survival mining is hold-driven — hold attack ~2.6s (a note
      // block is ~0.75s bare hand), release. The runtime owns teardown:
      // vanilla's break (and its note_block drop) is cancelled, the chunk
      // record and index are cleared, and the drops list is empty.
      await mineAway(ctx, lamp, 2600);

      await inspectCell(ctx, lamp, /found=false none material=air rawSlot=-/);
      ctx.expect(await ctx.rcon(
        `execute positioned ${lamp[0] + 0.5} ${lamp[1]} ${lamp[2] + 0.5} if entity @e[type=item,distance=..6]`,
      )).toBe('Test failed');
      // the placement index is back to empty: both scenes fully torn down
      await ctx.rcon('materione dev sweep');
      await ctx.waitForServerLog(/MATERIONE-DEV-SWEEP indexed=0 ok=0 /, { timeoutMs: 45_000 });
    },
  }),
];
