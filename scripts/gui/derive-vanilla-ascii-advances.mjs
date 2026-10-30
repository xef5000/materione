#!/usr/bin/env node
// Derives the vanilla default-font advance table (gui.md section 9.4) from a Minecraft client jar.
//
//   node scripts/gui/derive-vanilla-ascii-advances.mjs <client.jar> [--check] [--out <file>]
//
// The numbers are measured, not remembered. The script reads two entries of the client jar:
//   assets/minecraft/textures/font/ascii.png   the 16x16 ASCII grid (8 px cells, RGBA)
//   assets/minecraft/font/include/space.json   the space provider (the space advance)
// and applies the client's bitmap rule to every printable glyph (BitmapProvider.getActualGlyphWidth):
// the advance is the rightmost column holding a pixel with alpha != 0, plus one, plus the one px of
// spacing the client adds. Space takes its advance from include/space, because its cell is empty.
//
// The output is numbers only: the texture is never written anywhere. The default output is
// fonts/vanilla-ascii/advances.txt, the single source both the Java compiler and the studio read.
// --check re-derives the table and fails if the file on disk differs, which is how a changed client
// or a hand edit is caught. The script only reads the jar; it needs no network and no dependency.

import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { inflateRawSync, inflateSync } from 'node:zlib';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..');
const DEFAULT_OUT = join(repoRoot, 'fonts', 'vanilla-ascii', 'advances.txt');

const ASCII_ENTRY = 'assets/minecraft/textures/font/ascii.png';
const SPACE_ENTRY = 'assets/minecraft/font/include/space.json';
const FIRST = 0x21;
const LAST = 0x7e;

// --- zip (central directory) ------------------------------------------------

