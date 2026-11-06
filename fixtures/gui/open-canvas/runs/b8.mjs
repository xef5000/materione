// Q7: no background glyph at all: plain title, text-only offset-font title, empty title
import { exec, rcon, sleep, titles, shot } from './lib.mjs';
import { setTitle, openChest } from './chest.mjs';
import { setWindow } from './win.mjs';
const T = titles();
await rcon('gamemode creative Probe');
console.log('window', await setWindow(1280, 720));
await rcon(`data merge block 8 -60 3 {Items:[]}`);
for (const [name, comps, plain] of [['plain', null, 'Plain Title'], ['c7text', T.c7text.components, null], ['empty', null, '']]) {
  await setTitle(comps, 6, plain);
  exec(`player.closeContainer(); mc.options.guiScale().set(2); mc.resizeGui(); return 'ok'`);
  await openChest();
  await sleep(800);
  console.log(await shot(`q7_${name}_s2`));
}
