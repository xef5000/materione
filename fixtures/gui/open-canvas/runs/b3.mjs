// batch: c1r6 at 854x480 and 1280x720, scales 1..4 (clamped by the window)
import { exec, rcon, sleep, titles, shot } from './lib.mjs';
import { setTitle, openChest } from './chest.mjs';
import { analyzeC1 } from './an.mjs';
import { setWindow } from './win.mjs';
const T = titles();
await rcon('gamemode creative Probe');
await setTitle(T.c1r6.components, 6);
for (const [ww, wh] of [[854, 480], [1280, 720]]) {
  console.log('window', await setWindow(ww, wh));
  for (const sc of [1, 2, 3, 4]) {
    exec(`player.closeContainer(); mc.options.guiScale().set(${sc}); mc.resizeGui(); return 'ok'`);
    await openChest();
    await sleep(500);
    const info = exec(`def s = mc.screen.@target; return mc.getWindow().getWidth()+'x'+mc.getWindow().getHeight()+' '+mc.getWindow().getGuiScale()+' '+s.leftPos+' '+s.topPos`).replace(/"/g, '').split(/[x ]/).map(Number);
    const [w, h, scale, left, top] = info;
    if (sc > scale && sc > 1 && scale === Number(globalThis.__last)) { console.log(`requested ${sc}: clamped to ${scale}`); continue; }
    globalThis.__last = scale;
    const f = await shot(`c1r6_${w}x${h}_s${scale}`);
    console.log(`--- ${w}x${h} requested ${sc} got scale ${scale}, left ${left} top ${top}`);
    console.log(analyzeC1(f, scale, left, top, 6).join('\n'));
  }
  globalThis.__last = undefined;
}
