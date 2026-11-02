import test from 'node:test';
import assert from 'node:assert/strict';
import minecraft from 'minecraft-protocol';
import minecraftData from 'minecraft-data';
import { assertFullState, assertNoClose, assertRichTitle, closeTracker } from './floor-bot.mjs';

test('floor assertions reject stale menu, missing item/cursor and stripped font', () => {
  const data = minecraftData('1.21.4');
  const packet = { windowId: 7, items: [{ itemId: data.itemsByName.diamond.id, itemCount: 3 }],
    carriedItem: { itemId: data.itemsByName.emerald.id, itemCount: 2 } };
  assert.doesNotThrow(() => assertFullState(packet, 7));
  assert.throws(() => assertFullState(packet, 8));
  assert.throws(() => assertFullState({ ...packet, carriedItem: { itemCount: 0 } }, 7));
  assert.throws(() => assertFullState({ ...packet, items: [{ itemCount: 0 }] }, 7));
  assert.doesNotThrow(() => assertRichTitle({ extra: [
    { font: 'overlay:gui', text: '\uE200' }, { font: 'overlay:gui/default_y6', text: 'ACTIVE' },
  ] }, true));
  assert.doesNotThrow(() => assertRichTitle({ font: 'overlay:gui', text: '\uE200' }, false));
  assert.throws(() => assertRichTitle({ text: 'ACTIVE' }, true));
  assert.throws(() => assertRichTitle({ font: 'overlay:gui', text: 'ACTIVE' }, false));
  assert.throws(() => assertRichTitle({ font: 'overlay:gui', text: 'overlay:gui/default_y6 ACTIVE' }, true));
  const closes = closeTracker();
  closes.record({ windowId: 2 });
  const baseline = closes.snapshot(7);
  assert.doesNotThrow(() => assertNoClose(closes, baseline, 7));
  closes.record({ windowId: 7 });
  assert.throws(() => assertNoClose(closes, baseline, 7));
  assert.deepEqual(closes.snapshot(7), { total: 2, current: 1 });
});

test('floor click encodes with the 1.21.4 protocol and a nonempty cursor', () => {
  const data = minecraftData('1.21.4');
  const cursorItem = { itemCount: 2, itemId: data.itemsByName.emerald.id, addedComponentCount: 0,
    removedComponentCount: 0, components: [], removeComponents: [] };
  const serializer = minecraft.createSerializer({ state: minecraft.states.PLAY, isServer: false, version: '1.21.4' });
  const deserializer = minecraft.createDeserializer({ state: minecraft.states.PLAY, isServer: true, version: '1.21.4' });
  const buffer = serializer.createPacketBuffer({ name: 'window_click', params: {
    windowId: 7, stateId: 2, slot: 8, mouseButton: 0, mode: 0, changedSlots: [], cursorItem,
  } });
  const packet = deserializer.parsePacketBuffer(buffer).data;
  assert.equal(packet.name, 'window_click');
  assert.equal(packet.params.slot, 8);
  assert.equal(packet.params.cursorItem.itemCount, 2);
  assert.equal(packet.params.cursorItem.itemId, data.itemsByName.emerald.id);
});

test('floor assertions consume decoded 1.21.4 title and full-inventory packets', () => {
  const data = minecraftData('1.21.4');
  const serializer = minecraft.createSerializer({ state: minecraft.states.PLAY, isServer: true, version: '1.21.4' });
  const deserializer = minecraft.createDeserializer({ state: minecraft.states.PLAY, isServer: false, version: '1.21.4' });
  const roundTrip = (name, params) =>
    deserializer.parsePacketBuffer(serializer.createPacketBuffer({ name, params })).data.params;
  const slot = (name, count) => ({ itemCount: count, itemId: data.itemsByName[name].id,
    addedComponentCount: 0, removedComponentCount: 0, components: [], removeComponents: [] });
  const full = roundTrip('window_items', { windowId: 7, stateId: 2, items: [slot('diamond', 3)],
    carriedItem: slot('emerald', 2) });
  assertFullState(full, 7);
  const open = roundTrip('open_window', { windowId: 7, inventoryType: 0,
    windowTitle: { type: 'compound', value: { text: { type: 'string', value: '\uE200' },
      font: { type: 'string', value: 'overlay:gui' }, extra: { type: 'list',
        value: { type: 'compound', value: [{ text: { type: 'string', value: 'ACTIVE' },
          font: { type: 'string', value: 'overlay:gui/default_y6' } }] } } } } });
  assert.equal(open.windowId, 7);
  assertRichTitle(open.windowTitle, true);
  const close = roundTrip('close_window', { windowId: 7 });
  const closes = closeTracker();
  const baseline = closes.snapshot(7);
  closes.record(close);
  assert.throws(() => assertNoClose(closes, baseline, 7));
});
