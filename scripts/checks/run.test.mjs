import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  githubSlugs, markdownLinks, checkLinks, checkNaming, definedCodes, expandReserved, checkDocCodes, nextFreeCode,
  isRatchetedSource, milestoneCommentLines, checkRatchet,
} from './run.mjs';

const repo = (files) => ({ read: (f) => files[f], exists: (f) => f in files || Object.keys(files).some((k) => k.startsWith(`${f}/`)) });

test('slugs follow GitHub: punctuation dropped, spaces hyphenated, repeats numbered', () => {
  const s = githubSlugs('# 5.6 Lossless round-trip\n## Cloudflare cost guardrails (binding from M5 — cloud work)\n## Notes\n## Notes\n```\n# not a heading\n```');
  assert.ok(s.has('56-lossless-round-trip'));
  assert.ok(s.has('cloudflare-cost-guardrails-binding-from-m5--cloud-work'));
  assert.ok(s.has('notes') && s.has('notes-1'));
  assert.ok(!s.has('not-a-heading'));
});

test('links: the two broken links found on main in this change are caught', () => {
  // qa/README.md linked `probe.md` (it lives in docs/); cloud/editor-worker/README.md climbed one level too many.
  const { read, exists } = repo({
    'qa/README.md': 'the [agent probe loop](probe.md) and [ok](../docs/probe.md#run)',
    'cloud/editor-worker/README.md': '[fixture](../../../fixtures/protocol/relay-mailbox-v1.json) and [fine](../../fixtures/protocol/relay-mailbox-v1.json)',
    'docs/probe.md': '# Probe\n## Run\n',
    'fixtures/protocol/relay-mailbox-v1.json': '{}',
  });
  assert.deepEqual(checkLinks(['qa/README.md', 'cloud/editor-worker/README.md'], read, exists), [
    'qa/README.md: link to missing file probe.md',
    'cloud/editor-worker/README.md: link to missing file ../../../fixtures/protocol/relay-mailbox-v1.json',
  ]);
});

test('links: anchors must name a real heading; external links and code are ignored', () => {
  const { read, exists } = repo({
    'docs/a.md': 'See [§9](b.md#9-performance-budgets), [gone](b.md#8-removed), [self](#top), [web](https://x.dev/#y), `[code](nope.md)`',
    'docs/b.md': '# B\n## 9. Performance budgets\n',
  });
  assert.deepEqual(checkLinks(['docs/a.md'], read, exists), ['docs/a.md: link to missing heading b.md#8-removed', 'docs/a.md: link to missing heading #top']);
  assert.deepEqual(markdownLinks('[a](x.md "title") ![img](p.png)'), ['x.md', 'p.png']);
});

test('naming: the old working name is caught in any spelling', () => {
  const files = { 'docs/a.md': 'MaterYard pack\nmateryard:item\nMater-Yard', 'CLAUDE.md': 'The old working name "MaterYard" is dead.' };
  assert.equal(checkNaming(['docs/a.md', 'CLAUDE.md'], (f) => files[f]).length, 3);
});

test('diagnostic codes: docs may only name defined or reserved codes; next code skips reservations', () => {
  const defined = definedCodes('new DiagnosticCode("MTR-0001", ...)\n new DiagnosticCode(\n "MTR-0105", ...)\nnew DiagnosticCode("MTR-0125", x)');
  const reserved = expandReserved([{ codes: 'MTR-0106..MTR-0117', reason: 'HUD' }, { codes: 'MTR-1042', reason: 'example' }]);
  assert.equal(reserved.size, 13);
  const docs = { 'docs/logic.md': 'the block MTR-0106 to MTR-0117 is reserved; #465 takes MTR-0118' };
  assert.deepEqual(checkDocCodes(['docs/logic.md'], (f) => docs[f], defined, reserved), [
    'docs/logic.md:1: MTR-0118 is neither defined in api/src/main/java/dev/materione/api/diagnostics/DiagnosticCodes.java nor reserved in scripts/checks/mtr-reserved.json',
  ]);
  assert.equal(nextFreeCode(defined, reserved), 'MTR-0126');
  assert.equal(nextFreeCode(new Set(['MTR-0005']), expandReserved([{ codes: 'MTR-0006..MTR-0009', reason: 'r' }])), 'MTR-0010');
});

