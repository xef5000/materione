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

// Mirror of BlockStateSpaces' chorus identity bits (slot = the connection-bit
// pattern: up=1 down=2 north=4 east=8 south=16 west=32, slots 0..63). The
// alphabetical bracket order matches the client's BlockState#toString so the
// same string works as an `execute if block` predicate AND as the expected
// client-side render state.
function chorusStateKey(slot) {
  const bit = (mask) => (slot & mask) !== 0;
  return 'minecraft:chorus_plant'
    + `[down=${bit(2)},east=${bit(8)},north=${bit(4)},south=${bit(16)},up=${bit(1)},west=${bit(32)}]`;
}

// The client's locally rendered blockstate at a cell — the render-fidelity
// proxy for auto mode: the pack maps exactly the allocated slot's variant, so
// a client state that still equals the slot key renders the custom model,
// while a flipped state (vanilla connection recalc on the client) would hit
// an unmapped variant and break the look with no server-side trace.
// BlockState#toString is `Block{minecraft:chorus_plant}[down=…]` — the host
// and the bracket content are asserted separately.
const expectClientState = async (ctx, cell, slot) => {
  const state = String(await ctx.exec(
    `return mc.level.getBlockState(net.minecraft.core.BlockPos.containing(${cell[0]}d, ${cell[1]}d, ${cell[2]}d)).toString()`,
    15_000));
  ctx.expect(state).toContain('{minecraft:chorus_plant}');
  const bit = (mask) => (slot & mask) !== 0;
  ctx.expect(state).toContain(
    `[down=${bit(2)},east=${bit(8)},north=${bit(4)},south=${bit(16)},up=${bit(1)},west=${bit(32)}]`);
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
const mineAway = async (ctx, cell, holdMs, yaw = 0, pitch = 20, tries = 3) => {
  let last = '(no attempt)';
  const release = () => ctx.exec('mc.options.keyAttack.setDown(false)\nreturn \'released\'').catch(() => {});
  for (let i = 0; i < tries; i++) {
    try {
      const hit = parseHit(await aimRead(ctx, yaw, pitch));
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

  test({
    id: 'blocks.chorus-transparent',
    title: 'world:chorus_pillar (synthetic auto-placement item on the chorus-plant host) is giveable, places from a real client as exactly its allocated connection-bit state on end stone, both pillars of an adjacent pair keep their identity and state (no vanilla connection drift server- or client-side), a vanished support does not pop the custom block, and mining plus dev remove tear both down cleanly',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/blocks/**',
      'core/src/main/java/dev/materione/core/blocks/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/blocks/**',
      'fixtures/packs/world/blocks/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 360_000, // fresh-boot region-thread queues can hold packets/replies for ~1min under load
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.sleep(15_000); // let the fresh boot's region-thread queue drain before crosshair work

      // --- stage: two end-stone pads in front of the anchor ------------------
      // The chorus host survives only on end stone / chorus neighbors in
      // vanilla's client-side placement check, so the pads give the real
      // client a legal placement surface. Cells: pad A [0,-61,6], pad B
      // [1,-61,6]; the pillars land at [0,-60,6] and [1,-60,6] (face-adjacent
      // east-west). The stone cell [0,-60,5] is north of pillar 1.
      const padA = [0, -61, 6];
      const padB = [1, -61, 6];
      const pillar1 = [0, -60, 6];
      const pillar2 = [1, -60, 6];
      const stoneCell = [0, -60, 5];
      for (const cell of [pillar1, pillar2, [0, -59, 6], [1, -59, 6]]) {
        await cleanCell(ctx, cell); // idempotent reruns
      }
      for (const cell of [padA, padB, stoneCell]) {
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:air`);
      }
      for (const cell of [padA, padB]) {
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:end_stone`);
      }
      ctx.expect(await ctx.rcon(`execute if block ${padA.join(' ')} minecraft:end_stone`))
        .toBe('Test passed');

      // give resolves the synthetic auto-placement item (the block has no
      // item: link — the registry synthesized one at the block's own id)
      await ctx.rcon('materione give world:chorus_pillar Probe');
      await ctx.waitForChat(/Given world:chorus_pillar/);
      await ctx.rcon('materione dev stack world:chorus_pillar');
      await ctx.waitForServerLog(/MATERIONE-STACK id=world:chorus_pillar item_model=world:chorus_pillar/,
        { timeoutMs: 20_000 });
      await ctx.exec('player.getInventory().selected = 0');
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getHoverName().getString()'))).toBe('Chorus Pillar');

      // --- scene A: place pillar 1 on pad A from the client ------------------
      // verify the pad's top face first, then click atomically (pitch 28 puts
      // the crosshair on that top face ~3 blocks south-southeast).
      const hit = parseHit(await aimRead(ctx, 0, 28));
      if (!hit || hit.type !== 'BLOCK' || hit.dir !== 'up' || !onTarget(hit, padA)) {
        ctx.expect(JSON.stringify(hit)).toBe(`top face of pad ${JSON.stringify(padA)}`);
      }
      await aimedClick(ctx, padA, 0, 28, 'keyUse', 'use-clicked');

      // identity: state decodes through the allocation table and agrees with
      // the chunk-PDC record
      const line = await inspectCell(ctx, pillar1,
        /found=true world:chorus_pillar technique=chorus/);
      const slot = Number(/slot=(\d+)/.exec(line)[1]);
      const rawSlot = Number(/rawSlot=(\d+)/.exec(line)[1]);
      ctx.expect(rawSlot).toBe(slot);
      ctx.expect(slot).toBeGreaterThan(-1);
      ctx.expect(slot).toBeLessThan(64);

      // state: exactly the allocated slot's connection bits, server and client
      const key = chorusStateKey(slot);
      ctx.expect(await ctx.rcon(`execute if block ${pillar1.join(' ')} ${key}`))
        .toBe('Test passed');
      await expectClientState(ctx, pillar1, slot);

      // --- scene B: pillar 2 east-adjacent ------------------------------------
      // A second chorus placement is vanilla's trigger for connection
      // recalculation — the new state must still be the allocated slot (the
      // connection bits are the identity; vanilla's toward-neighbor arms
      // would decode to an unallocated slot). Give again: scene A consumed
      // the stack in survival.
      await ctx.rcon('materione give world:chorus_pillar Probe');
      await ctx.waitForChat(/Given world:chorus_pillar/);
      await ctx.exec('player.getInventory().selected = 0');
      const hit2 = parseHit(await aimRead(ctx, -18, 27));
      if (!hit2 || hit2.type !== 'BLOCK' || hit2.dir !== 'up' || !onTarget(hit2, padB)) {
        ctx.expect(JSON.stringify(hit2)).toBe(`top face of pad ${JSON.stringify(padB)}`);
      }
      await aimedClick(ctx, padB, -18, 27, 'keyUse', 'use-clicked');

      const line2 = await inspectCell(ctx, pillar2,
        /found=true world:chorus_pillar technique=chorus/);
      ctx.expect(Number(/slot=(\d+)/.exec(line2)[1])).toBe(slot);
      ctx.expect(await ctx.rcon(`execute if block ${pillar1.join(' ')} ${key}`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${pillar2.join(' ')} ${key}`))
        .toBe('Test passed');
      await expectClientState(ctx, pillar1, slot);
      await expectClientState(ctx, pillar2, slot);

      // --- scene C: a vanilla neighbor change leaves both states alone -------
      await ctx.rcon(`setblock ${stoneCell.join(' ')} minecraft:stone`);
      await ctx.sleep(800);
      ctx.expect(await ctx.rcon(`execute if block ${pillar1.join(' ')} ${key}`))
        .toBe('Test passed');
      await expectClientState(ctx, pillar1, slot);

      // --- scene D: vanished support does not pop the custom block -----------
      // State-as-identity: physics recalculation of a confirmed placement is
      // cancelled (fallback listener; the paper flag is off in this probe),
      // so the chorus pillar floats where its support was — a pop-off would
      // strand the chunk-PDC record as a quarantine (MTR-0024).
      await ctx.rcon(`setblock ${padA.join(' ')} minecraft:air`);
      await ctx.sleep(1500); // a scheduled-tick pop would fire within a tick
      ctx.expect(await ctx.rcon(`execute if block ${pillar1.join(' ')} ${key}`))
        .toBe('Test passed');
      await expectClientState(ctx, pillar1, slot);
      await inspectCell(ctx, pillar1, /found=true world:chorus_pillar technique=chorus/);

      // --- teardown: mining removes pillar 2 through the runtime pipeline ----
      // chorus plant is bare-hand fast; hold ~1.6s is plenty (pitch 23 /
      // yaw -22 puts the crosshair on pillar 2's north face)
      await mineAway(ctx, pillar2, 1600, -22, 23);
      await inspectCell(ctx, pillar2, /found=false none material=air rawSlot=-/);
      ctx.expect(await ctx.rcon(
        `execute positioned ${pillar2[0] + 0.5} ${pillar2[1]} ${pillar2[2] + 0.5} if entity @e[type=item,distance=..6]`,
      )).toBe('Test failed');

      // operator removal for pillar 1, then the staging blocks
      await ctx.rcon(`materione dev remove ${pillar1.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-REMOVE ok=true removed world:chorus_pillar world ${pillar1.join(' ')}`),
        { timeoutMs: 45_000 });
      for (const cell of [stoneCell, padB]) {
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:air`);
      }

      // the placement index is back to empty: everything torn down
      await ctx.rcon('materione dev sweep');
      await ctx.waitForServerLog(/MATERIONE-DEV-SWEEP indexed=0 ok=0 /, { timeoutMs: 45_000 });
    },
  }),

  test({
    id: 'blocks.technical-untex',
    title: 'world:ghost_panel (technical, bone_block host) places from a real client through its synthetic auto-placement item onto the host default state — no slot claimed, identity rides chunk PDC alone; a vanilla bone block placed beside it stays unidentified vanilla; a neighbor change moves nothing; mining and dev place/remove tear it down cleanly',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/blocks/**',
      'core/src/main/java/dev/materione/core/blocks/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/blocks/**',
      'fixtures/packs/world/blocks/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 300_000, // fresh-boot region-thread queues can hold packets/replies for ~1min under load
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      await ctx.sleep(15_000); // let the fresh boot's region-thread queue drain before crosshair work

      // --- stage: two smooth-stone pads in front of the anchor ---------------
      // Same proven geometry as the chorus test: pads at y=-61, placements at
      // y=-60 (face-adjacent east-west pair), stone cell north of the panel.
      const padA = [0, -61, 6];
      const padB = [1, -61, 6];
      const panel = [0, -60, 6];
      const vanilla = [1, -60, 6];
      const stoneCell = [0, -60, 5];
      for (const cell of [panel, vanilla, [0, -59, 6], [1, -59, 6], stoneCell]) {
        await cleanCell(ctx, cell); // idempotent reruns: custom AND vanilla leftovers
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:air`);
      }
      for (const cell of [padA, padB, stoneCell]) {
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:air`);
      }
      for (const cell of [padA, padB]) {
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:smooth_stone`);
      }
      ctx.expect(await ctx.rcon(`execute if block ${padA.join(' ')} minecraft:smooth_stone`))
        .toBe('Test passed');

      // give resolves the synthetic auto-placement item: the definition id,
      // the model, and the HOST's item form (bone block is placeable, so the
      // placement rides BlockPlaceEvent — runtime-systems §13)
      await ctx.rcon('materione give world:ghost_panel Probe');
      await ctx.waitForChat(/Given world:ghost_panel/);
      await ctx.rcon('materione dev stack world:ghost_panel');
      await ctx.waitForServerLog(/MATERIONE-STACK id=world:ghost_panel item_model=world:ghost_panel/,
        { timeoutMs: 20_000 });
      await ctx.exec('player.getInventory().selected = 0');
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getHoverName().getString()'))).toBe('Ghost Panel');
      ctx.expect(await ctx.rcon('data get entity Probe SelectedItem.id'))
        .toContain('minecraft:bone_block');

      // --- scene A: place the panel on pad A from the client ------------------
      // The runtime spends no state bits: the placed host is the DEFAULT
      // block data (axis=y) and the identity lives only in the chunk PDC —
      // inspect reports the definition slot -1 and rawSlot - (undecodable).
      const hit = parseHit(await aimRead(ctx, 0, 28));
      if (!hit || hit.type !== 'BLOCK' || hit.dir !== 'up' || !onTarget(hit, padA)) {
        ctx.expect(JSON.stringify(hit)).toBe(`top face of pad ${JSON.stringify(padA)}`);
      }
      await aimedClick(ctx, padA, 0, 28, 'keyUse', 'use-clicked');

      const line = await inspectCell(ctx, panel, /found=true world:ghost_panel technique=technical/);
      ctx.expect(line).toContain('slot=-1');
      ctx.expect(line).toContain('material=bone_block');
      ctx.expect(line).toContain('rawSlot=-');
      const key = 'minecraft:bone_block[axis=y]';
      ctx.expect(await ctx.rcon(`execute if block ${panel.join(' ')} ${key}`))
        .toBe('Test passed');
      // client side: the host's vanilla look is untouched (no state hijack to
      // render — the pack claims no bone_block blockstate)
      const clientState = String(await ctx.exec(
        `return mc.level.getBlockState(net.minecraft.core.BlockPos.containing(${panel[0]}d, ${panel[1]}d, ${panel[2]}d)).toString()`,
        15_000));
      ctx.expect(clientState).toContain('{minecraft:bone_block}');
      ctx.expect(clientState).toContain('[axis=y]');

      // --- scene B: a VANILLA bone block beside it stays vanilla --------------
      // The technical host TYPE is a PDC-only identify candidate, so the
      // absence of a chunk-PDC record must keep identify empty — a global
      // "bone blocks are ours" overclaim would make this cell found=true.
      await ctx.rcon('clear Probe');
      await ctx.rcon('give Probe minecraft:bone_block 1');
      await ctx.exec('player.getInventory().selected = 0');
      ctx.expect(await ctx.rcon('data get entity Probe SelectedItem.id'))
        .toContain('minecraft:bone_block');
      const hit2 = parseHit(await aimRead(ctx, -18, 27));
      if (!hit2 || hit2.type !== 'BLOCK' || hit2.dir !== 'up' || !onTarget(hit2, padB)) {
        ctx.expect(JSON.stringify(hit2)).toBe(`top face of pad ${JSON.stringify(padB)}`);
      }
      await aimedClick(ctx, padB, -18, 27, 'keyUse', 'use-clicked');
      await inspectCell(ctx, vanilla, /found=false none material=bone_block rawSlot=-/);
      ctx.expect(await ctx.rcon(`execute if block ${vanilla.join(' ')} ${key}`))
        .toBe('Test passed');

      // --- scene C: a neighbor change moves nothing ----------------------------
      // No state identity to drift — the vanilla neighbor update reshapes
      // nothing (bone block is a plain full block) and the PDC record holds.
      await ctx.rcon(`setblock ${stoneCell.join(' ')} minecraft:stone`);
      await ctx.sleep(800);
      ctx.expect(await ctx.rcon(`execute if block ${panel.join(' ')} ${key}`))
        .toBe('Test passed');
      await inspectCell(ctx, panel, /found=true world:ghost_panel technique=technical/);

      // --- scene D: mining tears it down through the runtime pipeline ---------
      // bone block is tool-gated (~10s bare hand, ~0.4s with a diamond
      // pickaxe) and the runtime's break pipeline only fires when the vanilla
      // break completes — so mine with a pickaxe. Pitch 12 is shallow enough
      // to clear the stone cell at [0,-60,5] (scene C) and land on the
      // panel's top face — pitch 20 would strike that stone first. The
      // definition lists no drops, so no item entities may appear.
      await ctx.rcon('clear Probe');
      await ctx.rcon('give Probe minecraft:diamond_pickaxe 1');
      await ctx.exec('player.getInventory().selected = 0');
      await mineAway(ctx, panel, 2600, 0, 12);
      await inspectCell(ctx, panel, /found=false none material=air rawSlot=-/);
      ctx.expect(await ctx.rcon(
        `execute positioned ${panel[0] + 0.5} ${panel[1]} ${panel[2] + 0.5} if entity @e[type=item,distance=..6]`,
      )).toBe('Test failed');

      // --- scene E: the operator path places and removes the same state -------
      await ctx.rcon(`materione dev place world:ghost_panel ${panel.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-PLACE ok=true placed world:ghost_panel world ${panel.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${panel.join(' ')} ${key}`))
        .toBe('Test passed');
      await ctx.rcon(`materione dev remove ${panel.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-REMOVE ok=true removed world:ghost_panel world ${panel.join(' ')}`),
        { timeoutMs: 45_000 });

      // --- teardown: the vanilla block (fail-soft remove), staging, sweep ----
      await cleanCell(ctx, vanilla);
      // A vanilla control block has no custom record for `dev remove` to
      // clear. Remove it explicitly so later in-game scenes start unobstructed.
      await ctx.rcon(`setblock ${vanilla.join(' ')} minecraft:air`);
      ctx.expect(await ctx.rcon(`execute if block ${vanilla.join(' ')} minecraft:air`))
        .toBe('Test passed');
      for (const cell of [stoneCell, padA, padB]) {
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:air`);
      }
      await ctx.rcon('materione dev sweep');
      await ctx.waitForServerLog(/MATERIONE-DEV-SWEEP indexed=0 ok=0 /, { timeoutMs: 45_000 });
    },
  }),
];
