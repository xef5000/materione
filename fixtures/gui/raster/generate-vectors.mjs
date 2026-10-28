// Generates the shared GUI raster vectors: `<name>.json` (input) and `<name>.png`
// (expected output) for every vector below. Run from the repo root:
//
//   node fixtures/gui/raster/generate-vectors.mjs
//
// Regeneration is for review: the committed bytes are the fixture, CI never
// regenerates. This script is deliberately an independent, naive "oracle": it
// shares no code with the studio's TypeScript renderer (webui/src/lib/gui/raster)
// or with the Java compiler, and renders from the rules in README.md with
// per-pixel arithmetic. Both implementations are tested against its output, so
// a rule that is wrong in an implementation, or ambiguous in the README, shows
// up as a mismatch instead of two implementations agreeing by copying.
import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------- colors

/** `#RRGGBB` or `#AARRGGBB` (alpha first, as the server's component descriptions say) to [r, g, b, a]. */
function parse(text) {
  const m = /^#(?:([0-9a-fA-F]{2}))?([0-9a-fA-F]{6})$/.exec(text);
  if (!m) throw new Error(`bad color ${text}`);
  const n = parseInt(m[2], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, m[1] === undefined ? 255 : parseInt(m[1], 16)];
}

const RAISED = { fill: '#C6C6C6', highlight: '#FFFFFF', shadow: '#555555', outline: '#000000' };
const SLOT = { fill: '#8B8B8B', highlight: '#FFFFFF', shadow: '#373737', outline: '#000000' };

function styleFor(layer, base) {
  const out = {};
  for (const key of ['fill', 'highlight', 'shadow', 'outline']) {
    out[key] = parse(layer.style && layer.style[key] !== undefined ? layer.style[key] : base[key]);
  }
  return out;
}

// ---------------------------------------------------------------- pixel buffers

function blank(w, h) {
  return { w, h, px: Array.from({ length: w * h }, () => [0, 0, 0, 0]) };
}

function put(buf, x, y, c) {
  if (x < 0 || y < 0 || x >= buf.w || y >= buf.h) return;
  buf.px[y * buf.w + x] = blendOver(buf.px[y * buf.w + x], c);
}

/** Straight-alpha source-over with integer round-half-up (README, "Compositing"). */
function blendOver(d, s) {
  const [sr, sg, sb, sa] = s;
  const [dr, dg, db, da] = d;
  if (sa === 0) return d;
  if (sa === 255 || da === 0) return [sr, sg, sb, sa];
  const den = sa * 255 + da * (255 - sa);
  const outA = Math.floor((den + 127) / 255);
  const ch = (sc, dc) => Math.floor((2 * (sc * sa * 255 + dc * da * (255 - sa)) + den) / (2 * den));
  return [ch(sr, dr), ch(sg, dg), ch(sb, db), outA];
}

function withOpacity(c, opacity) {
  const a = Math.floor((c[3] * opacity + 50) / 100);
  return a === 0 ? [0, 0, 0, 0] : [c[0], c[1], c[2], a];
}

// ---------------------------------------------------------------- component painters

function raised(buf, x, y, w, h, style, edge) {
  if (w <= 0 || h <= 0) return;
  // 1. the outline ring, minus the four corner pixels (each ring pixel once)
  const ends = (n) => (n === 1 ? [0] : [0, n - 1]);
  for (let i = 1; i < w - 1; i++) {
    for (const j of ends(h)) put(buf, x + i, y + j, style.outline);
  }
  for (let j = 1; j < h - 1; j++) {
    for (const i of ends(w)) put(buf, x + i, y + j, style.outline);
  }
  // 2. the interior, as bands: highlight on top and left, shadow on bottom and right
  const iw = w - 2;
  const ih = h - 2;
  for (let j = 0; j < ih; j++) {
    for (let i = 0; i < iw; i++) {
      const hl = j < edge || i < edge;
      const sh = j >= ih - edge || i >= iw - edge;
      const color = hl && sh ? style.fill : hl ? style.highlight : sh ? style.shadow : style.fill;
      put(buf, x + 1 + i, y + 1 + j, color);
    }
  }
}

