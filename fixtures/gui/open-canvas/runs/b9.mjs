// Q6: clicks outside the container, including on art drawn there, against a real Materione GUI (server rule gui.click_outside)
import fs from 'node:fs';
import { exec, rcon, sleep, titles, shot, REPO } from './lib.mjs';
const T = titles();
const comps = T.c1r6.components;
const json = JSON.stringify({ text: '', extra: comps }).replace(/'/g, "\\'");
const logf = `${REPO}/harness/build/probe/server/logs/latest.log`;
const marks = () => fs.readFileSync(logf, 'utf8').split('\n').filter((l) => l.includes('MTR275-CLICK')).length;

exec(`mc.options.guiScale().set(2); mc.resizeGui(); return 'ok'`);
console.log(exec(`
  def scr = mc.screen.@target;
  def js = com.google.gson.JsonParser.parseString('${json}');
  def r = net.minecraft.network.chat.ComponentSerialization.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, js);
  def comp = r.result().get();
  def f = net.minecraft.client.gui.screens.Screen.class.getDeclaredField('title');
  f.setAccessible(true); f.set(scr, comp);
  return 'title replaced: ' + scr.getTitle().getString().length() + ' chars, left=' + scr.leftPos + ' top=' + scr.topPos;`));
await sleep(500);
console.log(await shot('q6_gui_with_art_s2'));

// click at a GUI-scaled position (gx, gy); returns the number of new MTR275 markers
async function click(label, gx, gy, button = 0) {
  const off = fs.statSync(logf).size;
  exec(`
    def scr = mc.screen.@target;
    def ev = new net.minecraft.client.input.MouseButtonEvent(${gx}.0d, ${gy}.0d, new net.minecraft.client.input.MouseButtonInfo(${button}, 0));
    scr.mouseClicked(ev, false);
    scr.mouseReleased(ev);
    return 'clicked';`);
  await sleep(2000);
  const out = fs.readFileSync(logf).subarray(off).toString().split(/\r?\n/).filter((l) => l.includes('MTR275-CLICK'));
  console.log(`${label.padEnd(58)} -> ${out.length ? out.map((l) => l.replace(/^.*?\]: /, '')).join(' | ') : 'no rule fired'}`);
}
const [left, top] = exec(`def s = mc.screen.@target; return s.leftPos+' '+s.topPos`).replace(/"/g, '').split(' ').map(Number);
console.log('left/top', left, top);
// L marker: gui x -40..-8, y 50..82 relative to the screen; the 3-row screen is 176x168
await click('on art left of the screen (L, x=-24,y=66)', left - 24, top + 66);
await click('on art right of the screen (R, x=200,y=66)', left + 200, top + 66);
await click('on art above the screen (U, x=88,y=-24)', left + 88, top - 24);
await click('on art below the screen (D, x=88,y=244)', left + 88, top + 244);
await click('empty ground far outside (x=-150,y=-150)', left - 150, top - 150);
await click('right button on art left of the screen', left - 24, top + 66, 1);
await click('inside the container, on the gem slot 13', left + 8 + 4 * 18 + 9, top + 18 + 1 * 18 + 9);
await click('inside the container, on an empty slot 0', left + 8 + 9, top + 18 + 9);
await click('inside the container, on the ON art over slot 4 (x=88,y=30)', left + 88, top + 30);