function readZipEntries(jar) {
  const eocd = findEndOfCentralDirectory(jar);
  const count = jar.readUInt16LE(eocd + 10);
  let at = jar.readUInt32LE(eocd + 16);
  const entries = new Map();
  for (let i = 0; i < count; i++) {
    if (jar.readUInt32LE(at) !== 0x02014b50) throw new Error('jar: bad central directory entry');
    const method = jar.readUInt16LE(at + 10);
    const compressedSize = jar.readUInt32LE(at + 20);
    const nameLength = jar.readUInt16LE(at + 28);
    const extraLength = jar.readUInt16LE(at + 30);
    const commentLength = jar.readUInt16LE(at + 32);
    const localOffset = jar.readUInt32LE(at + 42);
    const name = jar.toString('utf8', at + 46, at + 46 + nameLength);
    entries.set(name, { method, compressedSize, localOffset });
    at += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

function findEndOfCentralDirectory(jar) {
  for (let at = jar.length - 22; at >= 0 && at >= jar.length - 22 - 0xffff; at--) {
    if (jar.readUInt32LE(at) === 0x06054b50) return at;
  }
  throw new Error('jar: no end of central directory (not a zip file)');
}

function readZipEntry(jar, entries, name) {
  const entry = entries.get(name);
  if (!entry) throw new Error(`jar: ${name} is missing`);
  const local = entry.localOffset;
  if (jar.readUInt32LE(local) !== 0x04034b50) throw new Error(`jar: bad local header for ${name}`);
  const start = local + 30 + jar.readUInt16LE(local + 26) + jar.readUInt16LE(local + 28);
  const data = jar.subarray(start, start + entry.compressedSize);
  if (entry.method === 0) return Buffer.from(data);
  if (entry.method === 8) return inflateRawSync(data);
  throw new Error(`jar: ${name} uses unsupported compression method ${entry.method}`);
}

// --- PNG (non-interlaced; the vanilla ascii.png is 1-bit palette with a tRNS entry) -------

function decodePng(bytes) {
  if (bytes.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') throw new Error('ascii.png: not a PNG');
  let at = 8;
  let header = null;
  let palette = null;
  let transparency = null;
  const idat = [];
  while (at < bytes.length) {
    const length = bytes.readUInt32BE(at);
    const type = bytes.toString('latin1', at + 4, at + 8);
    const data = bytes.subarray(at + 8, at + 8 + length);
    if (type === 'IHDR') {
      header = {
        width: data.readUInt32BE(0),
        height: data.readUInt32BE(4),
        bitDepth: data[8],
        colorType: data[9],
        interlace: data[12],
      };
    } else if (type === 'PLTE') {
      palette = data;
    } else if (type === 'tRNS') {
      transparency = data;
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    at += 12 + length;
  }
  if (!header) throw new Error('ascii.png: no IHDR');
  if (header.interlace !== 0) throw new Error('ascii.png: interlaced PNGs are not supported');
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[header.colorType];
  if (channels === undefined) throw new Error(`ascii.png: unsupported colour type ${header.colorType}`);
  const bitsPerPixel = channels * header.bitDepth;
  const bpp = Math.max(1, bitsPerPixel >> 3);
  const stride = Math.ceil((header.width * bitsPerPixel) / 8);
  const raw = inflateSync(Buffer.concat(idat));
  const pixels = Buffer.alloc(stride * header.height);
  for (let y = 0; y < header.height; y++) {
    const filter = raw[y * (stride + 1)];
    const src = y * (stride + 1) + 1;
    const dst = y * stride;
    for (let x = 0; x < stride; x++) {
      const left = x >= bpp ? pixels[dst + x - bpp] : 0;
      const up = y > 0 ? pixels[dst - stride + x] : 0;
      const upLeft = y > 0 && x >= bpp ? pixels[dst - stride + x - bpp] : 0;
      const value = raw[src + x];
      let out;
      switch (filter) {
        case 0: out = value; break;
        case 1: out = value + left; break;
        case 2: out = value + up; break;
        case 3: out = value + ((left + up) >> 1); break;
        case 4: out = value + paeth(left, up, upLeft); break;
        default: throw new Error(`ascii.png: bad filter type ${filter}`);
      }
      pixels[dst + x] = out & 0xff;
    }
  }
  return { ...header, channels, palette, transparency, pixels, stride };
}

/** The raw sample `index` of row `y` (samples are packed most significant bit first below 8 bits). */
function sampleAt(png, y, index) {
  const bits = png.bitDepth;
  if (![1, 2, 4, 8].includes(bits)) throw new Error(`ascii.png: unsupported bit depth ${bits}`);
  const row = y * png.stride;
  if (bits === 8) return png.pixels[row + index];
  const perByte = 8 / bits;
  const byte = png.pixels[row + Math.floor(index / perByte)];
  const shift = 8 - bits * ((index % perByte) + 1);
  return (byte >> shift) & ((1 << bits) - 1);
}

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}

/**
 * The alpha of a pixel. A palette image takes it from tRNS (an index without an entry is opaque);
 * an RGBA or grey-alpha image has it as its last sample; anything else is opaque.
 */
function alphaAt(png, x, y) {
  if (png.colorType === 3) {
    const index = sampleAt(png, y, x);
    return png.transparency && index < png.transparency.length ? png.transparency[index] : 255;
  }
  if (png.colorType === 4) return sampleAt(png, y, x * 2 + 1);
  if (png.colorType === 6) return sampleAt(png, y, x * 4 + 3);
  return 255;
}

// --- the client's rule ------------------------------------------------------

/** Rightmost column holding a pixel with alpha != 0, plus one (BitmapProvider.getActualGlyphWidth). */
function actualWidth(png, column, row) {
  const cell = png.width / 16;
  const cellHeight = png.height / 16;
  let width = 0;
  for (let x = 0; x < cell; x++) {
    for (let y = 0; y < cellHeight; y++) {
      if (alphaAt(png, column * cell + x, row * cellHeight + y) !== 0) {
        width = x + 1;
        break;
      }
    }
  }
  return width;
}

function deriveTable(jar) {
  const entries = readZipEntries(jar);
  const png = decodePng(readZipEntry(jar, entries, ASCII_ENTRY));
  const spaceJson = JSON.parse(readZipEntry(jar, entries, SPACE_ENTRY).toString('utf8'));
  const space = spaceJson.providers.find((p) => p.type === 'space').advances[' '];
  if (!Number.isInteger(space)) throw new Error('include/space.json: no integer space advance');
  const advances = [];
  for (let codepoint = FIRST; codepoint <= LAST; codepoint++) {
    const column = codepoint & 15;
    const row = codepoint >> 4;
    advances.push({ codepoint, advance: actualWidth(png, column, row) + 1 });
  }
  return {
    space,
    advances,
    pngSha1: createHash('sha1').update(readZipEntry(jar, entries, ASCII_ENTRY)).digest('hex'),
    pngSize: `${png.width}x${png.height}`,
  };
}

// --- output -----------------------------------------------------------------

function render(table, version) {
  const hex = (n) => '0x' + n.toString(16).toUpperCase().padStart(2, '0');
  const lines = [
    '; The vanilla default-font advance widths (docs/architecture/gui.md section 9.4). Numbers only:',
    '; the ASCII texture is referenced by resource location and never shipped.',
    `; Measured from the Minecraft ${version} client jar by scripts/gui/derive-vanilla-ascii-advances.mjs.`,
    `; assets/minecraft/textures/font/ascii.png (${table.pngSize}), sha1 ${table.pngSha1}.`,
    '; Advance = the client bitmap rule applied to the cell: rightmost ink column + 1, plus 1 px of spacing.',
    '; Space is the vanilla include/space provider (its cell is empty). Printable ASCII only.',
    '; The Java compiler (VanillaAscii) and the studio (font/vanilla-ascii.ts) both read this file.',
    '',
    `space ${table.space}`,
    ...table.advances.map((entry) => `${hex(entry.codepoint)} ${entry.advance}`),
    '',
  ];
  return lines.join('\n');
}

function argValue(args, flag) {
  const at = args.indexOf(flag);
  return at >= 0 ? args[at + 1] : undefined;
}

function main() {
  const args = process.argv.slice(2);
  const valued = new Set(['--out', '--version']);
  const positional = args.filter((arg, i) => !arg.startsWith('--') && !valued.has(args[i - 1]));
  const jarPath = positional[0];
  if (!jarPath) {
    console.error('usage: derive-vanilla-ascii-advances.mjs <client.jar> [--version 26.1.2] [--check] [--out <file>]');
    process.exit(2);
  }
  const version = argValue(args, '--version') ?? '26.1.2';
  const out = argValue(args, '--out') ?? DEFAULT_OUT;
  const text = render(deriveTable(readFileSync(jarPath)), version);
  if (args.includes('--check')) {
    if (readFileSync(out, 'utf8') !== text) {
      console.error(`${out} differs from the table derived from ${jarPath}`);
      process.exit(1);
    }
    console.log(`ok: ${out} matches ${jarPath} (Minecraft ${version})`);
    return;
  }
  writeFileSync(out, text);
  console.log(`wrote ${out} from ${jarPath} (Minecraft ${version})`);
}

main();
