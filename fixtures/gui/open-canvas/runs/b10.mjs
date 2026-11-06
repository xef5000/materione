// c4: the 1024x1024 art cap box in 16 tiles (4 rows: ascents 256, 0, -256, -512), 1920x1080 scale 1 and 2
import { exec, rcon, sleep, titles, shot } from './lib.mjs';
import { setTitle, openChest } from './chest.mjs';
import { analyzeBig } from './an.mjs';
import { setWindow } from './win.mjs';
const T = titles();
await rcon('gamemode creative Probe');
console.log('window', await setWindow(1920, 1080));
await rcon(`data merge block 8 -60 3 {Items:[]}`);
await setTitle(T.c4.components, 6);
console.log('title chars', T.c4.components.map((c) => c.text).join('').length, 'snbt bytes', JSON.stringify(T.c4.components).length);
for (const sc of [1, 2]) {
  exec(`player.closeContainer(); mc.options.guiScale().set(${sc}); mc.resizeGui(); return 'ok'`);
  await openChest();
  await sleep(700);
  const [scale, left, top] = exec(`def s = mc.screen.@target; return mc.getWindow().getGuiScale()+' '+s.leftPos+' '+s.topPos`).replace(/"/g, '').split(' ').map(Number);
  const f = await shot(`c4_1920x1080_s${scale}`);
  const r = analyzeBig(f, scale, left, top, 1024, 1024, -424, -243);
  console.log(`scale ${scale} left ${left} top ${top}: match ${r.match}, mismatch ${r.mismatch}, label ${r.label}, outside window ${r.offWindow}`);
  for (const b of r.bad) console.log('  ', b);
}