function inset(buf, x, y, w, h, style) {
  for (let j = 0; j < h; j++) {
    for (let i = 0; i < w; i++) {
      const dark = j === 0 || i === 0;
      const light = j === h - 1 || i === w - 1;
      const color = dark && light ? style.fill : dark ? style.shadow : light ? style.highlight : style.fill;
      put(buf, x + i, y + j, color);
    }
  }
}

function cells(rows, region) {
  const out = [];
  if (region === 'container' || region === 'all') {
    for (let r = 0; r < rows; r++) for (let c = 0; c < 9; c++) out.push([8 + c * 18, 18 + r * 18]);
  }
  if (region === 'player' || region === 'all') {
    for (let r = 0; r < 3; r++) for (let c = 0; c < 9; c++) out.push([8 + c * 18, rows * 18 + 31 + r * 18]);
    for (let c = 0; c < 9; c++) out.push([8 + c * 18, rows * 18 + 89]);
  }
  return out;
}

function imagePixels(vector, reference) {
  const image = vector.images?.[reference];
  if (!image) return null;
  return { w: image.width, h: image.height, px: image.pixels.map(parse) };
}

function paintLayer(vector, layer) {
  const buf = blank(vector.canvas.width, vector.canvas.height);
  const g = (key, fallback) => (layer[key] === undefined ? fallback : layer[key]);
  switch (layer.type) {
    case 'panel':
      raised(buf, g('x', 0), g('y', 0), g('width', 0), g('height', 0), styleFor(layer, RAISED), 2);
      break;
    case 'button':
      raised(buf, g('x', 0), g('y', 0), g('width', 0), g('height', 0), styleFor(layer, RAISED), 1);
      break;
    case 'slots':
      for (const [cx, cy] of cells(vector.rows, g('region', 'container'))) {
        inset(buf, cx - 1, cy - 1, 18, 18, styleFor(layer, SLOT));
      }
      break;
    case 'image': {
      const image = imagePixels(vector, layer.image);
      if (!image) break;
      const dw = g('width', image.w);
      const dh = g('height', image.h);
      for (let j = 0; j < dh; j++) {
        for (let i = 0; i < dw; i++) {
          const sx = Math.floor(((2 * i + 1) * image.w) / (2 * dw));
          const sy = Math.floor(((2 * j + 1) * image.h) / (2 * dh));
          put(buf, g('x', 0) + i, g('y', 0) + j, image.px[sy * image.w + sx]);
        }
      }
      break;
    }
    case 'paint': {
      const image = imagePixels(vector, layer.image);
      if (!image) break;
      for (let j = 0; j < image.h; j++) for (let i = 0; i < image.w; i++) put(buf, i, j, image.px[j * image.w + i]);
      break;
    }
    default:
      return null; // text, item and anything unknown are not raster components
  }
  return buf;
}

function render(vector) {
  const out = blank(vector.canvas.width, vector.canvas.height);
  for (const layer of vector.layers) {
    if (layer.visible === false) continue;
    const painted = paintLayer(vector, layer);
    if (!painted) continue;
    const opacity = layer.opacity === undefined ? 100 : layer.opacity;
    painted.px.forEach((c, index) => {
      if (c[3] === 0) return;
      out.px[index] = blendOver(out.px[index], withOpacity(c, opacity));
    });
  }
  return out;
}

// ---------------------------------------------------------------- PNG (RGBA8, no ancillary chunks)

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

function crc32(bytes) {
  let c = 0xffffffff;
  for (const b of bytes) c = CRC_TABLE[(c ^ b) & 255] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'latin1');
  data.copy(out, 8);
  out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length);
  return out;
}

function png(buf) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(buf.w, 0);
  header.writeUInt32BE(buf.h, 4);
  header[8] = 8; // bit depth
  header[9] = 6; // RGBA
  const raw = Buffer.alloc((buf.w * 4 + 1) * buf.h);
  for (let y = 0; y < buf.h; y++) {
    raw[y * (buf.w * 4 + 1)] = 0; // filter: none
    for (let x = 0; x < buf.w; x++) {
      const c = buf.px[y * buf.w + x];
      const o = y * (buf.w * 4 + 1) + 1 + x * 4;
      raw[o] = c[0];
      raw[o + 1] = c[1];
      raw[o + 2] = c[2];
      raw[o + 3] = c[3];
    }
  }
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------- the vectors

