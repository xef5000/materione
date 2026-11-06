// draw order: slot items and the Inventory label against art that covers them (c3), and against off-screen art (c1r6)
import { exec, rcon, sleep, titles, shot } from './lib.mjs';
import { setTitle, openChest } from './chest.mjs';
import { setWindow } from './win.mjs';
const T = titles();
await rcon('gamemode creative Probe');
console.log('window', await setWindow(1920, 1080));
const it = [];
for (let s = 0; s < 54; s += 4) it.push(`{Slot:${s}b,id:"minecraft:${['diamond', 'apple', 'emerald', 'gold_ingot'][(s / 4) % 4]}",count:${1 + (s % 7)}}`);
for (const id of ['c3', 'c1r6']) {
  await setTitle(T[id].components, 6);
  await rcon(`data merge block 8 -60 3 {Items:[${it.join(',')}]}`);
  await rcon(`data merge block 9 -60 3 {Items:[]}`);
  exec(`player.closeContainer(); mc.options.guiScale().set(2); mc.resizeGui(); return 'ok'`);
  await openChest();
  await sleep(800);
  console.log(await shot(`order_${id}_s2`));
}
