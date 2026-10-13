// Furniture suite: display-entity clusters as seen by a real player on a
// real server. Auto mode — the runner judges pass/fail from assertions.
//
// Tests here are dev-driven (`materione dev place/remove/inspect` falls
// through from the block runtime to the furniture runtime when the id is
// not a block); the placement-item seam is covered client-side by the
// blocks suite. The furniture markers carry the MATERIONE-DEV-FURNITURE-*
// prefix and land in the server log — region-thread replies never ride the
// RCON response.

import { test } from './_lib.mjs';

// The client's block-light layer at a cell — the emission proxy: a placed
// `minecraft:light[level=n]` cell only lights the scene if the client's
// light engine received the level, so 14 at the cell (13 one step away) is
// "this furniture actually emits", not just "a light block was set".
// LightLayer lives in world.level on 26.1.2 (not world.level.lighting — a
// wrong package surfaces as Groovy "No such property: net").
const blockLightAt = (ctx, cell) => ctx.exec(
  `return mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, `
  + `new net.minecraft.core.BlockPos(${cell[0]}, ${cell[1]}, ${cell[2]}))`, 15_000);

export default [
  test({
    id: 'furniture.light-emission',
    title: "world:workbench (lights: level 14 above the anchor) spawns exactly a minecraft:light[level=14] cell, the client's block-light layer reads 14 at the cell and 13 one step east (baseline 0 before, 0 again after dev remove turns the cell back to air), and dev inspect pins the cluster on the anchor cell",
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/furniture/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/furniture/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java',
      'fixtures/packs/world/furnitures/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 240_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot region-thread queue drain; no crosshair work here

      // --- scene: anchor [0,-60,7]; the definition's light offset [0,1,0] is
      // yaw-invariant, so the cell lands at [0,-59,7]. dev place uses yaw 0.
      const anchor = [0, -60, 7];
      const lightCell = [0, -59, 7];
      const east = [1, -59, 7];
      // idempotent reruns: both dev removes are fail-soft (not-custom →
      // not-furniture) and the light cell's cluster pointer would resolve
      // the cluster too, so clean both cells
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.rcon(`materione dev remove ${lightCell.join(' ')} world`);
      await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      await ctx.rcon(`setblock ${lightCell.join(' ')} minecraft:air`);

      // baseline: fresh superflat has no block-light sources around the scene
      ctx.expect(await blockLightAt(ctx, lightCell)).toBe(0);
      ctx.expect(await blockLightAt(ctx, east)).toBe(0);

      // --- place: the dev driver spawns the full cluster (root display,
      // barrier + interaction hitboxes, seat, label, storage, light cell)
      await ctx.rcon(`materione dev place world:workbench ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:workbench world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });

      // the light cell is exactly the definition's level — a wrong-but-close
      // level (13/15) must fail the predicate
      ctx.expect(await ctx.rcon(`execute if block ${lightCell.join(' ')} minecraft:light[level=14]`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${lightCell.join(' ')} minecraft:light[level=15]`))
        .toBe('Test failed');

      // emission, as the client's light engine received it
      await ctx.sleep(1500); // light packets → client engine
      ctx.expect(await blockLightAt(ctx, lightCell)).toBe(14);
      ctx.expect(await blockLightAt(ctx, east)).toBe(13);

      // the cluster owns the anchor: display-entity furniture leaves the
      // anchor block air and rides a chunk-PDC pointer, so the blocks
      // inspect answers "none" and the furniture inspect pins the id
      await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench /,
        { timeoutMs: 20_000 });

      // --- teardown: cluster removal clears the light cell and the dark
      // baseline returns (dev remove drops the placement item; killed below)
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${lightCell.join(' ')} minecraft:air`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${lightCell.join(' ')} minecraft:light`))
        .toBe('Test failed');
      await ctx.sleep(1500);
      ctx.expect(await blockLightAt(ctx, lightCell)).toBe(0);
      ctx.expect(await blockLightAt(ctx, east)).toBe(0);
      await ctx.rcon('kill @e[type=item]'); // the placement-item drop from remove
    },
  }),

  test({
    id: 'furniture.storage',
    title: 'world:workbench (storage: 9) opens a 9-slot "Workbench" menu on a client right-click (entity or barrier-cell hit both route to the cluster), a shift-clicked diamond leaves the inventory and persists into the root entity PDC (materione:storage byte array), survives close/reopen from that record, and dev remove spills it back as a real dropped diamond',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/furniture/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/furniture/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java',
      'fixtures/packs/world/furnitures/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 300_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(10_000); // fresh-boot region-thread queue drain before input work

      // --- scene: anchor [0,-60,7] (same cell as the light test; both tests
      // clean idempotently). The player stands at [0,-60,5]: entity reach is
      // 3.0 and the interaction box centers ~2.2 away from the eye there.
      const anchor = [0, -60, 7];
      const lightCell = [0, -59, 7];
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.rcon(`materione dev remove ${lightCell.join(' ')} world`);
      await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      await ctx.rcon(`setblock ${lightCell.join(' ')} minecraft:air`);
      await ctx.rcon(`materione dev place world:workbench ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:workbench world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon(`tp Probe 0 -60 5`);
      await ctx.rcon('give Probe minecraft:diamond 1'); // hotbar slot 0

      // Menu slot map for a 1-row menu (9 + 36): container 0..8, main
      // inventory 9..35, hotbar 36..44 — the diamond sits in menu slot 36.
      const menuOpen = async () => String(await ctx.exec(
        'return player.containerMenu.containerId == 0 ? "closed" : "open"'));
      const waitForMenu = async (want, timeoutMs = 8000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          if ((await menuOpen()) === want) return;
          if (Date.now() > deadline) ctx.expect(await menuOpen()).toBe(want);
          await ctx.sleep(300);
        }
      };
      const slotName = (index) => ctx.exec(`def s = player.containerMenu.getSlot(${index}).getItem()
return s.isEmpty() ? 'empty' : s.getHoverName().getString()`);
      const waitForSlot = async (index, want, timeoutMs = 8000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const name = String(await slotName(index));
          if (name === want) return;
          if (Date.now() > deadline) ctx.expect(name).toBe(want);
          await ctx.sleep(300);
        }
      };
      const quickMove = (slot) => ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player)
return 'clicked'`);
      const screenTitle = () => ctx.exec(
        "return mc.screen == null ? 'none' : mc.screen.getTitle().getString()");

      // A right-click routes to the cluster through either hit path: the
      // Interaction entity (onEntityInteract) or the barrier cell the model
      // occludes (onCellInteract — the barrier is a real block). Both end in
      // openStorage for this definition. Pitch 31 puts the crosshair on the
      // interaction box ~2.2 away; the readback decides which path fired.
      const aim = (yaw, pitch) => ctx.exec(`player.setRot(${yaw}F, ${pitch}F)
Thread.sleep(500)
def hit = mc.hitResult
def t = hit.getType().toString()
if (t == 'ENTITY') return 'ENTITY ' + hit.getEntity().getType().toString()
if (t == 'BLOCK') return 'BLOCK ' + hit.getBlockPos().getX() + ',' + hit.getBlockPos().getY() + ',' + hit.getBlockPos().getZ()
return t`, 15_000);
      const clickFurniture = async (cell, tries = 3) => {
        let last = '(no attempt)';
        for (let i = 0; i < tries; i++) {
          last = String(await aim(0, 31));
          // EntityType#toString is the namespaced id form on 26.1.2
          // ("entity.minecraft.interaction"), not the enum name
          const onEntity = last.startsWith('ENTITY') && last.includes('interaction');
          const onCell = last === `BLOCK ${cell.join(',')}`;
          if (onEntity || onCell) {
            const reply = await ctx.exec(`def hit = mc.hitResult
def t = hit.getType().toString()
if (t == 'ENTITY' || (t == 'BLOCK' && hit.getBlockPos().getX() == ${cell[0]} && hit.getBlockPos().getY() == ${cell[1]} && hit.getBlockPos().getZ() == ${cell[2]})) {
  mc.options.keyUse.click(mc.options.keyUse.getDefaultKey())
  return 'use-clicked'
}
return 'AIM-DRIFT'`, 15_000);
            if (String(reply) === 'use-clicked') return onEntity ? 'entity' : 'cell';
            last = String(reply);
          }
          await ctx.sleep(700);
        }
        ctx.expect(last).toBe(`use-clicked on ${JSON.stringify(cell)}`);
      };

      // --- open: the storage wins the shared click flow (ECA → storage → seat)
      const via = await clickFurniture(anchor);
      await waitForMenu('open');
      ctx.log(`storage opened via ${via} hit`);
      ctx.expect(String(await screenTitle())).toBe('Workbench');
      ctx.expect(Number(await ctx.exec(
        'return player.containerMenu.slots.size()'))).toBe(45);

      // --- deposit: shift-click the diamond in, watch hotbar and container agree
      ctx.expect(String(await slotName(36))).toBe('Diamond');
      await quickMove(36);
      await waitForSlot(0, 'Diamond');
      await waitForSlot(36, 'empty');
      await ctx.exec('player.closeContainer()');
      await waitForMenu('closed');
      await ctx.sleep(700); // MONITOR close listener writes the grid back to the root PDC

      // --- server truth: the root's entity PDC carries a serialized grid
      await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
      const inspect = await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench .+ root=[0-9a-f-]{36} /,
        { timeoutMs: 20_000 });
      const root = /root=([0-9a-f-]{36}) /.exec(inspect)[1];
      // Paper stores entity PDC under BukkitValues in entity NBT (item
      // components use custom_data.PublicBukkitValues — a different path)
      ctx.expect(await ctx.rcon(
        `data get entity ${root} BukkitValues."materione:storage"`)).toContain('[B;');

      // --- persistence: reopening rebuilds the menu FROM the PDC record
      await clickFurniture(anchor);
      await waitForMenu('open');
      await waitForSlot(0, 'Diamond');
      await ctx.exec('player.closeContainer()');
      await waitForMenu('closed');
      await ctx.sleep(700);

      // --- teardown spills the stored grid: dev remove drops the contents
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(
        `execute positioned ${anchor[0] + 0.5} ${anchor[1] + 0.5} ${anchor[2] + 0.5}`
        + ` as @e[type=item,distance=..4] run data get entity @s Item.id`))
        .toContain('minecraft:diamond');
      await ctx.rcon('kill @e[type=item]'); // the spill + the placement-item drop
      ctx.expect(await ctx.rcon(
        `execute positioned ${anchor[0] + 0.5} ${anchor[1] + 0.5} ${anchor[2] + 0.5}`
        + ` if entity @e[type=item,distance=..4]`)).toBe('Test failed');
    },
  }),

  test({
    id: 'furniture.limited-placing',
    title: 'furniture placement is limited to floor clicks: a workbench placement item right-clicked on a wall face places and consumes nothing, a floor click places the cluster (barrier + light cell) and consumes exactly one item, and the space pre-check denies an occupied anchor at the operator seam (dev place answers no-space, cluster root unchanged) — the deny is unreachable from real-client clicks because the cluster light cell caps the aim column',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/furniture/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/furniture/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java',
      'fixtures/packs/world/furnitures/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 300_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(10_000); // fresh-boot region-thread queue drain before input work

      const parseHit = (s) => {
        const m = /^(\w+) (-?\d+),(-?\d+),(-?\d+) (\w+)$/.exec(String(s));
        if (!m) return null;
        return { type: m[1], x: Number(m[2]), y: Number(m[3]), z: Number(m[4]), dir: m[5] };
      };
      const onTarget = (hit, cell) => hit && hit.type === 'BLOCK'
        && hit.x === cell[0] && hit.y === cell[1] && hit.z === cell[2];

      // --- scene: the floor cell [1,-61,7] (solid ground, top face at y=-60)
      // carries the allowed placement; the stone bump at [0,-60,5] offers the
      // denied wall face (its north side). The player stands one block east
      // (1,-60,3): the x=1.5 sight line to the floor clears the bump, while
      // the bump's north face stays clickable with a slight west yaw.
      const ground = [1, -61, 7];
      const anchor = [1, -60, 7];
      const light = [1, -59, 7];
      const bump = [0, -60, 5];
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.rcon(`materione dev remove ${light.join(' ')} world`);
      for (const cell of [anchor, light, bump]) {
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:air`);
      }
      await ctx.rcon(`setblock ${bump.join(' ')} minecraft:stone`);
      ctx.expect(await ctx.rcon(`execute if block ${ground.join(' ')} minecraft:air`))
        .toBe('Test failed'); // the ground is solid

      // the synthetic furniture placement item: paper material, the §13 seam
      await ctx.rcon('materione give world:workbench Probe');
      await ctx.waitForChat(/Given world:workbench/);
      await ctx.rcon('tp Probe 1 -60 3');
      await ctx.exec('player.getInventory().selected = 0');

      const stackCount = async () => Number(await ctx.exec(`def n = 0
for (int i = 0; i < 36; i++) {
  if (!player.getInventory().items.get(i).isEmpty()) n = n + 1
}
return n`));
      const aim = (yaw, pitch) => ctx.exec(`player.setRot(${yaw}F, ${pitch}F)
Thread.sleep(500)
def hit = mc.hitResult
def t = hit.getType().toString()
if (t == 'BLOCK') return 'BLOCK ' + hit.getBlockPos().getX() + ',' + hit.getBlockPos().getY() + ',' + hit.getBlockPos().getZ() + ' ' + hit.getDirection().toString()
return t`, 15_000);
      // Aim at `cell`'s face `dir` and send the use — all atomic with re-aim
      // retries. The use goes through gameMode.useItemOn (a real
      // ServerboundUseItemOnPacket built from the verified hitResult):
      // keyUse.click is equally real but builds the packet from the crosshair
      // at frame-processing time, so a desktop nudge between the readback and
      // the packet redirects or drops the click (observed once as a silently
      // vanished floor placement).
      const clickFace = async (cell, dir, yaw, pitch, tries = 3) => {
        let last = '(no attempt)';
        for (let i = 0; i < tries; i++) {
          const hit = parseHit(await aim(yaw, pitch));
          last = hit;
          if (hit && hit.type === 'BLOCK' && hit.dir === dir && onTarget(hit, cell)) {
            const reply = await ctx.exec(`def hit = mc.hitResult
if (hit.getType().toString() != 'BLOCK' || hit.getBlockPos().getX() != ${cell[0]} || hit.getBlockPos().getY() != ${cell[1]} || hit.getBlockPos().getZ() != ${cell[2]} || hit.getDirection().toString() != '${dir}') return 'AIM-DRIFT'
def result = mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)
return 'use:' + result.toString()`, 15_000);
            if (String(reply).startsWith('use:')) return;
            last = String(reply);
          }
          await ctx.sleep(700);
        }
        ctx.expect(JSON.stringify(last)).toBe(`use-clicked on ${JSON.stringify(cell)} ${dir}`);
      };

      // --- scene A: a wall face is denied — nothing places, nothing consumes.
      // Yaw 30 / pitch 33 puts the crosshair on the bump's north face (yaw 18
      // grazed the east face corner); the placement handler returns before
      // any placement on a non-UP face and the paper item has no vanilla
      // use. The vanilla anchor for such a click would be [0,-60,4] — it
      // must stay air.
      await clickFace(bump, 'north', 30, 33);
      ctx.expect(await ctx.rcon('execute if block 0 -60 4 minecraft:air')).toBe('Test passed');
      ctx.expect(await stackCount()).toBe(1);

      // --- scene B: the floor is allowed — cluster spawns, one item consumed
      await clickFace(ground, 'up', 0, 22);
      ctx.expect(await ctx.rcon(`execute if block ${anchor.join(' ')} minecraft:barrier`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${light.join(' ')} minecraft:light[level=14]`))
        .toBe('Test passed');
      ctx.expect(await stackCount()).toBe(0); // the paper was consumed
      await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
      const inspect = await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench .+ root=[0-9a-f-]{36} /,
        { timeoutMs: 20_000 });
      const root = /root=([0-9a-f-]{36}) /.exec(inspect)[1];

      // --- scene C: the space pre-check denies an occupied anchor. The deny
      // is only reachable at the operator seam: the cluster's own light cell
      // (non-targetable) caps the column above the barrier, so a real client
      // can never aim an UP-face click whose candidate anchor is claimed —
      // the crosshair either misses entirely (shallow) or lands on distant
      // ground (steep), both verified in the aim sweep. The dev driver is
      // the documented harness path for this seam.
      await ctx.rcon(`materione dev place world:workbench ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=false no-space world:workbench world ${anchor.join(' ')}`),
        { timeoutMs: 20_000 });
      await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
      const inspect2 = await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench .+ root=[0-9a-f-]{36} /,
        { timeoutMs: 20_000 });
      ctx.expect(/root=([0-9a-f-]{36}) /.exec(inspect2)[1]).toBe(root);

      // --- teardown
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon(`setblock ${bump.join(' ')} minecraft:air`);
      await ctx.rcon('clear Probe');
      await ctx.rcon('kill @e[type=item]');
    },
  }),
];
