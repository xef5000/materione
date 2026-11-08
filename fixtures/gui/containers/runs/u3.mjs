// U2, U3, U4 (gui.md 12.12), part 2, on the live client: the screen against the vanilla sheet, tiles across the
// screen edges, and draw order (art under slot items and under the Inventory label). 1920x1080, scales 1..3.
import fs from 'node:fs';
import { exec, rcon, sleep, setWindow, open, shot, decodePng, analyzeBig, SCRATCH } from './lib.mjs';

rcon('gamemode creative Probe');
rcon('clear Probe');
const SLOTS = {
  hopper: (i) => [44 + 18 * i, 20],
  dispenser: (i) => [62 + 18 * (i % 3), 17 + 18 * Math.trunc(i / 3)],
};
const COUNT = { hopper: 5, dispenser: 9 };
const label = (s) => (gx, gy) => gx >= 6 && gx < 70 && gy >= s.inventoryLabelY - 1 && gy < s.inventoryLabelY + 10;

for (const scale of [1, 2, 3]) {
  await setWindow(1920, 1080, scale);
  console.log(`\n=== 1920x1080, scale ${scale} ===`);
  for (const [kind, big, cover, trail] of [['hopper', 'bh', 'ch', false], ['dispenser', 'bdt', 'cdt', true]]) {
    // (a) no art at all: the live screen is the sheet, blitted at (leftPos, topPos), pixel for pixel
    let s = await open(kind, '.', { empty: true });
    let f = await shot(`u3_${kind}_plain_s${scale}`);
    const sheet = decodePng(`${SCRATCH}/vanilla/${kind}.png`);
    const img = decodePng(f);
    // the title is the one dot: at x=8 on the hopper, centred (x about 87) on the dispenser
    const skip = (gx, gy) => (gx >= 6 && gx < 96 && gy >= 4 && gy < 16) || label(s)(gx, gy);
    let match = 0, mismatch = 0; const bad = [];
    for (let Y = 0; Y < s.imageHeight; Y++) for (let X = 0; X < s.imageWidth; X++) {
      if (skip(X, Y) || sheet.rgba[(Y * sheet.w + X) * 4 + 3] < 255) continue; // the sheet's transparent corner pixels show the world
      for (let j = 0; j < scale; j++) for (let i = 0; i < scale; i++) {
        const px = (s.leftPos + X) * scale + i, py = (s.topPos + Y) * scale + j;
        const k = (py * img.w + px) * 4, m = (Y * sheet.w + X) * 4;
        if (img.rgba[k] === sheet.rgba[m] && img.rgba[k + 1] === sheet.rgba[m + 1] && img.rgba[k + 2] === sheet.rgba[m + 2]) match++;
        else { mismatch++; if (bad.length < 4) bad.push(`sheet(${X},${Y}) got ${[img.rgba[k], img.rgba[k + 1], img.rgba[k + 2]]} want ${[sheet.rgba[m], sheet.rgba[m + 1], sheet.rgba[m + 2]]}`); }
      }
    }
    console.log(`${kind} plain, ${s.screen}: ${s.imageWidth}x${s.imageHeight} vs ${kind}.png rows 0..${s.imageHeight - 1}: match ${match}, mismatch ${mismatch}${bad.length ? ' e.g. ' + bad.join('; ') : ''}`);

    // (b) tiles across the screen edges: a 300 x 230 position-coded image at (-60, -40), in two 256-px tiles
    s = await open(kind, big, { empty: true });
    f = await shot(`u3_${kind}_tiles_s${scale}`);
    const t = analyzeBig(f, s, 300, 230, -60, -40, label(s));
    console.log(`${kind} tiles (${big}): titleLabelX=${s.titleLabelX} match ${t.match}, mismatch ${t.mismatch}, outside the window ${t.offWindow}, label px skipped ${t.skipped}${t.bad.length ? ' e.g. ' + t.bad.join('; ') : ''}`);

    // (c) draw order: a full-screen art over every slot, with items in the slots
    s = await open(kind, cover, {});
    f = await shot(`u3_${kind}_order_s${scale}`);
    const o = decodePng(f);
    const W = s.imageWidth, H = s.imageHeight;
    const art = (X, Y) => (X % 32 === 0 || Y % 32 === 0) ? [0, 0, 0] : [Math.floor(X * 255 / (W - 1)), Math.floor(Y * 255 / (H - 1)), ((X ^ Y) & 1) ? 200 : 40];
    const at = (gx, gy, i, j) => { const k = (((s.topPos + gy) * scale + j) * o.w + (s.leftPos + gx) * scale + i) * 4; return [o.rgba[k], o.rgba[k + 1], o.rgba[k + 2]]; };
    const same = (a, b) => a[0] === b[0] && a[1] === b[1] && a[2] === b[2];
    const itemPx = [];
    for (let n = 0; n < COUNT[kind]; n++) {
      const [x, y] = SLOTS[kind](n); let diff = 0;
      for (let Y = 0; Y < 16; Y++) for (let X = 0; X < 16; X++) if (!same(at(x + X, y + Y, 0, 0), art(x + X, y + Y))) diff++;
      itemPx.push(diff);
    }
    let labelPx = 0;
    for (let Y = s.inventoryLabelY; Y < s.inventoryLabelY + 8; Y++) for (let X = 8; X < 64; X++) if (same(at(X, Y, 0, 0), [64, 64, 64])) labelPx++;
    let artKept = 0, artTotal = 0;
    for (let Y = 20; Y < 30; Y++) for (let X = 150; X < 170; X++) { artTotal++; if (same(at(X, Y, 0, 0), art(X, Y))) artKept++; }
    // (d) a lone marker over some slots (no full-screen art): the items still draw above it
    const lone = kind === 'hopper' ? 'mht' : 'mdt', onY = kind === 'hopper' ? 20 : 17;
    s = await open(kind, lone, {});
    f = await shot(`u3_${kind}_lone_s${scale}`);
    const l = decodePng(f), fill = [230, 60, 230], over = [];
    for (let n = 0; n < COUNT[kind]; n++) {
      const [x, y] = SLOTS[kind](n); let inBox = 0, notFill = 0;
      for (let Y = 0; Y < 16; Y++) for (let X = 0; X < 16; X++) {
        const gx = x + X, gy = y + Y;
        if (gx < 74 || gx >= 102 || gy < onY + 2 || gy >= onY + 30) continue; // the marker's fill box (32 px, 2-px border)
        inBox++;
        const k = (((s.topPos + gy) * scale) * l.w + (s.leftPos + gx) * scale) * 4;
        if (!(l.rgba[k] === fill[0] && l.rgba[k + 1] === fill[1] && l.rgba[k + 2] === fill[2])) notFill++;
      }
      if (inBox) over.push(`slot ${n}: ${notFill}/${inBox}`);
    }
    console.log(`${kind} lone marker (${lone}) over slots, pixels in marker-and-slot overlap that are not the marker's fill (an item drawn above it): ${over.join(', ')}`);
    console.log(`${kind} draw order (${cover}): pixels per slot that are not the art (item drawn over it): [${itemPx.join(', ')}]; "Inventory" label pixels (0x404040) over the art: ${labelPx}; art intact in a slot-free patch: ${artKept}/${artTotal}`);
  }
}
exec(`player.closeContainer(); return 'c'`);
fs.writeFileSync(`${SCRATCH}/u3.done`, 'ok');
