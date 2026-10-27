import { scanReferences } from '../src/lib/demo/references';
// The real server's type self-descriptions, recorded by the plugin's golden
// test: the ?modules fixture serves the block/furniture/... schemas exactly as
// Java declares them, so a spec cannot pass against a schema the server lacks.
import goldenSnapshot from '../../fixtures/golden/editor-demo-snapshot.json';
import type { AssetRef, ResourceRef, ResourceSummary, RpcChannel, TypeInfo } from '../src/lib/api';
import type { PushFrame } from '../src/lib/events';
import type { Response } from '../src/lib/protocol/wire';
import type { BrowserTestControls, FixtureAuditEntry, RecordedRpcCall, RecordedWrite } from './contract';

interface FixtureDocument {
  pack: string;
  path: string;
  rev: number;
  raw: string;
  parsed: Record<string, Record<string, unknown>>;
}

interface FixtureAsset {
  path: string;
  kind: string;
  data: string;
  bytes: number;
}

interface FieldEdit {
  field: string[];
  value: unknown;
  remove?: boolean;
}

const PACK = 'fixture_pack';
const PIXEL_PNG =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1WYAAAAASUVORK5CYII=';
// The existing 8x8 sample pack texture, not a Minecraft asset.
const GLYPH_PNG =
  'iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEUlEQVR4nGM4oaGBFTEMLQkAgl1GAWqNFmsAAAAASUVORK5CYII=';

const ITEM_TYPE: TypeInfo = {
  name: 'item',
  directory: 'items',
  displayName: 'Item',
  description: 'Deterministic browser regression resources.',
  parameters: [
    {
      id: 'display_name',
      type: 'string',
      required: false,
      label: 'Display name',
      section: 'identity',
      order: 0,
      uiHint: 'formatted-text',
      example: 'Ruby sword',
    },
    {
      id: 'material',
      type: 'enum',
      required: true,
      label: 'Base material',
      section: 'identity',
      order: 10,
      uiHint: 'material',
      allowedValues: ['DIAMOND_SWORD', 'BLAZE_ROD', 'STICK'],
      example: 'NETHERITE_SWORD',
    },
    {
      id: 'template',
      type: 'reference',
      required: false,
      label: 'Template',
      section: 'identity',
      order: 20,
      advanced: true,
      uiHint: 'reference',
      referenceKind: 'template',
    },
    {
      id: 'texture',
      type: 'reference',
      required: false,
      label: 'Texture',
      section: 'appearance',
      order: 10,
      uiHint: 'texture',
      referenceKind: 'texture',
      example: 'item/my_texture',
    },
    {
      id: 'model',
      type: 'reference',
      required: false,
      label: '3D model',
      section: 'appearance',
      order: 20,
      advanced: false,
      uiHint: 'model',
      referenceKind: 'model',
    },
    {
      id: 'model_parent',
      type: 'string',
      required: false,
      default: 'item/generated',
      label: 'Model style',
      section: 'appearance',
      order: 30,
      advanced: true,
      uiHint: 'text',
      example: 'item/generated',
    },
    {
      id: 'lore',
      type: 'list',
      required: false,
      label: 'Lore',
      section: 'gameplay',
      order: 10,
      uiHint: 'formatted-lines',
      example: 'A very special item',
    },
    {
      id: 'components',
      type: 'raw',
      required: false,
      label: 'Vanilla components',
      section: 'gameplay',
      order: 90,
      advanced: true,
      uiHint: 'advanced',
    },
    {
      id: 'jukebox_song',
      type: 'reference',
      required: false,
      label: 'Jukebox song',
      section: 'gameplay',
      order: 20,
      advanced: true,
      uiHint: 'reference',
      referenceKind: 'sound',
    },
    {
      id: 'mechanics',
      type: 'list',
      required: false,
      label: 'Mechanics',
      section: 'behavior',
      order: 10,
      uiHint: 'structured-list',
      referenceKind: 'mechanic',
    },
    {
      id: 'power',
      type: 'decimal',
      required: false,
      label: 'Power',
      section: 'gameplay',
      example: '1.5',
    },
    {
      id: 'custom_model_data',
      type: 'integer',
      required: false,
      label: 'Custom model data',
      section: 'advanced',
      advanced: true,
    },
  ],
};

const BLOCK_TYPE: TypeInfo = {
  name: 'block',
  directory: 'blocks',
  displayName: 'Blocks',
  description: 'A second registered type for per-type catalog preferences.',
  parameters: [
    {
      id: 'display_name',
      type: 'string',
      required: true,
      label: 'Display name',
      section: 'identity',
      uiHint: 'formatted-text',
    },
  ],
};

const GLYPH_TYPE: TypeInfo = {
  name: 'glyph',
  directory: 'glyphs',
  displayName: 'Glyph',
  description: 'A glyph with a source texture and a glyph with a missing texture.',
  parameters: [
    {
      id: 'texture',
      type: 'reference',
      required: true,
      label: 'Texture',
      section: 'appearance',
      uiHint: 'texture',
      referenceKind: 'texture',
    },
  ],
};

const RESOURCES: ResourceRef[] = [
  { pack: PACK, type: 'items', path: 'items/ruby_blade.yml', id: 'ruby_blade' },
  { pack: PACK, type: 'items', path: 'items/shared_gems.yml', id: 'ruby_a' },
  { pack: PACK, type: 'items', path: 'items/shared_gems.yml', id: 'ruby_b' },
  { pack: PACK, type: 'items', path: 'items/shared_gems.yml', id: 'ruby_c' },
  { pack: PACK, type: 'blocks', path: 'blocks/ruby_crate.yml', id: 'ruby_crate' },
  { pack: PACK, type: 'glyphs', path: 'glyphs/icons.yml', id: 'heart_icon' },
  { pack: PACK, type: 'glyphs', path: 'glyphs/icons.yml', id: 'missing_icon' },
];

/**
 * Opt-in (`?modelGallery`) blocks with live-render specs, kept out of the
 * default fixture so existing specs see the same catalog and RPC calls.
 */
