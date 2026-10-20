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
return read('sample', 'level_up') + ' | ' + read('sample', 'anthem') + ' | ' + read('sample', 'nope') + ' | ' + read('minecraft', 'entity.pig.ambient')`;

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
      ctx.expect(read).toContain('sample:anthem=sample:ui/anthem subtitle=Ruby Anthem stream=true'); // #94: entry-level stream
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
];
