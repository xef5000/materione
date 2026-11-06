import { exec, rcon, sleep, snbtOf, titles, shot } from './lib.mjs';

/** Place (or rename) a chest whose title is the case's title; rows 6 = double chest, 3 = single. */
export async function setTitle(components, rows = 6, plain = null) {
  const name = plain !== null ? JSON.stringify(plain) : snbtOf(components);
  if (rows === 6) {
    await rcon(`setblock 8 -60 3 minecraft:chest[facing=south,type=right]{CustomName:${name}} replace`);
    await rcon(`setblock 9 -60 3 minecraft:chest[facing=south,type=left]{CustomName:${name}} replace`);
  } else {
    await rcon(`setblock 8 -60 3 minecraft:air replace`);
    await rcon(`setblock 9 -60 3 minecraft:air replace`);
    await rcon(`setblock 8 -60 3 minecraft:chest[facing=south,type=single]{CustomName:${name}} replace`);
  }
}

export async function openChest() {
  exec(`player.closeContainer(); return 'c'`);
  await sleep(300);
  const r = exec(`
    player.setPos(8.5, -60, 1.5);
    player.setRot(0.0f, 28.0f);
    def hit = player.pick(5.0D, 1.0F, false);
    def t = hit.getType().toString();
    if (t.contains('BLOCK')) { mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit); }
    return t + ' ' + hit.getBlockPos().toShortString();`);
  await sleep(1200);
  return r;
}
