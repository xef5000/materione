// U5 (gui.md 12.12): a dynamic title refresh on an open hopper and dispenser, through the transport the product
// uses (a ClientboundOpenScreenPacket with the open menu's id, the type CraftContainer maps the inventory to, and
// the new title, then a resync). Reads, after each refresh: the screen's own title origin (init() re-centring), the
// markers' pixels, the menu id, the items in the client's menu, and the server's open/close events.
import { exec, rcon, sleep, layouts, setWindow, open, info, shot, logSize, logSince, analyzeMarkers } from './lib.mjs';

const L = layouts();
rcon('gamemode creative Probe');
rcon('clear Probe');
await setWindow(1920, 1080, 2);

for (const [kind, n] of [['hopper', 5], ['dispenser', 9]]) {
  console.log(`\n=== ${kind} ===`);
  let s = await open(kind, 's1t', {});
  const screenId = () => exec(`def m = player.containerMenu; def sb = new StringBuilder(); for (int i = 0; i < ${n}; i++) { sb.append(m.getSlot(i).getItem().getCount()).append(',') }; return System.identityHashCode(mc.screen.@target) + ' ' + m.containerId + ' ' + sb.toString()`).replace(/"/g, '');
  const before = screenId();
  console.log(`open s1t   : titleLabelX=${s.titleLabelX} width=${s.titleWidth} screen/menu/items=${before}`);
  for (const next of ['s2t', 's3n', 's1t']) {
    const off = logSize();
    rcon(`ctprobe title Probe ${next}`);
    await sleep(1500);
    s = info();
    const f = await shot(`u5_${kind}_${next}`);
    const a = analyzeMarkers(f, next, s);
    const ev = logSince(off, 'MATERIONE-CTPROBE').map((l) => l.replace(/^.*MATERIONE-CTPROBE /, '').split(' ')[0]);
    const expected = kind === 'dispenser' ? Math.trunc((s.imageWidth - L[next].width) / 2) : 8;
    console.log(`refresh ${next}: titleLabelX=${s.titleLabelX} (expected ${expected}) font.width=${s.titleWidth} (layout ${L[next].width}) screen/menu/items=${screenId()} markers off model: ${a.miss}; server events: ${ev.join(', ')}`);
    console.log(a.lines.map((l) => '   ' + l).join('\n'));
  }
  // the window still routes clicks after the refreshes (holder identity survives)
  const off = logSize();
  exec(`def scr = mc.screen.@target; def ev = new net.minecraft.client.input.MouseButtonEvent(${s.leftPos + (kind === 'hopper' ? 44 + 18 * 2 + 8 : 62 + 18 + 8)}.0d, ${s.topPos + (kind === 'hopper' ? 28 : 17 + 8)}.0d, new net.minecraft.client.input.MouseButtonInfo(0, 0)); scr.mouseClicked(ev, false); scr.mouseReleased(ev); return 'clicked'`);
  await sleep(700);
  console.log('click after the refreshes: ' + logSince(off, 'MATERIONE-CTPROBE click').map((l) => l.replace(/^.*MATERIONE-CTPROBE click /, '')).join(' | '));
}
exec(`player.closeContainer(); return 'c'`);
