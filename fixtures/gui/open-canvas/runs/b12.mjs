// c6first: a title that starts with the glyph itself (no leading shift): is it drawn one pixel left?
import { exec, rcon, sleep, titles, shot } from './lib.mjs';
import { setTitle, openChest } from './chest.mjs';
import { analyzeMarkers } from './an.mjs';
import { setWindow } from './win.mjs';
const T = titles();
await rcon('gamemode creative Probe');
console.log('window', await setWindow(1920, 1080));
console.log('title components', JSON.stringify(T.c6first.components).length, T.c6first.components.length);
await setTitle(T.c6first.components, 6);
exec(`player.closeContainer(); mc.options.guiScale().set(1); mc.resizeGui(); return 'ok'`);
await openChest();
await sleep(700);
const [scale, left, top] = exec(`def s = mc.screen.@target; return mc.getWindow().getGuiScale()+' '+s.leftPos+' '+s.topPos`).replace(/"/g, '').split(' ').map(Number);
const f = await shot('c6first_1920x1080_s1');
console.log(analyzeMarkers(f, scale, left, top, 'c6first').join('\n'));