const CRATE_PNG = 'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAnklEQVR4nGOIMZH5TwlmABHTYgz+H+kJAuNNVQ5wNj4M0gM3ACaATSE2A2Fq4QbAFOEyBBm3eMnB2XADNlQ4wTHIkHlpBihiyHLIfBQvYHMisjewuQ7FAHS/ImvAZiCGAdgCD6QRX7hgDQN0P8P8jS1cMFyA7A18XoCpJRiIuAzEGoj4FGJLA1jDAF8awJYWqJeU0Z2GDyMne7gBlGAAtARNyxEyZukAAAAASUVORK5CYII=';
const CRATE_TEXTURE = `${PACK}:block/crate`;
const LAMP_MODEL = {
  parent: 'minecraft:block/block',
  textures: { wood: CRATE_TEXTURE, stone: 'minecraft:block/stone', particle: CRATE_TEXTURE },
  elements: [
    { from: [2, 0, 2], to: [14, 4, 14], faces: Object.fromEntries(
      ['down', 'up', 'north', 'south', 'west', 'east'].map((side) => [side, { texture: '#stone' }])) },
    { from: [6, 4, 6], to: [10, 12, 10], faces: Object.fromEntries(
      ['up', 'north', 'south', 'west', 'east'].map((side) => [side, { texture: '#wood' }])) },
    { from: [4, 12, 4], to: [12, 16, 12], rotation: { origin: [8, 14, 8], axis: 'y', angle: 22.5 },
      faces: Object.fromEntries(
        ['down', 'up', 'north', 'south', 'west', 'east'].map((side) => [side, { texture: '#wood' }])) },
  ],
};
const GALLERY: { ref: ResourceRef; name: string; render: NonNullable<ResourceSummary['render']> }[] = [
  {
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'crate_cube' },
    name: 'Crate Cube',
    render: { pack: PACK, model: { parent: 'block/cube_all', textures: { all: CRATE_TEXTURE } } },
  },
  {
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'crate_stairs' },
    name: 'Crate Stairs',
    render: { pack: PACK, model: { parent: 'block/stairs',
      textures: { side: CRATE_TEXTURE, top: CRATE_TEXTURE, bottom: CRATE_TEXTURE } } },
  },
  {
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'crate_slab' },
    name: 'Crate Slab',
    render: { pack: PACK, model: { parent: 'block/slab',
      textures: { side: CRATE_TEXTURE, top: CRATE_TEXTURE, bottom: CRATE_TEXTURE } } },
  },
  {
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'crate_sprout' },
    name: 'Crate Sprout',
    render: { pack: PACK, model: { parent: 'block/cross', textures: { cross: CRATE_TEXTURE } } },
  },
  {
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'stone_lamp' },
    name: 'Stone Lamp',
    render: { pack: PACK, path: 'assets/models/block/stone_lamp.json' },
  },
  {
    // Geometry lives in a vanilla parent the editor cannot know: falls back.
    ref: { pack: PACK, type: 'blocks', path: 'blocks/gallery.yml', id: 'mystery_anvil' },
    name: 'Mystery Anvil',
    render: { pack: PACK, model: { parent: 'minecraft:block/anvil', textures: { body: CRATE_TEXTURE } } },
  },
];

/**
 * Opt-in (`?guided`) guided-authoring fixture: a server that answers
 * `authoring.describe`, a templated item, an item whose texture file is
 * missing (with the targeted quarantine diagnostic the server publishes) and
 * an item to add abilities and rules to.
 */
const GUIDED_RESOURCES: ResourceRef[] = [
  { pack: PACK, type: 'items', path: 'items/guided.yml', id: 'templated_blade' },
  { pack: PACK, type: 'items', path: 'items/guided.yml', id: 'cracked_gem' },
  { pack: PACK, type: 'items', path: 'items/guided.yml', id: 'storm_wand' },
];

const GUIDED_DOCUMENTS: Record<string, Record<string, Record<string, unknown>>> = {
  'items/guided.yml': {
    templated_blade: { template: 'blade_base', display_name: 'Templated Blade', material: 'STICK' },
    cracked_gem: { display_name: 'Cracked Gem', material: 'STICK', texture: 'item/cracked_gem' },
    storm_wand: { display_name: 'Storm Wand', material: 'BLAZE_ROD' },
  },
  'templates/blade_base.yml': {
    blade_base: { material: 'DIAMOND_SWORD', lore: ['Forged from a template'] },
  },
};

/** Opt-in (`?catalog`): six items sharing one texture, one file each, for the Assets and bulk-authoring specs. */
const AMULET_COLORS = ['red', 'blue', 'green', 'gold', 'pink', 'black'];
const CATALOG_RESOURCES: ResourceRef[] = AMULET_COLORS.map((color) => (
  { pack: PACK, type: 'items', path: `items/amulet_${color}.yml`, id: `amulet_${color}` }
));

const CATALOG_DOCUMENTS: Record<string, Record<string, Record<string, unknown>>> = Object.fromEntries(
  AMULET_COLORS.map((color, index) => [`items/amulet_${color}.yml`, {
    [`amulet_${color}`]: {
      display_name: `${color[0]!.toUpperCase()}${color.slice(1)} Amulet`,
      material: 'STICK',
      // two spellings of the same asset, one unrelated texture
      texture: index === 3 ? `${PACK}:amulets/shared` : index === 5 ? 'amulets/other' : 'amulets/shared',
    },
  }]),
);

/**
 * Opt-in (`?modules`) display + modules fixture (issue #152): the item type as
 * a schema-declaring module server reports it, a ruby-sword-like item whose
 * abilities are already authored, and a plain item to add modules to. The
 * vocabulary mirrors the built-in mechanics plus one add-on.
 */
const MODULES_RESOURCES: ResourceRef[] = [
  { pack: PACK, type: 'items', path: 'items/ruby_sword.yml', id: 'ruby_sword' },
  { pack: PACK, type: 'items', path: 'items/ember_charm.yml', id: 'ember_charm' },
  { pack: PACK, type: 'items', path: 'items/ruby.yml', id: 'ruby' },
  { pack: PACK, type: 'blocks', path: 'blocks/amethyst_lamp.yml', id: 'amethyst_lamp' },
  { pack: PACK, type: 'blocks', path: 'blocks/plain_stone.yml', id: 'plain_stone' },
  { pack: PACK, type: 'furnitures', path: 'furnitures/chair.yml', id: 'chair' },
  { pack: PACK, type: 'furnitures', path: 'furnitures/stool.yml', id: 'stool' },
];

function goldenType(name: string): TypeInfo {
  const found = (goldenSnapshot.types as unknown as TypeInfo[]).find((type) => type.name === name);
  if (!found) throw new Error(`the golden snapshot has no ${name} type`);
  return structuredClone(found);
}

/** The ?modules fixture's types: the module-declaring item stand-in plus the real schemas. */
function moduleTypes(): TypeInfo[] {
  return [structuredClone(MODULE_ITEM_TYPE), goldenType('block'), goldenType('furniture'),
    structuredClone(GLYPH_TYPE)];
}

const MODULES_DOCUMENTS: Record<string, Record<string, Record<string, unknown>>> = {
  'items/ruby_sword.yml': {
    ruby_sword: {
      template: 'base_item',
      material: 'NETHERITE_SWORD',
      model: `${PACK}:ruby_sword_model`,
      display_name: 'Ruby Sword',
      lore: ['Burns with inner fire'],
      future_component: { keep: 'me' },
      mechanics: [
        { durability: { max: 500 } },
        { soulbound: {} },
        { commands: { on_left_click: ['tellraw %player% {"text":"The ruby hums."}'] } },
      ],
    },
  },
  'items/ember_charm.yml': {
    ember_charm: {
      display_name: 'Ember Charm',
      material: 'BLAZE_ROD',
    },
  },
  'items/ruby.yml': {
    ruby: { display_name: 'Ruby', material: 'EMERALD' },
  },
  'blocks/amethyst_lamp.yml': {
    amethyst_lamp: {
      technique: 'slab',
      texture: 'block/amethyst_lamp',
      display_name: 'Amethyst Lamp',
      blast_resistant: true,
      drops: ['ruby', 'ruby', 'minecraft:diamond'],
      item: 'ruby',
      future_flag: { keep: 'me' },
    },
  },
  'blocks/plain_stone.yml': {
    plain_stone: { texture: 'block/plain_stone', display_name: 'Plain Stone' },
  },
  'furnitures/chair.yml': {
    chair: {
      texture: 'block/marble',
      display_name: 'Chair',
      rotation: 90,
      hitboxes: [{ type: 'interaction', offset: [0, 0, 0], width: 1, height: 1 }],
      seats: [{ offset: [0, 0.3, 0] }],
      lights: [{ offset: [0.5, 1, 0], level: 12 }],
    },
  },
  'furnitures/stool.yml': {
    stool: { texture: 'block/marble', display_name: 'Stool' },
  },
  'templates/base_item.yml': {
    base_item: { display_name: 'A base item' },
  },
};

