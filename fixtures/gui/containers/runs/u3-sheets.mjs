// U3 (gui.md 12.12), part 1: where the vanilla player-inventory art begins in the hopper and dispenser sheets.
// The chest's sheet (generic_54) holds the player block at rows 126..221 (the second blit of ContainerScreen). If the
// hopper and the dispenser sheets carry the same 96-row block at their bottom, it starts at imageHeight - 96, i.e.
// 37 and 70, which is `inventory.y - 14` (51 - 14, 84 - 14). Reads the three sheets from SCRATCH/vanilla/*.png
// (extracted from the 26.1.2 client jar, not committed).
import { decodePng, SCRATCH } from './lib.mjs';

const sheet = (n) => decodePng(`${SCRATCH}/vanilla/${n}.png`);
const chest = sheet('generic_54');
const rowEq = (a, ar, b, br, w = 176) => {
  for (let x = 0; x < w; x++) {
    const i = (ar * a.w + x) * 4, j = (br * b.w + x) * 4;
    for (let c = 0; c < 4; c++) if (a.rgba[i + c] !== b.rgba[j + c]) return false;
  }
  return true;
};
for (const [name, H, expected] of [['hopper', 133, 37], ['dispenser', 166, 70]]) {
  const s = sheet(name);
  // how many rows from the bottom equal the chest's player block (the chest's last art row is 221)
  let k = 0;
  while (k < 96 && rowEq(s, H - 1 - k, chest, 221 - k)) k++;
  const above = rowEq(s, H - 1 - 96, chest, 221 - 96);
  console.log(`${name}: ${s.w}x${s.h} sheet; bottom ${k} of 96 rows equal the chest's player block; ` +
    `block starts at row ${H - k} (expected ${expected}); the row above it (${H - 97}) ${above ? 'also equals' : 'differs from'} the chest's row ${221 - 96}`);
}
