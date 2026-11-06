import { exec, sleep } from './lib.mjs';

/** Replace the open screen by a client-side one-row (or other) chest screen with the same title. rows 1..6 */
export async function openClientRows(rows) {
  const factory = { 1: 'oneRow', 2: 'twoRows', 3: 'threeRows', 4: 'fourRows', 5: 'fiveRows', 6: 'sixRows' }[rows];
  const r = exec(`
    def inv = player.getInventory().@target;
    def t = mc.screen.getTitle().@target;
    def menu = net.minecraft.world.inventory.ChestMenu.${factory}(9001, inv);
    mc.setScreen(new net.minecraft.client.gui.screens.inventory.ContainerScreen(menu, inv, t));
    return 'client-side ${rows}-row';`);
  await sleep(600);
  return r;
}
