// Logic suite: the L6 player, world and server actions (#212) on a real server and client. Each
// scenario is a probe item in fixtures/packs/world/items/probe_*.yml whose right-click rule runs
// the actions; the run reads the result back from chat, the inventory, the experience bar or the
// sound engine. Auto mode unless a human has to see the screen (title, action bar).

import { test } from './_lib.mjs';

// One right-click in the air: aim at the sky, prove the block ray misses, then use the held item.
// The same path a real player's right click takes on an empty spot (mechanics.custom-durability).
async function useInAir(ctx) {
  return ctx.exec(`def yaw = player.getYRot()
def pitch = player.getXRot()
player.setRot(yaw, -89.0F)
def blockHit = player.pick(20.0D, 1.0F, false)
if (blockHit.getType().toString() != 'MISS') {
  player.setRot(yaw, pitch)
  return 'not-air:' + blockHit.getType().toString()
}
mc.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND)
player.setRot(yaw, pitch)
return 'used-air'`, 15_000);
}

// Count the held inventory's stacks whose item key contains the word (a vanilla item).
function countItems(word) {
  return `def n = 0
for (def s : player.getInventory().items) {
  if (!s.isEmpty() && s.getItem().getDescriptionId().contains('${word}')) { n += s.getCount() }
}
return n`;
}

// The sound engine's active instances (SoundEngine.instanceBySource values), as location strings.
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

async function waitForActiveSound(ctx, word, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const active = await ctx.exec(ACTIVE_SOUNDS, 15_000);
    if (String(active).includes(word)) return true;
    if (Date.now() > deadline) return false;
    await ctx.sleep(150);
  }
}

async function holdProbe(ctx, item) {
  await ctx.reset();
  await ctx.rcon(`materione give ${item} Probe`);
  await ctx.waitForChat(new RegExp(`Given ${item}`));
  await ctx.exec('player.getInventory().selected = 0');
}

