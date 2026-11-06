// c2b (minimal padding, equal boundary) and c2mix (valid + violating provider in one font)
import { exec, rcon, sleep, titles, shot } from './lib.mjs';
import { setTitle, openChest } from './chest.mjs';
import { analyzeMarkers } from './an.mjs';
const T = titles();
await rcon('gamemode creative Probe');
for (const id of ['c2b', 'c2mix']) {
  await setTitle(T[id].components, 6);
  exec(`player.closeContainer(); mc.options.guiScale().set(1); mc.resizeGui(); return 'ok'`);
  await openChest();
  await sleep(600);
  const [scale, left, top] = exec(`def s = mc.screen.@target; return mc.getWindow().getGuiScale()+' '+s.leftPos+' '+s.topPos`).replace(/"/g, '').split(' ').map(Number);
  const f = await shot(`${id}_1920x1080_s1`);
  console.log('---', id);
  console.log(analyzeMarkers(f, scale, left, top, id).join('\n'));
}