test('ratchet: only production sources are counted', () => {
  assert.ok(isRatchetedSource('content/src/main/java/dev/materione/content/furniture/FurnitureDefinition.java'));
  assert.ok(isRatchetedSource('webui/src/codemode/core.ts'));
  assert.ok(isRatchetedSource('cloud/model-proxy/src/index.ts'));
  assert.ok(!isRatchetedSource('core/src/test/java/X.java'));
  assert.ok(!isRatchetedSource('webui/src/a.test.ts'));
  assert.ok(!isRatchetedSource('docs/roadmap.md'));
});

test('ratchet: milestone references in comments count, in code and strings they do not', () => {
  const src = [
    '/**',
    ' * Furniture cycling is parsed but inert until M7.', // FurnitureDefinition.java:14, stale once M7 work lands
    ' */',
    'String label = "M2 // not a comment";',
    'String url = "https://example.dev/M3"; // see M4 and M4.5',
    'int M5 = 5;',
  ].join('\n');
  assert.deepEqual(milestoneCommentLines(src).map((h) => [h.line, h.count]), [[2, 1], [5, 2]]);
});

test('ratchet: a file may not grow past its baseline; shrinking passes', () => {
  const file = 'content/src/main/java/dev/materione/content/furniture/FurnitureDefinition.java';
  const grown = { [file]: '// inert until M7\n// grows in M8\n' };
  assert.equal(checkRatchet([file], (f) => grown[f], { [file]: 1 }).problems.length, 1);
  assert.equal(checkRatchet([file], (f) => grown[f], { [file]: 2 }).problems.length, 0);
  const fresh = { 'core/src/main/java/New.java': '// the M6 executors' };
  assert.equal(checkRatchet(['core/src/main/java/New.java'], (f) => fresh[f], {}).problems.length, 1);
});

test('blockbench: a vendored byte change, addition or removal fails; PIN.md may change', async () => {
  const { blockbenchDrift } = await import('./run.mjs');
  const manifest = { 'webui/public/blockbench/dist/bundle.js': 'aaa', 'webui/public/blockbench/index.html': 'bbb' };
  const hashes = { 'webui/public/blockbench/dist/bundle.js': 'aaa', 'webui/public/blockbench/index.html': 'bbb', 'webui/public/blockbench/PIN.md': 'zzz' };
  const files = Object.keys(hashes);
  assert.deepEqual(blockbenchDrift(files, (f) => hashes[f], manifest), []);
  assert.equal(blockbenchDrift(files, (f) => (f.endsWith('bundle.js') ? 'patched' : hashes[f]), manifest).length, 1);
  assert.equal(blockbenchDrift([...files, 'webui/public/blockbench/js/hook.js'], (f) => hashes[f] ?? 'x', manifest).length, 1);
  assert.equal(blockbenchDrift(files.filter((f) => !f.endsWith('index.html')), (f) => hashes[f], manifest).length, 1);
});

// Findings from the interrogate run on PR #485 (two adversaries), each replayed.
test('interrogate #485: block comments opening mid-line, and their continuation lines, are scanned', () => {
  const src = 'int x = 1; /* inert until M7 */\n/** a */ int y; /* M2 ships */\nx(); /* start\n * M3 here */\nval s = "a"; // ok\nconst re = /\\/\\*M4/; const u = "http://M5"; // M6';
  assert.deepEqual(milestoneCommentLines(src).map((h) => [h.line, h.count]), [[1, 1], [2, 1], [4, 1], [6, 1]]);
  assert.deepEqual(milestoneCommentLines('String t = """\n  M2 in a text block // not a comment\n  """; // M3').map((h) => h.line), [3]);
});

test('interrogate #485: link forms the first regex missed are checked', () => {
  assert.deepEqual(markdownLinks("[e](gone.md 't') <a href=\"gone2.md\">h</a> [p](dir/f(1).md) [s](<my file.md>) [q](a.md (t))"),
    ['gone.md', 'dir/f(1).md', 'my file.md', 'a.md', 'gone2.md']);
});

test('interrogate #485: setext headings get anchors', () => {
  const s = githubSlugs('Setext\n======\n\nSub head\n--------\n\n- list item\n---\n');
  assert.ok(s.has('setext') && s.has('sub-head'));
  assert.ok(!s.has('-list-item') && !s.has('list-item'));
});

test('interrogate #485: the naming check passes on its own files', () => {
  const files = { 'scripts/checks/run.mjs': '"MaterYard"', 'scripts/checks/README.md': 'MaterYard', 'docs/x.md': 'MaterYard' };
  assert.deepEqual(checkNaming(Object.keys(files), (f) => files[f]), ['docs/x.md:1: the old name "MaterYard" (CLAUDE.md: naming is "Materione" everywhere)']);
});
