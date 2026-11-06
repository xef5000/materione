// Generator of the #275 research pack (docs/architecture/gui.md section 9.8 onward).
//   node fixtures/gui/open-canvas/gen.mjs <outDir>
// Writes a client resource pack (outDir/pack) and the titles (outDir/titles.json).
// Nothing here is shipped: it is the exact input the probe runs used.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const out = path.resolve(process.argv[2] ?? 'open-canvas-out');
const NS = 'r275';

// ---------------------------------------------------------------- PNG ----
const crcTable = new Int32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c; });
const crc32 = (b) => { let c = -1; for (const x of b) c = crcTable[(c ^ x) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; };
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
export function encodePng(w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4); }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

// ---------------------------------------------------------------- art ----
function paint(w, h, f) {
  const b = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const [r, g, bl, a] = f(x, y); b.set([r, g, bl, a], (y * w + x) * 4); }
  return b;
}
/** Marker: a solid colour, 2 px black border, a 4x4 white corner at the top-left (orientation). */
const marker = (w, h, rgb, padBottom = 0, padRight = 0) => ({
  w: w + padRight, h: h + padBottom, inkW: w, artH: h, fill: rgb,
  rgba: paint(w + padRight, h + padBottom, (x, y) => {
    if (x >= w || y >= h) return [0, 0, 0, 0];
    if (x < 4 && y < 4) return [255, 255, 255, 255];
    if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) return [0, 0, 0, 255];
    return [...rgb, 255];
  }),
});
/** The pixel of the big image at its GLOBAL position, so a gap or an overlap between tiles shows. */
export const bigPixel = (X, Y, W, H) => {
  if (X % 32 === 0 || Y % 32 === 0) return [0, 0, 0, 255];
  return [Math.floor(X * 255 / (W - 1)), Math.floor(Y * 255 / (H - 1)), ((X ^ Y) & 1) ? 200 : 40, 255];
};
const tile = (X0, Y0, tw, th, W, H, padBottom = 0, padRight = 0) => ({
  w: tw + padRight, h: th + padBottom, inkW: tw,
  rgba: paint(tw + padRight, th + padBottom, (x, y) => (x >= tw || y >= th) ? [0, 0, 0, 0] : bigPixel(X0 + x, Y0 + y, W, H)),
});

// -------------------------------------------------------------- shifts ----
// shift font: one space glyph per power of two, both signs (E100.. positive, E140.. negative).
const POW = [1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024];
const posCh = (i) => String.fromCharCode(0xE100 + i);
const negCh = (i) => String.fromCharCode(0xE140 + i);
const shiftFont = { providers: [{ type: 'space', advances: Object.fromEntries([...POW.map((v, i) => [posCh(i), v]), ...POW.map((v, i) => [negCh(i), -v])]) }] };
function shiftChars(n) {
  let s = ''; const sign = n < 0 ? -1 : 1; let m = Math.abs(n);
  for (let i = POW.length - 1; i >= 0; i--) while (m >= POW[i]) { s += sign > 0 ? posCh(i) : negCh(i); m -= POW[i]; }
  return s;
}

// -------------------------------------------------------------- cases ----
const textures = {};  // file under assets/r275/textures/font -> png buffer
const fonts = {};     // name -> font json
const titles = {};    // case -> { components, layout }

/**
 * glyphs = [{name, img, x, y, ascent?, height?}]: x/y are the GUI px of the art's top-left.
 * Default ascent = 13 - y, default height = the bitmap's own height. The pen starts at 8 (titleLabelX);
 * a glyph is drawn at the pen, then the pen advances by ink width + 1.
 */
