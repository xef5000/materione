// c2v: the advance of a missing glyph (a failed font, a refused 257-px cell); c5on: items over a lone small marker
import { exec, rcon, sleep, titles, shot } from './lib.mjs';
import { setTitle, openChest } from './chest.mjs';
import { analyzeMarkers } from './an.mjs';
import { setWindow } from './win.mjs';
const T = titles();
await rcon('gamemode creative Probe');
console.log('window', await setWindow(1920, 1080));
await rcon(`data merge block 8 -60 3 {Items:[]}`);

// the glyphs after a missing one are drawn 27 px left of the model (33 - 27 = 6: the missing glyph's advance), and 54 after two
await setTitle(T.c2v.components, 6);
exec(`player.closeContainer(); mc.options.guiScale().set(1); mc.resizeGui(); return 'ok'`);
await openChest();
await sleep(700);
const [scale, left, top] = exec(`def s = mc.screen.@target; return mc.getWindow().getGuiScale()+' '+s.leftPos+' '+s.topPos`).replace(/"/g, '').split(' ').map(Number);
const f = await shot('c2v_1920x1080_s1');
console.log(analyzeMarkers(f, scale, left, top, 'c2v', ['AFTER', 'AFTER2']).join('\n'));

// items over a lone small marker: the items draw above it (set the items AFTER the title: setblock replaces the block entity)
const it = [];
for (let s = 0; s < 27; s += 2) it.push(`{Slot:${s}b,id:"minecraft:${['diamond', 'apple', 'emerald', 'gold_ingot'][s % 4]}",count:${1 + (s % 7)}}`);
await setTitle(T.c5on.components, 6);
await rcon(`data merge block 8 -60 3 {Items:[${it.join(',')}]}`);
exec(`player.closeContainer(); mc.options.guiScale().set(2); mc.resizeGui(); return 'ok'`);
await openChest();
await sleep(800);
console.log(await shot('order_c5on_s2'));
await rcon(`data merge block 8 -60 3 {Items:[]}`);
