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
const OUT = `${REPO}/docs/screenshots/gui-open-canvas`;
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
make('q1-offscreen-6row-1080p-s2', 'c1r6_1920x1080_s2', { down: 2 });
make('q1-offscreen-1row-1080p-s2', 'c1r1_1920x1080_s2', { down: 2 });
make('q1-cut-by-window-1080p-s4', 'c1r6_1920x1080_s4', { down: 2 });
make('q2-ascent-limit-1080p-s1', 'c2_1920x1080_s1', { down: 2 });
make('q2-minimal-padding-1080p-s1', 'c2b_1920x1080_s1', { crop: [772, 329, 400, 200] });
make('q2-mixed-font-fails-1080p-s1', 'c2mix_1920x1080_s1', { crop: [772, 329, 400, 200] });
make('q3-tiles-seam-1080p-s4', 'c3_1920x1080_s4', { crop: [764, 424, 320, 160] });
make('q3-tiles-1024-1080p-s1', 'c4_1920x1080_s1', { down: 2 });
make('q4-window-854x480-s2', 'c1r6_854x480_s2');
make('q4-window-1280x720-s3', 'c1r6_1280x720_s3', { down: 2 });
make('q5-draw-order-tiles-1080p-s2', 'order_c3_s2', { crop: [780, 330, 380, 290] });
make('q5-draw-order-lone-marker-1080p-s2', 'order_c5on_s2', { crop: [780, 330, 380, 290] });
make('q5-draw-order-offscreen-1080p-s2', 'order_c1r6_s2', { down: 2 });
make('q6-click-outside-1280x720-s2', 'q6_gui_with_art_s2', { down: 2 });
make('q7-text-only-1280x720-s2', 'q7_c7text_s2', { crop: [440, 130, 400, 460] });
make('q7-plain-title-1280x720-s2', 'q7_plain_s2', { crop: [440, 130, 400, 460] });