function buildCase(id, glyphs, textRuns = [], merge = false) {
  const comps = [];
  const layout = [];
  let pen = 8, cp = 0xE000;
  for (const g of glyphs) {
    const ch = String.fromCharCode(cp++);
    const file = `${id}_${g.name.toLowerCase()}`;
    textures[`${file}.png`] = encodePng(g.img.w, g.img.h, g.img.rgba);
    const ascent = g.ascent ?? (13 - g.y);
    const height = g.height ?? g.img.h;
    const fid = g.font ? `${id}_${g.font}` : id;           // a glyph may live in a font of its own
    (fonts[fid] ??= { providers: [] }).providers.push({ type: 'bitmap', file: `${NS}:font/${file}.png`, ascent, height, chars: [ch] });
    const shift = g.x - pen;
    comps.push({ text: shiftChars(shift), font: `${NS}:shift` });
    comps.push({ text: ch, font: `${NS}:${fid}`, color: '#FFFFFF' });
    const adv = g.img.inkW + 1;       // actualWidth + 1 at scale 1
    layout.push({ name: g.name, cp: ch.charCodeAt(0).toString(16), x: g.x, y: g.y, ascent, height, w: g.img.w, h: g.img.h, advance: adv, shift, artW: g.img.inkW, artH: g.img.artH, fill: g.img.fill });
    pen = g.x + adv;
  }
  for (const r of textRuns) {
    const fid = `${id}_t${r.y}`;
    comps.push({ text: shiftChars(r.x - pen), font: `${NS}:shift` });
    comps.push({ text: r.text, font: `${NS}:${fid}`, color: r.color ?? '#202020' });
    fonts[fid] = textFont(13 - r.y);
    pen = r.x + r.advance;
  }
  let components = comps.filter((c) => c.text !== '');
  if (merge) {
    // one font holds the shift characters and the glyphs: the title is a single run (a shorter command for the console)
    fonts[id].providers.unshift(shiftFont.providers[0]);
    components = [{ text: components.map((c) => c.text).join(''), font: `${NS}:${id}`, color: '#FFFFFF' }];
  }
  titles[id] = { components, layout };
}
function textFont(ascent) {
  const rows = [];
  for (let r = 0; r < 16; r++) {
    let s = '';
    for (let c = 0; c < 16; c++) { const code = r * 16 + c; s += code >= 0x20 && code <= 0x7e ? String.fromCharCode(code) : '\u0000'; }
    rows.push(s);
  }
  return { providers: [{ type: 'space', advances: { ' ': 4 } }, { type: 'bitmap', file: 'minecraft:font/ascii.png', ascent, height: 8, chars: rows }] };
}

const M = (rgb, w = 32, h = 32, pb = 0) => marker(w, h, rgb, pb);

// c1: markers around the 6-row (222 high) and 1-row (132 high) screens
for (const [id, H] of [['c1r6', 222], ['c1r1', 132]]) {
  buildCase(id, [
    { name: 'L', img: M([220, 40, 40]), x: -40, y: 50 },
    { name: 'R', img: M([40, 200, 60]), x: 184, y: 50 },
    // above: the art top is 40 rows above the screen, so ascent 53 needs a cell of >= 53 rows (32 transparent rows below)
    { name: 'U', img: M([60, 90, 240], 32, 32, 32), x: 72, y: -40 },
    { name: 'D', img: M([240, 220, 40]), x: 72, y: H + 8 },
    { name: 'ON', img: M([230, 60, 230]), x: 72, y: 20 },
    { name: 'LL', img: M([40, 220, 220]), x: -300, y: 50 },
    { name: 'RR', img: M([250, 140, 30]), x: 476, y: 50 },
  ]);
}

// c2: the ascent <= height rule and its limits. Glyphs that fail to load come last: a missing glyph has its own advance.
buildCase('c2', [
  { name: 'PAD', font: 'pad', img: M([40, 200, 60], 32, 32, 32), x: 40, y: -40 },            // ascent 53, cell 64: padded
  { name: 'OK13', font: 'ok13', img: M([60, 90, 240]), x: 80, y: 0 },                          // ascent 13, cell 32: ordinary
  { name: 'EDGE', font: 'edge', img: M([240, 220, 40], 32, 256), x: 120, y: -243 },           // cell 256, ascent 256: the highest possible
  { name: 'FARL', font: 'farl', img: M([250, 140, 30]), x: -860, y: 300 },
  { name: 'FARR', font: 'farr', img: M([30, 140, 250]), x: 1000, y: 300 },
  { name: 'FARD', font: 'fard', img: M([140, 250, 30]), x: 300, y: 640 },
  { name: 'SCALE', font: 'scale', img: M([40, 220, 220]), x: 200, y: -100, ascent: 113, height: 128 }, // height 128 on a 32 cell: scales x4
  { name: 'VIOL', font: 'viol', img: M([220, 40, 40]), x: 0, y: -40 },                       // ascent 53, height 32: violates
  { name: 'OVER', font: 'over', img: M([230, 60, 230], 32, 32, 225), x: 160, y: -244 },       // cell 257, ascent 257
]);

// c2b: the cheapest padding (ascent == cell height), and the boundary ascent == height without padding
buildCase('c2b', [
  { name: 'MIN', font: 'min', img: M([40, 200, 60], 32, 32, 21), x: 40, y: -40 },            // ascent 53, cell 53: 21 blank rows
  { name: 'EQ13', font: 'eq13', img: M([60, 90, 240], 32, 13), x: 80, y: 0 },                 // ascent 13, cell 13: no padding at all
  { name: 'EQ0', font: 'eq0', img: M([240, 220, 40], 32, 10), x: 120, y: 3 },                 // ascent 10, cell 10: top row 3, art ends at row 13
]);

