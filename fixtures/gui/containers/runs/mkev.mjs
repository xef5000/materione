// Crops and halves the captures kept under docs/screenshots/gui-containers/ (full-size originals stay in the scratch dir).
import fs from 'node:fs';
import zlib from 'node:zlib';
import { decodePng, SCRATCH, REPO } from './lib.mjs';

const crcTable = new Int32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c; });
const crc32 = (b) => { let c = -1; for (const x of b) c = crcTable[(c ^ x) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; };
const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td)); return Buffer.concat([len, td, crc]); };
function encodeRgb(w, h, rgb) {
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 3 + 1)] = 0; rgb.copy(raw, y * (w * 3 + 1) + 1, y * w * 3, (y + 1) * w * 3); }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}
const OUT = `${REPO}/docs/screenshots/gui-containers`;
fs.mkdirSync(OUT, { recursive: true });
function make(name, src, { crop, down = 1 } = {}) {
  const img = decodePng(`${SCRATCH}/shots/${src}.png`);
  const [cx, cy, cw, ch] = crop ?? [0, 0, img.w, img.h];
  const w = Math.floor(cw / down), h = Math.floor(ch / down);
  const rgb = Buffer.alloc(w * h * 3);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    let r = 0, g = 0, b = 0;
    for (let j = 0; j < down; j++) for (let i = 0; i < down; i++) { const k = ((cy + y * down + j) * img.w + cx + x * down + i) * 4; r += img.rgba[k]; g += img.rgba[k + 1]; b += img.rgba[k + 2]; }
    const n = down * down; rgb[(y * w + x) * 3] = Math.round(r / n); rgb[(y * w + x) * 3 + 1] = Math.round(g / n); rgb[(y * w + x) * 3 + 2] = Math.round(b / n);
  }
  const png = encodeRgb(w, h, rgb);
  fs.writeFileSync(`${OUT}/${name}.png`, png);
  console.log(name, `${w}x${h}`, Math.round(png.length / 1024) + ' KB');
}
// 1920 x 1080 at scale 2: hopper left=392 top=203 (352 x 266 px), dispenser left=392 top=187 (352 x 332 px)
make('hopper-markers-1080p-s2', 'u1_mh_s2', { crop: [690, 310, 540, 470], down: 2 });
make('dispenser-trailing-shift-1080p-s2', 'u1_mdt_s2', { crop: [330, 290, 900, 520], down: 2 });
make('dispenser-no-trailing-shift-1080p-s2', 'u1_mdn_s2', { crop: [330, 290, 900, 520], down: 2 });
make('hopper-tiles-1080p-s2', 'u3_hopper_tiles_s2', { crop: [664, 326, 600, 460], down: 2 });
make('dispenser-tiles-1080p-s2', 'u3_dispenser_tiles_s2', { crop: [664, 294, 600, 460], down: 2 });
make('hopper-draw-order-1080p-s2', 'u3_hopper_order_s2', { crop: [772, 394, 376, 290] });
make('dispenser-draw-order-1080p-s2', 'u3_dispenser_order_s2', { crop: [772, 362, 376, 356] });
make('dispenser-refresh-recentred-1080p-s2', 'u5_dispenser_s3n', { crop: [772, 362, 376, 356] });
