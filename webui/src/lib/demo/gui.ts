/**
 * The GUI content type for the in-tab servers (demo and browser fixture),
 * until the recorded snapshot carries the real one (issue 183 registers the
 * `gui` type and the `gui-components` registry on the server). It mirrors the
 * shapes gui.md sections 2 and 7 describe, so the studio is fully usable
 * without a server; once the snapshot already has a `guis` type this overlay
 * leaves it alone.
 *
 * The component schemas are the registry's self-description: the palette, the
 * inspector and the new-layer defaults in the studio are derived from them and
 * from nothing else.
 */
import { parse } from 'yaml';
import type { TypeInfo, TypeParam } from '../api';
import { formatColor } from '../gui/raster/color';
import { RAISED_DEFAULT, SLOT_DEFAULT } from '../gui/raster/flatten';
import type { GuiComponentInfo } from '../gui/registry';
import type { DemoSnapshot, SnapshotAsset } from './snapshot';

const hash = String.fromCharCode(35);

function int(id: string, label: string, def: string, order: number, extra: Partial<TypeParam> = {}): TypeParam {
  return {
    id, label, order, type: 'integer', required: true, default: def, uiHint: 'number',
    section: 'layout', unit: 'px', description: label, ...extra,
  };
}

function colorField(id: string, label: string, def: number): TypeParam {
  return { id, label, type: 'string', required: false, default: formatColor(def), uiHint: 'color', description: `${label} color` };
}

function styleParam(base: typeof RAISED_DEFAULT, outline: boolean): TypeParam {
  return {
    id: 'style', label: 'Style', type: 'map', required: false, uiHint: 'style', section: 'style', order: 50,
    description: 'Bevel colors; anything left out keeps the vanilla look',
    nested: [
      colorField('fill', 'Fill', base.fill),
      colorField('highlight', 'Highlight', base.highlight),
      colorField('shadow', 'Shadow', base.shadow),
      ...(outline ? [colorField('outline', 'Outline', base.outline)] : []),
    ],
  };
}

export const GUI_COMPONENTS: GuiComponentInfo[] = [
  {
    id: 'panel', displayName: 'Panel', target: 'RASTER', icon: 'square',
    description: 'A raised, bevelled rectangle: the frame of the screen or a card inside it',
    parameters: [
      int('x', 'X', '0', 0), int('y', 'Y', '0', 1),
      int('width', 'Width', '64', 2, { min: 1 }), int('height', 'Height', '40', 3, { min: 1 }),
      styleParam(RAISED_DEFAULT, true),
    ],
  },
  {
    id: 'slots', displayName: 'Slot wells', target: 'RASTER', icon: 'grid-3x3',
    description: 'The inset wells behind the container and inventory slots',
    parameters: [
      {
        id: 'region', label: 'Region', type: 'string', required: true, default: 'all', uiHint: 'select',
        section: 'layout', order: 0, description: 'Which slots get a well',
        allowedValues: ['container', 'player', 'all'],
        options: [
          { value: 'container', label: 'Container' },
          { value: 'player', label: 'Inventory' },
          { value: 'all', label: 'Both' },
        ],
      },
      styleParam(SLOT_DEFAULT, false),
    ],
  },
  {
    id: 'image', displayName: 'Image', target: 'RASTER', icon: 'image',
    description: 'A texture from the pack, scaled with crisp pixels',
    parameters: [
      {
        id: 'image', label: 'Texture', type: 'string', required: true, uiHint: 'texture', section: 'content', order: 0,
        description: 'A texture under assets/textures/',
      },
      int('x', 'X', '0', 1), int('y', 'Y', '0', 2),
      int('width', 'Width', '16', 3, { min: 1 }), int('height', 'Height', '16', 4, { min: 1 }),
    ],
  },
  {
    id: 'paint', displayName: 'Paint', target: 'RASTER', icon: 'pencil',
    description: 'A pixel layer the size of the screen, painted in the studio',
    parameters: [
      {
        id: 'image', label: 'Texture', type: 'string', required: true, uiHint: 'texture', section: 'content', order: 0,
        description: 'Owned by the studio: a canvas-sized PNG',
      },
    ],
  },
  {
    id: 'button', displayName: 'Button', target: 'RASTER', icon: 'rectangle-horizontal',
    description: 'A raised button, one pixel of bevel',
    parameters: [
      int('x', 'X', '0', 0), int('y', 'Y', '0', 1),
      int('width', 'Width', '52', 2, { min: 1 }), int('height', 'Height', '20', 3, { min: 1 }),
      styleParam(RAISED_DEFAULT, true),
    ],
  },
  {
    id: 'text', displayName: 'Text', target: 'TEXT', icon: 'type',
    description: 'Text drawn in the title, positioned to the pixel',
    parameters: [
      int('x', 'X', '8', 0), int('y', 'Y', '6', 1),
      { id: 'text', label: 'Text', type: 'string', required: true, default: 'Text', uiHint: 'text', section: 'content', order: 2, description: 'What it says' },
      {
        id: 'font', label: 'Font', type: 'string', required: false, default: 'smallcaps', uiHint: 'select', section: 'content',
        order: 3, description: 'smallcaps is pixel-exact in the preview', allowedValues: ['smallcaps', 'default'],
        options: [{ value: 'smallcaps', label: 'Small caps' }, { value: 'default', label: 'Default' }],
      },
      colorField('color', 'Color', 0xff404040),
    ],
  },
  {
    id: 'item', displayName: 'Item', target: 'SLOT', icon: 'package',
    description: 'A real item in a container slot',
    parameters: [
      { id: 'slot', label: 'Slot', type: 'integer', required: true, default: '13', uiHint: 'number', section: 'layout', order: 0, min: 0, max: 89, description: 'Menu slot index' },
      { id: 'item', label: 'Item', type: 'string', required: true, default: 'minecraft:diamond', uiHint: 'item', section: 'content', order: 1, description: 'A Materione item or a vanilla material' },
      { id: 'amount', label: 'Amount', type: 'integer', required: false, default: '1', uiHint: 'number', section: 'content', order: 2, min: 1, max: 99, description: 'Stack size' },
    ],
  },
];

