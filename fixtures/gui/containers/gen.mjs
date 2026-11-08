// Generator of the #289 research pack (docs/architecture/gui.md section 9.16): hopper and dispenser screens.
//   node fixtures/gui/containers/gen.mjs <outDir>
// Writes a client resource pack (outDir/pack), the server-side title components (outDir/titles/<case>.json,
// to be copied into plugins/MaterioneContainerProbe/) and the layouts (outDir/layouts.json).
// Nothing here is shipped: it is the exact input the probe runs used. Same conventions as
// fixtures/gui/open-canvas/gen.mjs (#275): a glyph at title position x has its top-left at (leftPos + x,
// topPos + 13 - ascent); the advance is ink width + 1; the pen starts at titleLabelX.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const out = path.resolve(process.argv[2] ?? 'containers-out');
const NS = 'r289';

// ---------------------------------------------------------------- PNG ----
const crcTable = new Int32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c; });
const crc32 = (b) => { let c = -1; for (const x of b) c = crcTable[(c ^ x) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; };
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function encodePng(w, h, rgba) {
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
const marker = (w, h, rgb, padBottom = 0) => ({
  w, h: h + padBottom, inkW: w, artH: h, fill: rgb,
  rgba: paint(w, h + padBottom, (x, y) => {
    if (y >= h) return [0, 0, 0, 0];
    if (x < 4 && y < 4) return [255, 255, 255, 255];
    if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) return [0, 0, 0, 255];
    return [...rgb, 255];
  }),
});
/** The pixel of the big image at its GLOBAL position, so a gap or an overlap between tiles shows. */
const bigPixel = (X, Y, W, H) => {
  if (X % 32 === 0 || Y % 32 === 0) return [0, 0, 0, 255];
  return [Math.floor(X * 255 / (W - 1)), Math.floor(Y * 255 / (H - 1)), ((X ^ Y) & 1) ? 200 : 40, 255];
};
const tile = (X0, Y0, tw, th, W, H) => ({
  w: tw, h: th, inkW: tw, artH: th,
  rgba: paint(tw, th, (x, y) => bigPixel(X0 + x, Y0 + y, W, H)),
});

// -------------------------------------------------------------- shifts ----
const POW = [1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024];
const posCh = (i) => String.fromCharCode(0xE100 + i);
const negCh = (i) => String.fromCharCode(0xE140 + i);
const shiftProvider = { type: 'space', advances: Object.fromEntries([...POW.map((v, i) => [posCh(i), v]), ...POW.map((v, i) => [negCh(i), -v])]) };
function shiftChars(n) {
  let s = ''; const sign = n < 0 ? -1 : 1; let m = Math.abs(n);
  for (let i = POW.length - 1; i >= 0; i--) while (m >= POW[i]) { s += sign > 0 ? posCh(i) : negCh(i); m -= POW[i]; }
  return s;
}

// -------------------------------------------------------------- cases ----
const textures = {};
const fonts = {};
const titles = {};   // case -> Adventure JSON (the server-side component)
const layouts = {};  // case -> glyph layout, for the analysis

/**
 * One font per case (shift characters and glyphs together), the title a single run.
 * glyphs = [{name, img, x, y}]: x/y are the GUI px of the art's top-left, relative to the screen.
 * trail: end the title with the shift that puts the pen at 168 (titleX0 + imageWidth - 16), so that the
 * total advance is 160 and a centring screen puts its origin back at 8 (gui.md 12.5.3).
 */
function buildCase(id, glyphs, { trail = false } = {}) {
  const font = { providers: [shiftProvider] };
  let text = '';
  const layout = [];
  let pen = 8, cp = 0xE000;
  for (const g of glyphs) {
    const ch = String.fromCharCode(cp++);
    const file = `${id}_${g.name.toLowerCase()}`;
    textures[`${file}.png`] = encodePng(g.img.w, g.img.h, g.img.rgba);
    const ascent = 13 - g.y;
    const height = g.img.h;
    if (ascent > height) throw new Error(`${id}/${g.name}: ascent ${ascent} > height ${height}`);
    font.providers.push({ type: 'bitmap', file: `${NS}:font/${file}.png`, ascent, height, chars: [ch] });
    const shift = g.x - pen;
    text += shiftChars(shift) + ch;
    const adv = g.img.inkW + 1;
    layout.push({ name: g.name, x: g.x, y: g.y, ascent, height, artW: g.img.inkW, artH: g.img.artH, fill: g.img.fill, advance: adv });
    pen = g.x + adv;
  }
  let trailing = 0;
  if (trail) { trailing = 168 - pen; text += shiftChars(trailing); pen = 168; }
  fonts[id] = font;
  titles[id] = { text, font: `${NS}:${id}`, color: 'white' };
  layouts[id] = { glyphs: layout, trailingShift: trailing, endPen: pen, width: pen - 8 };
}

const M = (rgb, w = 32, h = 32, pb = 0) => marker(w, h, rgb, pb);

/** Markers around a screen of height H: left, right, above (padded), below, on the container, far left/right. */
function around(id, H, onY, opts) {
  buildCase(id, [
    { name: 'L', img: M([220, 40, 40]), x: -40, y: 50 },
    { name: 'R', img: M([40, 200, 60]), x: 184, y: 50 },
    { name: 'U', img: M([60, 90, 240], 32, 32, 32), x: 72, y: -40 },
    { name: 'D', img: M([240, 220, 40]), x: 72, y: H + 8 },
    { name: 'ON', img: M([230, 60, 230]), x: 72, y: onY },
    { name: 'LL', img: M([40, 220, 220]), x: -300, y: 50 },
    { name: 'RR', img: M([250, 140, 30]), x: 476, y: 50 },
  ], opts);
}
around('mh', 133, 20, { trail: false });     // hopper: its title is not centred, no trailing shift needed
around('mht', 133, 20, { trail: true });     // hopper with the trailing shift anyway (it must be harmless)
around('mdt', 166, 17, { trail: true });     // dispenser with the trailing shift: origin back at 8
around('mdn', 166, 17, { trail: false });    // dispenser without it: the screen centres the title

// A single small title, to centre or not: one marker at the origin (the first glyph at the pen).
buildCase('o_t', [{ name: 'O', img: M([230, 60, 230], 16, 16), x: 8, y: 20 }], { trail: true });
buildCase('o_n', [{ name: 'O', img: M([230, 60, 230], 16, 16), x: 8, y: 20 }], { trail: false });

// A position-coded image across the screen edges, cut into 256-px tiles (as the compiler does): 300 x 230 at (-60, -40).
function bigImage(id, W, H, x0, y0, opts) {
  const cuts = (n) => { const c = [0]; while (c[c.length - 1] < n) c.push(Math.min(c[c.length - 1] + 256, n)); return c; };
  const cx = cuts(W), cy = cuts(H), gl = [];
  for (let r = 0; r < cy.length - 1; r++) for (let c = 0; c < cx.length - 1; c++) {
    gl.push({ name: `t${r}${c}`, img: tile(cx[c], cy[r], cx[c + 1] - cx[c], cy[r + 1] - cy[r], W, H), x: x0 + cx[c], y: y0 + cy[r] });
  }
  buildCase(id, gl, opts);
}
bigImage('bh', 300, 230, -60, -40, { trail: false });
bigImage('bdt', 300, 230, -60, -40, { trail: true });

// Draw order: art over the container's slots and over the player-inventory area, the items must stay above it.
buildCase('ch', [{ name: 'COVER', img: tile(0, 0, 176, 133, 176, 133), x: 0, y: 0 }], { trail: false });
buildCase('cdt', [{ name: 'COVER', img: tile(0, 0, 176, 166, 176, 166), x: 0, y: 0 }], { trail: true });

// U5: titles to switch between on an open window (marker colours differ so a screenshot says which is up)
buildCase('s1t', [{ name: 'A', img: M([230, 60, 230], 24, 24), x: 20, y: 30 }], { trail: true });
buildCase('s2t', [{ name: 'B', img: M([40, 200, 60], 40, 24), x: 90, y: 30 }], { trail: true });
buildCase('s3n', [{ name: 'C', img: M([240, 220, 40], 40, 24), x: 90, y: 30 }], { trail: false });

const root = path.join(out, 'pack');
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(path.join(root, 'assets', NS, 'font'), { recursive: true });
fs.mkdirSync(path.join(root, 'assets', NS, 'textures', 'font'), { recursive: true });
fs.mkdirSync(path.join(out, 'titles'), { recursive: true });
fs.writeFileSync(path.join(root, 'pack.mcmeta'), JSON.stringify({ pack: { description: 'materione #289 research', min_format: 84, max_format: 84 } }));
for (const [n, f] of Object.entries(fonts)) fs.writeFileSync(path.join(root, 'assets', NS, 'font', `${n}.json`), JSON.stringify(f));
for (const [n, b] of Object.entries(textures)) fs.writeFileSync(path.join(root, 'assets', NS, 'textures', 'font', n), b);
for (const [n, t] of Object.entries(titles)) fs.writeFileSync(path.join(out, 'titles', `${n}.json`), JSON.stringify(t), 'utf8');
fs.writeFileSync(path.join(out, 'layouts.json'), JSON.stringify(layouts, null, 1));
console.log(`wrote ${Object.keys(fonts).length} fonts, ${Object.keys(textures).length} textures to ${root}`);
