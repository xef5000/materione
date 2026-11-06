// batch: c1r6 at 1920x1080, scales 1..4
import { exec, rcon, sleep, titles, shot } from './lib.mjs';
import { setTitle, openChest } from './chest.mjs';
import { analyzeC1 } from './an.mjs';
const T = titles();
await rcon('gamemode creative Probe');
await setTitle(T.c1r6.components, 6);
for (const sc of [1, 2, 3, 4]) {
  exec(`player.closeContainer(); mc.options.guiScale().set(${sc}); mc.resizeGui(); return 'ok'`);
  await openChest();
  await sleep(500);
  const info = exec(`def s = mc.screen.@target; return mc.getWindow().getWidth()+'x'+mc.getWindow().getHeight()+' '+mc.getWindow().getGuiScale()+' '+s.leftPos+' '+s.topPos`).replace(/"/g, '').split(/[x ]/).map(Number);
  const [w, h, scale, left, top] = info;
  const f = await shot(`c1r6_${w}x${h}_s${scale}`);
  console.log(`--- ${w}x${h} requested ${sc} got scale ${scale}, left ${left} top ${top}`);
  console.log(analyzeC1(f, scale, left, top, 6).join('\n'));
}