const MODULE_ITEM_TYPE: TypeInfo = {
  name: 'item',
  directory: 'items',
  displayName: 'Item',
  description: 'Deterministic module-studio regression resources.',
  parameters: [
    {
      id: 'material', type: 'enum', required: true, label: 'Base material',
      section: 'identity', order: 10, uiHint: 'material',
      allowedValues: ['NETHERITE_SWORD', 'DIAMOND_SWORD', 'BLAZE_ROD', 'STICK', 'DIAMOND', 'EMERALD'],
      example: 'NETHERITE_SWORD',
      module: true, category: 'identity', icon: 'box',
      summary: 'The vanilla item this builds on - its looks and stats until a model or behaviour takes over',
    },
    {
      id: 'template', type: 'reference', required: false, label: 'Template',
      section: 'identity', order: 20, advanced: true, uiHint: 'reference', referenceKind: 'template',
    },
    {
      id: 'model', type: 'reference', required: false, label: '3D model',
      section: 'appearance', order: 20, uiHint: 'model', referenceKind: 'model',
    },
    {
      id: 'texture', type: 'reference', required: false, label: 'Texture',
      section: 'appearance', order: 10, uiHint: 'texture', referenceKind: 'texture',
      example: 'item/my_texture',
    },
    {
      id: 'model_parent', type: 'string', required: false, default: 'item/generated',
      label: 'Model style', section: 'appearance', order: 30, uiHint: 'text',
      example: 'item/generated',
      module: true, category: 'appearance', icon: 'layers',
      summary: 'The shape family the generated model renders with (flat sprite or handheld)',
    },
    {
      id: 'display_name', type: 'string', required: false, label: 'Display name',
      section: 'identity', order: 0, uiHint: 'formatted-text', example: 'Ruby sword',
    },
    {
      id: 'lore', type: 'list', required: false, label: 'Lore',
      section: 'gameplay', order: 10, uiHint: 'formatted-lines', example: 'A very special item',
    },
    {
      id: 'components', type: 'raw', required: false, label: 'Vanilla components',
      section: 'gameplay', order: 90, advanced: true, uiHint: 'advanced',
    },
    {
      id: 'mechanics', type: 'list', required: false, label: 'Abilities',
      section: 'behavior', order: 10, uiHint: 'mechanics', referenceKind: 'mechanic',
      module: true, category: 'behaviour', icon: 'zap',
    },
    {
      id: 'behavior', type: 'list', required: false, label: 'When… do…',
      section: 'behavior', order: 20, uiHint: 'behavior-rules',
      description: 'React to game events with conditions and actions',
      module: true, category: 'behaviour', icon: 'activity',
      summary: 'React to game events with conditions and actions',
    },
    {
      id: 'jukebox_song', type: 'reference', required: false, label: 'Jukebox song',
      section: 'gameplay', order: 20, uiHint: 'reference', referenceKind: 'sound',
      module: true, category: 'gameplay', icon: 'volume-2',
      summary: 'Play a custom song when this disc goes into a jukebox',
    },
    {
      id: 'power', type: 'decimal', required: false, label: 'Power',
      section: 'gameplay', example: '1.5',
    },
    {
      id: 'custom_model_data', type: 'integer', required: false, label: 'Custom model data',
      section: 'advanced', advanced: true,
    },
  ],
};

const MODULES_VOCABULARY = {
  events: [
    { id: 'click', displayName: 'Click', description: 'The player left- or right-clicks with the item, or right-clicks the furniture', details: ['right', 'left'], firedBy: ['item', 'furniture'] },
    { id: 'break', displayName: 'Break', description: 'The player breaks a block while holding the item, or breaks this block', details: [], firedBy: ['item', 'block'] },
    { id: 'consume', displayName: 'Consume', description: 'The player eats or drinks the item', details: [], firedBy: ['item'] },
  ],
  actions: [
    { id: 'message', displayName: 'Send message', description: 'Shows text to the player, on the action bar, or to everyone', shorthand: 'text', deferred: false,
      parameters: [PARAM('text', 'string', true, 'Message text; & colour codes allowed', { label: 'Text' }),
        PARAM('target', 'enum', false, 'Where the text appears', { uiHint: 'select', label: 'Where the text appears', allowedValues: ['player', 'actionbar', 'broadcast'] })] },
    { id: 'run_command', displayName: 'Run command', description: 'Runs a server command', shorthand: 'command', deferred: true,
      parameters: [PARAM('command', 'string', true, 'Command without the leading slash', { label: 'Command' })] },
  ],
  ruleOptions: [PARAM('cooldown', 'integer', false, 'Milliseconds before this rule can fire again', { uiHint: 'number' })],
  actionOptions: [PARAM('chance', 'decimal', false, 'Probability from 0 to 1 that the action runs', { uiHint: 'number' })],
  mechanics: [
    { id: 'materione:soulbound', displayName: 'Soulbound', description: 'The item stays with its owner through death', repeatable: false, registeredBy: 'Materione',
      activation: 'When the owner dies the item is kept back and returned on respawn or next join', parameters: [] },
    { id: 'materione:durability', displayName: 'Durability', description: 'Custom durability that repairs and re damage', repeatable: false, registeredBy: 'Materione',
      parameters: [PARAM('max', 'integer', true, 'Maximum durability', { uiHint: 'number', label: 'Max durability' })] },
    { id: 'materione:commands', displayName: 'Console Commands', description: 'Runs commands from the server console', repeatable: false, registeredBy: 'Materione',
      parameters: [PARAM('on_left_click', 'list', false, 'Commands on left click (one per line)', { label: 'Left-click commands' }), PARAM('on_right_click', 'list', false, 'Commands on right click (one per line)', { label: 'Right-click commands' })] },
    { id: 'materione:food', displayName: 'Food', description: 'Custom nutrition, saturation and consume effects', repeatable: false, registeredBy: 'Materione',
      activation: 'When the player eats the item, its nutrition, saturation and effects apply',
      parameters: [PARAM('nutrition', 'integer', true, 'Hunger points restored (0..20)', { uiHint: 'number', label: 'Nutrition' }), PARAM('saturation', 'decimal', true, 'Saturation restored (0..40)', { uiHint: 'number', label: 'Saturation' }), PARAM('can_always_eat', 'boolean', false, 'Edible even at full hunger', { label: 'Can always eat' })] },
    { id: 'materione:backpack', displayName: 'Backpack', description: 'A container that travels inside the item', repeatable: false, registeredBy: 'Materione',
      activation: 'Right-clicking with the item opens its storage; contents save when the inventory closes',
      parameters: [PARAM('rows', 'integer', true, 'Inventory rows (1..6)', { uiHint: 'number', label: 'Inventory rows' }), PARAM('title', 'string', false, 'Inventory title', { label: 'Backpack title' })] },
    { id: 'example:charge', displayName: 'Charge', description: 'Stores charge on use', repeatable: true, registeredBy: 'ExampleAddon',
      parameters: [PARAM('capacity', 'integer', true, 'Maximum charge', { uiHint: 'number', label: 'Capacity' })] },
  ],
};

const GUIDED_DIAGNOSTICS = [
  {
    code: 'MTR-0010',
    severity: 'WARN',
    message: "resource fixture_pack:cracked_gem quarantined: unresolved texture 'item/cracked_gem'",
    resource: 'fixture_pack:cracked_gem',
    hint: 'fix the reported problems, then /materione reload',
    target: { field: ['texture'] },
  },
];

