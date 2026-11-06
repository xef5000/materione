// batch: c1r1 (one-row client-side screen) at 1920x1080, scales 1..4
import { exec, rcon, sleep, titles, shot } from './lib.mjs';
import { setTitle, openChest } from './chest.mjs';
import { openClientRows } from './chest1.mjs';
import { analyzeC1 } from './an.mjs';
const T = titles();
await rcon('gamemode creative Probe');
await setTitle(T.c1r1.components, 6);
for (const sc of [1, 2, 3, 4]) {
  exec(`player.closeContainer(); mc.options.guiScale().set(${sc}); mc.resizeGui(); return 'ok'`);
  await openChest();
  console.log(await openClientRows(1));
  const info = exec(`def s = mc.screen.@target; return mc.getWindow().getWidth()+'x'+mc.getWindow().getHeight()+' '+mc.getWindow().getGuiScale()+' '+s.leftPos+' '+s.topPos+' '+s.getClass().getSimpleName()`).replace(/"/g, '').split(/[x ]/);
  const [w, h, scale, left, top] = info.slice(0, 5).map(Number);
  const f = await shot(`c1r1_${w}x${h}_s${scale}`);
  console.log(`--- ${w}x${h} scale ${scale}, left ${left} top ${top} (${info[5]})`);
  console.log(analyzeC1(f, scale, left, top, 1).join('\n'));
}
exec(`mc.screen.onClose(); return 'closed'`);
