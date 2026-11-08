// Helpers of the #289 probe runs (hopper and dispenser). Drivers call the probe CLI; nothing here is shipped.
import { execFileSync, execFile } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

export const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..').split(path.sep).join('/');
export const CLIENT = `${REPO}/harness/build/probe/client`;
export const SERVER = `${REPO}/harness/build/probe/server`;
export const SCRATCH = process.env.MTR_R289_OUT ?? `${REPO}/harness/build/probe/r289`;
export const layouts = () => JSON.parse(fs.readFileSync(`${SCRATCH}/layouts.json`, 'utf8'));
export const LOG = `${SERVER}/logs/latest.log`;

function probe(...a) {
  return execFileSync('node', [`${REPO}/scripts/probe/probe.mjs`, ...a], { cwd: REPO, encoding: 'utf8', maxBuffer: 1 << 26 });
}
export const exec = (code) => probe('exec', code).trim();
export const rcon = (cmd) => probe('rcon', cmd).trim();
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const logSize = () => fs.statSync(LOG).size;
/** Server log lines written since `offset` that contain `needle`. */
export const logSince = (offset, needle) => fs.readFileSync(LOG).subarray(offset).toString().split(/\r?\n/).filter((l) => l.includes(needle));

/** Generate the pack, install it in the client, select it, and put the titles where the probe plugin reads them. */
export async function install() {
  execFileSync('node', [`${REPO}/fixtures/gui/containers/gen.mjs`, SCRATCH], { stdio: 'inherit' });
  fs.rmSync(`${CLIENT}/resourcepacks/r289`, { recursive: true, force: true });
  fs.cpSync(`${SCRATCH}/pack`, `${CLIENT}/resourcepacks/r289`, { recursive: true });
  const data = `${SERVER}/plugins/MaterioneContainerProbe`;
  fs.mkdirSync(data, { recursive: true });
  for (const f of fs.readdirSync(`${SCRATCH}/titles`)) fs.copyFileSync(`${SCRATCH}/titles/${f}`, `${data}/${f}`);
  exec(`def r = mc.getResourcePackRepository(); r.reload(); r.setSelected(['vanilla','file/r289']); mc.reloadResourcePacks(); return 'ok'`);
  await sleep(9000);
}

/** The window size, in pixels, and the GUI scale it settles at. */
export async function setWindow(w, h, scale) {
  exec(`def hd = mc.getWindow().handle(); mc.execute({ org.lwjgl.glfw.GLFW.glfwSetWindowSize(hd, ${w}, ${h}) } as Runnable); return 'q'`);
  await sleep(1500);
  exec(`mc.options.guiScale().set(${scale}); mc.resizeGui(); return 'ok'`);
  await sleep(500);
}

/** Open a probe window and wait for the screen. `kind` hopper|dispenser; `title` a file of the plugin's data folder or plain text. */
export async function open(kind, title = '', { empty = false } = {}) {
  exec(`player.closeContainer(); return 'c'`);
  await sleep(300);
  rcon(`ctprobe open ${kind} Probe ${title || 'Probe_' + kind}${empty ? ' empty' : ''}`);
  await sleep(1200);
  return info();
}

/** What the open screen says about itself. */
export function info() {
  const raw = exec(`def s = mc.screen.@target; return [s.getClass().getSimpleName(), s.imageWidth, s.imageHeight, s.leftPos, s.topPos, s.titleLabelX, s.titleLabelY, s.inventoryLabelX, s.inventoryLabelY, mc.font.width(s.getTitle()), mc.getWindow().getGuiScale(), mc.getWindow().getWidth(), mc.getWindow().getHeight(), player.containerMenu.containerId, player.containerMenu.slots.size()].join(' ')`).replace(/"/g, '').split(' ');
  const n = raw.slice(1).map(Number);
  return { screen: raw[0], imageWidth: n[0], imageHeight: n[1], leftPos: n[2], topPos: n[3], titleLabelX: n[4], titleLabelY: n[5], inventoryLabelX: n[6], inventoryLabelY: n[7], titleWidth: n[8], scale: n[9], windowW: n[10], windowH: n[11], menuId: n[12], menuSlots: n[13] };
}

/** Grab a lossless PNG of the framebuffer into SCRATCH/shots/<name>.png */
export async function shot(name) {
  const f = `${CLIENT}/screenshots/${name}.png`;
  fs.rmSync(f, { force: true });
  exec(`def gd = mc.gameDirectory.@target; def rt = mc.getMainRenderTarget().@target; mc.execute({ net.minecraft.client.Screenshot.grab(gd, '${name}.png', rt, 1, { c -> } as java.util.function.Consumer) } as Runnable); return 'q'`);
  for (let i = 0; i < 50 && !fs.existsSync(f); i++) await sleep(100);
  await sleep(300);
  fs.mkdirSync(`${SCRATCH}/shots`, { recursive: true });
  fs.copyFileSync(f, `${SCRATCH}/shots/${name}.png`);
  return `${SCRATCH}/shots/${name}.png`;
}

/** RGB/RGBA 8-bit PNGs (the framebuffer grabs) and palette PNGs of 1/2/4/8 bits (the vanilla container sheets). */
export function decodePng(file) {
  const b = fs.readFileSync(file);
  let p = 8, w = 0, h = 0, ct = 0, depth = 8, plte = null, trns = null; const idat = [];
  while (p < b.length) {
    const len = b.readUInt32BE(p), type = b.toString('latin1', p + 4, p + 8), d = b.subarray(p + 8, p + 8 + len);
    if (type === 'IHDR') { w = d.readUInt32BE(0); h = d.readUInt32BE(4); depth = d[8]; ct = d[9]; }
    if (type === 'PLTE') plte = d;
    if (type === 'tRNS') trns = d;
    if (type === 'IDAT') idat.push(d);
    p += 12 + len;
  }
  const bpp = ct === 6 ? 4 : ct === 3 ? 1 : 3;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const out = Buffer.alloc(w * h * 4);
  const stride = ct === 3 ? Math.ceil(w * depth / 8) : w * bpp; let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)]; const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? line[i - bpp] : 0, up = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
      let v = line[i];
      if (f === 1) v += a; else if (f === 2) v += up; else if (f === 3) v += (a + up) >> 1;
      else if (f === 4) { const pp = a + up - c, pa = Math.abs(pp - a), pb = Math.abs(pp - up), pc = Math.abs(pp - c); v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? up : c); }
      line[i] = v & 255;
    }
    if (ct === 3) {
      for (let x = 0; x < w; x++) {
        const bit = x * depth, idx = (line[bit >> 3] >> (8 - depth - (bit & 7))) & ((1 << depth) - 1);
        out.set([plte[idx * 3], plte[idx * 3 + 1], plte[idx * 3 + 2], trns && idx < trns.length ? trns[idx] : 255], (y * w + x) * 4);
      }
      prev = line;
      continue;
    }
    for (let x = 0; x < w; x++) { out[(y * w + x) * 4] = line[x * bpp]; out[(y * w + x) * 4 + 1] = line[x * bpp + 1]; out[(y * w + x) * 4 + 2] = line[x * bpp + 2]; out[(y * w + x) * 4 + 3] = bpp === 4 ? line[x * bpp + 3] : 255; }
    prev = line;
  }
  return { w, h, rgba: out };
}