export const GUI_TYPE: TypeInfo = {
  name: 'gui',
  directory: 'guis',
  displayName: 'GUI',
  description: 'A container screen designed layer by layer: panels, slot wells, buttons, text and items',
  parameters: [
    { id: 'title', label: 'Title', type: 'string', required: false, uiHint: 'text', section: 'identity', order: 0, description: 'Fallback title (narration, title-less clients)' },
    { id: 'rows', label: 'Rows', type: 'integer', required: true, default: '6', uiHint: 'number', section: 'identity', order: 1, min: 1, max: 6, description: 'Chest rows, 1 to 6' },
  ],
};

/** The starter every new GUI opens with (gui.md section 6): a vanilla chest. */
export const GUI_SCAFFOLD = `# demo_scaffold — created by the Materione editor
demo_scaffold:
  title: Demo Scaffold
  rows: 6
  layers:
    - id: frame
      type: panel
      name: Frame
      x: 0
      y: 0
      width: 176
      height: 222
    - id: wells
      type: slots
      name: Slots
      region: all
`;

export function sampleGuiRaw(): string {
  const green = [formatColor(0xff3c8527), formatColor(0xff7fd158), formatColor(0xff1d4a12), formatColor(0xff102308)];
  return `# main — a shop screen made in the Materione studio
main:
  title: Shop
  rows: 3
  layers:
    - id: frame
      type: panel
      name: Frame
      x: 0
      y: 0
      width: 176
      height: 168
    - id: wells
      type: slots
      name: Slots
      region: all
    - id: art
      type: paint
      name: Header art
      image: gui/shop/main/art
    - id: heading
      type: text
      name: Heading
      x: 8
      y: 6
      text: Shop
      font: smallcaps
      color: "${hash}404040"
    - id: tag
      type: panel
      name: Price tag
      x: 114
      y: 17
      width: 56
      height: 56
    - id: buy
      type: button
      name: Buy button
      x: 118
      y: 50
      width: 48
      height: 18
      style: { fill: "${green[0]}", highlight: "${green[1]}", shadow: "${green[2]}", outline: "${green[3]}" }
    - id: coin
      type: image
      name: Coin
      image: gui/shop/coin
      x: 126
      y: 24
      width: 32
      height: 32
    - id: sword
      type: item
      name: Sword
      slot: 13
      item: minecraft:diamond_sword
      amount: 1
    - id: apple
      type: item
      name: Apple
      slot: 10
      item: minecraft:golden_apple
      amount: 3
`;
}

