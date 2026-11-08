// U1 and U2 (gui.md 12.12): the dispenser's centred title with and without the trailing shift, and art placement
// on both screens. Needs the pack installed (install.mjs) and the probe joined; creative mode for the player.
import { exec, rcon, sleep, layouts, setWindow, open, shot, analyzeMarkers } from './lib.mjs';

const L = layouts();
rcon('gamemode creative Probe');
await setWindow(1920, 1080, 2);
for (const scale of [1, 2, 3]) {
  await setWindow(1920, 1080, scale);
  console.log(`\n=== 1920x1080, requested scale ${scale} ===`);
  for (const [kind, title, label] of [
    ['hopper', 'mh', 'hopper, markers, no trailing shift'],
    ['hopper', 'mht', 'hopper, markers, trailing shift (harmless?)'],
    ['dispenser', 'mdt', 'dispenser, markers, trailing shift'],
    ['dispenser', 'mdn', 'dispenser, markers, NO trailing shift'],
    ['dispenser', 'o_t', 'dispenser, one marker at the origin, trailing shift'],
    ['dispenser', 'o_n', 'dispenser, one marker at the origin, no trailing shift'],
  ]) {
    const s = await open(kind, title, { empty: true });
    const f = await shot(`u1_${title}_s${s.scale}`);
    const a = analyzeMarkers(f, title, s);
    console.log(`-- ${label}: ${s.screen} ${s.imageWidth}x${s.imageHeight} left=${s.leftPos} top=${s.topPos} titleLabel=(${s.titleLabelX},${s.titleLabelY}) inventoryLabel=(${s.inventoryLabelX},${s.inventoryLabelY}) font.width=${s.titleWidth} layoutWidth=${L[title].width} expectedOrigin=${Math.trunc((s.imageWidth - L[title].width) / 2)} menuSlots=${s.menuSlots}`);
    console.log(a.lines.map((l) => '   ' + l).join('\n'));
    console.log(`   markers off the model: ${a.miss}`);
  }
}
exec(`player.closeContainer(); return 'c'`);