// A hoisted declaration: fixture vocabularies above this line read it at
// module-init time.
function PARAM(id: string, type: string, required: boolean, description: string,
  extra: Record<string, unknown> = {}) {
  return { id, type, required, description, uiHint: 'text', label: '', ...extra };
}

const GUIDED_VOCABULARY = {
  events: [
    { id: 'click', displayName: 'Click', description: 'The player left- or right-clicks with the item, or right-clicks the furniture', details: ['right', 'left'], firedBy: ['item', 'furniture'] },
    { id: 'break', displayName: 'Break', description: 'The player breaks a block while holding the item, or breaks this block', details: [], firedBy: ['item', 'block'] },
    { id: 'place', displayName: 'Place', description: 'The block or furniture is placed', details: [], firedBy: ['block', 'furniture'] },
    { id: 'consume', displayName: 'Consume', description: 'The player eats or drinks the item', details: [], firedBy: ['item'] },
    { id: 'tick', displayName: 'Tick', description: 'Reserved; not fired yet', details: [], firedBy: [] },
  ],
  actions: [
    { id: 'message', displayName: 'Send message', description: 'Shows text to the player, on the action bar, or to everyone', shorthand: 'text', deferred: false,
      parameters: [PARAM('text', 'string', true, 'Message text; & colour codes allowed'),
        PARAM('target', 'enum', false, 'Where the text appears', { uiHint: 'select', allowedValues: ['player', 'actionbar', 'broadcast'] })] },
    { id: 'run_command', displayName: 'Run command', description: 'Runs a server command', shorthand: 'command', deferred: true,
      parameters: [PARAM('command', 'string', true, 'Command without the leading slash')] },
  ],
  ruleOptions: [PARAM('cooldown', 'integer', false, 'Milliseconds before this rule can fire again', { uiHint: 'number' })],
  actionOptions: [PARAM('chance', 'decimal', false, 'Probability from 0 to 1 that the action runs', { uiHint: 'number' })],
  mechanics: [
    { id: 'example:charge', displayName: 'Charge', description: 'Stores charge on use', repeatable: false, registeredBy: 'ExampleAddon',
      parameters: [PARAM('capacity', 'integer', true, 'Maximum charge', { uiHint: 'number' })] },
    { id: 'materione:soulbound', displayName: 'Soulbound', description: 'The item stays with its owner through death', repeatable: false, registeredBy: 'Materione',
      activation: 'When the owner dies the item is kept back and returned on respawn or next join', parameters: [] },
  ],
};

const DIAGNOSTICS = [
  {
    code: 'MTR-9001',
    severity: 'ERROR',
    message: 'Fixture diagnostic with a stable source.',
    resource: 'fixture_pack:ruby_blade',
    location: 'C:\\server\\plugins\\Materione\\content\\packs\\fixture_pack\\items\\ruby_blade.yml:3:3',
    hint: 'This entry is only returned by the browser fixture.',
  },
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function stringValue(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function numberValue(value: unknown, fallback = Number.NaN): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function stringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((entry: unknown) => typeof entry === 'string');
}

function parseEdits(value: unknown): FieldEdit[] | null {
  if (!Array.isArray(value)) return null;
  const edits: FieldEdit[] = [];
  for (const entry of value) {
    if (!isRecord(entry) || !stringArray(entry.field) || entry.field.length < 2) return null;
    if (entry.remove === true) {
      if ('value' in entry) return null;
      edits.push({ field: entry.field, value: undefined, remove: true });
      continue;
    }
    edits.push({ field: entry.field, value: entry.value });
  }
  return edits;
}

function parseScalar(value: string): unknown {
  const trimmed = value.trim();
  if (trimmed === 'true') return true;
  if (trimmed === 'false') return false;
  if (trimmed === 'null' || trimmed === '~') return null;
  if (/^-?\d+(?:\.\d+)?$/.test(trimmed)) return Number(trimmed);
  if (trimmed.startsWith('"') || trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      const parsed: unknown = JSON.parse(trimmed);
      return parsed;
    } catch {
      return trimmed;
    }
  }
  if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
    return trimmed.slice(1, -1).replaceAll("''", "'");
  }
  return trimmed;
}

function parseFixtureYaml(raw: string): Record<string, Record<string, unknown>> {
  const parsed: Record<string, Record<string, unknown>> = {};
  let root: string | null = null;
  let listField: string | null = null;

  for (const line of raw.split(/\r?\n/)) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    const rootMatch = /^([A-Za-z0-9_-]+):\s*$/.exec(line);
    if (rootMatch) {
      root = rootMatch[1] ?? null;
      if (root) parsed[root] = {};
      listField = null;
      continue;
    }
    const fieldMatch = /^  ([A-Za-z0-9_-]+):(?:\s*(.*))?$/.exec(line);
    if (root && fieldMatch) {
      const key = fieldMatch[1];
      const scalar = fieldMatch[2] ?? '';
      if (!key) continue;
      if (!scalar) {
        parsed[root]![key] = [];
        listField = key;
      } else {
        parsed[root]![key] = parseScalar(scalar);
        listField = null;
      }
      continue;
    }
    const itemMatch = /^    -\s*(.*)$/.exec(line);
    if (root && listField && itemMatch) {
      const list = parsed[root]![listField];
      if (Array.isArray(list)) list.push(parseScalar(itemMatch[1] ?? ''));
    }
  }
  return parsed;
}

