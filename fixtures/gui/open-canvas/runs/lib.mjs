// scratch helpers for the #275 probe runs
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

export const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..').split(path.sep).join('/');
export const CLIENT = `${REPO}/harness/build/probe/client`;
export const SCRATCH = process.env.MTR_R275_OUT ?? `${REPO}/harness/build/probe/r275`;
export const titles = () => JSON.parse(fs.readFileSync(`${SCRATCH}/titles.json`, 'utf8'));

function probe(...a) {
  return execFileSync('node', [`${REPO}/scripts/probe/probe.mjs`, ...a], { cwd: REPO, encoding: 'utf8', maxBuffer: 1 << 26 });
}
export const exec = (code) => probe('exec', code).trim();
export const rcon = (cmd) => probe('rcon', cmd).trim();
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** JSON text component -> SNBT-safe string (ascii only, \u escapes). */
export function snbtOf(components) {
  const j = JSON.stringify({ text: '', extra: components });
  if (j.length > 400) return j; // long titles go as raw UTF-8: a \u escape is six bytes
  return j.replace(/[^\x20-\x7e]/g, (c) => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'));
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

export function decodePng(file) {
  const b = fs.readFileSync(file);
  let p = 8, w = 0, h = 0, ct = 0; const idat = [];
  while (p < b.length) {
    const len = b.readUInt32BE(p), type = b.toString('latin1', p + 4, p + 8), d = b.subarray(p + 8, p + 8 + len);
    if (type === 'IHDR') { w = d.readUInt32BE(0); h = d.readUInt32BE(4); ct = d[9]; }
    if (type === 'IDAT') idat.push(d);
    p += 12 + len;
  }
  const bpp = ct === 6 ? 4 : 3;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const out = Buffer.alloc(w * h * 4);
  const stride = w * bpp; let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)]; const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? line[i - bpp] : 0, up = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
      let v = line[i];
      if (f === 1) v += a; else if (f === 2) v += up; else if (f === 3) v += (a + up) >> 1;
      else if (f === 4) { const pp = a + up - c, pa = Math.abs(pp - a), pb = Math.abs(pp - up), pc = Math.abs(pp - c); v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? up : c); }
      line[i] = v & 255;
    }
    for (let x = 0; x < w; x++) { out[(y * w + x) * 4] = line[x * bpp]; out[(y * w + x) * 4 + 1] = line[x * bpp + 1]; out[(y * w + x) * 4 + 2] = line[x * bpp + 2]; out[(y * w + x) * 4 + 3] = bpp === 4 ? line[x * bpp + 3] : 255; }
    prev = line;
  }
  return { w, h, rgba: out };
}