const COIN_PNG =
  'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAY0lEQVR42mNgoDaYlSTwHx8mSvP/Z1Vw/PWAHhzjNYSQZryG4NIMEydoCC6bcRkAk8NqADabiDYAl1OJMgBfgFHdgBv9YuQbANKM1QB80YVNM860gM0lBDUTMoQozVTJTOQAACrcunqOCgT3AAAAAElFTkSuQmCC';
const ART_PNG =
  'iVBORw0KGgoAAAANSUhEUgAAALAAAACoCAYAAABAMmSoAAAA9ElEQVR42u3asQmAMBRFUQdwBVdwFAdwFNdwIVsHsv5WgqWgQpJ/DrwuXS5oka4DAPhORMxW5tT5zGjFDgELWMAmYAGbgAUsYAQsYAGbgAVsAhawgAVsAhawCVjAJmCPeTzmAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHjv2MbIeoYG4r2W7UxK+zpEK7tf8rUsZ2rd64CXqY+Wdr/kbGdqnE+If2AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB+cAK6C8b0h8ISaQAAAABJRU5ErkJggg==';

function pngBytes(base64: string): number {
  const padding = base64.endsWith('==') ? 2 : base64.endsWith('=') ? 1 : 0;
  return Math.floor((base64.length * 3) / 4) - padding;
}

/** The sample GUI's two textures: a paint layer's canvas and a 16 x 16 coin. */
export const SAMPLE_GUI_ASSETS = [
  { path: 'assets/textures/gui/shop/coin.png', data: COIN_PNG },
  { path: 'assets/textures/gui/shop/main/art.png', data: ART_PNG },
];

function asset(path: string, data: string): SnapshotAsset {
  return { path, kind: 'png', bytes: pngBytes(data), data };
}

/** A new GUI's starter file for `id`, titled `title`. */
export function scaffoldGui(id: string, title: string): string {
  return GUI_SCAFFOLD.replaceAll('demo_scaffold', id).replace('title: Demo Scaffold', `title: ${title}`);
}

/** Adds the GUI type, its component registry, a starter scaffold and one sample GUI to a snapshot (in place). */
export function withGui(snapshot: DemoSnapshot): DemoSnapshot {
  if (snapshot.types.some((type) => type.directory === 'guis')) return snapshot;
  snapshot.types = [...snapshot.types, GUI_TYPE];
  snapshot.describe = {
    ...snapshot.describe,
    types: [...snapshot.describe.types, GUI_TYPE],
    guiComponents: GUI_COMPONENTS,
  };
  snapshot.scaffolds = { ...snapshot.scaffolds, guis: GUI_SCAFFOLD };
  const pack = snapshot.packs.find((candidate) => candidate.name === 'sample') ?? snapshot.packs[0];
  if (!pack) return snapshot;
  const raw = sampleGuiRaw();
  const path = 'guis/shop/main.yml';
  pack.documents[path] = { raw, rev: 1, sha256: 'd'.repeat(64), parsed: parse(raw) as Record<string, unknown> };
  pack.resources.push({ pack: pack.name, type: 'guis', path, id: 'main' });
  pack.summaries.push({
    pack: pack.name, type: 'guis', path, id: 'main', name: 'Shop',
    health: 'unknown', healthMessage: 'pipeline validation and apply freshness are unknown',
  });
  pack.folders = [...new Set([...pack.folders, 'guis', 'guis/shop'])].sort();
  pack.assets = [
    ...pack.assets,
    ...SAMPLE_GUI_ASSETS.map((entry) => asset(entry.path, entry.data)),
  ].sort((a, b) => a.path.localeCompare(b.path));
  return snapshot;
}
