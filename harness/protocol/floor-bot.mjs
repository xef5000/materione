import assert from 'node:assert/strict';
import readline from 'node:readline';
import { pathToFileURL } from 'node:url';
import minecraft from 'minecraft-protocol';
import minecraftData from 'minecraft-data';

const data = minecraftData('1.21.4');
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function closeTracker() {
  const byId = new Map();
  let total = 0;
  return {
    record(packet) {
      total++;
      byId.set(packet.windowId, (byId.get(packet.windowId) ?? 0) + 1);
    },
    snapshot(windowId) {
      return { total, current: byId.get(windowId) ?? 0 };
    },
  };
}

export function assertNoClose(tracker, baseline, windowId) {
  assert.deepEqual(tracker.snapshot(windowId), baseline, 'server sent a close_window packet');
}

export function assertFullState(packet, windowId) {
  assert.equal(packet?.windowId, windowId, 'full state belongs to the current native menu');
  assert.equal(packet.items?.[0]?.itemId, data.itemsByName.diamond.id, 'input material');
  assert.equal(packet.items?.[0]?.itemCount, 3, 'input count');
  assert.equal(packet.carriedItem?.itemId, data.itemsByName.emerald.id, 'cursor material');
  assert.equal(packet.carriedItem?.itemCount, 2, 'cursor count');
}

export function assertRichTitle(title, shown) {
  const fonts = [];
  const text = [];
  function visit(node) {
    if (!node || typeof node !== 'object') return;
    for (const [key, value] of Object.entries(node)) {
      if (key === 'font' || key === 'text') {
        const string = typeof value === 'string' ? value
          : value?.type === 'string' && typeof value.value === 'string' ? value.value : undefined;
        if (string !== undefined) (key === 'font' ? fonts : text).push(string);
      }
      if (typeof value === 'object') visit(value);
    }
  }
  visit(title);
  assert.ok(fonts.includes('overlay:gui'), 'native component carries the authored background font');
  assert.equal(fonts.includes('overlay:gui/default_y6'), shown, 'text font visibility');
  assert.equal(text.join('').includes('ACTIVE'), shown, 'title visibility');
}

async function waitFor(predicate, message) {
  for (let i = 0; i < 100; i++) {
    if (predicate()) return;
    await pause(100);
  }
  throw new Error(`timeout waiting for ${message}`);
}

async function main() {
  const port = Number(process.argv[2]);
  assert.ok(Number.isInteger(port) && port > 0, 'server port required');
  const client = minecraft.createClient({ host: '127.0.0.1', port, username: 'FloorBot',
    auth: 'offline', version: '1.21.4' });
  let latestOpen;
  let latestFull;
  let stateId = 0;
  let opens = 0;
  let fullStates = 0;
  let fullStatesAtOpen = 0;
  let currentId;
  let cursor;
  let failed;
  const closes = closeTracker();
  let baselineCloses;
  client.on('error', (error) => { failed = error; });
  client.on('end', (reason) => { failed = new Error(`client disconnected: ${reason}`); });
  client.on('position', (packet) => client.write('teleport_confirm', { teleportId: packet.teleportId }));
  client.on('add_resource_pack', (packet) => client.write('resource_pack_receive', {
    uuid: packet.uuid, result: 1,
  }));
  client.on('open_window', (packet) => {
    latestOpen = packet;
    fullStatesAtOpen = fullStates;
    opens++;
  });
  client.on('close_window', (packet) => closes.record(packet));
  client.on('window_items', (packet) => {
    if (latestOpen && packet.windowId === latestOpen.windowId) {
      latestFull = packet;
      stateId = packet.stateId;
      cursor = packet.carriedItem;
      fullStates++;
    }
  });
  client.on('set_slot', (packet) => {
    if (latestOpen && packet.windowId === latestOpen.windowId) stateId = packet.stateId;
    if (packet.windowId === -1 && packet.slot === -1) cursor = packet.item;
  });
  client.on('login', () => console.log('MATERIONE-FLOOR-BOT stage=login ok=true'));
  const commands = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  try {
    for await (const stage of commands) {
      if (failed) throw failed;
      if (stage === 'baseline') {
        await waitFor(() => latestOpen && latestFull && latestFull.items?.[0]?.itemCount === 3
          && latestFull.carriedItem?.itemCount === 2, 'initial full state');
        currentId = latestOpen.windowId;
        assert.notEqual(currentId, 0);
        baselineCloses = closes.snapshot(currentId);
        assertRichTitle(latestOpen.windowTitle, false);
        assertFullState(latestFull, currentId);
      } else if (stage === 'shown' || stage === 'hidden') {
        const oldOpens = opens;
        const oldFullStates = fullStates;
        client.write('window_click', { windowId: currentId, stateId, slot: 8, mouseButton: 0,
          mode: 0, changedSlots: [], cursorItem: cursor });
        await waitFor(() => opens > oldOpens && fullStates > oldFullStates && fullStates > fullStatesAtOpen,
          `${stage} title and subsequent full state`);
        assert.equal(opens, oldOpens + 1, 'one title packet per changed render');
        assert.equal(latestOpen.windowId, currentId, 'native container id changed');
        assertNoClose(closes, baselineCloses, currentId);
        assertRichTitle(latestOpen.windowTitle, stage === 'shown');
        assertFullState(latestFull, currentId);
      } else if (stage === 'steady') {
        const before = opens;
        await pause(1600); // more than two refresh periods at 10 ticks
        assert.equal(opens, before, 'unchanged title was sent again');
        assertNoClose(closes, baselineCloses, currentId);
        assertFullState(latestFull, currentId);
      } else if (stage === 'stop') {
        console.log('MATERIONE-FLOOR-BOT stage=stop ok=true');
        break;
      } else {
        throw new Error(`unknown stage ${stage}`);
      }
      if (failed) throw failed;
      const observedCloses = closes.snapshot(currentId);
      console.log(`MATERIONE-FLOOR-BOT stage=${stage} ok=true id=${currentId} opens=${opens} full=${fullStates}`
        + ` closes=${observedCloses.total} currentCloses=${observedCloses.current}`);
    }
  } catch (error) {
    console.error(`MATERIONE-FLOOR-BOT stage=error ok=false ${error.stack || error}`);
    process.exitCode = 1;
  } finally {
    client.end('floor scenario complete');
    commands.close();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
