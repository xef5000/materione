// Mechanics suite: item behavior as seen by a real player on a real server
// (issue #38). Auto mode — the runner judges pass/fail from assertions.

import { test } from './_lib.mjs';

async function groundSwing(ctx, tries = 3) {
  let last = '(no attempt)';
  for (let i = 0; i < tries; i++) {
    const reply = await ctx.exec(`player.setRot(0F, 50F)
Thread.sleep(400)
def hit = mc.hitResult
if (hit.getType().toString() != 'BLOCK') return 'AIR'
mc.options.keyAttack.click(mc.options.keyAttack.getDefaultKey())
return 'left-clicked'`, 15_000);
    if (String(reply) === 'left-clicked') return;
    last = String(reply);
    await ctx.sleep(700);
  }
  ctx.expect(last).toBe('left-clicked');
}

export default [
  test({
    id: 'mechanics.commands-reload-once',
    title: 'world:storm_wand retains both repeated command bindings after reload: console tellraw exactly once and the player version command',
    mode: 'auto',
    covers: [
      'api/src/main/java/dev/materione/api/mechanics/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/mechanics/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/PipelineService.java',
      'plugin/src/main/java/dev/materione/plugin/runtime/behavior/**',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione give world:storm_wand Probe');
      await ctx.waitForChat(/Given world:storm_wand/);
      await ctx.exec('player.getInventory().selected = 0');
      await ctx.rcon('materione reload');
      await ctx.waitForServerLog(/MATERIONE-RELOAD-OK items=\d+/);
      const count = async () => (await ctx.chat(60))
        .filter((line) => line.includes('The wand sparks.')).length;
      const before = await count();
      await groundSwing(ctx);
      await ctx.waitForChat(/The wand sparks\./);
      await ctx.waitForServerLog(/MATERIONE-VERSION/);
      await ctx.sleep(700);
      ctx.expect((await count()) - before).toBe(1);
    },
  }),

  test({
    id: 'mechanics.soulbound-keep-inventory',
    title: 'world:talisman is retained exactly once on death with keepInventory enabled',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/runtime/mechanics/builtin/SoulboundMechanic.java',
      'platform/src/main/java/dev/materione/platform/items/ItemListCodec.java',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      const original = await ctx.rcon('gamerule keep_inventory');
      ctx.expect(original).toMatch(/\b(true|false)\b/);
      const restore = /\btrue\b/.test(original) ? 'true' : 'false';
      try {
        await ctx.rcon('gamerule keep_inventory true');
        await ctx.rcon('gamemode survival Probe');
        await ctx.rcon('materione give world:talisman Probe');
        await ctx.waitForChat(/Given world:talisman/);
        await ctx.rcon('kill Probe');
        await ctx.sleep(800);
        ctx.expect(String(await ctx.exec(`player.respawn()
return 'respawned'`))).toBe('respawned');
        await ctx.sleep(1500);
        const count = await ctx.exec(`int count = 0
for (int i = 0; i < 36; i++) {
  def stack = player.getInventory().items.get(i)
  if (!stack.isEmpty() && stack.getHoverName().getString() == "Warden's Talisman") {
    count += stack.getCount()
  }
}
return count.toString()`);
        ctx.expect(Number(count)).toBe(1);
      } finally {
        await ctx.rcon(`gamerule keep_inventory ${restore}`);
      }
    },
  }),

  test({
    id: 'mechanics.commands-and-cooldown',
    title: 'storm_wand left-click runs console + player commands on every swing; the behavior rule is suppressed inside its cooldown window',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/runtime/mechanics/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/behavior/**',
      'behavior/src/main/java/dev/materione/behavior/**',
      'fixtures/packs/world/**',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione give world:storm_wand Probe');
      await ctx.waitForChat(/Given world:storm_wand/);
      await ctx.exec('player.getInventory().selected = 0');
      const held = await ctx.exec(
        'return player.getInventory().items.get(0).getHoverName().getString()');
      ctx.expect(String(held)).toBe('Storm Wand');

      // A momentary left-click on the ground rides the vanilla input path
      // (keyAttack click → dig-start → LEFT_CLICK_BLOCK) — one click drives
      // three surfaces: the console tellraw (commands mechanic, %player%
      // substituted), the player-executed version command (as_player — the
      // MATERIONE-VERSION log marker proves the dispatch), and the behavior
      // rule whose message is gated by its 3s cooldown. The bare-swing packet
      // path (LocalPlayer.swing → LEFT_CLICK_AIR) proved unreliable on 26.1.2
      // probe sessions once block sessions ran earlier in the same client
      // session, while dig-path clicks stay stable — the same choice a real
      // player's press produces.
      const counts = async () => {
        const lines = await ctx.chat(60);
        return {
          sparks: lines.filter((l) => l.includes('The wand sparks.')).length,
          crackles: lines.filter((l) => l.includes('The wand crackles.')).length,
        };
      };
      // aim at the ground and left-click it — atomic (the crosshair must not
      // drift off the block between verify and click), retrying against
      // desktop-mouse nudges; a momentary click starts no dig progress
      const waitForCount = async (name, want, timeoutMs = 8000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const c = await counts();
          if (c[name] >= want) {
            return c;
          }
          if (Date.now() > deadline) {
            // assertion failure carries both counters into the dossier
            ctx.expect(c[name]).toBe(want);
          }
          await ctx.sleep(300);
        }
      };

      const baseline = await counts();

      // left-click 1: cooldown fresh — every surface fires
      await groundSwing(ctx);
      await waitForCount('sparks', baseline.sparks + 1);
      await ctx.waitForServerLog(/MATERIONE-VERSION/);
      await waitForCount('crackles', baseline.crackles + 1, 3000);

      // left-click 2 immediately: the mechanics fire again, the rule is suppressed
      await groundSwing(ctx);
      await waitForCount('sparks', baseline.sparks + 2);
      const suppressed = await counts();
      ctx.expect(suppressed.crackles - baseline.crackles).toBe(1);

      // after the 3s window the rule fires again
      await ctx.sleep(3300);
      await groundSwing(ctx);
      await waitForCount('sparks', baseline.sparks + 3);
      await waitForCount('crackles', baseline.crackles + 2, 3000);
    },
  }),

  test({
    id: 'mechanics.custom-durability',
    title: 'crucible_blade counts vanilla uses in stack PDC while the vanilla damage bar stays at zero, the wear counter survives materione reload, and the third use breaks the blade',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/runtime/mechanics/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/behavior/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/ItemAutoUpdater.java',
      'behavior/src/main/java/dev/materione/behavior/**',
      'fixtures/packs/world/**',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      // Vanilla item wear only happens outside creative; clear any targets a
      // previous run left near the anchor before summoning a fresh one.
      await ctx.rcon('gamemode survival Probe');
      await ctx.rcon('execute as Probe at @s run kill @e[type=villager,distance=..8]');
      await ctx.rcon('materione give world:crucible_blade Probe');
      await ctx.waitForChat(/Given world:crucible_blade/);
      await ctx.exec('player.getInventory().selected = 0');
      const held = await ctx.exec(
        'return player.getInventory().items.get(0).getHoverName().getString()');
      ctx.expect(String(held)).toBe('Crucible Blade');
      // Baseline floor: an undamaged iron sword — the mechanic must keep it
      // exactly here (event.setCancelled) while its own counter advances.
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getDamageValue()'))).toBe('0');
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getMaxDamage()'))).toBe('250');

      // Melee target: a static NoAI villager two blocks down the player's
      // look vector (armor stands refuse the hit on 26.1.2). The client's
      // crosshair picks it up and a real attack-key click drives the whole
      // vanilla pipeline — ServerboundAttackPacket → player.attack →
      // PlayerItemDamageEvent — exactly what a human click produces.
      await ctx.rcon(
        'execute as Probe at @s run summon minecraft:villager ^ ^ ^2 '
        + '{NoAI:1b,AbsorptionAmount:40f}');
      await ctx.sleep(800); // entity tracker + crosshair pick settle
      const aim = () => ctx.exec(`def hit = player.raycastHitResult(1.0F, mc.getCameraEntity())
return hit.getType().toString() == 'ENTITY'
  ? hit.getEntity().getType().toString()
  : hit.getType().toString()`);
      ctx.expect(String(await aim())).toBe('entity.minecraft.villager');

      // ServerboundUseItemPacket raytraces blocks, not the client's entity
      // crosshair. Aim into the sky and prove that server-side block ray is a
      // miss so this specifically exercises RIGHT_CLICK_AIR (a cancelled
      // RIGHT_CLICK_BLOCK is intentionally vetoed by the behavior bridge).
      const probe = () => ctx.exec(`def yaw = player.getYRot()
def pitch = player.getXRot()
player.setRot(yaw, -89.0F)
def blockHit = player.pick(20.0D, 1.0F, false)
def blockHitType = blockHit.getType().toString()
if (blockHitType != 'MISS') {
  player.setRot(yaw, pitch)
  return 'not-air:' + blockHitType
}
mc.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND)
player.setRot(yaw, pitch)
return 'probed-air'`);
      const wearPath = 'data get entity Probe SelectedItem.components."minecraft:custom_data"'
        + '.PublicBukkitValues."materione:durability-used"';
      const wearValue = async () => {
        const reply = String(await ctx.rcon(wearPath));
        const match = reply.match(/:\s*(-?\d+)\s*$/);
        return match ? Number(match[1]) : null;
      };
      const waitForPdcWear = async (want, timeoutMs = 10_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const actual = await wearValue();
          if (actual === want) return;
          if (Date.now() > deadline) {
            ctx.expect(actual).toBe(want);
          }
          await ctx.sleep(300);
        }
      };
      const wearMessageCount = async (wear) =>
        (await ctx.chat(60)).filter((line) => line.includes(`blade-wear ${wear}/3`)).length;
      const waitForWearMessage = async (wear, want, timeoutMs = 10_000) => {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
          const actual = await wearMessageCount(wear);
          if (actual >= want) return;
          if (Date.now() > deadline) ctx.expect(actual).toBe(want);
          await ctx.sleep(300);
        }
      };
      const baselineWearMessages = (await ctx.chat(60))
        .filter((line) => line.includes('blade-wear')).length;

      const hit = async () => {
        // LocalPlayer.raycastHitResult is the fresh, entity-aware client ray
        // (Entity.pick / mc.player.pick is block-only); attack that result in
        // the same call instead of relying on mc.hitResult's render cache.
        // Keep the direct attack packet for the #63 input-decay lead; the PDC
        // read below proves the server damage event actually reached durability.
        const result = await ctx.exec(`def hit = player.raycastHitResult(1.0F, mc.getCameraEntity())
if (hit.getType().toString() != 'ENTITY'
    || hit.getEntity().getType().toString() != 'entity.minecraft.villager') {
  return hit.getType().toString()
}
mc.gameMode.attack(player, hit.getEntity())
return 'attacked'`);
        ctx.expect(String(result)).toBe('attacked');
        // the server-side attack cooldown still scales damage — spacing
        // keeps it full (the villager's 40f absorption absorbs any hit)
        await ctx.sleep(1700);
      };

      // use 1: the counter appears in PDC (1/3), vanilla bar untouched
      await hit();
      await waitForPdcWear(1);
      const preFirstWearMessage = await wearMessageCount(1);
      ctx.expect(String(await probe())).toBe('probed-air');
      await waitForWearMessage(1, preFirstWearMessage + 1);
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getDamageValue()'))).toBe('0');

      // use 2: counter advances (2/3) — the probe re-reads it from chat
      await hit();
      await waitForPdcWear(2);
      const preSecondWearMessage = await wearMessageCount(2);
      ctx.expect(String(await probe())).toBe('probed-air');
      await waitForWearMessage(2, preSecondWearMessage + 1);
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getDamageValue()'))).toBe('0');

      // reload: the auto-updater refreshes held stacks but must carry the
      // per-instance counters across — a second wear probe still reads 2/3.
      const preTwos = await wearMessageCount(2);
      await ctx.rcon('materione reload');
      await ctx.waitForServerLog(/MATERIONE-RELOAD-OK items=\d+/);
      await waitForPdcWear(2);
      ctx.expect(String(await probe())).toBe('probed-air');
      await waitForWearMessage(2, preTwos + 1);

      // use 3 hits max: the blade breaks — the slot empties and no third
      // wear value can come (the PDC was cleared with the stack; the three
      // counted lines are 1/3, 2/3, and the post-reload re-read of 2/3)
      await hit();
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().isEmpty()'))).toBe('true');
      ctx.expect((await ctx.chat(60)).filter((line) => line.includes('blade-wear')).length
        - baselineWearMessages).toBe(3);
      ctx.expect(
        (await ctx.chat(60)).filter((l) => l.includes('blade-wear 3/3')).length,
      ).toBe(0);
      ctx.expect(String(await ctx.rcon(wearPath))).toContain('Found no elements');

      await ctx.rcon('execute as Probe at @s run kill @e[type=villager,distance=..8]');
    },
  }),

  test({
    id: 'mechanics.custom-food',
    title: 'feast_bread projects nutrition/saturation/can_always_eat onto the vanilla food component (a full-hunger eat consumes it, moves saturation by exactly the component value, applies the speed effect and fires the consume rule) while light_snack with can_always_eat false cannot be eaten at full hunger',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/runtime/mechanics/**',
      'plugin/src/main/java/dev/materione/plugin/runtime/PipelineService.java',
      'plugin/src/main/java/dev/materione/plugin/runtime/behavior/**',
      'behavior/src/main/java/dev/materione/behavior/**',
      'platform/src/main/java/dev/materione/platform/items/MaterioneStacks.java',
      'fixtures/packs/world/**',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      // data-get helper: strips the "Probe has the following entity data: " wrapper
      const dataValue = async (path) => {
        const reply = await ctx.rcon(`data get entity Probe ${path}`);
        return reply.slice(reply.indexOf(': ') + 2);
      };
      const countLines = async (needle) =>
        (await ctx.chat(60)).filter((l) => l.includes(needle)).length;
      // one bite = a real use-key click plus a hold; eating takes ~1.6s of
      // hold. The camera aims at open sky first (atomically): earlier tests
      // leave the crosshair wherever their aiming stopped, and a bite with a
      // block under the crosshair becomes a useItemOn instead of an eat.
      const bite = () => ctx.exec(`player.setRot(0F, -60F)
mc.options.keyUse.click(mc.options.keyUse.getDefaultKey())
mc.options.keyUse.setDown(true)
return 'bite-queued'`);
      const release = () => ctx.exec(`mc.options.keyUse.setDown(false)
return 'released using=' + player.isUsingItem()`);
      const heldName = () => ctx.exec(
        'return player.getMainHandItem().getHoverName().getString()');

      // --- negative gate: light_snack (can_always_eat false) at full hunger ---
      await ctx.rcon('materione give world:light_snack Probe');
      await ctx.waitForChat(/Given world:light_snack/);
      await ctx.exec('player.getInventory().selected = 0');
      ctx.expect(String(await heldName())).toBe('Light Snack');
      const snackBaseline = await countLines('snack-devoured');
      ctx.expect(await dataValue('foodLevel')).toBe('20'); // gate precondition
      ctx.expect(String(await bite())).toBe('bite-queued');
      await ctx.sleep(700);
      // the use never starts: full hunger + can_always_eat false
      ctx.expect(String(await ctx.exec('return player.isUsingItem()'))).toBe('false');
      ctx.expect(String(await release())).toBe('released using=false');
      ctx.expect(String(await heldName())).toBe('Light Snack'); // survived
      ctx.expect(await countLines('snack-devoured')).toBe(snackBaseline);
      ctx.expect((await dataValue('active_effects')).includes('minecraft:haste'))
        .toBe(false);

      // --- positive: feast_bread eats at full hunger (can_always_eat true) ---
      await ctx.rcon('clear Probe');
      await ctx.rcon('materione give world:feast_bread Probe');
      await ctx.waitForChat(/Given world:feast_bread/);
      await ctx.exec('player.getInventory().selected = 0');
      ctx.expect(String(await heldName())).toBe('Feast Bread');
      // the authored values ride the vanilla food component (bread's own is
      // nutrition 5 / saturation 6.0 — the projection is distinguishable)
      const comp = (await dataValue('SelectedItem.components."minecraft:food"'))
        .replace(/\s+/g, ' ');
      ctx.expect(comp.includes('nutrition: 6')).toBe(true);
      ctx.expect(comp.includes('saturation: 12.0')).toBe(true);
      ctx.expect(comp.includes('can_always_eat: 1b')).toBe(true);

      const foodBefore = Number(await dataValue('foodLevel'));
      const satBefore = parseFloat(await dataValue('foodSaturationLevel'));
      const feastBaseline = await countLines('feast-devoured');

      ctx.expect(String(await bite())).toBe('bite-queued');
      await ctx.sleep(700); // mid-eat: the hold is consuming
      ctx.expect(String(await ctx.exec('return player.isUsingItem()'))).toBe('true');
      await ctx.sleep(1300); // hold past the 1.6s eat window: it completes alone
      ctx.expect(String(await release())).toBe('released using=false');
      await ctx.sleep(500); // MONITOR listener applies effects next tick + chat

      // consumed
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().isEmpty()'))).toBe('true');
      // vanilla ate the authored component: food +6 (clamped), saturation
      // +12 clamped to the new food level (26.1 FoodData.add formula)
      const foodAfter = Number(await dataValue('foodLevel'));
      const satAfter = parseFloat(await dataValue('foodSaturationLevel'));
      ctx.expect(foodAfter).toBe(Math.min(20, foodBefore + 6));
      ctx.expect(satAfter).toBe(Math.min(foodAfter, satBefore + 12));
      // the effects list applied on consume
      const fx = await dataValue('active_effects');
      ctx.expect(fx.includes('minecraft:speed')).toBe(true);
      ctx.expect(fx.includes('amplifier: 1b')).toBe(true);
      // and the ECA consume rule fired
      ctx.expect((await countLines('feast-devoured')) - feastBaseline).toBe(1);
    },
  }),

  test({
    id: 'mechanics.soulbound',
    title: "the warden's talisman survives death — pulled from the drop list into the player-PDC stash while dead, restored on respawn with its materione identity intact, while unsoulbound diamonds drop at the death spot",
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/runtime/mechanics/**',
      'platform/src/main/java/dev/materione/platform/items/ItemListCodec.java',
      'fixtures/packs/world/**',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('gamemode survival Probe');
      // the probe world keeps keepInventory off, so the death drop list is live
      await ctx.rcon('materione give world:talisman Probe');
      await ctx.waitForChat(/Given world:talisman/);
      await ctx.rcon('give Probe minecraft:diamond 2');
      await ctx.exec('player.getInventory().selected = 0');
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getHoverName().getString()')))
        .toBe("Warden's Talisman");

      const inventoryDump = () => ctx.exec(`def found = ''
for (int i = 0; i < 36; i++) {
  def s = player.getInventory().items.get(i)
  if (!s.isEmpty()) found = found + i + ':' + s.getHoverName().getString() + '; '
}
return found`);

      // death: the soulbound drop leaves the drop list and rides the
      // player-PDC stash until respawn (context reset clears stale drops)
      await ctx.rcon('kill Probe');
      await ctx.sleep(800);
      const stash = await ctx.rcon('data get entity Probe BukkitValues');
      ctx.expect(stash.includes('"materione:soulbound"')).toBe(true);

      // the client sits on the death screen; respawn through the real
      // client path (LocalPlayer.respawn → PERFORM_RESPAWN)
      ctx.expect(String(await ctx.exec(`player.respawn()
return 'respawned'`))).toBe('respawned');
      await ctx.sleep(1500); // MONITOR respawn listener restores next tick

      // the talisman is back, the diamonds are not
      const inv = String(await inventoryDump());
      ctx.expect(inv.includes("Warden's Talisman")).toBe(true);
      ctx.expect(inv.includes('Diamond')).toBe(false);

      // the restored stack kept its materione identity through the codec
      await ctx.exec('player.getInventory().selected = 0');
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getHoverName().getString()')))
        .toBe("Warden's Talisman");
      ctx.expect((await ctx.rcon(
        'data get entity Probe SelectedItem.components."minecraft:custom_data"'))
        .includes('world:talisman')).toBe(true);

      // the unsoulbound diamonds dropped as an item entity at the death spot
      const drops = await ctx.rcon(
        'execute positioned 0.5 -60 3.5 run data get entity '
        + '@e[type=item,distance=..8,limit=1] Item');
      ctx.expect(drops.includes('minecraft:diamond')).toBe(true);
    },
  }),

  test({
    id: 'mechanics.backpack',
    title: "the world:satchel opens a 2-row 'Satchel' container on right-click, a diamond deposited through a real container click rides the held stack's own PDC across close/reopen, and comes back out on withdrawal",
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/runtime/mechanics/**',
      'platform/src/main/java/dev/materione/platform/items/ItemListCodec.java',
      'fixtures/packs/world/**',
    ],
    dependsOn: ['commands.give-basic'],
    steps: async (ctx) => {
      await ctx.reset();
      await ctx.rcon('materione give world:satchel Probe');
      await ctx.waitForChat(/Given world:satchel/);
      await ctx.rcon('give Probe minecraft:diamond 1');
      await ctx.exec('player.getInventory().selected = 0');
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getHoverName().getString()')))
        .toBe("Traveler's Satchel");

      // DebugBridge wraps collections — direct method calls only. Container
      // slot mapping for a 2-row menu: 0..17 are the backpack, 18..44 the
      // main inventory, 45..53 the hotbar — the diamond given second sits in
      // hotbar slot 1, i.e. container slot 46.
      // Open/closed reads off the menu id: 26.1.2's closeContainer() resets
      // containerMenu to the player's own inventoryMenu (id 0) — the field
      // is never null, so null-checking it never sees "closed".
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
      // A real container click — the same input a shift-click sends.
      // 26.1.2 renamed the old handleInventoryMouseClick/ClickType pair to
      // handleContainerInput/ContainerInput (verified against the client jar).
      const quickMove = (slot) => ctx.exec(`mc.gameMode.handleContainerInput(player.containerMenu.containerId, ${slot}, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player)
return 'clicked'`);
      // useItem fires ServerboundUseItemPacket; the plugin's PlayerInteractEvent
      // listener runs despite Paper's pre-cancelled RIGHT_CLICK_AIR (its
      // @EventHandler does not set ignoreCancelled) and opens the menu.
      const useHand = () => ctx.exec(`mc.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND)
return 'use-queued'`);
      // Targeted PDC reads: a full custom_data dump truncates the long
      // backpack-data byte array before backpack-id would print (both keys
      // live under custom_data.PublicBukkitValues."materione:*"). Presence
      // reads off the value shape: a UUID string for the id, "[B;" for the
      // byte array; a missing path answers "Found no elements matching ...".
      const backpackIdReply = () => ctx.rcon(
        'data get entity Probe SelectedItem.components."minecraft:custom_data"'
        + '.PublicBukkitValues."materione:backpack-id"');
      const hasBackpackId = async () =>
        /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/
          .test(await backpackIdReply());
      const hasBackpackData = async () => (await ctx.rcon(
        'data get entity Probe SelectedItem.components."minecraft:custom_data"'
        + '.PublicBukkitValues."materione:backpack-data"')).includes('[B;');
      const inventoryDump = () => ctx.exec(`def found = ''
for (int i = 0; i < 36; i++) {
  def s = player.getInventory().items.get(i)
  if (!s.isEmpty()) found = found + i + ':' + s.getHoverName().getString() + '; '
}
return found`);
      // the title lives on the container screen in 26.x, not on the menu
      const screenTitle = () => ctx.exec(
        "return mc.screen == null ? 'none' : mc.screen.getTitle().getString()");

      // a fresh stack carries no per-instance identity yet
      ctx.expect(await hasBackpackId()).toBe(false);
      ctx.expect(await hasBackpackData()).toBe(false);

      // right-click opens the backpack (the plugin cancels the interact, so
      // the chest item never places): 2 rows × 9 + 36 player slots, and the
      // authored '&6Satchel' title — not the item's display name
      await useHand();
      await waitForMenu('open');
      ctx.expect(String(await screenTitle())).toContain('Satchel');
      ctx.expect(Number(await ctx.exec(
        'return player.containerMenu.slots.size()'))).toBe(54);
      // the first open assigned the stack its durable backpack-id
      ctx.expect(await hasBackpackId()).toBe(true);

      // deposit the marker with a real shift-click, close
      ctx.expect(String(await slotName(46))).toBe('Diamond');
      await quickMove(46);
      await waitForSlot(0, 'Diamond');
      await ctx.exec('player.closeContainer()');
      await waitForMenu('closed');
      await ctx.sleep(700); // MONITOR close listener encodes the menu into PDC

      // server truth: the contents are serialized inside the held stack
      ctx.expect(await hasBackpackId()).toBe(true);
      ctx.expect(await hasBackpackData()).toBe(true);

      // persistence: reopening restores the marker from the stack PDC
      await useHand();
      await waitForMenu('open');
      await waitForSlot(0, 'Diamond');
      ctx.expect(String(await slotName(1))).toBe('empty');

      // withdrawal: shift-click back out, close — inventory and PDC agree
      await quickMove(0);
      await waitForSlot(0, 'empty');
      await ctx.exec('player.closeContainer()');
      await waitForMenu('closed');
      await ctx.sleep(700);
      const inv = String(await inventoryDump());
      ctx.expect(inv).toContain('Diamond');
      ctx.expect(await hasBackpackData()).toBe(true);
      // and a final reopen proves the emptied container round-trips too
      await useHand();
      await waitForMenu('open');
      ctx.expect(String(await slotName(0))).toBe('empty');
      await ctx.exec('player.closeContainer()');
      await waitForMenu('closed');
      // the satchel itself was never consumed or duplicated by all this
      ctx.expect(inv).toContain("Traveler's Satchel");
      ctx.expect(String(await ctx.exec(
        'return player.getMainHandItem().getHoverName().getString()')))
        .toBe("Traveler's Satchel");
    },
  }),
];
