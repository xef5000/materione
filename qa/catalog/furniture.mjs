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

// The client-side view of one cluster entity, by uuid: namespaced type plus
// block position, or 'null' when the client does not know the entity.
const clientEntity = (ctx, uuid) => ctx.exec(`def e = mc.level.getEntity(java.util.UUID.fromString('${uuid}'))
return e == null ? 'null' : e.getType().toString() + '@' + e.getBlockX() + ',' + e.getBlockY() + ',' + e.getBlockZ()`, 15_000);

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

  test({
    id: 'furniture.display-entity',
    title: 'furniture is a display-entity cluster: one item_display root centers on the anchor cell carrying the authored model, the member record names exactly an interaction hitbox, a marker-seat armor stand and the text-display label (client-visible by uuid with those types), all of it survives a full client relaunch, and dev remove dissolves every member server- and client-side',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/furniture/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/furniture/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java',
      'fixtures/packs/world/furnitures/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 600_000, // includes one full client relaunch (ctx.rejoin)
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot region-thread queue drain

      // --- scene: anchor [1,-60,7]; display-entity furniture renders from
      // entities alone — the model carrier is the root, collision is the
      // tagged barrier cell, nothing else is placed
      const anchor = [1, -60, 7];
      const light = [1, -59, 7];
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.rcon(`materione dev remove ${light.join(' ')} world`);
      await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      await ctx.rcon(`setblock ${light.join(' ')} minecraft:air`);

      await ctx.rcon(`materione dev place world:workbench ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:workbench world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });

      // the root pins on the anchor and its record carries the cluster:
      // display= centers the model on the anchor cell (x.5, y.5, z.5)
      await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
      const inspect = await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:workbench `
          + `display=${anchor[0] + 0.5},${anchor[1] + 0.5},${anchor[2] + 0.5} root=[0-9a-f-]{36} `),
        { timeoutMs: 20_000 });
      const root = /root=([0-9a-f-]{36}) /.exec(inspect)[1];

      // composition near the anchor: exactly one of each display-entity
      // member — the model root, the interaction hitbox, the marker seat,
      // the label (execute if entity echoes the match count)
      for (const type of ['item_display', 'interaction', 'armor_stand', 'text_display']) {
        ctx.expect(await ctx.rcon(
          `execute if entity @e[type=${type},x=${anchor[0] + 0.5},y=${anchor[1] + 0.5},`
          + `z=${anchor[2] + 0.5},distance=..3]`)).toBe('Test passed. Count: 1');
      }
      // the members record names exactly three uuids (interaction, seat, label)
      const membersReply = await ctx.rcon(
        `data get entity ${root} BukkitValues."materione:members"`);
      const memberUuids = membersReply.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g) ?? [];
      ctx.expect(memberUuids.length).toBe(3);
      // the label carries the authored text (legacy &e formatting stripped to
      // the plain content by the component read)
      ctx.expect(await ctx.rcon(
        `data get entity @e[type=text_display,x=${anchor[0] + 0.5},y=${anchor[1] + 0.5},`
        + `z=${anchor[2] + 0.5},distance=..3,limit=1] text`)).toContain('Workbench');
      // the model rides the item_model component on the root's displayed stack
      ctx.expect(await ctx.rcon(
        `data get entity ${root} item.components."minecraft:item_model"`))
        .toContain('world:workbench');

      // the client sees the cluster: the root plus all three member types
      const clientTypes = async () => {
        const entries = [await clientEntity(ctx, root)];
        for (const uuid of memberUuids) entries.push(await clientEntity(ctx, uuid));
        return entries.map((e) => String(e).split('@')[0]).sort();
      };
      const wantTypes = ['entity.minecraft.armor_stand', 'entity.minecraft.interaction',
        'entity.minecraft.item_display', 'entity.minecraft.text_display'];
      const waitForClientTypes = async (want, timeoutMs = 15_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          if (JSON.stringify(await clientTypes()) === JSON.stringify(want)) return;
          if (Date.now() > deadline) ctx.expect(await clientTypes()).toEqual(want);
          await ctx.sleep(500);
        }
      };
      await waitForClientTypes(wantTypes);
      // the root centers on the anchor cell; -59.5 floors to the anchor's
      // own block y
      const rootView = String(await clientEntity(ctx, root));
      ctx.expect(rootView).toBe(
        `entity.minecraft.item_display@${anchor[0]},${anchor[1]},${anchor[2]}`);

      // persistence: a full client relaunch re-syncs the same entities
      await ctx.rejoin();
      await waitForClientTypes(wantTypes);

      // --- teardown: the cluster dissolves server- and client-side
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      for (const type of ['item_display', 'interaction', 'armor_stand', 'text_display']) {
        ctx.expect(await ctx.rcon(
          `execute if entity @e[type=${type},x=${anchor[0] + 0.5},y=${anchor[1] + 0.5},`
          + `z=${anchor[2] + 0.5},distance=..3]`)).toBe('Test failed');
      }
      await ctx.sleep(1000); // despawn packets → client
      ctx.expect(await clientEntity(ctx, root)).toBe('null');
      for (const uuid of memberUuids) {
        ctx.expect(await clientEntity(ctx, uuid)).toBe('null');
      }
      await ctx.rcon('kill @e[type=item]'); // the placement-item drop
    },
  }),

  test({
    id: 'furniture.hitboxes',
    title: 'the shipped hitbox pair behaves: a barrier cell is real collision that stops a walking player (who crosses the anchor cell freely before the cluster exists), the interaction entity is sized exactly per the fixture (width 1.2, height 1.1, responsive) and routes clicks to the cluster, and removal releases the cell so the walk-through works again',
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
      await ctx.sleep(5_000); // fresh-boot region-thread queue drain before input work

      // --- scene: the cluster anchors at [1,-60,7]; the barrier hitbox
      // (offset [0,0,0]) occupies the anchor cell, dead in the walk path
      // from the spawn point (1,-60,3) heading south.
      const anchor = [1, -60, 7];
      const light = [1, -59, 7];
      const spawn = 'tp Probe 1 -60 3';
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.rcon(`materione dev remove ${light.join(' ')} world`);
      await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      await ctx.rcon(`setblock ${light.join(' ')} minecraft:air`);

      // movement is hold-driven: walk south for 1.2s, release, let the
      // server-corrected position settle, then read the player z
      const walk = async () => {
        await ctx.exec('player.setRot(0F, 0F)\nreturn \'faced-south\'');
        await ctx.exec('mc.options.keyUp.setDown(true)\nreturn \'walking\'');
        await ctx.sleep(1200);
        await ctx.exec('mc.options.keyUp.setDown(false)\nreturn \'released\'');
        await ctx.sleep(600);
        return Number(await ctx.exec('return player.getZ()'));
      };

      // --- baseline: the empty scene is walkable — the player crosses the
      // anchor cell's z (and the interaction column) entirely
      await ctx.rcon(spawn);
      const openZ = await walk();
      ctx.expect(openZ).toBeGreaterThan(7.5);
      await ctx.rcon(spawn);

      // --- place: the barrier hitbox claims the anchor cell
      await ctx.rcon(`materione dev place world:workbench ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:workbench world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${anchor.join(' ')} minecraft:barrier`))
        .toBe('Test passed');

      // --- collision: the same walk stops at the barrier's north face
      const blockedZ = await walk();
      ctx.expect(blockedZ).toBeLessThan(6.9);
      ctx.expect(await ctx.rcon(`execute if block ${anchor.join(' ')} minecraft:barrier`))
        .toBe('Test passed');
      await ctx.rcon(spawn);

      // --- the interaction hitbox is sized per the fixture (width 1.2,
      // height 1.1) and responsive; its click routing to the cluster is
      // covered by furniture.storage
      ctx.expect(await ctx.rcon(
        `data get entity @e[type=interaction,x=${anchor[0] + 0.5},y=${anchor[1] + 0.5},`
        + `z=${anchor[2] + 0.5},distance=..3,limit=1] width`)).toContain('1.2');
      ctx.expect(await ctx.rcon(
        `data get entity @e[type=interaction,x=${anchor[0] + 0.5},y=${anchor[1] + 0.5},`
        + `z=${anchor[2] + 0.5},distance=..3,limit=1] height`)).toContain('1.1');
      ctx.expect(await ctx.rcon(
        `data get entity @e[type=interaction,x=${anchor[0] + 0.5},y=${anchor[1] + 0.5},`
        + `z=${anchor[2] + 0.5},distance=..3,limit=1] response`)).toContain('1b');

      // --- teardown: the cell releases and the path is walkable again
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${anchor.join(' ')} minecraft:air`))
        .toBe('Test passed');
      const reopenedZ = await walk();
      ctx.expect(reopenedZ).toBeGreaterThan(7.5);
      await ctx.rcon('clear Probe');
      await ctx.rcon('kill @e[type=item]');
    },
  }),

  test({
    id: 'furniture.seats',
    title: 'seats (single + multi) on a storage-free cluster: a client right-click seats the player on the FIRST recorded seat armor stand (client vehicle type and uuid match the record), dismounting frees it, an occupied first seat is skipped in favor of the second, and dismounting leaves the player free again',
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

      // --- scene: the two-seat bench at [1,-60,7]; seat A (first in the
      // record) at (1.2,-59.7,7.5), seat B at (1.8,-59.7,7.5). The click
      // goes through the barrier cell's NORTH face at pitch 22 — the
      // interaction hitbox above the cell eats every shallower ray (the
      // same occlusion the light cell produces on the workbench), and
      // onCellInteract routes on any face of the tagged cell.
      const anchor = [1, -60, 7];
      const light = [1, -59, 7];
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.rcon(`materione dev remove ${light.join(' ')} world`);
      await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      await ctx.rcon(`setblock ${light.join(' ')} minecraft:air`);
      await ctx.rcon(`materione dev place world:bench ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:bench world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });

      await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
      const inspect = await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:bench .+ root=[0-9a-f-]{36} /,
        { timeoutMs: 20_000 });
      const root = /root=([0-9a-f-]{36}) /.exec(inspect)[1];
      // two seat armor stands, in record order (A first, B second)
      const seatsReply = await ctx.rcon(
        `data get entity ${root} BukkitValues."materione:seats"`);
      const seats = seatsReply.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g) ?? [];
      ctx.expect(seats.length).toBe(2);
      const [seatA, seatB] = seats;

      // the player's vehicle: 'none' or '<type> <uuid>'
      const vehicle = () => ctx.exec(`def v = player.getVehicle()
return v == null ? 'none' : v.getType().toString() + ' ' + v.getUUID().toString()`, 15_000);

      // click the barrier's north face — the sit runs on the region thread
      const sit = async (tries = 3) => {
        for (let i = 0; i < tries; i++) {
          await ctx.exec('player.setRot(0F, 22F)\nThread.sleep(500)\ndef hit = mc.hitResult\nreturn hit.getType().toString()');
          const reply = await ctx.exec(`def hit = mc.hitResult
if (hit.getType().toString() != 'BLOCK' || hit.getBlockPos().getX() != ${anchor[0]} || hit.getBlockPos().getY() != ${anchor[1]} || hit.getBlockPos().getZ() != ${anchor[2]} || hit.getDirection().toString() != 'north') return 'AIM-DRIFT'
def result = mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)
return 'use:' + result.toString()`, 15_000);
          if (String(reply).startsWith('use:')) {
            // the seat mount lands a beat later (region-thread teleport +
            // passenger sync)
            const deadline = Date.now() + 8000;
            for (;;) {
              const v = String(await vehicle());
              if (v !== 'none') return v;
              if (Date.now() > deadline) break;
              await ctx.sleep(400);
            }
          }
          await ctx.sleep(700);
        }
        ctx.expect(await vehicle()).toContain('armor_stand');
      };

      // --- sit #1: the first recorded seat
      await ctx.rcon('tp Probe 1 -60 3');
      const first = await sit();
      ctx.expect(first).toBe(`entity.minecraft.armor_stand ${seatA}`);

      // dismount frees the seat
      await ctx.rcon('ride Probe dismount');
      await ctx.sleep(800);
      ctx.expect(await vehicle()).toBe('none');

      // --- sit #2: an occupied first seat is skipped — a NoAI villager
      // holds seat A ("Villager started riding Armor Stand"), so the same
      // click lands the player on seat B
      await ctx.rcon('summon minecraft:villager 1.2 -58.5 7.5 {NoAI:1b,Invulnerable:1b}');
      await ctx.rcon(
        'ride @e[type=villager,distance=..2,x=1.2,y=-59.7,z=7.5,limit=1] mount '
        + '@e[type=armor_stand,distance=..1,x=1.2,y=-59.7,z=7.5,limit=1]');
      await ctx.rcon('tp Probe 1 -60 3');
      const second = await sit();
      ctx.expect(second).toBe(`entity.minecraft.armor_stand ${seatB}`);

      // --- teardown: dismount, clear the dummy, dissolve the cluster
      await ctx.rcon('ride Probe dismount');
      await ctx.sleep(800);
      ctx.expect(await vehicle()).toBe('none');
      await ctx.rcon('kill @e[type=villager]');
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon('kill @e[type=item]');
    },
  }),

  test({
    id: 'furniture.light-toggle',
    title: 'light cells carry per-cell levels (minecraft:light[level=8] above the anchor, [level=7] east — exact predicates reject the neighbor values, and the client engine reads each cell at its own level, 8 and 7, since the 8-cell floods only 6 into the 7-cell) and a right-click on the cluster falls through the empty ECA→storage→seat priority chain as a silent no-op that changes neither cell — toggleable lights are not built (the only light writes are placement and record-driven removal), and dev remove clears both cells',
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
      await ctx.sleep(10_000); // fresh-boot region-thread queue drain before input work

      // --- scene: anchor [1,-60,7]; the definition's lights are [0,1,0]
      // level 8 (yaw-invariant → [1,-59,7]) and [1,0,0] level 7 (yaw 0 →
      // the east cell [2,-60,7]). The near-tied levels keep each cell's OWN
      // level client-visible: the 8-cell floods only 6 into the 7-cell, so
      // the client reads 8 and 7 — a level-15 sibling would dominate the
      // pair (its flood reaches 13 two steps away) and mask the 7. No
      // barrier hitbox: the anchor cell stays air and every click targets
      // the interaction entity.
      const anchor = [1, -60, 7];
      const cellA = [1, -59, 7]; // level 8, above
      const cellB = [2, -60, 7]; // level 7, east
      const shared = [2, -59, 7]; // adjacent to both: max(7, 6)
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      for (const cell of [anchor, cellA, cellB]) {
        await ctx.rcon(`materione dev remove ${cell.join(' ')} world`);
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:air`);
      }

      // baseline: the scene is dark
      ctx.expect(await blockLightAt(ctx, cellA)).toBe(0);
      ctx.expect(await blockLightAt(ctx, cellB)).toBe(0);

      // --- place: both cells land at their per-cell levels — a wrong-but-
      // close level on either cell must fail the predicate
      await ctx.rcon(`materione dev place world:lamp ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:lamp world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${cellA.join(' ')} minecraft:light[level=8]`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${cellA.join(' ')} minecraft:light[level=7]`))
        .toBe('Test failed');
      ctx.expect(await ctx.rcon(`execute if block ${cellB.join(' ')} minecraft:light[level=7]`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${cellB.join(' ')} minecraft:light[level=8]`))
        .toBe('Test failed');

      // each cell reads at its OWN level on the client — the exactness the
      // block predicates pinned server-side is what the engine received
      await ctx.sleep(1500); // light packets → client engine
      ctx.expect(await blockLightAt(ctx, cellA)).toBe(8);
      ctx.expect(await blockLightAt(ctx, cellB)).toBe(7);
      ctx.expect(await blockLightAt(ctx, shared)).toBe(7);

      // --- the toggle question: a right-click falls through the priority
      // chain (no ECA rules, storage 0, no seats) and must leave every cell
      // exactly as placed. The click is a real ServerboundInteractPacket
      // built from the verified entity hit — the same packet path the
      // storage and seats tests click through, aimed here at the lamp's
      // interaction entity (player at [1,-60,5]: eye (1.5,-58.62,5.5), box
      // (1.5,-59.5,7.5) spans y -60..-59, pitch 24 enters its north face
      // ~2.2 away, inside the 3.0 entity reach).
      await ctx.rcon('tp Probe 1 -60 5');
      const vehicle = () => ctx.exec(`def v = player.getVehicle()
return v == null ? 'none' : v.getType().toString()`, 15_000);
      const clickLamp = async (tries = 3) => {
        let last = '(no attempt)';
        for (let i = 0; i < tries; i++) {
          last = String(await ctx.exec(`player.setRot(0F, 24F)
Thread.sleep(500)
def hit = mc.hitResult
return hit.getType().toString() + ' ' + (hit.getEntity() == null ? '-' : hit.getEntity().getType().toString())`, 15_000));
          if (last === 'ENTITY entity.minecraft.interaction') {
            const reply = await ctx.exec(`def hit = mc.hitResult
if (hit.getType().toString() != 'ENTITY' || hit.getEntity() == null || hit.getEntity().getType().toString() != 'entity.minecraft.interaction') return 'AIM-DRIFT'
def result = mc.gameMode.interact(player, hit.getEntity(), hit, net.minecraft.world.InteractionHand.MAIN_HAND)
return 'interact:' + result.toString()`, 15_000);
            if (String(reply).startsWith('interact:')) return;
            last = String(reply);
          }
          await ctx.sleep(700);
        }
        ctx.expect(last).toBe('interact sent on entity.minecraft.interaction');
      };
      await clickLamp();
      await ctx.sleep(1000); // any region-thread reaction would land by now
      ctx.expect(await vehicle()).toBe('none'); // no seat consumed the click
      ctx.expect(Number(await ctx.exec(
        'return player.containerMenu.containerId'))).toBe(0); // no storage opened
      ctx.expect(await ctx.rcon(`execute if block ${cellA.join(' ')} minecraft:light[level=8]`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${cellB.join(' ')} minecraft:light[level=7]`))
        .toBe('Test passed');
      ctx.expect(await blockLightAt(ctx, cellA)).toBe(8);
      ctx.expect(await blockLightAt(ctx, cellB)).toBe(7);

      // --- teardown: the light record drives removal — both cells release
      await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(`execute if block ${cellA.join(' ')} minecraft:air`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block ${cellB.join(' ')} minecraft:air`))
        .toBe('Test passed');
      await ctx.sleep(1500);
      ctx.expect(await blockLightAt(ctx, cellA)).toBe(0);
      ctx.expect(await blockLightAt(ctx, cellB)).toBe(0);
      await ctx.rcon('kill @e[type=item]'); // the placement-item drop from remove
    },
  }),

  test({
    id: 'furniture.storage-capacity',
    title: 'a 54-slot crate opens a 6-row "Crate" menu (90 slots) on a client right-click, a shift-clicked diamond reaches slot 0 and a cursor pickup pair places it in the LAST container slot 53 (full-grid depth), two crates of the same id hold independent grids (diamond vs iron), and both records survive materione reload with the root uuids unchanged — removal spills each grid',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/furniture/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/furniture/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java',
      'fixtures/packs/world/furnitures/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 360_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(10_000); // fresh-boot region-thread queue drain before input work

      // --- scene: two crates of the same id, 4 blocks apart on the same
      // column so one aim geometry serves both: crate A anchors at
      // [1,-60,7] (clicked from [1,-60,5]) and crate B at [1,-60,11]
      // (clicked from [1,-60,9]) — the interaction box is always dz=2,
      // yaw 0 / pitch 24 south. No barrier hitbox: clicks target the
      // interaction entity.
      const anchorA = [1, -60, 7];
      const anchorB = [1, -60, 11];
      for (const anchor of [anchorA, anchorB]) {
        await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
        await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      }
      await ctx.rcon(`materione dev place world:crate ${anchorA.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:crate world ${anchorA.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon(`materione dev place world:crate ${anchorB.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:crate world ${anchorB.join(' ')}`),
        { timeoutMs: 45_000 });

      // Menu slot map for a 6-row menu (54 + 36): container 0..53, main
      // inventory 54..80, hotbar 81..89 — the given items ride hotbar 0/1
      // = menu slots 81/82.
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
      const click = (slot, mode) => ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, 0, net.minecraft.world.inventory.ContainerInput.${mode}, player)
return 'clicked'`);
      const screenTitle = () => ctx.exec(
        "return mc.screen == null ? 'none' : mc.screen.getTitle().getString()");

      // right-click the crate's interaction entity (same geometry the
      // light-toggle test proves: box (1.5,-59.5,7.5) from an eye at
      // (1.5,-58.62,5.5), pitch 24, ~2.2 away, inside the 3.0 reach)
      const openCrate = async (anchor, tries = 3) => {
        let last = '(no attempt)';
        for (let i = 0; i < tries; i++) {
          last = String(await ctx.exec(`player.setRot(0F, 24F)
Thread.sleep(500)
def hit = mc.hitResult
return hit.getType().toString() + ' ' + (hit.getEntity() == null ? '-' : hit.getEntity().getType().toString())`, 15_000));
          if (last === 'ENTITY entity.minecraft.interaction') {
            const reply = await ctx.exec(`def hit = mc.hitResult
if (hit.getType().toString() != 'ENTITY' || hit.getEntity() == null || hit.getEntity().getType().toString() != 'entity.minecraft.interaction') return 'AIM-DRIFT'
def result = mc.gameMode.interact(player, hit.getEntity(), hit, net.minecraft.world.InteractionHand.MAIN_HAND)
return 'interact:' + result.toString()`, 15_000);
            if (String(reply).startsWith('interact:')) {
              await waitForMenu('open');
              return;
            }
            last = String(reply);
          }
          await ctx.sleep(700);
        }
        ctx.expect(last).toBe('crate menu open');
      };
      const closeMenu = async () => {
        await ctx.exec('player.closeContainer()\nreturn \'closed\'');
        await waitForMenu('closed');
        await ctx.sleep(700); // MONITOR close listener writes the grid back
      };

      // --- capacity: 54 container slots + the 36-slot player inventory,
      // title from the fixture's display_name
      await ctx.rcon('tp Probe 1 -60 5');
      await ctx.rcon('give Probe minecraft:diamond 1'); // hotbar 0 → menu 81
      await openCrate(anchorA);
      ctx.expect(String(await screenTitle())).toBe('Crate');
      ctx.expect(Number(await ctx.exec(
        'return player.containerMenu.slots.size()'))).toBe(90);
      // shift-click deposits into slot 0; a cursor PICKUP pair reaches the
      // last container slot (54 diamonds would be needed to fill there by
      // shift-click alone)
      await click(81, 'QUICK_MOVE');
      await waitForSlot(0, 'Diamond');
      await click(0, 'PICKUP'); // diamond → cursor
      await click(53, 'PICKUP'); // cursor → last container slot
      await waitForSlot(53, 'Diamond');
      await waitForSlot(0, 'empty');
      await closeMenu();

      // --- isolation: crate B carries its own grid from its own record
      await ctx.rcon('tp Probe 1 -60 9');
      await ctx.rcon('give Probe minecraft:iron_ingot 1'); // hotbar 0 → menu 81
      await openCrate(anchorB);
      ctx.expect(Number(await ctx.exec(
        'return player.containerMenu.slots.size()'))).toBe(90);
      await click(81, 'QUICK_MOVE');
      await waitForSlot(0, 'Iron Ingot');
      await closeMenu();

      // reopen A: the diamond sits at 53 and B's iron never crossed over
      await ctx.rcon('tp Probe 1 -60 5');
      await openCrate(anchorA);
      await waitForSlot(53, 'Diamond');
      ctx.expect(String(await slotName(0))).toBe('empty');
      await closeMenu();
      // reopen B: the iron sits at 0 and A's diamond never crossed over
      await ctx.rcon('tp Probe 1 -60 9');
      await openCrate(anchorB);
      await waitForSlot(0, 'Iron Ingot');
      ctx.expect(String(await slotName(53))).toBe('empty');
      await closeMenu();

      // --- reload survival: records ride the root entities, which reload
      // never rewrites (visuals-only policy) — the root uuid must come back
      // identical, not a fresh entity with an empty record
      await ctx.rcon(`materione dev inspect ${anchorA.join(' ')} world`);
      const preReload = await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:crate .+ root=[0-9a-f-]{36} /,
        { timeoutMs: 20_000 });
      const rootA = /root=([0-9a-f-]{36}) /.exec(preReload)[1];
      await ctx.rcon('materione reload');
      // the item count itself is pinned by the harness IT — this test only
      // needs the reload-completed marker, so the count stays unmatched
      await ctx.waitForServerLog(/MATERIONE-RELOAD-OK items=\d+/, { timeoutMs: 45_000 });
      await ctx.rcon(`materione dev inspect ${anchorA.join(' ')} world`);
      const postReload = await ctx.waitForServerLog(
        /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=world:crate .+ root=[0-9a-f-]{36} /,
        { timeoutMs: 20_000 });
      ctx.expect(/root=([0-9a-f-]{36}) /.exec(postReload)[1]).toBe(rootA);
      await ctx.rcon('tp Probe 1 -60 5');
      await openCrate(anchorA);
      await waitForSlot(53, 'Diamond');
      await closeMenu();
      await ctx.rcon('tp Probe 1 -60 9');
      await openCrate(anchorB);
      await waitForSlot(0, 'Iron Ingot');
      await closeMenu();

      // --- teardown: removal spills each grid beside its own cluster
      await ctx.rcon(`materione dev remove ${anchorA.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchorA.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(
        `execute positioned ${anchorA[0] + 0.5} ${anchorA[1] + 0.5} ${anchorA[2] + 0.5}`
        + ` as @e[type=item,distance=..4] run data get entity @s Item.id`))
        .toContain('minecraft:diamond');
      await ctx.rcon('kill @e[type=item]'); // the spill + the placement-item drop
      await ctx.rcon(`materione dev remove ${anchorB.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchorB.join(' ')}`),
        { timeoutMs: 45_000 });
      ctx.expect(await ctx.rcon(
        `execute positioned ${anchorB[0] + 0.5} ${anchorB[1] + 0.5} ${anchorB[2] + 0.5}`
        + ` as @e[type=item,distance=..4] run data get entity @s Item.id`))
        .toContain('minecraft:iron_ingot');
      await ctx.rcon('kill @e[type=item]');
      await ctx.rcon('clear Probe');
    },
  }),

  test({
    id: 'furniture.evolution',
    title: 'evolution stages apply lazily on chunk-load replay from absolute placement timestamps: a replay before 180s keeps the cluster, a replay in the [180s, 330s) window applies exactly world:sprout (the furthest DUE step — bloom at 330s is listed on the same definition but is not yet eligible), and a replay past 330s applies world:bloom — all in place (root uuids and member records unchanged, the deposited diamond rides along, the menu title tracks the current stage) — and removal dissolves the evolved cluster',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/furniture/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/furniture/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java',
      'fixtures/packs/world/furnitures/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 600_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(5_000); // fresh-boot region-thread queue drain

      // --- scene: two seedlings of the same chain, 4 blocks apart on one
      // column so every chunk cycle serves both: A at [1,-60,7] (clicked
      // from [1,-60,5]), B at [1,-60,11] (clicked from [1,-60,9]). Stage
      // clocks run from PLACEMENT (absolute) and a replay only happens on
      // a chunk reload. A cycle is deterministic: 120s away (Paper holds
      // player chunks ~60s after the last viewer, so the column surely
      // unloads) + tp back = the replay lands ~125s after the cycle
      // starts. The schedule pins three windows: cycle 1 starts ~40s
      // (replay ~165 < 180 → nothing due), cycle 2 starts ~165 (replay
      // ~290: sprout due, bloom not yet), cycle 3 starts ~330 (replay
      // ~455: bloom).
      const t0 = Date.now();
      const elapsed = () => (Date.now() - t0) / 1000;
      const idleUntil = async (seconds) => {
        while (elapsed() < seconds) await ctx.sleep(2_000);
      };
      const anchorA = [1, -60, 7];
      const anchorB = [1, -60, 11];
      for (const anchor of [anchorA, anchorB]) {
        await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
        await ctx.rcon(`setblock ${anchor.join(' ')} minecraft:air`);
      }
      await ctx.rcon(`materione dev place world:seedling ${anchorA.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:seedling world ${anchorA.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon(`materione dev place world:seedling ${anchorB.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-PLACE ok=true placed world:seedling world ${anchorB.join(' ')}`),
        { timeoutMs: 45_000 });

      // roots and their member records — the in-place claim compares both
      const inspectRoot = async (anchor) => {
        await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
        const line = await ctx.waitForServerLog(
          /MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=\S+ .+ root=[0-9a-f-]{36} /,
          { timeoutMs: 20_000 });
        return /root=([0-9a-f-]{36}) /.exec(line)[1];
      };
      const rootA = await inspectRoot(anchorA);
      const rootB = await inspectRoot(anchorB);
      const idOf = (root) => ctx.rcon(
        `data get entity ${root} BukkitValues."materione:furniture-id"`);
      const membersOf = (root) => ctx.rcon(
        `data get entity ${root} BukkitValues."materione:members"`);
      ctx.expect(await idOf(rootA)).toContain('world:seedling');
      ctx.expect(await idOf(rootB)).toContain('world:seedling');
      const membersA = await membersOf(rootA);

      // the deposited diamond must ride every later transform
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
      const screenTitle = () => ctx.exec(
        "return mc.screen == null ? 'none' : mc.screen.getTitle().getString()");
      const openCluster = async (anchor, wantTitle, tries = 3) => {
        let last = '(no attempt)';
        for (let i = 0; i < tries; i++) {
          last = String(await ctx.exec(`player.setRot(0F, 24F)
Thread.sleep(500)
def hit = mc.hitResult
return hit.getType().toString() + ' ' + (hit.getEntity() == null ? '-' : hit.getEntity().getType().toString())`, 15_000));
          if (last === 'ENTITY entity.minecraft.interaction') {
            const reply = await ctx.exec(`def hit = mc.hitResult
if (hit.getType().toString() != 'ENTITY' || hit.getEntity() == null || hit.getEntity().getType().toString() != 'entity.minecraft.interaction') return 'AIM-DRIFT'
def result = mc.gameMode.interact(player, hit.getEntity(), hit, net.minecraft.world.InteractionHand.MAIN_HAND)
return 'interact:' + result.toString()`, 15_000);
            if (String(reply).startsWith('interact:')) {
              await waitForMenu('open');
              const title = String(await screenTitle());
              if (title === wantTitle) return;
              ctx.expect(title).toBe(wantTitle); // opened the WRONG stage
            }
            last = String(reply);
          }
          await ctx.sleep(700);
        }
        ctx.expect(last).toBe(`menu open with title ${wantTitle}`);
      };
      const closeMenu = async () => {
        await ctx.exec('player.closeContainer()\nreturn \'closed\'');
        await waitForMenu('closed');
        await ctx.sleep(700); // MONITOR close listener writes the grid back
      };

      await ctx.rcon('tp Probe 1 -60 5');
      await ctx.rcon('give Probe minecraft:diamond 1'); // hotbar 0 → menu 36
      await openCluster(anchorA, 'Seedling');
      ctx.expect(Number(await ctx.exec(
        'return player.containerMenu.slots.size()'))).toBe(45);
      await ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, 36, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player)
return 'clicked'`);
      await waitForSlot(0, 'Diamond');
      await closeMenu();

      // one chunk cycle: walk far enough that the origin column unloads
      // (Paper holds player chunks ~60s after the last viewer — the fixed
      // 120s sleep removes the latency variance), return, wait for the
      // replay — EntityAddToWorld re-registers the root and applies the
      // due stages ~125s after the cycle started
      const replayCycle = async () => {
        const rootPresent = () => ctx.rcon(
          `execute if entity @e[type=item_display,x=${anchorA[0] + 0.5},`
          + `y=${anchorA[1] + 0.5},z=${anchorA[2] + 0.5},distance=..3]`);
        await ctx.rcon('tp Probe 400 -60 400');
        await ctx.sleep(120_000); // Paper's ~60s hold on player-less chunks
        const unloadDeadline = Date.now() + 60_000; // insurance if it grows
        while (Date.now() < unloadDeadline) {
          if ((await rootPresent()) === 'Test failed') break;
          await ctx.sleep(5_000);
        }
        await ctx.rcon('tp Probe 1 -60 5');
        const backDeadline = Date.now() + 30_000;
        for (;;) {
          if ((await rootPresent()) === 'Test passed. Count: 1') break;
          if (Date.now() > backDeadline) {
            ctx.expect(await rootPresent()).toBe('Test passed. Count: 1');
          }
          await ctx.sleep(1_000);
        }
        await ctx.sleep(2_000); // replayLater runs a tick after the load
      };

      // --- cycle 1 (replay ~165s elapsed): before the 180s stage — nothing due
      await idleUntil(40);
      await replayCycle();
      ctx.expect(await idOf(rootA)).toContain('world:seedling');
      ctx.expect(await idOf(rootB)).toContain('world:seedling');

      // --- cycle 2 (replay ~290s elapsed): on the seedling list only
      // sprout is due — the furthest DUE step applies, not the last listed
      ctx.expect(await idOf(rootA)).toContain('world:seedling');
      await replayCycle();
      ctx.expect(await idOf(rootA)).toContain('world:sprout');
      ctx.expect(await idOf(rootB)).toContain('world:sprout');

      // --- cycle 3 (replay ~455s elapsed): past 330s → bloom on both
      await idleUntil(330);
      await replayCycle();
      ctx.expect(await idOf(rootA)).toContain('world:bloom');
      ctx.expect(await idOf(rootB)).toContain('world:bloom');
      // the transform kept the root entity, its member record and the
      // stored grid; only the visual (item_model) and id changed
      ctx.expect(await membersOf(rootA)).toBe(membersA);
      ctx.expect(await ctx.rcon(
        `data get entity ${rootA} item.components."minecraft:item_model"`))
        .toContain('world:bloom_model');
      const clientEntity = (uuid) => ctx.exec(`def e = mc.level.getEntity(java.util.UUID.fromString('${uuid}'))
return e == null ? 'null' : e.getType().toString() + '@' + e.getBlockX() + ',' + e.getBlockY() + ',' + e.getBlockZ()`, 15_000);
      ctx.expect(await clientEntity(rootA)).toBe(
        `entity.minecraft.item_display@${anchorA[0]},${anchorA[1]},${anchorA[2]}`);

      // --- the evolved cluster still serves its storage, titled by the
      // current stage, with the deposited diamond intact
      await ctx.rcon('tp Probe 1 -60 5');
      await openCluster(anchorA, 'Bloom');
      await waitForSlot(0, 'Diamond');
      await closeMenu();

      // --- teardown: the evolved cluster dissolves like any other
      await ctx.rcon(`materione dev remove ${anchorA.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchorA.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon(`materione dev remove ${anchorB.join(' ')} world`);
      await ctx.waitForServerLog(
        new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchorB.join(' ')}`),
        { timeoutMs: 45_000 });
      await ctx.rcon('kill @e[type=item]'); // the spills + placement-item drops
      await ctx.rcon('clear Probe');
    },
  }),

  test({
    id: 'furniture.rotation',
    title: 'rotation snaps the cluster to the definition grid and restricted rotation disables it: a bench (rotation 90) hand-placed at look yaw 90 lands its seat pair north/south of center with the root Rotation [90] and a rotated model transformation, at look yaw 135 it snaps to 180 (seat pair east/west), and a signpost (rotation 0) hand-placed at the same off-grid yaw stays unrotated (root Rotation [0,0], identity transformation, light cell and seat east of the anchor exactly as at yaw 0)',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/furniture/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/furniture/**',
      'core/src/main/java/dev/materione/core/runtime/RuntimeFurniture.java',
      'fixtures/packs/world/furnitures/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 360_000,
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.sleep(10_000); // fresh-boot region-thread queue drain before input work

      // --- scene: three hand placements on the x=1 column. The placement
      // yaw IS the look yaw, so each spot stands where the desired yaw's
      // ray actually hits its ground cell (reach 4.5, top face ~1.4 below
      // the eye): bench A from the east looking west (yaw 90), bench B and
      // the signpost from the south-east looking north-west (yaw 135,
      // which the 90-grid snaps half-up to 180). The signpost (rotation 0)
      // must ignore that yaw entirely.
      const benchA = { anchor: [1, -60, 7], ground: [1, -61, 7], spot: [4.5, -60, 7.5], yaw: 90, pitch: 26 };
      const benchB = { anchor: [1, -60, 13], ground: [1, -61, 13], spot: [3.5, -60, 15.5], yaw: 135, pitch: 26 };
      const signpost = { anchor: [1, -60, 19], ground: [1, -61, 19], spot: [3.5, -60, 21.5], yaw: 135, pitch: 26 };
      for (const cell of [[1, -60, 7], [1, -60, 13], [1, -60, 19],
        [2, -60, 19], [0, -60, 19], [1, -60, 18], [1, -60, 20]]) {
        await ctx.rcon(`materione dev remove ${cell.join(' ')} world`);
        await ctx.rcon(`setblock ${cell.join(' ')} minecraft:air`);
      }

      const stackCount = async () => Number(await ctx.exec(`def n = 0
for (int i = 0; i < 36; i++) {
  if (!player.getInventory().items.get(i).isEmpty()) n = n + 1
}
return n`));
      const parseHit = (s) => {
        const m = /^(\w+) (-?\d+),(-?\d+),(-?\d+) (\w+)$/.exec(String(s));
        if (!m) return null;
        return { type: m[1], x: Number(m[2]), y: Number(m[3]), z: Number(m[4]), dir: m[5] };
      };
      const placeByHand = async (scene, tries = 3) => {
        await ctx.rcon(`tp Probe ${scene.spot.join(' ')}`);
        let last = '(no attempt)';
        for (let i = 0; i < tries; i++) {
          last = String(await ctx.exec(`player.setRot(${scene.yaw}F, ${scene.pitch}F)
Thread.sleep(500)
def hit = mc.hitResult
def t = hit.getType().toString()
if (t == 'BLOCK') return 'BLOCK ' + hit.getBlockPos().getX() + ',' + hit.getBlockPos().getY() + ',' + hit.getBlockPos().getZ() + ' ' + hit.getDirection().toString()
return t`, 15_000));
          const hit = parseHit(last);
          if (hit && hit.type === 'BLOCK' && hit.dir === 'up'
            && hit.x === scene.ground[0] && hit.y === scene.ground[1] && hit.z === scene.ground[2]) {
            const reply = await ctx.exec(`def hit = mc.hitResult
if (hit.getType().toString() != 'BLOCK' || hit.getBlockPos().getX() != ${scene.ground[0]} || hit.getBlockPos().getY() != ${scene.ground[1]} || hit.getBlockPos().getZ() != ${scene.ground[2]} || hit.getDirection().toString() != 'up') return 'AIM-DRIFT'
def result = mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)
return 'use:' + result.toString()`, 15_000);
            if (String(reply).startsWith('use:')) return;
            last = String(reply);
          }
          await ctx.sleep(700);
        }
        ctx.expect(last).toBe(`placed on ${JSON.stringify(scene.ground)}`);
      };
      const seatsOf = async (anchor) => {
        await ctx.rcon(`materione dev inspect ${anchor.join(' ')} world`);
        // the display coordinates pin THIS anchor — a loose regex would
        // match an earlier cluster's inspect line still in the log
        const esc = (n) => String(n).replace('.', '\\.');
        const inspect = await ctx.waitForServerLog(
          new RegExp(`MATERIONE-DEV-FURNITURE-INSPECT found=true furniture id=\\S+ `
            + `display=${esc(anchor[0] + 0.5)},${esc(anchor[1] + 0.5)},${esc(anchor[2] + 0.5)} `
            + `root=[0-9a-f-]{36} `),
          { timeoutMs: 20_000 });
        const root = /root=([0-9a-f-]{36}) /.exec(inspect)[1];
        const seatsReply = await ctx.rcon(
          `data get entity ${root} BukkitValues."materione:seats"`);
        const seats = seatsReply.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g) ?? [];
        return { root, seats };
      };
      const seatPos = (uuid) => ctx.exec(`def e = mc.level.getEntity(java.util.UUID.fromString('${uuid}'))
return e == null ? 'null' : (Math.round(e.getX() * 100.0) / 100.0) + ',' + (Math.round(e.getZ() * 100.0) / 100.0)`, 15_000);
      // each give lands in the first empty hotbar slot (previous placements
      // consume theirs), so select by scanning, never by fixed index
      const selectPlacementItem = async () => ctx.exec(`def slot = -1
for (int i = 0; i < 9; i++) {
  if (!player.getInventory().items.get(i).isEmpty()) { slot = i; break }
}
if (slot >= 0) player.getInventory().selected = slot
return 'slot-' + slot`, 15_000);

      // --- bench A at look yaw 90: the seat pair rotates a quarter turn
      // from its authored west/east offsets to north/south of center; the
      // model transformation carries the matching quarter-turn quaternion
      await ctx.rcon('materione give world:bench Probe');
      await ctx.waitForChat(/Given world:bench/);
      await selectPlacementItem();
      await placeByHand(benchA);
      ctx.expect(await ctx.rcon(`execute if block ${benchA.anchor.join(' ')} minecraft:barrier`))
        .toBe('Test passed');
      const benchARoot = await seatsOf(benchA.anchor);
      ctx.expect(benchARoot.seats.length).toBe(2);
      // seat A (first in the record, authored [-0.3,0.3,0]) at yaw 90 →
      // (0, +0.3): south of center; the client readback carries the same
      // rotated position the server computed
      ctx.expect(await seatPos(benchARoot.seats[0])).toBe('1.5,7.8');
      ctx.expect(await seatPos(benchARoot.seats[1])).toBe('1.5,7.2');
      ctx.expect(await ctx.rcon(`data get entity ${benchARoot.root} Rotation`))
        .toContain('[90.0f, 0.0f]');
      ctx.expect(await ctx.rcon(
        `data get entity ${benchARoot.root} transformation.left_rotation`))
        .toContain('0.7071'); // rotationY(90°) = [0, sin45°, 0, cos45°]

      // --- bench B at look yaw 135: the 90-grid snaps half-up to 180 —
      // the same seat pair lands east/west instead
      await ctx.rcon('materione give world:bench Probe');
      await ctx.waitForChat(/Given world:bench/);
      await selectPlacementItem();
      await placeByHand(benchB);
      const benchBRoot = await seatsOf(benchB.anchor);
      // seat A at yaw 180 → (+0.3, 0): east of center
      ctx.expect(await seatPos(benchBRoot.seats[0])).toBe('1.8,13.5');
      ctx.expect(await seatPos(benchBRoot.seats[1])).toBe('1.2,13.5');
      ctx.expect(await ctx.rcon(`data get entity ${benchBRoot.root} Rotation`))
        .toContain('[-180.0f, 0.0f]'); // 180 normalizes to the [-180,180) record

      // --- signpost (rotation: 0) at the same off-grid look yaw 135:
      // restricted rotation — snapYaw answers 0 whatever the placer faces,
      // so the cluster lands exactly as at yaw 0
      await ctx.rcon('materione give world:signpost Probe');
      await ctx.waitForChat(/Given world:signpost/);
      await selectPlacementItem();
      await placeByHand(signpost);
      // the asymmetric light cell always lands EAST ([1,0,0] at yaw 0) —
      // a wrong rotation would put it north/west/south of the anchor
      ctx.expect(await ctx.rcon(`execute if block 2 -60 19 minecraft:light[level=7]`))
        .toBe('Test passed');
      ctx.expect(await ctx.rcon(`execute if block 0 -60 19 minecraft:light`))
        .toBe('Test failed');
      ctx.expect(await ctx.rcon(`execute if block 1 -60 20 minecraft:light`))
        .toBe('Test failed');
      const signRoot = await seatsOf(signpost.anchor);
      ctx.expect(signRoot.seats.length).toBe(1);
      ctx.expect(await seatPos(signRoot.seats[0])).toBe('1.9,19.5'); // east seat
      ctx.expect(await ctx.rcon(`data get entity ${signRoot.root} Rotation`))
        .toContain('[0.0f, 0.0f]');
      ctx.expect(await ctx.rcon(
        `data get entity ${signRoot.root} transformation.left_rotation`))
        .toContain('1.0f'); // identity

      // the client engine sees the restricted cluster's light too
      await ctx.sleep(1500);
      ctx.expect(await blockLightAt(ctx, [2, -60, 19])).toBe(7);

      // --- teardown
      for (const anchor of [benchA.anchor, benchB.anchor, signpost.anchor]) {
        await ctx.rcon(`materione dev remove ${anchor.join(' ')} world`);
        await ctx.waitForServerLog(
          new RegExp(`MATERIONE-DEV-FURNITURE-REMOVE ok=true removed furniture world ${anchor.join(' ')}`),
          { timeoutMs: 45_000 });
      }
      ctx.expect(await stackCount()).toBe(0); // all three placements consumed
      await ctx.rcon('kill @e[type=item]');
      await ctx.rcon('clear Probe');
    },
  }),
];