/**
 * Check each marker's fill box against the model. `screen` is info(); `origin` the pen origin the screen actually
 * used (titleLabelX): a glyph at layout x is drawn at leftPos + (x - 8) + origin. Returns lines and a miss count.
 */
export function analyzeMarkers(file, id, screen, only) {
  const img = decodePng(file);
  const s = screen.scale, L = screen.leftPos * s, Tp = screen.topPos * s;
  const shiftX = (screen.titleLabelX - 8) * s;
  const lines = []; let miss = 0;
  for (const g of layouts()[id].glyphs) {
    if (only && !only.includes(g.name)) continue;
    const c = g.fill;
    let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1, n = 0;
    for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) {
      const i = (y * img.w + x) * 4;
      if (img.rgba[i] === c[0] && img.rgba[i + 1] === c[1] && img.rgba[i + 2] === c[2]) { n++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    }
    // the title's text row is titleLabelY + 7 = 13: the glyph's top is at topPos + titleLabelY + 7 - ascent
    const ex0 = L + shiftX + s * (g.x + 2), ey0 = Tp + s * (g.y + 2), ex1 = L + shiftX + s * (g.x + g.artW - 2) - 1, ey1 = Tp + s * (g.y + g.artH - 2) - 1;
    const vis = ex1 >= 0 && ey1 >= 0 && ex0 < img.w && ey0 < img.h;
    const ok = n > 0 && x0 === Math.max(ex0, 0) && y0 === Math.max(ey0, 0) && x1 === Math.min(ex1, img.w - 1) && y1 === Math.min(ey1, img.h - 1);
    if (vis && !ok) miss++;
    if (!vis && n) miss++;
    lines.push(`${g.name.padEnd(3)} (${String(g.x).padStart(4)},${String(g.y).padStart(4)}) ${n ? `found [${x0},${y0}]-[${x1},${y1}]` : 'absent'} expect [${ex0},${ey0}]-[${ex1},${ey1}] ${vis ? (ok ? 'MATCH' : 'DIFF') : (n ? 'DIFF' : 'outside window, absent')}`);
  }
  return { lines, miss };
}

/**
 * Compare the position-coded image (W x H at x0,y0 relative to the screen) with the framebuffer, pixel by pixel.
 * `skip(gx, gy)` excludes GUI-space points (the Inventory label, slot items).
 */
export function analyzeBig(file, screen, W, H, x0, y0, skip = () => false) {
  const img = decodePng(file);
  const s = screen.scale, shiftX = screen.titleLabelX - 8;
  const bigPixel = (X, Y) => (X % 32 === 0 || Y % 32 === 0) ? [0, 0, 0] : [Math.floor(X * 255 / (W - 1)), Math.floor(Y * 255 / (H - 1)), ((X ^ Y) & 1) ? 200 : 40];
  let match = 0, mismatch = 0, offWindow = 0, skipped = 0; const bad = [];
  for (let Y = 0; Y < H; Y++) for (let X = 0; X < W; X++) {
    const e = bigPixel(X, Y), gx = x0 + X, gy = y0 + Y;
    if (skip(gx, gy)) { skipped++; continue; }
    for (let j = 0; j < s; j++) for (let i = 0; i < s; i++) {
      const px = (screen.leftPos + shiftX + x0 + X) * s + i, py = (screen.topPos + y0 + Y) * s + j;
      if (px < 0 || py < 0 || px >= img.w || py >= img.h) { offWindow++; continue; }
      const k = (py * img.w + px) * 4;
      if (img.rgba[k] === e[0] && img.rgba[k + 1] === e[1] && img.rgba[k + 2] === e[2]) match++;
      else { mismatch++; if (bad.length < 6) bad.push(`img(${X},${Y}) screen(${px},${py}) got ${[img.rgba[k], img.rgba[k + 1], img.rgba[k + 2]]} want ${e}`); }
    }
  }
  return { match, mismatch, offWindow, skipped, bad };
}
