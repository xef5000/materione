// gui.click_outside and clicks by slot id (gui.md 12.12 row R), on the live client. Real client clicks go through
// AbstractContainerScreen.mouseClicked (GUI-scaled coordinates); the probe plugin logs what the server's
// InventoryClickEvent says: the three facts GuiListener.route reads (clickedInventory == null for "outside",
// is it the top inventory, getSlot) plus rawSlot.
import { exec, rcon, sleep, setWindow, open, info, logSize, logSince } from './lib.mjs';

rcon('gamemode survival Probe');
rcon('clear Probe');
await setWindow(1920, 1080, 2);
const SLOTS = {
  hopper: (i) => [44 + 18 * i, 20],
  dispenser: (i) => [62 + 18 * (i % 3), 17 + 18 * Math.trunc(i / 3)],
};
const INV = { hopper: [8, 51], dispenser: [8, 84] };
const COUNT = { hopper: 5, dispenser: 9 };

async function click(s, label, gx, gy, button = 0) {
  const off = logSize();
  exec(`def scr = mc.screen.@target; def ev = new net.minecraft.client.input.MouseButtonEvent(${s.leftPos + gx}.0d, ${s.topPos + gy}.0d, new net.minecraft.client.input.MouseButtonInfo(${button}, 0)); scr.mouseClicked(ev, false); scr.mouseReleased(ev); return 'clicked'`);
  await sleep(700);
  const lines = logSince(off, 'MATERIONE-CTPROBE click').map((l) => l.replace(/^.*MATERIONE-CTPROBE click /, ''));
  console.log(`${label.padEnd(52)} -> ${lines.length ? lines.join(' | ') : 'no click event'}`);
}

for (const kind of ['hopper', 'dispenser']) {
  // the art covers the whole screen, and (markers) the ground around it, to show that art takes no click
  const cover = kind === 'hopper' ? 'ch' : 'cdt', around = kind === 'hopper' ? 'mht' : 'mdt';
  let s = await open(kind, cover, {});
  console.log(`\n=== ${kind}: ${s.screen} ${s.imageWidth}x${s.imageHeight}, left=${s.leftPos} top=${s.topPos}, menuSlots=${s.menuSlots} ===`);
  for (let i = 0; i < COUNT[kind]; i++) {
    const [x, y] = SLOTS[kind](i);
    await click(s, `slot ${i} (centre ${x + 8},${y + 8}), full-screen art over it`, x + 8, y + 8);
  }
  await click(s, 'player inventory, first main slot', INV[kind][0] + 8, INV[kind][1] + 8);
  await click(s, 'player inventory, first hotbar slot', INV[kind][0] + 8, INV[kind][1] + 58 + 8);
  await click(s, 'inside the screen, between slots (no slot)', 4, 100);
  s = await open(kind, around, {});
  await click(s, 'outside, left, on art L (x=-24,y=66)', -24, 66);
  await click(s, 'outside, right, on art R (x=200,y=66)', 200, 66);
  await click(s, 'outside, above, on art U (x=88,y=-24)', 88, -24);
  await click(s, `outside, below, on art D (x=88,y=${kind === 'hopper' ? 157 : 190})`, 88, kind === 'hopper' ? 157 : 190);
  await click(s, 'outside, empty ground (x=-150,y=-120)', -150, -120);
  await click(s, 'outside, right button, on art L', -24, 66, 1);
  const [x1] = SLOTS[kind](1);
  await click(s, 'slot 1, right button', x1 + 8, SLOTS[kind](1)[1] + 8, 1);
}
exec(`player.closeContainer(); return 'c'`);
rcon('gamemode creative Probe');
