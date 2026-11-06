// c3: a 530x530 image in 3x3 tiles, scales 1..4 at 1920x1080, then 1280x720 and 854x480; pixel-exact comparison
import { exec, rcon, sleep, titles, shot } from './lib.mjs';
import { setTitle, openChest } from './chest.mjs';
import { analyzeC3 } from './an.mjs';
import { setWindow } from './win.mjs';
const T = titles();
await rcon('gamemode creative Probe');
await setTitle(T.c3.components, 6);
for (const [ww, wh] of [[1920, 1080], [1280, 720], [854, 480]]) {
  console.log('window', await setWindow(ww, wh));
  let last = 0;
  for (const sc of [1, 2, 3, 4]) {
    exec(`player.closeContainer(); mc.options.guiScale().set(${sc}); mc.resizeGui(); return 'ok'`);
    await openChest();
    await sleep(500);
    const [w, h, scale, left, top] = exec(`def s = mc.screen.@target; return mc.getWindow().getWidth()+'x'+mc.getWindow().getHeight()+' '+mc.getWindow().getGuiScale()+' '+s.leftPos+' '+s.topPos`).replace(/"/g, '').split(/[x ]/).map(Number);
    if (scale === last) { console.log(`requested ${sc}: clamped to ${scale}`); continue; }
    last = scale;
    const f = await shot(`c3_${w}x${h}_s${scale}`);
    const r = analyzeC3(f, scale, left, top);
    console.log(`${w}x${h} scale ${scale} (left ${left}, top ${top}): match ${r.match}, mismatch ${r.mismatch}, under the Inventory label ${r.label}, outside window ${r.offWindow}`);
    for (const b of r.bad) console.log('   ', b);
  }
}