/** `count` pixels cycling through `colors`, as a vector image. */
function image(width, height, colors) {
  return {
    width,
    height,
    pixels: Array.from({ length: width * height }, (_, i) => colors[i % colors.length]),
  };
}

const CHEST = (rows) => ({ width: 176, height: 114 + rows * 18 });
const VANILLA_PANEL = (rows) => ({ id: 'frame', type: 'panel', x: 0, y: 0, width: 176, height: 114 + rows * 18 });

const vectors = [
  {
    name: 'button-1px-bevel',
    description: 'Raised 1 px bevel at several sizes: transparent outline corners, highlight top/left, shadow bottom/right, fill at the two off-diagonal corners.',
    canvas: { width: 56, height: 28 },
    layers: [
      { id: 'a', type: 'button', x: 1, y: 1, width: 10, height: 6 },
      { id: 'b', type: 'button', x: 14, y: 1, width: 20, height: 12 },
      { id: 'c', type: 'button', x: 38, y: 1, width: 5, height: 5 },
      { id: 'd', type: 'button', x: 1, y: 14, width: 3, height: 3 },
      { id: 'e', type: 'button', x: 8, y: 14, width: 2, height: 2 },
      { id: 'f', type: 'button', x: 14, y: 16, width: 40, height: 10 },
    ],
  },
  {
    name: 'panel-2px-bevel',
    description: 'Raised 2 px bevel (a panel): the 2x2 off-diagonal corner blocks are fill; small panels where the bands meet.',
    canvas: { width: 56, height: 36 },
    layers: [
      { id: 'big', type: 'panel', x: 2, y: 2, width: 30, height: 24 },
      { id: 'small', type: 'panel', x: 36, y: 2, width: 8, height: 8 },
      { id: 'tiny', type: 'panel', x: 46, y: 2, width: 6, height: 6 },
      { id: 'thin', type: 'panel', x: 36, y: 14, width: 16, height: 5 },
    ],
  },
  {
    name: 'button-custom-style',
    description: 'A button with a full custom style, and one with a partial style (the missing fields fall back to the raised defaults).',
    canvas: { width: 64, height: 24 },
    layers: [
      {
        id: 'full', type: 'button', x: 2, y: 2, width: 28, height: 18,
        style: { fill: '#3C8527', highlight: '#7FD158', shadow: '#1D4A12', outline: '#102308' },
      },
      { id: 'partial', type: 'button', x: 34, y: 2, width: 28, height: 18, style: { fill: '#8B4A2B', outline: '#2B1408' } },
    ],
  },
  {
    name: 'slots-container-1row',
    description: 'Inset 18x18 slot wells for the container region of a 1-row chest (wells drawn at item origin - 1).',
    canvas: CHEST(1),
    rows: 1,
    layers: [{ id: 'wells', type: 'slots', region: 'container' }],
  },
  {
    name: 'slots-player-3rows',
    description: 'The player inventory region of a 3-row chest: 27 slots from y = rows*18+31, then the hotbar at y = rows*18+89.',
    canvas: CHEST(3),
    rows: 3,
    layers: [{ id: 'wells', type: 'slots', region: 'player' }],
  },
  {
    name: 'slots-custom-style',
    description: 'Slot wells with a partial style: shadow and fill overridden, highlight left at the slot default.',
    canvas: CHEST(2),
    rows: 2,
    layers: [{ id: 'wells', type: 'slots', region: 'all', style: { fill: '#4A5568', shadow: '#1A202C' } }],
  },
  {
    name: 'chest-6-rows',
    description: 'The vanilla look of a new GUI: a panel plus an all-region slots layer on a 176x222 canvas.',
    canvas: CHEST(6),
    rows: 6,
    layers: [VANILLA_PANEL(6), { id: 'wells', type: 'slots', region: 'all' }],
  },
  {
    name: 'chest-1-row',
    description: 'The same starter on the smallest chest, 176x132.',
    canvas: CHEST(1),
    rows: 1,
    layers: [VANILLA_PANEL(1), { id: 'wells', type: 'slots', region: 'all' }],
  },
  {
    name: 'chest-with-button',
    description: 'A starter chest with a button on top: later layers cover earlier ones, bevel pixels included.',
    canvas: CHEST(3),
    rows: 3,
    layers: [
      VANILLA_PANEL(3),
      { id: 'wells', type: 'slots', region: 'all' },
      { id: 'buy', type: 'button', x: 115, y: 24, width: 52, height: 20 },
      { id: 'inner', type: 'panel', x: 60, y: 60, width: 40, height: 24, opacity: 70 },
    ],
  },
  {
    name: 'blend-translucent',
    description: 'Translucent fills over transparent, translucent and opaque backgrounds (the full straight-alpha source-over formula).',
    canvas: { width: 48, height: 24 },
    layers: [
      { id: 'base', type: 'panel', x: 0, y: 0, width: 30, height: 24, style: { fill: '#C0FF0000', highlight: '#8000FF00', shadow: '#400000FF', outline: '#20FFFFFF' } },
      { id: 'over', type: 'panel', x: 12, y: 4, width: 30, height: 16, style: { fill: '#993366CC', highlight: '#33FFFF00', shadow: '#99000000', outline: '#80808080' } },
      { id: 'cover', type: 'button', x: 36, y: 0, width: 12, height: 24, style: { fill: '#01FFFFFF', highlight: '#02FFFFFF', shadow: '#03000000', outline: '#04000000' } },
    ],
  },
  {
    name: 'opacity-rounding',
    description: 'Layer opacity scales source alpha with round half up: opacities 0, 1, 33, 49, 50, 67, 99, 100 over an opaque panel, then a translucent fill at 50 and 51.',
    canvas: { width: 80, height: 32 },
    layers: [
      { id: 'floor', type: 'panel', x: 0, y: 0, width: 80, height: 32, style: { fill: '#202020', highlight: '#202020', shadow: '#202020', outline: '#202020' } },
      ...[0, 1, 33, 49, 50, 67, 99, 100].map((opacity, i) => ({
        id: `o${opacity}`, type: 'button', x: 1 + i * 9, y: 1, width: 8, height: 8, opacity,
        style: { fill: '#F0A030', highlight: '#F0A030', shadow: '#F0A030', outline: '#F0A030' },
      })),
      { id: 'half', type: 'button', x: 1, y: 12, width: 8, height: 8, opacity: 50, style: { fill: '#03FFFFFF', highlight: '#03FFFFFF', shadow: '#03FFFFFF', outline: '#03FFFFFF' } },
      { id: 'tiny', type: 'button', x: 11, y: 12, width: 8, height: 8, opacity: 49, style: { fill: '#01FFFFFF', highlight: '#01FFFFFF', shadow: '#01FFFFFF', outline: '#01FFFFFF' } },
      { id: 'tiny2', type: 'button', x: 21, y: 12, width: 8, height: 8, opacity: 50, style: { fill: '#01FFFFFF', highlight: '#01FFFFFF', shadow: '#01FFFFFF', outline: '#01FFFFFF' } },
    ],
  },
  {
    name: 'image-nearest-neighbour',
    description: 'Image blits: exact 4x and 3x upscales, a non-integer 7x5 stretch of a 3x3, a 3x3 downscale of a 4x4, a 1x1 stretch, translucent texels, and a natural-size image (no width/height).',
    canvas: { width: 48, height: 32 },
    images: {
      'gui/test/checker': image(2, 2, ['#FF0000', '#00FF00', '#0000FF', '#FFFFFF']),
      'gui/test/grad3': image(3, 3, ['#101010', '#404040', '#707070', '#A0A0A0', '#D0D0D0', '#FFFFFF', '#FF0000', '#00FF00', '#0000FF']),
      'gui/test/grad4': {
        width: 4, height: 4,
        pixels: ['#FF0000', '#FF8000', '#FFFF00', '#80FF00', '#00FF00', '#00FF80', '#00FFFF', '#0080FF', '#0000FF', '#8000FF', '#FF00FF', '#FF0080', '#000000', '#555555', '#AAAAAA', '#FFFFFF'],
      },
      'gui/test/dot': image(1, 1, ['#FFAA00']),
      'gui/test/glass': image(2, 2, ['#80FF0000', '#00000000', '#400000FF', '#FFFFFFFF']),
    },
    layers: [
      { id: 'x4', type: 'image', image: 'gui/test/checker', x: 0, y: 0, width: 8, height: 8 },
      { id: 'x3', type: 'image', image: 'gui/test/checker', x: 10, y: 0, width: 6, height: 6 },
      { id: 'stretch', type: 'image', image: 'gui/test/grad3', x: 18, y: 0, width: 7, height: 5 },
      { id: 'down', type: 'image', image: 'gui/test/grad4', x: 28, y: 0, width: 3, height: 3 },
      { id: 'dot', type: 'image', image: 'gui/test/dot', x: 34, y: 0, width: 4, height: 2 },
      { id: 'glass', type: 'image', image: 'gui/test/glass', x: 0, y: 12, width: 8, height: 8 },
      { id: 'natural', type: 'image', image: 'gui/test/grad4', x: 12, y: 12 },
      { id: 'blur', type: 'image', image: 'gui/test/glass', x: 20, y: 12, width: 8, height: 8, opacity: 50 },
      { id: 'missing', type: 'image', image: 'gui/test/not_there', x: 30, y: 12, width: 8, height: 8 },
    ],
  },
  {
    name: 'paint-layer',
    description: 'A paint layer is copied 1:1 at the origin (a bitmap smaller than the canvas leaves the rest untouched), then composited with its opacity.',
    canvas: { width: 32, height: 24 },
    images: {
      'gui/test/art': {
        width: 4, height: 3,
        pixels: ['#FF0000', '#80FF0000', '#00000000', '#FFFFFF', '#00FF00', '#4000FF00', '#0000FF', '#010000FF', '#FFFF00', '#C0FFFF00', '#FF00FF', '#00FFFF'],
      },
    },
    layers: [
      { id: 'floor', type: 'panel', x: 0, y: 0, width: 32, height: 24 },
      { id: 'art', type: 'paint', image: 'gui/test/art' },
      { id: 'ghost', type: 'paint', image: 'gui/test/art', opacity: 50 },
    ],
  },
  {
    name: 'clipping',
    description: 'Layers partly or entirely outside the canvas are clipped to it; a layer left of or above the origin keeps its bevel only where it overlaps.',
    canvas: { width: 24, height: 16 },
    images: { 'gui/test/checker': image(2, 2, ['#FF0000', '#00FF00', '#0000FF', '#FFFFFF']) },
    layers: [
      { id: 'left', type: 'panel', x: -5, y: -3, width: 14, height: 10 },
      { id: 'right', type: 'button', x: 18, y: 10, width: 14, height: 10 },
      { id: 'img', type: 'image', image: 'gui/test/checker', x: 10, y: -4, width: 8, height: 8 },
      { id: 'away', type: 'panel', x: 40, y: 40, width: 10, height: 10 },
      { id: 'empty', type: 'button', x: 3, y: 3, width: 0, height: 5 },
    ],
  },
  {
    name: 'visibility-and-targets',
    description: 'Hidden layers are skipped; text, item and unknown components are not raster components and draw nothing.',
    canvas: { width: 40, height: 20 },
    layers: [
      { id: 'shown', type: 'button', x: 1, y: 1, width: 12, height: 8 },
      { id: 'hidden', type: 'button', x: 15, y: 1, width: 12, height: 8, visible: false },
      { id: 'label', type: 'text', x: 2, y: 12, text: 'Shop', font: 'smallcaps', color: '#404040' },
      { id: 'sword', type: 'item', slot: 3, item: 'minecraft:diamond_sword', amount: 1 },
      { id: 'future', type: 'addon:progress', x: 20, y: 12, width: 10, height: 4 },
    ],
  },
];

for (const vector of vectors) {
  const dir = here;
  mkdirSync(dir, { recursive: true });
  const rendered = render(vector);
  writeFileSync(join(dir, `${vector.name}.json`), JSON.stringify(vector, null, 2) + '\n');
  writeFileSync(join(dir, `${vector.name}.png`), png(rendered));
  const painted = rendered.px.filter((c) => c[3] !== 0).length;
  console.log(`${vector.name}: ${rendered.w}x${rendered.h}, ${painted} painted pixels`);
}
