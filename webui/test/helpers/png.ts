// A minimal PNG reader for tests: non-interlaced, 8-bit RGBA / RGB, every
// scanline filter. Just enough to read the shared GUI raster vectors'
// expected images without a dependency.
import { inflateSync } from 'node:zlib';
import { ArgbCanvas } from '../../src/lib/gui/raster/canvas';

const SIGNATURE = [137, 80, 78, 71, 13, 10, 26, 10];

export function decodePng(bytes: Uint8Array): ArgbCanvas {
  for (let i = 0; i < SIGNATURE.length; i++) {
    if (bytes[i] !== SIGNATURE[i]) throw new Error('not a PNG');
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let width = 0;
  let height = 0;
  let channels = 4;
  const data: Uint8Array[] = [];
  let offset = 8;
  while (offset < bytes.length) {
    const length = view.getUint32(offset);
    const type = String.fromCharCode(...bytes.subarray(offset + 4, offset + 8));
    const body = bytes.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      const header = new DataView(body.buffer, body.byteOffset, body.byteLength);
      width = header.getUint32(0);
      height = header.getUint32(4);
      if (body[8] !== 8 || (body[9] !== 6 && body[9] !== 2) || body[12] !== 0) {
        throw new Error('only non-interlaced 8-bit RGB/RGBA PNGs are supported');
      }
      channels = body[9] === 6 ? 4 : 3;
    } else if (type === 'IDAT') {
      data.push(body);
    }
    offset += 12 + length;
  }
  const raw = inflateSync(Buffer.concat(data));
  const stride = width * channels;
  const pixels = new Uint8Array(stride * height);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)]!;
    for (let x = 0; x < stride; x++) {
      const value = raw[y * (stride + 1) + 1 + x]!;
      const left = x >= channels ? pixels[y * stride + x - channels]! : 0;
      const up = y > 0 ? pixels[(y - 1) * stride + x]! : 0;
      const upLeft = x >= channels && y > 0 ? pixels[(y - 1) * stride + x - channels]! : 0;
      let predictor = 0;
      if (filter === 1) predictor = left;
      else if (filter === 2) predictor = up;
      else if (filter === 3) predictor = (left + up) >> 1;
      else if (filter === 4) {
        const p = left + up - upLeft;
        const pa = Math.abs(p - left);
        const pb = Math.abs(p - up);
        const pc = Math.abs(p - upLeft);
        predictor = pa <= pb && pa <= pc ? left : pb <= pc ? up : upLeft;
      }
      pixels[y * stride + x] = (value + predictor) & 255;
    }
  }
  const rgba = new Uint8Array(width * height * 4);
  for (let index = 0; index < width * height; index++) {
    rgba[index * 4] = pixels[index * channels]!;
    rgba[index * 4 + 1] = pixels[index * channels + 1]!;
    rgba[index * 4 + 2] = pixels[index * channels + 2]!;
    rgba[index * 4 + 3] = channels === 4 ? pixels[index * channels + 3]! : 255;
  }
  return ArgbCanvas.fromRgba(width, height, rgba);
}