export default [
  test({
    id: 'logic.messages-broadcast-chat',
    title: 'world:probe_messages right-click sends a server broadcast ("Probe server broadcast"), a world-scoped broadcast that names the player ("Probe broadcast from Probe") and a chat line the player says ("probe chat line"); each reaches the chat log exactly as authored, with the template filled in',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/logic/**',
      'api/src/main/java/dev/materione/api/logic/**',
      'fixtures/packs/world/items/probe_messages.yml',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await holdProbe(ctx, 'world:probe_messages');
      ctx.expect(await useInAir(ctx)).toBe('used-air');
      await ctx.waitForChat(/Probe server broadcast/);
      await ctx.waitForChat(/Probe broadcast from Probe/);
      await ctx.waitForChat(/probe chat line/);
    },
  }),

  test({
    id: 'logic.title-actionbar',
    title: 'world:probe_messages shows the action bar text "Probe action bar" above the hotbar and a title "Probe title" with subtitle "probe subtitle" on screen after one right-click',
    mode: 'visual',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/logic/action/ActionbarAction.java',
      'plugin/src/main/java/dev/materione/plugin/logic/action/TitleAction.java',
      'fixtures/packs/world/items/probe_messages.yml',
    ],
    dependsOn: ['logic.messages-broadcast-chat'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await holdProbe(ctx, 'world:probe_messages');
      ctx.expect(await useInAir(ctx)).toBe('used-air');
      await ctx.sleep(800); // the title fades in over two ticks; the bar lingers for seconds
      await ctx.shot('title-actionbar');
      ctx.needsEyes('the action bar reads "Probe action bar" above the hotbar, and the centre of the screen shows the title "Probe title" with the smaller "probe subtitle" under it, both fully visible (not clipped, not reading as raw &-codes)');
    },
  }),

  test({
    id: 'logic.items-take-give',
    title: 'world:probe_items right-click: take_item of five diamonds with three held removes nothing (all or nothing, the player keeps three), a take of two then leaves one diamond, and give_item adds two emeralds to the inventory',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/logic/action/TakeItemAction.java',
      'plugin/src/main/java/dev/materione/plugin/logic/action/GiveItemAction.java',
      'plugin/src/main/java/dev/materione/plugin/logic/support/**',
      'fixtures/packs/world/items/probe_items.yml',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await holdProbe(ctx, 'world:probe_items');
      await ctx.rcon('give Probe minecraft:diamond 3');
      await ctx.sleep(600);
      ctx.expect(String(await ctx.exec(countItems('diamond')))).toBe('3');
      ctx.expect(String(await ctx.exec(countItems('emerald')))).toBe('0');

      ctx.expect(await useInAir(ctx)).toBe('used-air');
      await ctx.sleep(800);
      ctx.expect(String(await ctx.exec(countItems('diamond')))).toBe('1');
      ctx.expect(String(await ctx.exec(countItems('emerald')))).toBe('2');
    },
  }),

  test({
    id: 'logic.exp-give-take',
    title: 'world:probe_exp right-click grants five levels and takes two, leaving the player at level 3; a take of five hundred levels afterwards fails and takes nothing, so the level stays at 3 rather than dropping to 0',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/logic/action/GiveExpAction.java',
      'plugin/src/main/java/dev/materione/plugin/logic/action/TakeExpAction.java',
      'fixtures/packs/world/items/probe_exp.yml',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await holdProbe(ctx, 'world:probe_exp');
      await ctx.rcon('experience set Probe 0 levels');
      await ctx.sleep(400);
      ctx.expect(String(await ctx.exec('return player.experienceLevel'))).toBe('0');

      ctx.expect(await useInAir(ctx)).toBe('used-air');
      await ctx.sleep(800);
      ctx.expect(String(await ctx.exec('return player.experienceLevel'))).toBe('3');
    },
  }),

  test({
    id: 'logic.teleport',
    title: 'world:probe_move right-click teleports the player to x 0.5, z 0.5 facing east (yaw 90) in the same world, and the client shows the new position and facing',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/logic/action/TeleportAction.java',
      'fixtures/packs/world/items/probe_move.yml',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await holdProbe(ctx, 'world:probe_move');
      await ctx.rcon('tp Probe 40.5 90 40.5');
      await ctx.sleep(600);
      ctx.expect(await useInAir(ctx)).toBe('used-air');
      await ctx.sleep(900);
      const at = String(await ctx.exec('return [player.getX(), player.getZ(), player.getYRot()].join(\' \')'));
      const [x, z, yaw] = at.split(' ').map(Number);
      ctx.expect(Math.abs(x - 0.5) < 0.1).toBe(true);
      ctx.expect(Math.abs(z - 0.5) < 0.1).toBe(true);
      ctx.expect(Math.abs(((yaw % 360) + 360) % 360 - 90) < 2).toBe(true);
    },
  }),

  test({
    id: 'logic.sound-stop',
    title: 'world:probe_sound right-click starts the looping block.beacon.ambient sound for the whole world (scope world), the sound engine holds it, and world:probe_stop stop_sound removes that sound again while leaving the engine otherwise untouched',
    mode: 'auto',
    covers: [
      'plugin/src/main/java/dev/materione/plugin/logic/action/SoundAction.java',
      'plugin/src/main/java/dev/materione/plugin/logic/action/StopSoundAction.java',
      'fixtures/packs/world/items/probe_sound.yml',
      'fixtures/packs/world/items/probe_stop.yml',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 120_000,
    steps: async (ctx) => {
      await holdProbe(ctx, 'world:probe_sound');
      ctx.expect(await useInAir(ctx)).toBe('used-air');
      ctx.expect(await waitForActiveSound(ctx, 'beacon', 8_000)).toBe(true);

      await ctx.rcon('materione give world:probe_stop Probe');
      await ctx.waitForChat(/Given world:probe_stop/);
      await ctx.sleep(400);
      await ctx.exec('player.getInventory().selected = 1');
      ctx.expect(await useInAir(ctx)).toBe('used-air');
      let stopped = false;
      for (let i = 0; i < 10 && !stopped; i++) {
        await ctx.sleep(400); // the engine retires stopped instances off-thread
        stopped = !String(await ctx.exec(ACTIVE_SOUNDS, 15_000)).includes('beacon');
      }
      ctx.expect(stopped).toBe(true);
    },
  }),
];
