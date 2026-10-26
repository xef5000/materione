/**
 * The cloud editor's demo (web.md §6, cloud landing): the real Workbench over the
 * deterministic in-browser backend the browser regression suite uses, seeded
 * with a small sample pack. There is no game server, no session and no
 * network backend: every read and write is answered from memory in this tab,
 * and a reload starts over. Loaded lazily so only a demo visit downloads it.
 */
import {
  DeterministicRpcAdapter,
  SAMPLE_GLYPH_PNG,
  addModelGallery,
  fixtureItemType,
  fixtureSupportTypes,
  guidedVocabulary,
  seedAsset,
  seedDocument,
  type FixtureSeed,
} from '../../browser/rpc-adapter';
import { handlePush, markConnectionLost } from './state.svelte';

export const DEMO_PACK = 'demo_pack';

const DEMO_MATERIALS = [
  'DIAMOND_SWORD',
  'NETHERITE_AXE',
  'BLAZE_ROD',
  'EMERALD',
  'AMETHYST_SHARD',
  'GOLDEN_APPLE',
  'STICK',
];

export function demoSeed(): FixtureSeed {
  const pack = DEMO_PACK;
  const item = fixtureItemType();
  item.description = 'Custom items with their own name, lore, texture and model.';
  item.parameters = item.parameters.map((param) =>
    param.id === 'material' ? { ...param, allowedValues: [...DEMO_MATERIALS] } : param);
  const [block, glyph] = fixtureSupportTypes();
  if (block) block.description = 'Custom blocks with live model previews.';
  if (glyph) glyph.description = 'Chat and HUD glyphs drawn from a texture.';

  const documents = new Map([
    seedDocument(pack, 'items/weapons.yml', 1, {
      ruby_blade: {
        display_name: '&cRuby Blade',
        material: 'DIAMOND_SWORD',
        lore: ['&7Forged from a single flawless ruby'],
        power: 1.5,
      },
      storm_axe: {
        display_name: '&bStorm Axe',
        material: 'NETHERITE_AXE',
        lore: ['&7Crackles when it rains'],
        power: 2,
      },
      ember_wand: {
        template: 'wand_base',
        display_name: '&6Ember Wand',
        material: 'BLAZE_ROD',
      },
    }),
    seedDocument(pack, 'items/treasures.yml', 1, {
      crimson_jewel: { display_name: '&4Crimson Jewel', material: 'EMERALD', lore: [] },
      amethyst_charm: {
        display_name: '&dAmethyst Charm',
        material: 'AMETHYST_SHARD',
        lore: ['&7Hums faintly'],
      },
      feast_apple: { display_name: '&eFeast Apple', material: 'GOLDEN_APPLE', lore: ['&7Feeds the whole party'] },
    }),
    seedDocument(pack, 'templates/wand_base.yml', 1, {
      wand_base: { lore: ['&7Warm to the touch'], power: 1 },
    }),
    seedDocument(pack, 'glyphs/icons.yml', 1, {
      heart: { texture: 'gui/heart', height: 8, ascent: 8 },
      coin: { texture: 'gui/coin', height: 8, ascent: 8 },
    }),
  ].map((document) => [`${pack}/${document.path}`, document]));

  const resources = [
    ...['ruby_blade', 'storm_axe', 'ember_wand'].map((id) =>
      ({ pack, type: 'items', path: 'items/weapons.yml', id })),
    ...['crimson_jewel', 'amethyst_charm', 'feast_apple'].map((id) =>
      ({ pack, type: 'items', path: 'items/treasures.yml', id })),
    { pack, type: 'glyphs', path: 'glyphs/icons.yml', id: 'heart' },
    { pack, type: 'glyphs', path: 'glyphs/icons.yml', id: 'coin' },
  ];

  const seed: FixtureSeed = {
    pack,
    types: [item, ...(block ? [block] : []), ...(glyph ? [glyph] : [])],
    resources,
    documents,
    assets: new Map([seedAsset(pack, 'assets/textures/gui/heart.png', SAMPLE_GLYPH_PNG)]),
    renders: new Map(),
    visuals: new Map([['heart', 'assets/textures/gui/heart.png']]),
    names: new Map([['heart', 'Heart'], ['coin', 'Coin']]),
    // One deliberate problem so the Problems view has something to show.
    failures: new Map([['coin', 'Texture not found: gui/coin']]),
    diagnostics: [
      {
        code: 'MTR-0010',
        severity: 'WARN',
        message: `resource ${pack}:coin quarantined: unresolved texture 'gui/coin'`,
        resource: `${pack}:coin`,
        location: 'plugins/Materione/content/packs/demo_pack/glyphs/icons.yml:5:3',
        hint: 'add assets/textures/gui/coin.png, then save',
      },
    ],
    // Abilities and behaviour rules get their guided editors, as on a current server.
    behavior: guidedVocabulary(),
  };
  addModelGallery(seed, false);
  return seed;
}

/** A demo backend wired into the workspace state, ready for `loadWorkspace()`. */
export function createDemoChannel(): DeterministicRpcAdapter {
  const adapter = new DeterministicRpcAdapter({ seed: demoSeed() });
  adapter.setPushHandler(handlePush);
  adapter.setDisconnectHandler(() => markConnectionLost('The demo ended.'));
  return adapter;
}
