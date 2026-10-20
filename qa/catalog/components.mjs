// Data-components suite (Section B "1.20.5+ data components"): the vanilla
// component surface Materione actually projects onto its stacks, verified on
// the live serialized form — the server's held-item NBT
// (`data get entity Probe SelectedItem.components`) and the client's decoded
// component map must agree, per item. The generic `components:` authoring
// block is NOT covered: it is parsed and never applied (#103) — this suite
// pins the sugar that is real so the row cannot quietly claim more.
//
// Shipped projections (MaterioneStacks.MetaEditors): item_model (1.21.4),
// custom_name/lore (display_name/lore), food (the food mechanic's
// nutrition/saturation/can_always_eat), jukebox_playable (jukebox_song), and
// the PDC identity riding custom_data over the wire.

import { test } from './_lib.mjs';

// One server-side component read: full rcon reply (the "Found no elements"
// negative included) for the held stack's named component.
async function heldComponent(ctx, component) {
  return String(await ctx.rcon(
    `data get entity Probe SelectedItem.components."minecraft:${component}"`));
}

// The client's decoded component map, read reflectively (DataComponents
// constants per name) — the same stack the server serialized.
async function clientComponent(ctx, fieldName) {
  return String(await ctx.exec(`def item = mc.player.getMainHandItem().@target
def dc = Class.forName('net.minecraft.core.component.DataComponents')
def v = item.getComponents().get(dc.getField('${fieldName}').get(null))
return v == null ? 'NULL' : v.toString()`, 15_000));
}

async function holdItem(ctx, item) {
  await ctx.rcon('clear Probe');
  await ctx.rcon(`materione give ${item} Probe`);
  await ctx.sleep(500); // the stack lands in the hotbar and syncs
}

export default [
  test({
    id: 'components.applied',
    title: "the data-component surface that ships is live end to end: the ruby sword's stack carries item_model=sample:ruby_sword, custom_name, lore and the PDC identity serialized into custom_data — read back both from the server's held-item NBT and from the client's decoded component map; the food mechanic projects nutrition/saturation/can_always_eat onto the vanilla food component; jukebox_song lands as jukebox_playable whose reference the client resolves to the datapack song (Ruby Anthem, 90s, comparator 7); a vanilla item carries none of it; and the dev stack introspection reports the model",
    mode: 'auto',
    covers: [
      'platform/src/main/java/dev/materione/platform/items/**',
      'content/src/main/java/dev/materione/content/items/**',
      'plugin/src/main/java/dev/materione/plugin/command/**',
      'fixtures/packs/sample/items/**',
      'fixtures/packs/world/items/**',
    ],
    dependsOn: ['commands.give-basic'],
    timeoutMs: 180_000,
    steps: async (ctx) => {
      await ctx.reset();

      // --- the sword: identity, appearance, text — server truth first
      await holdItem(ctx, 'sample:ruby_sword');
      ctx.expect(await heldComponent(ctx, 'item_model')).toContain('"sample:ruby_sword"');
      ctx.expect(await heldComponent(ctx, 'custom_name')).toContain('Ruby Sword');
      ctx.expect(await heldComponent(ctx, 'lore')).toContain('Burns with inner fire');
      ctx.expect(await heldComponent(ctx, 'custom_data'))
        .toContain('"materione:id": "sample:ruby_sword"');
      // vanilla damage components are NOT projected (the durability mechanic
      // is PDC-based, deliberately independent of the base material)
      ctx.expect(await heldComponent(ctx, 'max_damage')).toContain('Found no elements');
      ctx.expect(await heldComponent(ctx, 'damage')).toContain('Found no elements');

      // ... and the client's decoded map agrees on the same stack
      ctx.expect(await clientComponent(ctx, 'ITEM_MODEL')).toBe('sample:ruby_sword');
      ctx.expect(await clientComponent(ctx, 'CUSTOM_NAME')).toContain('Ruby Sword');
      ctx.expect(await clientComponent(ctx, 'CUSTOM_DATA')).toContain('sample:ruby_sword');

      // --- the food mechanic projects onto the vanilla food component
      await holdItem(ctx, 'world:feast_bread');
      ctx.expect(await heldComponent(ctx, 'food'))
        .toMatch(/nutrition: 6, saturation: 12\.0f, can_always_eat: 1b/);
      ctx.expect(await heldComponent(ctx, 'custom_data'))
        .toContain('"materione:id": "world:feast_bread"');

      // --- the disc carries jukebox_playable; the client resolves the
      // reference through to the datapack song's own properties
      await holdItem(ctx, 'sample:ruby_disc');
      ctx.expect(await heldComponent(ctx, 'jukebox_playable')).toContain('"sample:anthem"');
      const playable = await clientComponent(ctx, 'JUKEBOX_PLAYABLE');
      ctx.expect(playable).toContain('sample:anthem');
      ctx.expect(playable).toContain('Ruby Anthem');
      ctx.expect(playable).toContain('lengthInSeconds=90.0');
      ctx.expect(playable).toContain('comparatorOutput=7');

      // --- a plain vanilla stack carries none of ours (components are
      // per-item; nothing leaks across the give path)
      await ctx.rcon('clear Probe');
      await ctx.rcon('give Probe minecraft:paper 1');
      await ctx.sleep(400);
      ctx.expect(await heldComponent(ctx, 'item_model')).toContain('Found no elements');
      ctx.expect(await heldComponent(ctx, 'custom_data')).toContain('Found no elements');

      // --- the shipped introspection surface reports the built stack
      await ctx.rcon('clear Probe');
      await ctx.rcon('materione give sample:ruby_sword Probe');
      await ctx.sleep(400);
      await ctx.rcon('materione dev stack sample:ruby_sword');
      ctx.expect(await ctx.serverLogLine(/MATERIONE-STACK id=sample:ruby_sword item_model=sample:ruby_sword/))
        .toBeTruthy();
    },
  }),
];