function yamlScalar(value: unknown): string {
  if (typeof value === 'string') {
    if (/^[A-Za-z0-9_./:@+-]+$/.test(value) && !/^(true|false|null)$/i.test(value)) {
      return value;
    }
    return JSON.stringify(value);
  }
  if (value === null) return 'null';
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

function serializeFixtureYaml(parsed: Record<string, Record<string, unknown>>): string {
  const lines: string[] = [];
  for (const [root, fields] of Object.entries(parsed)) {
    lines.push(`${root}:`);
    for (const [key, value] of Object.entries(fields)) {
      if (Array.isArray(value)) {
        lines.push(`  ${key}:`);
        for (const entry of value) lines.push(`    - ${yamlScalar(entry)}`);
      } else {
        lines.push(`  ${key}: ${yamlScalar(value)}`);
      }
    }
  }
  return `${lines.join('\n')}\n`;
}

function assignPath(target: Record<string, unknown>, path: string[], value: unknown): boolean {
  if (path.length === 0) return false;
  let current = target;
  for (const segment of path.slice(0, -1)) {
    const child = current[segment];
    if (!isRecord(child)) return false;
    current = child;
  }
  const last = path[path.length - 1];
  if (!last) return false;
  current[last] = structuredClone(value);
  return true;
}

function makeDocument(
  path: string,
  rev: number,
  parsed: Record<string, Record<string, unknown>>,
): FixtureDocument {
  return { pack: PACK, path, rev, parsed, raw: serializeFixtureYaml(parsed) };
}

function initialDocuments(): Map<string, FixtureDocument> {
  const blade = makeDocument('items/ruby_blade.yml', 7, {
    ruby_blade: {
      display_name: 'Ruby Blade',
      material: 'DIAMOND_SWORD',
      model: '',
      lore: ['A deterministic browser fixture'],
      power: 1,
      custom_model_data: 41,
      future_flag: 'preserved',
    },
  });
  blade.raw = blade.raw.replaceAll('\n', '\r\n');
  const shared = makeDocument('items/shared_gems.yml', 3, {
    ruby_a: {
      display_name: 'Crimson Jewel',
      material: 'BLAZE_ROD',
      lore: [],
      future_flag: 'left-root',
    },
    ruby_b: {
      display_name: 'Ruby B',
      material: 'CUSTOM_FUTURE_MATERIAL',
      lore: ['One intact line', 'second\nembedded line'],
      future_flag: 'right-root',
    },
    ruby_c: {
      display_name: 'Malformed Lore',
      material: 'STICK',
      lore: ['One intact line', 42],
      future_flag: 'malformed-lore-root',
    },
  });
  const crate = makeDocument('blocks/ruby_crate.yml', 2, {
    ruby_crate: {
      // legacy codes on purpose: the rich text editor must show them as formatting and
      // leave the bytes alone until the author changes the look (issue 151)
      display_name: '&6Ruby &lCrate',
      future_flag: 'block-root',
    },
  });
  const glyphs = makeDocument('glyphs/icons.yml', 1, {
    heart_icon: { texture: 'gui/heart', height: 8, ascent: 8 },
    missing_icon: { texture: 'gui/missing', height: 8, ascent: 8 },
  });
  const documents = new Map<string, FixtureDocument>();
  documents.set(`${PACK}/${blade.path}`, blade);
  documents.set(`${PACK}/${shared.path}`, shared);
  documents.set(`${PACK}/${crate.path}`, crate);
  documents.set(`${PACK}/${glyphs.path}`, glyphs);
  return documents;
}

function assetPath(pack: string, path: string): string {
  return `${pack}/${path}`;
}

function isWriteOperation(op: string): boolean {
  return (
    op.startsWith('edit.') ||
    op === 'authoring.commit' ||
    op === 'authoring.setFields' ||
    op === 'authoring.bulk' ||
    op === 'authoring.saveModel' ||
    op === 'authoring.createResource' ||
    op === 'authoring.duplicateResource' ||
    op === 'assets.put' ||
    op === 'assets.delete' ||
    op === 'assets.move' ||
    op === 'delivery.publish' ||
    op === 'admin.settings.put'
  );
}

export class DeterministicRpcAdapter implements RpcChannel, BrowserTestControls {
  readonly #documents = initialDocuments();
  readonly #calls: RecordedRpcCall[] = [];
  readonly #assets = new Map<string, FixtureAsset>([
    [
      assetPath(PACK, 'assets/textures/items/ruby_blade.png'),
      {
        path: 'assets/textures/items/ruby_blade.png',
        kind: 'png',
        data: PIXEL_PNG,
        bytes: 68,
      },
    ],
    [
      assetPath(PACK, 'assets/textures/gui/heart.png'),
      { path: 'assets/textures/gui/heart.png', kind: 'png', data: GLYPH_PNG, bytes: 74 },
    ],
  ]);
  readonly #writes: RecordedWrite[] = [];
  #auditEntries: FixtureAuditEntry[] = [];
  #pushHandler: ((frame: PushFrame) => void) | null = null;
  #disconnectHandler: (() => void) | null = null;
  #nextResponseId = 0;
  #nextWriteDelay = 0;
  #conflictNext = false;
  #disconnectAfterNextWrite = false;
  #disconnected = false;
  #candidateSha256 = 'a'.repeat(64);
  #publishedSha256 = '0'.repeat(64);
  #summaryError: boolean;
  readonly #resources: ResourceRef[];
  readonly #renders = new Map<string, NonNullable<ResourceSummary['render']>>();

  readonly #guided: boolean;
  readonly #modules: boolean;

  constructor(options: { summaryError?: boolean; modelGallery?: boolean; guided?: boolean; catalog?: boolean; modules?: boolean } = {}) {
    this.#summaryError = options.summaryError ?? false;
    this.#guided = options.guided ?? false;
    this.#modules = options.modules ?? false;
    this.#resources = [...RESOURCES];
    if (this.#guided) {
      this.#resources.push(...GUIDED_RESOURCES);
      for (const [path, parsed] of Object.entries(GUIDED_DOCUMENTS)) {
        this.#documents.set(`${PACK}/${path}`, makeDocument(path, 1, structuredClone(parsed)));
      }
    }
    if (this.#modules) {
      this.#resources.push(...MODULES_RESOURCES);
      for (const [path, parsed] of Object.entries(MODULES_DOCUMENTS)) {
        this.#documents.set(`${PACK}/${path}`, makeDocument(path, 1, structuredClone(parsed)));
      }
    }
    if (options.catalog) {
      this.#resources.push(...CATALOG_RESOURCES);
      for (const [path, parsed] of Object.entries(CATALOG_DOCUMENTS)) {
        this.#documents.set(`${PACK}/${path}`, makeDocument(path, 1, structuredClone(parsed)));
      }
      for (const name of ['shared', 'other']) {
        this.#assets.set(assetPath(PACK, `assets/textures/amulets/${name}.png`),
          { path: `assets/textures/amulets/${name}.png`, kind: 'png', data: PIXEL_PNG, bytes: 68 });
      }
    }
    if (options.modelGallery) {
      const gallery: Record<string, Record<string, unknown>> = {};
      for (const entry of GALLERY) {
        this.#resources.push(entry.ref);
        this.#renders.set(entry.ref.id, entry.render);
        gallery[entry.ref.id] = { display_name: entry.name };
      }
      this.#documents.set(`${PACK}/blocks/gallery.yml`, makeDocument('blocks/gallery.yml', 1, gallery));
      this.#assets.set(assetPath(PACK, 'assets/textures/block/crate.png'),
        { path: 'assets/textures/block/crate.png', kind: 'png', data: CRATE_PNG, bytes: 214 });
      const model = new TextEncoder().encode(JSON.stringify(LAMP_MODEL));
      this.#assets.set(assetPath(PACK, 'assets/models/block/stone_lamp.json'), {
        path: 'assets/models/block/stone_lamp.json', kind: 'json',
        data: btoa(String.fromCharCode(...model)), bytes: model.length,
      });
    }
  }

  setPushHandler(handler: (frame: PushFrame) => void): void {
    this.#pushHandler = handler;
  }

  setDisconnectHandler(handler: () => void): void {
    this.#disconnectHandler = handler;
  }

  async call(op: string, payload: Record<string, unknown> = {}): Promise<Response> {
    if (this.#disconnected) throw new Error('fixture channel is disconnected');
    this.#calls.push({ op, payload: structuredClone(payload) });
    const write: RecordedWrite | null = isWriteOperation(op)
      ? {
          op,
          payload: structuredClone(payload),
          outcome: 'pending',
        }
      : null;
    if (write) this.#writes.push(write);

    if (write && this.#nextWriteDelay > 0) {
      const delay = this.#nextWriteDelay;
      this.#nextWriteDelay = 0;
      await new Promise((resolve) => setTimeout(resolve, delay));
    }

    if (write && this.#conflictNext) {
      this.#conflictNext = false;
      write.outcome = 'conflict';
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }

    const response = this.dispatch(op, payload);
    if (write) {
      write.outcome = response.ok ? 'saved' : response.error.code === 'MTR-0034' ? 'conflict' : 'rejected';
      if (response.ok && this.#disconnectAfterNextWrite) {
        this.#disconnectAfterNextWrite = false;
        this.#disconnected = true;
        write.outcome = 'response-lost';
        this.#disconnectHandler?.();
        throw new Error('connection closed after the write was submitted');
      }
    }
    return response;
  }

  calls(): readonly RecordedRpcCall[] {
    return structuredClone(this.#calls);
  }

  documents(): readonly import('./contract').FixtureDocumentSnapshot[] {
    return structuredClone([...this.#documents.values()]);
  }

  writes(): readonly RecordedWrite[] {
    return structuredClone(this.#writes);
  }

  clearWrites(): void {
    this.#writes.length = 0;
  }

  delayNextWrite(milliseconds: number): void {
    this.#nextWriteDelay = Math.max(0, Math.min(milliseconds, 10_000));
  }

  conflictNextWrite(): void {
    this.#conflictNext = true;
  }

  externalEdit(pack: string, path: string): void {
    const document = this.#documents.get(`${pack}/${path}`);
    if (!document) throw new Error('fixture document does not exist');
    const root = Object.keys(document.parsed)[0];
    if (!root) throw new Error('fixture document has no resource root');
    const body = document.parsed[root];
    if (!body) throw new Error('fixture root is missing');
    body.display_name = `${stringValue(body.display_name, root)} (external edit)`;
    document.rev += 1;
    document.raw = serializeFixtureYaml(document.parsed);
    this.emitPush('push.document.changed', { pack, path, deleted: false });
  }

  disconnectAfterNextWrite(): void {
    this.#disconnectAfterNextWrite = true;
  }

  simulateDisconnect(): void {
    this.#disconnected = true;
    this.#disconnectHandler?.();
  }

  setCandidateSha256(value: string): void {
    this.#candidateSha256 = value;
  }

  deliveryState(): { candidateSha256: string; publishedSha256: string } {
    return {
      candidateSha256: this.#candidateSha256,
      publishedSha256: this.#publishedSha256,
    };
  }

  emitPush(op: string, payload: Record<string, unknown>): void {
    this.#pushHandler?.({ op, payload: structuredClone(payload) });
  }

  emitReload(payload: Record<string, unknown>): void {
    this.emitPush('push.reload', payload);
  }

  setAuditEntries(entries: readonly FixtureAuditEntry[]): void {
    this.#auditEntries = structuredClone([...entries]);
  }

  private success(op: string, data: unknown): Response {
    return {
      op,
      id: `fixture-${++this.#nextResponseId}`,
      ok: true,
      data,
    };
  }

  private failure(op: string, code: string, message: string): Response {
    return {
      op,
      id: `fixture-${++this.#nextResponseId}`,
      ok: false,
      error: { code, message },
    };
  }

  private dispatch(op: string, payload: Record<string, unknown>): Response {
    switch (op) {
      case 'session.info':
        return this.success(op, {
          serverVersion: '1.21.4',
          minecraftVersion: '1.21.4',
          protocolVersion: 1,
          editorRunning: true,
          editorPort: 8089,
        });
      case 'browse.packs':
        return this.success(op, { packs: [PACK] });
      case 'authoring.describe':
        if (this.#modules) {
          return this.success(op, {
            types: moduleTypes(),
            behavior: structuredClone(MODULES_VOCABULARY),
          });
        }
        if (!this.#guided) return this.failure(op, 'MTR-0001', `unsupported fixture operation: ${op}`);
        return this.success(op, {
          types: [this.guidedItemType(), structuredClone(BLOCK_TYPE), structuredClone(GLYPH_TYPE)],
          behavior: structuredClone(GUIDED_VOCABULARY),
        });
      case 'browse.types':
        return this.success(op, {
          types: this.#modules
            ? moduleTypes()
            : [structuredClone(ITEM_TYPE), structuredClone(BLOCK_TYPE), structuredClone(GLYPH_TYPE)],
        });
      case 'browse.resources': {
        const pack = stringValue(payload.pack);
        return this.success(op, {
          resources: this.#resources.filter((resource) => resource.pack === pack).map((resource) =>
            structuredClone(resource),
          ),
        });
      }
      case 'authoring.summaries':
        return this.summaries(op, payload);
      case 'browse.folders':
        return this.success(op, { folders: [] });
      case 'browse.get':
        return this.getDocument(op, payload);
      case 'edit.put':
        return this.putDocument(op, payload);
      case 'authoring.setFields':
        return this.setFields(op, payload);
      case 'authoring.references':
        return this.references(op, payload);
      case 'authoring.bulk':
        return this.bulk(op, payload);
      case 'authoring.saveModel':
        return this.saveModel(op, payload);
      case 'assets.list':
        return this.listAssets(op, payload);
      case 'assets.get':
        return this.getAsset(op, payload);
      case 'assets.put':
        return this.putAsset(op, payload);
      case 'assets.delete':
        return this.deleteAsset(op, payload);
      case 'assets.move':
        return this.moveAsset(op, payload);
      case 'admin.diagnostics.recent':
        return this.success(op, {
          diagnostics: structuredClone(this.#guided ? [...DIAGNOSTICS, ...GUIDED_DIAGNOSTICS] : DIAGNOSTICS),
        });
      case 'delivery.status':
        return this.success(op, {
          candidateSha256: this.#candidateSha256,
          publishedSha256: this.#publishedSha256,
        });
      case 'delivery.publish':
        return this.publish(op, payload);
      case 'pipeline.reload':
        return this.success(op, { accepted: true, itemsLive: 1, quarantined: 0 });
      case 'validate.dryRun':
        return this.success(op, { valid: true, diagnostics: [] });
      case 'admin.audit.recent':
        return this.success(op, { entries: structuredClone(this.#auditEntries) });
      case 'admin.settings.get':
        return this.success(op, { raw: 'editor:\n  enabled: true\n', sha256: 'fixture-settings' });
      case 'admin.settings.put':
        return this.success(op, { sha256: 'fixture-settings-updated' });
      default:
        return this.failure(op, 'MTR-0001', `unsupported fixture operation: ${op}`);
    }
  }

  private summaries(op: string, payload: Record<string, unknown>): Response {
    if (this.#summaryError) {
      return this.failure(op, 'MTR-0001', 'deterministic resource summary read failure');
    }
    const pack = stringValue(payload.pack);
    const type = stringValue(payload.type);
    const query = stringValue(payload.query).replaceAll('_', ' ').trim().toLowerCase();
    const all: ResourceSummary[] = [];
    for (const resource of this.#resources) {
      if (resource.pack !== pack || (type && resource.type !== type)) continue;
      const document = this.#documents.get(`${resource.pack}/${resource.path}`);
      const fields = document?.parsed[resource.id];
      if (!fields) continue;
      const material = stringValue(fields.material);
      const visual: ResourceSummary['visual'] = resource.id === 'ruby_blade'
        ? { pack: PACK, path: 'assets/textures/items/ruby_blade.png', kind: 'texture' }
        : resource.id === 'heart_icon'
          ? { pack: PACK, path: 'assets/textures/gui/heart.png', kind: 'texture' }
          : undefined;
      const summary: ResourceSummary = {
        ...resource,
        name: resource.id === 'heart_icon' ? 'Heart Icon'
          : resource.id === 'missing_icon' ? 'Missing Icon'
            : stringValue(fields.display_name, resource.id),
        ...(material ? { material } : {}),
        ...(visual ? { visual } : {}),
        ...(this.#renders.has(resource.id) ? { render: this.#renders.get(resource.id) } : {}),
        health: resource.id === 'missing_icon' ? 'error' : 'unknown',
        ...(resource.id === 'missing_icon' ? { healthMessage: 'Texture not found: gui/missing' } : {}),
      };
      const searchable = [summary.id, summary.path, summary.name, summary.material ?? '']
        .join(' ')
        .replaceAll('_', ' ')
        .toLowerCase();
      if (query && !searchable.includes(query)) continue;
      all.push(summary);
    }
    const offset = numberValue(payload.offset, 0);
    const limit = numberValue(payload.limit, 100);
    if (!Number.isInteger(offset) || offset < 0
        || !Number.isInteger(limit) || limit < 1 || limit > 200) {
      return this.failure(op, 'MTR-0012', 'summary page has invalid offset or limit');
    }
    const start = Math.min(offset, all.length);
    const end = Math.min(start + limit, all.length);
    return this.success(op, {
      resources: all.slice(start, end),
      total: all.length,
      ...(end < all.length ? { nextOffset: end } : {}),
    });
  }

  private getDocument(op: string, payload: Record<string, unknown>): Response {
    const document = this.findDocument(payload);
    if (!document) return this.failure(op, 'MTR-0009', 'fixture document not found');
    return this.success(op, {
      pack: document.pack,
      path: document.path,
      rev: document.rev,
      raw: document.raw,
      parsed: structuredClone(document.parsed),
      computed: this.inheritance(document),
    });
  }

  /** The registry item type a describing server reports: abilities and rules get their editors. */
  private guidedItemType(): TypeInfo {
    const type = structuredClone(ITEM_TYPE);
    type.parameters = type.parameters.map((param) =>
      param.id === 'mechanics' ? { ...param, label: 'Abilities', uiHint: 'mechanics' } : param);
    type.parameters.push({
      id: 'behavior', type: 'list', required: false, label: 'Behaviour rules', section: 'behavior',
      order: 20, uiHint: 'behavior-rules', description: 'ECA rules fired by this item',
    });
    return type;
  }

  /** Mirrors the server's `computed.inheritance` for roots naming a fixture template. */
  private inheritance(document: FixtureDocument): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const [root, fields] of Object.entries(document.parsed)) {
      const authored = stringValue(fields.template);
      if (!authored) continue;
      const id = authored.includes(':') ? authored : `${PACK}:${authored}`;
      const path = `templates/${id.slice(id.indexOf(':') + 1)}.yml`;
      const template = this.#documents.get(`${PACK}/${path}`)?.parsed[id.slice(id.indexOf(':') + 1)];
      if (!template) {
        out[root] = { template: authored, chain: [id], inherited: {}, sources: {}, templates: {}, failure: `unknown template ${id}` };
        continue;
      }
      const inherited: Record<string, unknown> = {};
      const sources: Record<string, string> = {};
      for (const [key, value] of Object.entries(template)) {
        if (key === 'template') continue;
        inherited[key] = structuredClone(value);
        sources[key] = id;
      }
      out[root] = { template: authored, chain: [id], inherited, sources, templates: { [id]: { pack: PACK, path } } };
    }
    return Object.keys(out).length ? { inheritance: out } : {};
  }

  private putDocument(op: string, payload: Record<string, unknown>): Response {
    const document = this.findDocument(payload);
    if (!document) return this.failure(op, 'MTR-0009', 'fixture document not found');
    if (numberValue(payload.rev) !== document.rev) {
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }
    const raw = stringValue(payload.content);
    const parsed = parseFixtureYaml(raw);
    if (Object.keys(parsed).length === 0) {
      return this.failure(op, 'MTR-0012', 'fixture YAML has no resource root');
    }
    document.raw = raw;
    document.parsed = parsed;
    document.rev += 1;
    return this.success(op, {
      rev: document.rev,
      raw: document.raw,
      parsed: structuredClone(document.parsed),
    });
  }

  private setFields(op: string, payload: Record<string, unknown>): Response {
    const document = this.findDocument(payload);
    if (!document) return this.failure(op, 'MTR-0009', 'fixture document not found');
    if (numberValue(payload.rev) !== document.rev) {
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }
    const edits = parseEdits(payload.edits);
    if (!edits) return this.failure(op, 'MTR-0012', 'fixture field edits are malformed');
    const parsed = structuredClone(document.parsed);
    for (const edit of edits) {
      const root = edit.field[0];
      if (!root || !parsed[root]) {
        return this.failure(op, 'MTR-0009', 'fixture resource root not found');
      }
      if (edit.remove) {
        if (edit.field.length !== 2) return this.failure(op, 'MTR-0012', 'fixture removal path is malformed');
        delete parsed[root][edit.field[1]!];
        continue;
      }
      if (!assignPath(parsed[root], edit.field.slice(1), edit.value)) {
        return this.failure(op, 'MTR-0012', 'fixture field path is malformed');
      }
    }
    document.parsed = parsed;
    document.rev += edits.length > 0 ? 1 : 0;
    document.raw = serializeFixtureYaml(document.parsed);
    return this.success(op, { rev: document.rev });
  }

  private references(op: string, payload: Record<string, unknown>): Response {
    const kind = stringValue(payload.kind);
    if (kind !== 'asset' && kind !== 'resource') return this.failure(op, 'MTR-0008', 'bad reference kind');
    const documents = [...this.#documents.values()].map((document) => (
      { pack: document.pack, path: document.path, parsed: document.parsed }));
    return this.success(op, scanReferences(documents, {
      kind, pack: stringValue(payload.pack), target: stringValue(payload.target),
    }));
  }

  /** All-or-none: plan every operation on copies, then commit. */
  private bulk(op: string, payload: Record<string, unknown>): Response {
    const pack = stringValue(payload.pack, PACK);
    const operations = Array.isArray(payload.operations) ? payload.operations : [];
    if (operations.length === 0 || operations.length > 50) {
      return this.failure(op, 'MTR-0008', 'a bulk change needs 1 to 50 documents');
    }
    const planned: {
      path: string; kind: string; rev: number; created: boolean;
      before?: Record<string, Record<string, unknown>>; parsed: Record<string, Record<string, unknown>>;
      edits: FieldEdit[]; ref?: ResourceRef;
    }[] = [];
    for (const entry of operations) {
      if (!isRecord(entry)) return this.failure(op, 'MTR-0008', 'operations must be objects');
      const kind = stringValue(entry.kind);
      const edits = entry.edits === undefined ? [] : parseEdits(entry.edits);
      if (!edits) return this.failure(op, 'MTR-0012', 'fixture field edits are malformed');
      let path = '';
      let parsed: Record<string, Record<string, unknown>>;
      let before: Record<string, Record<string, unknown>> | undefined;
      let rev = 1;
      let ref: ResourceRef | undefined;
      if (kind === 'set') {
        path = stringValue(entry.path);
        const document = this.#documents.get(`${pack}/${path}`);
        if (!document) return this.failure(op, 'MTR-0009', `fixture document not found: ${path}`);
        if (numberValue(entry.rev) !== document.rev) {
          return this.failure(op, 'MTR-0034', `${path}: document revision no longer matches`);
        }
        before = document.parsed;
        parsed = structuredClone(document.parsed);
        rev = document.rev + 1;
      } else if (kind === 'duplicate' || kind === 'create') {
        const id = stringValue(entry.id);
        if (!/^[a-z0-9_-]{2,64}$/.test(id)) return this.failure(op, 'MTR-0008', 'bad resource id');
        const type = kind === 'create'
          ? stringValue(entry.type, 'items')
          : stringValue(entry.fromPath).split('/')[0] ?? 'items';
        path = `${type}/${id}.yml`;
        if (this.#documents.has(`${pack}/${path}`)) {
          return this.failure(op, 'MTR-0008', `document already exists: ${path}`);
        }
        if (kind === 'duplicate') {
          const source = this.#documents.get(`${pack}/${stringValue(entry.fromPath)}`);
          const sourceId = source ? Object.keys(source.parsed)[0] : undefined;
          if (!source || !sourceId) return this.failure(op, 'MTR-0009', 'fixture duplicate source not found');
          parsed = { [id]: structuredClone(source.parsed[sourceId]!) };
        } else {
          parsed = { [id]: type === 'recipes' ? { type: 'shaped', result: id } : { display_name: id } };
        }
        ref = { pack, type, path, id };
      } else {
        return this.failure(op, 'MTR-0008', 'operation kind must be set, create or duplicate');
      }
      if (planned.some((other) => other.path === path)) {
        return this.failure(op, 'MTR-0008', `the same document appears twice: ${path}`);
      }
      for (const edit of edits) {
        const root = edit.field[0];
        if (!root || !parsed[root]) return this.failure(op, 'MTR-0009', `fixture resource root not found in ${path}`);
        if (edit.remove) delete parsed[root][edit.field[1]!];
        else if (!assignPath(parsed[root], edit.field.slice(1), edit.value)) {
          return this.failure(op, 'MTR-0012', 'fixture field path is malformed');
        }
      }
      planned.push({
        path, kind, rev, created: kind !== 'set', parsed, edits, ...(before ? { before } : {}),
        ...(ref ? { ref } : {}),
      });
    }
    const unchanged = (entry: (typeof planned)[number]) =>
      !entry.created && JSON.stringify(entry.parsed) === JSON.stringify(entry.before);
    const apply = payload.dryRun !== true && planned.some((entry) => !unchanged(entry));
    const valueAtPath = (tree: Record<string, Record<string, unknown>> | undefined, field: string[]): unknown => {
      let node: unknown = tree;
      for (const part of field) {
        if (!isRecord(node)) return null;
        node = node[part];
      }
      return node ?? null;
    };
    const documents = planned.map((entry) => {
      const same = unchanged(entry);
      const changes = same ? [] : entry.edits
        .map((edit) => ({
          field: edit.field,
          before: valueAtPath(entry.before, edit.field),
          after: valueAtPath(entry.parsed, edit.field),
        }))
        .filter((change) => JSON.stringify(change.before) !== JSON.stringify(change.after));
      if (apply && !same) {
        const key = `${pack}/${entry.path}`;
        const current = this.#documents.get(key);
        if (current) {
          current.parsed = entry.parsed;
          current.rev = entry.rev;
          current.raw = serializeFixtureYaml(entry.parsed);
        } else {
          this.#documents.set(key, makeDocument(entry.path, 1, entry.parsed));
          if (entry.ref) this.#resources.push(entry.ref);
        }
      }
      return { path: entry.path, kind: entry.kind, rev: same ? entry.rev - 1 : entry.rev, unchanged: same, changes };
    });
    return this.success(op, { applied: apply, documents });
  }

  private saveModel(op: string, payload: Record<string, unknown>): Response {
    const assets = Array.isArray(payload.assets) ? payload.assets : [];
    const stagedAssets: { pack: string; path: string; data: string }[] = [];
    for (const asset of assets) {
      if (!isRecord(asset)) return this.failure(op, 'MTR-0012', 'fixture model asset is malformed');
      const path = stringValue(asset.path);
      const data = stringValue(asset.data);
      if (!path) return this.failure(op, 'MTR-0012', 'fixture model asset path is missing');
      stagedAssets.push({ pack: stringValue(payload.pack, PACK), path, data });
    }

    const ownerPath = stringValue(payload.ownerPath);
    const document = ownerPath
      ? this.#documents.get(`${stringValue(payload.pack, PACK)}/${ownerPath}`)
      : undefined;
    if (ownerPath && !document) return this.failure(op, 'MTR-0009', 'fixture model owner not found');
    if (document && numberValue(payload.rev, document.rev) !== document.rev) {
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }

    const field = stringArray(payload.field) ? payload.field : [];
    const modelRef = stringValue(payload.modelRef);
    let updatedDocument: Record<string, Record<string, unknown>> | null = null;
    if (document && field.length > 1) {
      const root = field[0];
      if (!root || !document.parsed[root]) {
        return this.failure(op, 'MTR-0009', 'fixture model resource root not found');
      }
      updatedDocument = structuredClone(document.parsed);
      if (!assignPath(updatedDocument[root]!, field.slice(1), modelRef)) {
        return this.failure(op, 'MTR-0012', 'fixture model field path is malformed');
      }
    }

    for (const asset of stagedAssets) this.storeAsset(asset.pack, asset.path, asset.data);
    if (!document) return this.success(op, { rev: 0 });

    if (updatedDocument) {
      document.parsed = updatedDocument;
      document.rev += 1;
      document.raw = serializeFixtureYaml(document.parsed);
    }
    return this.success(op, { rev: document.rev });
  }

  private listAssets(op: string, payload: Record<string, unknown>): Response {
    const pack = stringValue(payload.pack);
    const assets: AssetRef[] = [...this.#assets.values()]
      .filter((asset) => this.#assets.has(assetPath(pack, asset.path)))
      .map((asset) => ({ path: asset.path, kind: asset.kind, bytes: asset.bytes }));
    return this.success(op, { assets });
  }

  private getAsset(op: string, payload: Record<string, unknown>): Response {
    const asset = this.#assets.get(
      assetPath(stringValue(payload.pack), stringValue(payload.path)),
    );
    if (!asset) return this.failure(op, 'MTR-0009', 'fixture asset not found');
    return this.success(op, { path: asset.path, data: asset.data, bytes: asset.bytes });
  }

  private putAsset(op: string, payload: Record<string, unknown>): Response {
    const pack = stringValue(payload.pack);
    const path = stringValue(payload.path);
    if (!pack || !path) return this.failure(op, 'MTR-0012', 'fixture asset path is missing');
    const asset = this.storeAsset(pack, path, stringValue(payload.data));
    return this.success(op, { path: asset.path, bytes: asset.bytes });
  }

  private deleteAsset(op: string, payload: Record<string, unknown>): Response {
    const key = assetPath(stringValue(payload.pack), stringValue(payload.path));
    if (!this.#assets.delete(key)) return this.failure(op, 'MTR-0009', 'fixture asset not found');
    return this.success(op, { deleted: true });
  }

  private moveAsset(op: string, payload: Record<string, unknown>): Response {
    const pack = stringValue(payload.pack);
    const from = stringValue(payload.from);
    const to = stringValue(payload.to);
    const oldKey = assetPath(pack, from);
    const asset = this.#assets.get(oldKey);
    if (!asset) return this.failure(op, 'MTR-0009', 'fixture asset not found');
    this.#assets.delete(oldKey);
    this.#assets.set(assetPath(pack, to), { ...asset, path: to });
    return this.success(op, { from, to });
  }

  private publish(op: string, payload: Record<string, unknown>): Response {
    const expected = stringValue(payload.expectedCandidateSha256);
    if (!expected || expected !== this.#candidateSha256) {
      return this.failure(op, 'MTR-0042', 'candidate hash changed; refresh status before publishing');
    }
    this.#publishedSha256 = expected;
    this.#candidateSha256 = '';
    return this.success(op, { publishedSha256: this.#publishedSha256 });
  }

  private findDocument(payload: Record<string, unknown>): FixtureDocument | undefined {
    return this.#documents.get(
      `${stringValue(payload.pack)}/${stringValue(payload.path)}`,
    );
  }

  private storeAsset(pack: string, path: string, data: string): FixtureAsset {
    const asset: FixtureAsset = {
      path,
      kind: path.split('.').pop() ?? 'bin',
      data,
      bytes: Math.floor((data.length * 3) / 4),
    };
    this.#assets.set(assetPath(pack, path), asset);
    return asset;
  }
}
