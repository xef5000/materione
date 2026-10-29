// node --test scripts/compat/editor-compat.test.mjs
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  extractEditor,
  extractPlugin,
  extractRelayRoutes,
  findProblems,
  readGates,
  readManifests,
  sourceAt,
} from './editor-compat.mjs';

const release = {
  version: '0.1.0',
  protocolVersions: [1],
  capabilities: ['items', 'assets'],
  ops: ['ping', 'browse.get'],
};
const editor = (overrides = {}) => ({
  ops: ['ping', 'browse.get'],
  dynamicCalls: [],
  protocolVersions: [1],
  capabilityChecks: new Set(),
  ...overrides,
});

test('an editor that only calls released ops passes', () => {
  assert.deepEqual(findProblems({ editor: editor(), targets: [release] }), []);
});

test('a new op the release lacks fails', () => {
  const problems = findProblems({ editor: editor({ ops: ['ping', 'items.preview'] }), targets: [release] });
  assert.equal(problems.length, 1);
  assert.match(problems[0], /0\.1\.0: the editor calls op "items\.preview"/);
});

test('a gated op passes only when the editor checks the capability', () => {
  const gates = { 'items.preview': { capability: 'item-preview' } };
  const unchecked = findProblems({ editor: editor({ ops: ['items.preview'] }), targets: [release], gates });
  assert.match(unchecked[0], /never calls hasCapability/);
  const checked = findProblems({
    editor: editor({ ops: ['items.preview'], capabilityChecks: new Set(['item-preview']) }),
    targets: [release],
    gates,
  });
  assert.deepEqual(checked, []);
});

test('a gate does not help when the release advertises the capability or has no list', () => {
  const gates = { 'items.preview': { capability: 'items' } };
  const checks = new Set(['items']);
  const advertised = findProblems({ editor: editor({ ops: ['items.preview'], capabilityChecks: checks }), targets: [release], gates });
  assert.match(advertised[0], /advertises it/);
  const preCapabilities = { ...release, capabilities: undefined };
  const absent = findProblems({ editor: editor({ ops: ['items.preview'], capabilityChecks: checks }), targets: [preCapabilities], gates });
  assert.match(absent[0], /pre-capabilities/);
});

test('the current tree never gets the gate exemption', () => {
  const gates = { 'items.preview': { capability: 'item-preview' } };
  const current = { ...release, version: 'current' };
  const problems = findProblems({
    editor: editor({ ops: ['items.preview'], capabilityChecks: new Set(['item-preview']) }),
    targets: [current],
    gates,
  });
  assert.match(problems[0], /this tree's plugin does not register/);
});

test('no common protocol version fails', () => {
  const problems = findProblems({ editor: editor({ protocolVersions: [2] }), targets: [release] });
  assert.match(problems[0], /no common protocol version/);
});

test('dynamic op names fail', () => {
  const problems = findProblems({ editor: editor({ dynamicCalls: ['x.ts: .call(op...)'] }), targets: [] });
  assert.match(problems[0], /non-literal op name/);
});

test('relay routes are read with their methods and ids', () => {
  const text = [
    '    JsonObject body = postJson("/v1/channels", "", "", 201);',
    '    postJson("/v1/channels/" + channelId + "/frames", token, body, 202);',
    '    URI uri = base.resolve("/v1/channels/" + channelId + "/frames?after=" + after',
    '        + "&wait=" + wait);',
    '    HttpRequest request = HttpRequest.newBuilder(uri)',
    '            .GET()',
    '    HttpRequest close = HttpRequest.newBuilder(base.resolve("/v1/channels/" + channelId + "/close"))',
    '            .POST(HttpRequest.BodyPublishers.noBody())',
    '    StringBuilder query = new StringBuilder("/v1/store?ttl=").append(ttl);',
    '            .POST(HttpRequest.BodyPublishers.ofString(json))',
    '    HttpRequest get = HttpRequest.newBuilder(base.resolve("/v1/store/" + code))',
    '            .GET()',
  ].join('\n');
  assert.deepEqual(extractRelayRoutes(text), [
    'GET /v1/channels/{id}/frames',
    'GET /v1/store/{id}',
    'POST /v1/channels',
    'POST /v1/channels/{id}/close',
    'POST /v1/channels/{id}/frames',
    'POST /v1/store',
  ]);
});

test('the editor ops are read from literal call sites, multi-line included', () => {
  const files = {
    'webui/src/lib/api.ts': "x = c.call('ping');\ny = c.call(\n  'browse.get',\n  {});",
    'webui/src/lib/protocol/session.ts': 'export const SUPPORTED_PROTOCOL_VERSIONS = [1];',
    'webui/src/App.svelte': "if (hasCapability(blob, 'items')) {}",
  };
  const fake = { read: (path) => files[path], list: () => Object.keys(files) };
  const read = extractEditor(fake);
  assert.deepEqual(read.ops, ['browse.get', 'ping']);
  assert.deepEqual(read.dynamicCalls, []);
  assert.deepEqual([...read.capabilityChecks], ['items']);
});

test('a computed op name is reported as dynamic', () => {
  const files = {
    'webui/src/lib/api.ts': 'c.call(opName, {});',
    'webui/src/lib/protocol/session.ts': 'export const SUPPORTED_PROTOCOL_VERSIONS = [1];',
  };
  const fake = { read: (path) => files[path], list: () => Object.keys(files) };
  assert.equal(extractEditor(fake).dynamicCalls.length, 1);
});

test('this tree passes against itself and every committed release', () => {
  const targets = [{ version: 'current', ...extractPlugin(sourceAt()) }, ...readManifests()];
  assert.ok(targets.length >= 2, 'at least one released manifest is committed');
  // the same gates `check` reads: the self-test judges what CI judges, not a stricter reading of it
  assert.deepEqual(findProblems({ editor: extractEditor(), targets, gates: readGates() }), []);
});

test('the preview ops are gated on the capability the editor checks', () => {
  const gates = readGates();
  for (const op of ['runtime.openGui', 'runtime.previewTarget']) {
    assert.equal(gates[op]?.capability, 'gui', `${op} must name the gui capability`);
  }
  assert.ok(extractEditor().capabilityChecks.has('gui'), 'the editor must call hasCapability(…, \'gui\')');
});

test('the GUI builder ops are gated on the capability the editor checks', () => {
  const gates = readGates();
  for (const op of ['gui.get', 'gui.edit', 'gui.customizeItem', 'gui.render', 'gui.validate']) {
    assert.equal(gates[op]?.capability, 'gui', `${op} must name the gui capability`);
  }
  assert.ok(extractEditor().capabilityChecks.has('gui'), 'the editor must call hasCapability(…, \'gui\')');
});
