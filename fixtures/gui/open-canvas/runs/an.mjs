import { decodePng, titles } from './lib.mjs';

const colors = { L: [220, 40, 40], R: [40, 200, 60], U: [60, 90, 240], D: [240, 220, 40], ON: [230, 60, 230], LL: [40, 220, 220], RR: [250, 140, 30] };

/** left/top in GUI units (as printed by the screen); s the GUI scale. Checks the fill bbox of each marker. */
export function analyzeC1(png, s, left, top, rows) { return analyzeMarkers(png, s, left, top, rows === 1 ? 'c1r1' : 'c1r6'); }
export function analyzeMarkers(png, s, left, top, id, only) {
  const img = decodePng(png);
  const lines = [];
  const L = left * s, Tp = top * s;
  for (const g of titles()[id].layout) {
    if (only && !only.includes(g.name)) continue;
    const c = g.fill;
    let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1, n = 0;
    for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) {
      const i = (y * img.w + x) * 4;
      if (img.rgba[i] === c[0] && img.rgba[i + 1] === c[1] && img.rgba[i + 2] === c[2]) { n++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    }
    const ex0 = L + s * (g.x + 2), ey0 = Tp + s * (g.y + 2), ex1 = L + s * (g.x + g.artW - 2) - 1, ey1 = Tp + s * (g.y + g.artH - 2) - 1;
    const vis = ex1 >= 0 && ey1 >= 0 && ex0 < img.w && ey0 < img.h;
    const ok = n > 0 && x0 === Math.max(ex0, 0) && y0 === Math.max(ey0, 0) && x1 === Math.min(ex1, img.w - 1) && y1 === Math.min(ey1, img.h - 1);
    lines.push(`${g.name.padEnd(3)} (${String(g.x).padStart(4)},${String(g.y).padStart(4)}) ${n ? `found [${x0},${y0}]-[${x1},${y1}]` : 'absent'} expect [${ex0},${ey0}]-[${ex1},${ey1}] ${vis ? (ok ? 'MATCH' : 'DIFF') : (n ? 'DIFF' : 'outside window, absent')}`);
  }
  return lines;
}

/** c3: compare each on-screen pixel inside the big image with bigPixel. Returns counts. */
export function analyzeC3(png, s, left, top) { return analyzeBig(png, s, left, top, 530, 530, -177, -154); }
export function analyzeBig(png, s, left, top, W, H, x0, y0) {
  const img = decodePng(png);
    const bigPixel = (X, Y) => (X % 32 === 0 || Y % 32 === 0) ? [0, 0, 0] : [Math.floor(X * 255 / (W - 1)), Math.floor(Y * 255 / (H - 1)), ((X ^ Y) & 1) ? 200 : 40];
  let match = 0, mismatch = 0, offWindow = 0, label = 0; const bad = [];
  for (let Y = 0; Y < H; Y++) for (let X = 0; X < W; X++) {
    const e = bigPixel(X, Y);
    const gx = x0 + X, gy = y0 + Y, inLabel = gx >= 8 && gx < 64 && gy >= 127 && gy < 137; // the vanilla Inventory label
    for (let j = 0; j < s; j++) for (let i = 0; i < s; i++) {
      const px = (left + x0 + X) * s + i, py = (top + y0 + Y) * s + j;
      if (px < 0 || py < 0 || px >= img.w || py >= img.h) { offWindow++; continue; }
      const k = (py * img.w + px) * 4;
      if (img.rgba[k] === e[0] && img.rgba[k + 1] === e[1] && img.rgba[k + 2] === e[2]) match++;
      else if (inLabel) label++;
      else { mismatch++; if (bad.length < 8) bad.push(`img(${X},${Y}) screen(${px},${py}) got ${[img.rgba[k], img.rgba[k + 1], img.rgba[k + 2]]} want ${e}`); }
    }
  }
  return { match, mismatch, offWindow, label, bad };
}
