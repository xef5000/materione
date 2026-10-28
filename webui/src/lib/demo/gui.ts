/**
 * The sample GUI of the in-tab servers (demo and browser fixture): a shop screen
 * with a paint layer, an image, text, a button and two items, so the studio has
 * something to open without a server. The `gui` type, its `gui-components`
 * registry and the New GUI starter all come from the recorded snapshot (the
 * server's own, issue 183); this adds only the sample, and only to a snapshot
 * that has the type.
 */
import { parse } from 'yaml';
import { formatColor } from '../gui/raster/color';
import type { DemoSnapshot, SnapshotAsset } from './snapshot';

const hash = String.fromCharCode(35);

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

/** Adds the sample shop screen (and its two textures) to a snapshot that carries the gui type (in place). */
export function withGui(snapshot: DemoSnapshot): DemoSnapshot {
  if (!snapshot.types.some((type) => type.directory === 'guis')) return snapshot;
  const pack = snapshot.packs.find((candidate) => candidate.name === 'sample') ?? snapshot.packs[0];
  if (!pack) return snapshot;
  const raw = sampleGuiRaw();
  const path = 'guis/shop/main.yml';
  if (pack.documents[path]) return snapshot;
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
