// c2: ascent <= height rule and limits, 1920x1080 scale 1
import { exec, rcon, sleep, titles, shot } from './lib.mjs';
import { setTitle, openChest } from './chest.mjs';
import { setWindow } from './win.mjs';
import { analyzeMarkers } from './an.mjs';
const T = titles();
await rcon('gamemode creative Probe');
console.log('window', await setWindow(1920, 1080));
await setTitle(T.c2.components, 6);
exec(`player.closeContainer(); mc.options.guiScale().set(1); mc.resizeGui(); return 'ok'`);
await openChest();
await sleep(600);
const [scale, left, top] = exec(`def s = mc.screen.@target; return mc.getWindow().getGuiScale()+' '+s.leftPos+' '+s.topPos`).replace(/"/g, '').split(' ').map(Number);
const f = await shot('c2_1920x1080_s1');
console.log(analyzeMarkers(f, scale, left, top, 'c2', ['PAD', 'OK13', 'EDGE', 'FARL', 'FARR', 'FARD', 'SCALE']).join('\n'));