// c2mix: a valid and a violating provider in the SAME font: what survives?
buildCase('c2mix', [
  { name: 'MIXOK', img: M([40, 200, 60]), x: 60, y: 30 },
  { name: 'MIXBAD', img: M([220, 40, 40]), x: 120, y: -40 },                                  // ascent 53, cell 32
]);

// c2v: what a font that failed to load costs in advance: BAD violates ascent <= height, AFTER follows it
buildCase('c2v', [
  { name: 'BAD', font: 'bad', img: M([220, 40, 40]), x: 0, y: -40 },
  { name: 'AFTER', font: 'after', img: M([60, 90, 240]), x: 80, y: 0 },
  { name: 'BIG', font: 'big', img: M([240, 220, 40], 32, 32, 225), x: 120, y: -50 },   // cell 257 tall, ascent 63: refused by the atlas
  { name: 'AFTER2', font: 'after2', img: M([40, 200, 60]), x: 160, y: 0 },
]);

// c5on: only the small ON marker, over slots that hold items: the title's bounds are that marker alone
buildCase('c5on', [{ name: 'ON', img: M([230, 60, 230]), x: 72, y: 20 }]);

// c6first: the first glyph sits exactly at the pen (x = 8), so the title starts with the glyph itself, not a shift
buildCase('c6first', [{ name: 'FIRST', img: M([230, 60, 230]), x: 8, y: 20 }]);

// c3 and c4: one big image in 256-px tiles, every row with its own ascents, all tiles in one font.
// c3 is 530 x 530 (the right column's PNG has 4 trailing blank columns, the bottom row's has 6 blank rows);
// c4 is the 1024 x 1024 art cap box proposed in gui.md section 11.3: x -424..599, y -243..780.
function bigImage(id, W, H, x0, y0, pad, merge = false) {
  const cuts = (n) => { const c = [0]; while (c[c.length - 1] < n) c.push(Math.min(c[c.length - 1] + 256, n)); return c; };
  const cx = cuts(W), cy = cuts(H), gl = [];
  for (let r = 0; r < cy.length - 1; r++) for (let c = 0; c < cx.length - 1; c++) {
    const tw = cx[c + 1] - cx[c], th = cy[r + 1] - cy[r];
    const last = { r: r === cy.length - 2, c: c === cx.length - 2 };
    gl.push({ name: `t${r}${c}`, img: tile(cx[c], cy[r], tw, th, W, H, pad && last.r ? 6 : 0, pad && last.c ? 4 : 0), x: x0 + cx[c], y: y0 + cy[r] });
  }
  buildCase(id, gl, [], merge);
}
bigImage('c3', 530, 530, -177, -154, true);
bigImage('c4', 1024, 1024, -424, -243, false, true);

// c7: text only, no background glyph at all
buildCase('c7text', [], [{ x: 30, y: 40, text: 'No art here', advance: 60 }]);

const root = path.join(out, 'pack');
fs.rmSync(root, { recursive: true, force: true });
fs.mkdirSync(path.join(root, 'assets', NS, 'font'), { recursive: true });
fs.mkdirSync(path.join(root, 'assets', NS, 'textures', 'font'), { recursive: true });
fs.writeFileSync(path.join(root, 'pack.mcmeta'), JSON.stringify({ pack: { description: 'materione #275 research', min_format: 84, max_format: 84 } }));
fs.writeFileSync(path.join(root, 'assets', NS, 'font', 'shift.json'), JSON.stringify(shiftFont));
for (const [n, f] of Object.entries(fonts)) fs.writeFileSync(path.join(root, 'assets', NS, 'font', `${n}.json`), JSON.stringify(f));
for (const [n, b] of Object.entries(textures)) fs.writeFileSync(path.join(root, 'assets', NS, 'textures', 'font', n), b);
fs.writeFileSync(path.join(out, 'titles.json'), JSON.stringify(titles, null, 1));
console.log(`wrote ${Object.keys(fonts).length} fonts, ${Object.keys(textures).length} textures to ${root}`);

// The same fonts and titles as readable JSON (private-use characters escaped), for the record: <out>/json
const esc = (v) => JSON.stringify(v, null, 1).replace(/[^\x20-\x7e\n]/g, (c) => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'));
const jdir = path.join(out, 'json');
fs.rmSync(jdir, { recursive: true, force: true });
fs.mkdirSync(path.join(jdir, 'font'), { recursive: true });
fs.writeFileSync(path.join(jdir, 'font', 'shift.json'), esc(shiftFont));
for (const [n, f] of Object.entries(fonts)) fs.writeFileSync(path.join(jdir, 'font', `${n}.json`), esc(f));
fs.writeFileSync(path.join(jdir, 'titles.json'), esc(titles));
