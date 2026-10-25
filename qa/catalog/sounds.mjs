// Sounds suite: pack-authored sounds/ definitions verified on a real client —
// the client's sound registry must resolve the custom events to the pack's
// .ogg sources (subtitles, per-entry flags) and the dev-sound command must
// drive real playback through the client's sound engine. Auto mode — the
// runner judges pass/fail from assertions.

import { test } from './_lib.mjs';

// Groovy that reads the client's sound registry: event → resolved source
// location, subtitle, per-entry flags. RandomSource picks a weighted entry —
// the fixtures carry one source each, so the pick is deterministic.
const REGISTRY_READ = `def idc = Class.forName('net.minecraft.resources.Identifier')
def rnd = Class.forName('net.minecraft.util.RandomSource').getMethod('create').invoke(null)
def sm = mc.getSoundManager().@target
def read = { ns, name ->
  def ev = sm.getSoundEvent(idc.fromNamespaceAndPath(ns, name))
  if (ev == null) return ns + ':' + name + '=NULL'
  def snd = ev.getSound(rnd)
  def sub = ev.getSubtitle() == null ? 'none' : ev.getSubtitle().getString()
  return ns + ':' + name + '=' + snd.getLocation().toString() + ' subtitle=' + sub + ' stream=' + snd.shouldStream()
}
return read('sample', 'level_up') + ' | ' + read('sample', 'anthem') + ' | ' + read('sample', 'chime') + ' | ' + read('sample', 'nope') + ' | ' + read('minecraft', 'entity.pig.ambient')`;

// The sound engine's active instances (SoundEngine.instanceBySource values).
const ACTIVE_SOUNDS = `def sm = mc.getSoundManager().@target
def f = sm.getClass().getDeclaredField('soundEngine')
f.setAccessible(true)
def engine = f.get(sm)
def g = engine.getClass().getDeclaredField('instanceBySource')
g.setAccessible(true)
def mm = g.get(engine)
def out = []
for (def inst : mm.values()) { out.add(inst.getSound().getLocation().toString()) }
return out.join(' | ')`;

// The dev-sound command travels as a raw command packet: the client's synced
// tree collapses everything after /materione into one quoted-string argument,
// so the chat GUI would reject the namespaced id before it is ever sent.
async function devSound(ctx, key) {
  await ctx.exec(`mc.getConnection().@target.sendCommand('materione dev sound ${key}')
return 'sent'`, 15_000);
}

// Poll the engine's active instances until the key's source appears.
async function waitForActiveSound(ctx, location, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const active = await ctx.exec(ACTIVE_SOUNDS, 15_000);
    if (String(active).includes(location)) return true;
    if (Date.now() > deadline) return false;
    await ctx.sleep(150);
  }
}

export default [
  test({
    id: 'sounds.registration',
    title: 'custom sounds are registered end to end on a real client: the client\'s sound registry resolves sample:level_up and sample:anthem to the pack\'s own .ogg sources with their authored subtitles ("Level up!", "Ruby Anthem"), the anthem carries stream:true on the sound entry where the client reads it (event-level stream is silently dropped — #94), the .ogg itself resolves in the client\'s resource manager, an unregistered id resolves to nothing while a vanilla event stays vanilla, and /materione dev sound sample:anthem drives real playback (the engine holds an active sample:ui/anthem instance, stopped again on demand) while an unregistered key logs the command but never plays',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/sounds/**',
      'plugin/src/main/java/dev/materione/plugin/command/**',
      'fixtures/packs/sample/sounds/**',
    ],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();

      // --- registry resolution: events, sources, subtitles, per-entry flags
      const read = await ctx.exec(REGISTRY_READ, 15_000);
      ctx.expect(read).toContain('sample:level_up=sample:ui/level_up subtitle=Level up! stream=false');
      // #94: entry-level stream — pinned on the chime (the anthem stays
      // non-streamed: streamed jukebox-song events ignore eject, #98)
      ctx.expect(read).toContain('sample:anthem=sample:ui/anthem subtitle=Ruby Anthem stream=false');
      ctx.expect(read).toContain('sample:chime=sample:ui/click subtitle=Chime stream=true');
      ctx.expect(read).toContain('sample:nope=NULL'); // registration is real, not "anything resolves"
      ctx.expect(read).toMatch(/minecraft:entity\.pig\.ambient=minecraft:mob\/pig\//); // vanilla stays vanilla

      // --- the .ogg itself ships and resolves in the client's resources
      // (a sounds.json source "sample:ui/level_up" maps to the file
      // assets/sample/sounds/ui/level_up.ogg — resource id sounds/ui/…)
      const resource = await ctx.exec(`def idc = Class.forName('net.minecraft.resources.Identifier')
def opt = mc.getResourceManager().@target.getResource(idc.fromNamespaceAndPath('sample', 'sounds/ui/level_up.ogg'))
return opt.isPresent() ? 'present' : 'missing'`, 15_000);
      ctx.expect(String(resource)).toBe('present');

      // --- real playback through the dev command (packet-sent): the engine
      // holds an active instance of the pack's own source, and stops on demand
      await devSound(ctx, 'sample:anthem');
      const played = await waitForActiveSound(ctx, 'sample:ui/anthem', 8_000);
      ctx.expect(played).toBe(true);
      ctx.expect(await ctx.serverLogLine(/MATERIONE-DEV-SOUND key=sample:anthem/)).toBeTruthy();
      // stop in a bounded loop: an early stop can race the queued instance
      // (it starts playing after the stop already swept the map)
      const STOP_ANTHEM = `def idc = Class.forName('net.minecraft.resources.Identifier')
def master = Class.forName('net.minecraft.sounds.SoundSource').getField('MASTER').get(null)
mc.getSoundManager().@target.stop(idc.fromNamespaceAndPath('sample', 'anthem'), master)
return 'stopped'`;
      let stopped = false;
      for (let i = 0; i < 10 && !stopped; i++) {
        await ctx.exec(STOP_ANTHEM, 15_000);
        await ctx.sleep(400); // the engine retires stopped instances off-thread
        const active = await ctx.exec(ACTIVE_SOUNDS, 15_000);
        stopped = !String(active).includes('sample:ui/anthem');
      }
      ctx.expect(stopped).toBe(true);

      // --- an unregistered key: the command runs, silence follows — no
      // crash, no fallback sound
      await devSound(ctx, 'sample:nope');
      await ctx.sleep(800); // the command packet settles before the log check
      ctx.expect(await ctx.serverLogLine(/MATERIONE-DEV-SOUND key=sample:nope/)).toBeTruthy();
      const everPlayed = await waitForActiveSound(ctx, 'sample:', 1_500);
      ctx.expect(everPlayed).toBe(false);
    },
  }),

  test({
    id: 'sounds.replace-vanilla',
    title: 'a replace_vanilla sound overrides the vanilla event outright: every weighted pick from minecraft:ui.button.click resolves to the pack\'s sample:ui/click with the authored subtitle "Materione click" — without event-level replace the client MERGES the custom sources into the vanilla pool instead (#95) — a vanilla event\'s pool stays untouched, and playing the overridden event plays the pack\'s own source',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/sounds/**',
      'fixtures/packs/sample/sounds/**',
    ],
    dependsOn: ['sounds.registration'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await ctx.reset();

      // --- the overridden event: every weighted pick is ours, subtitle ours
      const picks = await ctx.exec(`def idc = Class.forName('net.minecraft.resources.Identifier')
def rnd = Class.forName('net.minecraft.util.RandomSource').getMethod('create').invoke(null)
def click = mc.getSoundManager().@target.getSoundEvent(idc.fromNamespaceAndPath('minecraft', 'ui.button.click'))
def seen = new java.util.HashSet()
for (int i = 0; i < 60; i++) { seen.add(click.getSound(rnd).getLocation().toString()) }
return new ArrayList(seen).toString() + ' subtitle=' + (click.getSubtitle() == null ? 'none' : click.getSubtitle().getString())`, 15_000);
      ctx.expect(String(picks)).toBe('[sample:ui/click] subtitle=Materione click'); // #95: replaced, not merged

      // --- a vanilla event that nobody overrides keeps its vanilla pool
      const pig = await ctx.exec(`def idc = Class.forName('net.minecraft.resources.Identifier')
def rnd = Class.forName('net.minecraft.util.RandomSource').getMethod('create').invoke(null)
def ev = mc.getSoundManager().@target.getSoundEvent(idc.fromNamespaceAndPath('minecraft', 'entity.pig.ambient'))
def seen = new java.util.HashSet()
for (int i = 0; i < 40; i++) { seen.add(ev.getSound(rnd).getLocation().getNamespace()) }
return new ArrayList(seen).toString()`, 15_000);
      ctx.expect(String(pig)).toBe('[minecraft]');

      // --- playing the overridden event plays the pack's own source
      await devSound(ctx, 'minecraft:ui.button.click');
      const played = await waitForActiveSound(ctx, 'sample:ui/click', 8_000);
      ctx.expect(played).toBe(true);
      await ctx.exec(`def idc = Class.forName('net.minecraft.resources.Identifier')
def master = Class.forName('net.minecraft.sounds.SoundSource').getField('MASTER').get(null)
mc.getSoundManager().@target.stop(idc.fromNamespaceAndPath('minecraft', 'ui.button.click'), master)
return 'stopped'`, 15_000);
    },
  }),

  test({
    id: 'sounds.jukebox',
    title: 'a music disc plays the pack\'s jukebox song end to end in a real jukebox: the ruby disc carries the jukebox_playable component resolved against the generated datapack (song sample:anthem, description "Ruby Anthem", length 90s, comparator output 7), inserting it through the client flips the jukebox to has_record=true and plays sample:ui/anthem through the sound engine, the authored comparator_output reaches the redstone channel (the wire behind the comparator reads exactly 7), and an empty-hand click ejects the disc — record gone, wire back to 0, playback stopped, the disc back in the inventory',
    mode: 'auto',
    covers: [
      'content/src/main/java/dev/materione/content/sounds/**',
      'content/src/main/java/dev/materione/content/items/**',
      'platform/src/main/java/dev/materione/platform/items/**',
      'fixtures/packs/sample/sounds/**',
      'fixtures/packs/sample/items/**',
    ],
    dependsOn: ['sounds.replace-vanilla'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      const spot = { x: 2, y: -60, z: 3 };
      const floorY = spot.y - 1;
      await ctx.rcon(
        `fill ${spot.x - 3} ${floorY} ${spot.z - 1} ${spot.x + 3} ${floorY} ${spot.z + 2} minecraft:stone`,
      );
      await ctx.reset();
      ctx.expect(await ctx.rcon(
        `execute if block 0 ${floorY} ${spot.z} minecraft:stone`)).toBe('Test passed');
      for (let z = spot.z; z <= spot.z + 2; z++) {
        ctx.expect(await ctx.rcon(
          `execute if block ${spot.x} ${floorY} ${z} minecraft:stone`)).toBe('Test passed');
      }
      await ctx.rcon('gamemode survival Probe');
      await ctx.sleep(400);
      await ctx.rcon('materione give sample:ruby_disc Probe 1');

      // --- the disc's component, resolved against the loaded datapack song
      const comp = await ctx.exec(`def st = mc.player.getInventory().getItem(0)
def dc = Class.forName('net.minecraft.core.component.DataComponents')
def comp = st.get(dc.getField('JUKEBOX_PLAYABLE').get(null))
return comp == null ? 'NONE' : comp.toString()`, 15_000);
      ctx.expect(String(comp)).toContain('sample:anthem');
      ctx.expect(String(comp)).toContain('description=literal{Ruby Anthem}');
      ctx.expect(String(comp)).toContain('lengthInSeconds=90.0');
      ctx.expect(String(comp)).toContain('comparatorOutput=7');

      // --- the stage: jukebox, comparator reading it, wire as the readout
      // (the comparator's cached NBT output lags — the wire's client state is
      // the honest channel)
      await ctx.rcon('setblock 2 -60 3 minecraft:jukebox');
      await ctx.rcon('setblock 2 -60 4 minecraft:comparator[mode=compare,facing=north]');
      await ctx.rcon('setblock 2 -60 5 minecraft:redstone_wire');

      // --- insert through the client: disc selected, useItemOn
      await ctx.exec(`def inv = mc.player.getInventory()
for (int i = 0; i < 9; i++) { if (inv.getItem(i).getHoverName().getString().contains('Ruby disc')) { inv.selected = i; break } }
def player = mc.player
def hit = new net.minecraft.world.phys.BlockHitResult(
    new net.minecraft.world.phys.Vec3(2.0, -59.5, 3.5),
    net.minecraft.core.Direction.WEST,
    new net.minecraft.core.BlockPos(2, -60, 3), false)
mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)
return 'inserted'`, 15_000);

      const state = async () => ctx.exec(`def jukebox = mc.level.getBlockState(new net.minecraft.core.BlockPos(2, -60, 3))
def wire = mc.level.getBlockState(new net.minecraft.core.BlockPos(2, -60, 5))
return jukebox.toString() + ' ' + wire.toString()`, 15_000);
      const deadline = Date.now() + 8_000;
      let now = await state();
      for (;;) {
        if (String(now).includes('has_record=true') && String(now).includes('power=7')) break;
        if (Date.now() > deadline) break;
        await ctx.sleep(400);
        now = await state();
      }
      ctx.expect(String(now)).toContain('has_record=true');
      ctx.expect(String(now)).toContain('power=7'); // the authored comparator_output
      ctx.expect(await waitForActiveSound(ctx, 'sample:ui/anthem', 4_000)).toBe(true);
      ctx.expect(await ctx.shot('jukebox-playing')).toBeTruthy();
      const inventoryAfterInsert = await ctx.rcon('data get entity Probe Inventory');
      ctx.expect(String(inventoryAfterInsert).includes('Ruby disc')).toBe(false);

      // --- eject with an empty hand (the disc left the hand on insert),
      // late enough that the sound is fully live — an eject that races the
      // engine's queue can leave the young instance playing its course
      await ctx.sleep(1_500);
      await ctx.exec(`def player = mc.player
def hit = new net.minecraft.world.phys.BlockHitResult(
    new net.minecraft.world.phys.Vec3(2.0, -59.5, 3.5),
    net.minecraft.core.Direction.WEST,
    new net.minecraft.core.BlockPos(2, -60, 3), false)
mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)
return 'ejected'`, 15_000);
      let after = await state();
      const ejectDeadline = Date.now() + 6_000;
      for (;;) {
        if (String(after).includes('has_record=false') && String(after).includes('power=0')) break;
        if (Date.now() > ejectDeadline) break;
        await ctx.sleep(400);
        after = await state();
      }
      ctx.expect(String(after)).toContain('has_record=false');
      ctx.expect(String(after)).toContain('power=0');
      // the sound engine retires instances off-thread once the eject
      // broadcast lands — poll, don't race the stop update
      const stillPlaying = await waitForActiveSound(ctx, 'sample:ui/anthem', 10_000);
      ctx.expect(stillPlaying).toBe(false);

      // --- observe the server-side drop, then move onto its actual position.
      // A fixed "nearby" teleport can leave the player outside the item hitbox.
      const itemAtJukebox =
        'execute positioned 2.5 -59.5 3.5 as @e[type=item,distance=..4,limit=1,sort=nearest]';
      const dropped = async () => ctx.rcon(`${itemAtJukebox} run data get entity @s Item`);
      const dropDeadline = Date.now() + 5_000;
      let drop = await dropped();
      while (!String(drop).includes('sample:ruby_disc') && Date.now() <= dropDeadline) {
        await ctx.sleep(250);
        drop = await dropped();
      }
      ctx.expect(String(drop)).toContain('Ruby disc');
      ctx.expect(String(drop)).toContain('sample:ruby_disc');
      await ctx.rcon(`${itemAtJukebox} run data get entity @s Pos`);
      const movedToDrop = await ctx.rcon(`${itemAtJukebox} at @s run tp Probe ~ ~ ~`);
      ctx.expect(String(movedToDrop)).toContain('Teleported Probe to');

      // Poll the authoritative inventory through pickup, then wait for the
      // client inventory to reflect the server's pickup update.
      const serverInventory = async () => ctx.rcon('data get entity Probe Inventory');
      const pickupDeadline = Date.now() + 5_000;
      let serverInv = await serverInventory();
      while (!String(serverInv).includes('sample:ruby_disc') && Date.now() <= pickupDeadline) {
        await ctx.sleep(250);
        serverInv = await serverInventory();
      }
      ctx.expect(String(serverInv)).toContain('Ruby disc');
      ctx.expect(String(serverInv)).toContain('sample:ruby_disc');

      const clientInventory = async () => ctx.exec(`def out = []
def playerInv = mc.player.getInventory()
for (int i = 0; i < 46; i++) {
  def st = playerInv.getItem(i)
  if (!st.isEmpty()) out.add(st.getHoverName().getString())
}
return out.join(' | ')`, 15_000);
      const clientDeadline = Date.now() + 5_000;
      let inv = await clientInventory();
      while (!String(inv).includes('Ruby disc') && Date.now() <= clientDeadline) {
        await ctx.sleep(250);
        inv = await clientInventory();
      }
      ctx.expect(String(inv)).toContain('Ruby disc');
    },
  }),
];
