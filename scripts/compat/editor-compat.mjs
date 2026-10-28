#!/usr/bin/env node
// Editor/plugin/relay compatibility gate (web.md §4.2, build.md §8).
//
// The cloud editor (SPA) and the relay Worker deploy independently of the
// plugin jars servers already run. Every published plugin version leaves a
// manifest in fixtures/protocol/released/<version>.json: the editor protocol
// versions it accepts, the RPC ops it registers, the capabilities its session
// blob advertises, and the relay routes it calls. This script
//
//   snapshot <version> [--ref <git-ref>] [--check]
//       writes (or, with --check, verifies) the manifest for <version>,
//       extracted from the plugin sources at <git-ref> (default: working tree)
//   check
//       fails when the editor in webui/src would break a released plugin:
//       no common protocol version, or an RPC op the release does not
//       register (unless gated, see fixtures/protocol/editor-op-gates.json)
//
// The relay half (released routes still served by the Worker) is checked by
// cloud/editor-worker/test/released-routes.test.ts against the same manifests.
// Pure Node, no dependencies, so CI runs it without a Gradle or npm install.

import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
export const RELEASED_DIR = join(ROOT, 'fixtures', 'protocol', 'released');
export const GATES_FILE = join(ROOT, 'fixtures', 'protocol', 'editor-op-gates.json');

const WEB_JAVA = 'web/src/main/java';
const WIRE = `${WEB_JAVA}/dev/materione/web/protocol/Wire.java`;
const SESSION_BLOB = `${WEB_JAVA}/dev/materione/web/protocol/SessionBlob.java`;
const RELAY_CLIENT = `${WEB_JAVA}/dev/materione/web/relay/RelayClient.java`;
const SPA_SRC = 'webui/src';
const SPA_SESSION = 'webui/src/lib/protocol/session.ts';

/** File access over the working tree, or over a git ref. */
export function sourceAt(ref) {
  if (!ref) {
    return {
      read: (path) => readFileSync(join(ROOT, path), 'utf8'),
      list: (dir) => walk(join(ROOT, dir)).map((file) => relative(ROOT, file).replaceAll('\\', '/')),
    };
  }
  const git = (...args) => execFileSync('git', args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 64 << 20 });
  return {
    read: (path) => git('show', `${ref}:${path}`),
    list: (dir) => git('ls-tree', '-r', '--name-only', ref, '--', dir).split('\n').filter(Boolean),
  };
}

function walk(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

const sortedUnique = (values) => [...new Set(values)].sort();

/** The plugin side of the contract, extracted from its Java sources. */
export function extractPlugin(source) {
  const javaFiles = source.list(WEB_JAVA).filter((file) => file.endsWith('.java'));
  const ops = [];
  for (const file of javaFiles) {
    const text = source.read(file);
    for (const match of text.matchAll(/dispatcher\s*\.\s*register\(\s*"([^"]+)"/g)) ops.push(match[1]);
    if (/dispatcher\s*\.\s*register\(\s*[^"\s]/.test(text)) {
      throw new Error(`${file}: an op is registered under a non-literal name; the compat gate cannot read it`);
    }
  }
  if (!ops.length) throw new Error(`no dispatcher.register("…") calls found under ${WEB_JAVA}`);

  const wire = source.read(WIRE);
  const constants = new Map([...wire.matchAll(/static\s+final\s+int\s+(\w+)\s*=\s*(\d+)\s*;/g)]
    .map((match) => [match[1], Number(match[2])]));
  const supported = /SUPPORTED_VERSIONS\s*=\s*List\.of\(([^)]*)\)/.exec(wire);
  if (!supported) throw new Error(`${WIRE}: SUPPORTED_VERSIONS = List.of(…) not found`);
  const protocolVersions = supported[1].split(',').map((token) => token.trim()).filter(Boolean).map((token) => {
    const value = /^\d+$/.test(token) ? Number(token) : constants.get(token);
    if (value === undefined) throw new Error(`${WIRE}: cannot resolve protocol version "${token}"`);
    return value;
  });

  const blob = source.read(SESSION_BLOB);
  const caps = /CURRENT_CAPABILITIES\s*=\s*List\.of\(([^)]*)\)/s.exec(blob);
  if (!caps) throw new Error(`${SESSION_BLOB}: CURRENT_CAPABILITIES = List.of(…) not found`);
  const capabilities = [...caps[1].matchAll(/"([^"]+)"/g)].map((match) => match[1]);

  return {
    protocolVersions: sortedUnique(protocolVersions),
    capabilities: sortedUnique(capabilities),
    ops: sortedUnique(ops),
    relayRoutes: extractRelayRoutes(source.read(RELAY_CLIENT)),
  };
}

/**
 * Relay routes the plugin calls: each "/v1/…" literal in RelayClient, with
 * concatenated ids as {id} and the query dropped. A literal passed to
 * postJson is a POST; otherwise the first .GET()/.POST(/.DELETE() after it.
 */
export function extractRelayRoutes(text) {
  const lines = text.split('\n');
  const routes = [];
  lines.forEach((line, index) => {
    for (const match of line.matchAll(/"(\/v1\/[^"]*)"((?:\s*\+\s*\w+\s*\+\s*"[^"]*")*)/g)) {
      // "/v1/channels/" + channelId + "/frames?after=" -> /v1/channels/{id}/frames
      const raw = match[0].replace(/"\s*\+\s*\w+\s*\+\s*"/g, '{id}').replaceAll('"', '');
      let path = raw.split('?')[0];
      // A literal ending in "/" is followed by an id: "/v1/store/" + code.
      if (path.endsWith('/') && !raw.includes('?')) path += '{id}';
      let method;
      if (/postJson\(\s*$/.test(line.slice(0, match.index))) method = 'POST';
      for (let next = index; !method && next < Math.min(lines.length, index + 10); next += 1) {
        const verb = /\.(GET|DELETE)\(\)|\.(POST)\(/.exec(lines[next]);
        if (verb) method = verb[1] ?? verb[2];
      }
      if (!method) throw new Error(`RelayClient line ${index + 1}: cannot tell the HTTP method for ${raw}`);
      routes.push(`${method} ${path}`);
    }
  });
  return sortedUnique(routes);
}

/** The editor side: ops it calls and protocol versions it speaks. */
export function extractEditor(source = sourceAt()) {
  const files = source.list(SPA_SRC).filter((file) => /\.(ts|svelte)$/.test(file));
  const ops = [];
  const dynamic = [];
  for (const file of files) {
    const text = source.read(file);
    for (const match of text.matchAll(/\.call\(\s*(['"`])([^'"`]+)\1/g)) ops.push(match[2]);
    // Look past whitespace, so a literal op on the next line is not "dynamic".
    for (const match of text.matchAll(/\.call\((?=\s*([^\s'"`)][^)\s,]*))/g)) {
      dynamic.push(`${file}: .call(${match[1]}...)`);
    }
  }
  const session = source.read(SPA_SESSION);
  const versions = /SUPPORTED_PROTOCOL_VERSIONS\s*=\s*\[([^\]]*)\]/.exec(session);
  if (!versions) throw new Error(`${SPA_SESSION}: SUPPORTED_PROTOCOL_VERSIONS = […] not found`);
  const capabilityChecks = new Set(
    files.flatMap((file) => [...source.read(file).matchAll(/hasCapability\([^,]+,\s*['"]([^'"]+)['"]\)/g)].map((m) => m[1])),
  );
  return {
    ops: sortedUnique(ops),
    dynamicCalls: dynamic,
    protocolVersions: versions[1].split(',').map((v) => v.trim()).filter(Boolean).map(Number),
    capabilityChecks,
  };
}

export function readManifests(dir = RELEASED_DIR) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).filter((name) => name.endsWith('.json')).sort().map((name) => {
    const manifest = JSON.parse(readFileSync(join(dir, name), 'utf8'));
    if (`${manifest.version}.json` !== name) throw new Error(`${name}: "version" is ${manifest.version}`);
    return manifest;
  });
}

/**
 * Problems that would break a released plugin (or the jar built from this
 * tree, as "current"). An op missing from a release passes only when it is
 * gated: editor-op-gates.json names a capability for it, the release
 * advertises a capability list without it, and the editor checks it with
 * hasCapability(…, '<capability>').
 */
export function findProblems({ editor, targets, gates = {} }) {
  const problems = [];
  if (editor.dynamicCalls.length) {
    problems.push(`RPC calls with a non-literal op name (the gate cannot check them):\n    ${editor.dynamicCalls.join('\n    ')}`);
  }
  for (const target of targets) {
    const label = target.version;
    const common = editor.protocolVersions.filter((v) => target.protocolVersions.includes(v));
    if (!common.length) {
      problems.push(`${label}: no common protocol version (editor speaks [${editor.protocolVersions}], plugin accepts [${target.protocolVersions}])`);
    }
    for (const op of editor.ops) {
      if (target.ops.includes(op)) continue;
      const gate = gates[op];
      if (gate && target.version !== 'current') {
        const gated = Array.isArray(target.capabilities) && !target.capabilities.includes(gate.capability);
        if (gated && editor.capabilityChecks.has(gate.capability)) continue;
        if (!gated) {
          problems.push(`${label}: op "${op}" is gated on capability "${gate.capability}", but ${label} ${Array.isArray(target.capabilities) ? 'advertises it' : 'advertises no capability list (pre-capabilities jars assume full support)'}`);
          continue;
        }
        problems.push(`${label}: op "${op}" is gated on capability "${gate.capability}", but the editor never calls hasCapability(…, '${gate.capability}')`);
        continue;
      }
      problems.push(`${label}: the editor calls op "${op}", which ${label === 'current' ? 'this tree\'s plugin does not register' : 'that release does not register'}`);
    }
  }
  return problems;
}

function manifestFor(version, ref) {
  return { version, ...(ref ? { ref } : {}), ...extractPlugin(sourceAt(ref)) };
}

const stable = (value) => `${JSON.stringify(value, null, 2)}\n`;

function main(argv) {
  const [command, ...rest] = argv;
  if (command === 'snapshot') {
    const version = rest.find((arg) => !arg.startsWith('--') && rest[rest.indexOf(arg) - 1] !== '--ref');
    const refIndex = rest.indexOf('--ref');
    const ref = refIndex >= 0 ? rest[refIndex + 1] : undefined;
    if (!version) throw new Error('usage: editor-compat.mjs snapshot <version> [--ref <git-ref>] [--check]');
    const file = join(RELEASED_DIR, `${version}.json`);
    const manifest = manifestFor(version, ref);
    if (rest.includes('--check')) {
      if (!existsSync(file)) {
        console.error(`missing ${relative(ROOT, file)}; run: node scripts/compat/editor-compat.mjs snapshot ${version} and commit it`);
        return 1;
      }
      const committed = JSON.parse(readFileSync(file, 'utf8'));
      const { ref: _ignored, ...withoutRef } = committed;
      const { ref: _fresh, ...freshWithoutRef } = manifest;
      if (stable(withoutRef) !== stable(freshWithoutRef)) {
        console.error(`${relative(ROOT, file)} does not match the plugin sources; re-run snapshot ${version} and commit it`);
        return 1;
      }
      console.log(`${relative(ROOT, file)} matches the plugin sources`);
      return 0;
    }
    mkdirSync(RELEASED_DIR, { recursive: true });
    writeFileSync(file, stable(manifest));
    console.log(`wrote ${relative(ROOT, file)}: ${manifest.ops.length} ops, protocol [${manifest.protocolVersions}], ${manifest.relayRoutes.length} relay routes`);
    return 0;
  }
  if (command === 'check') {
    const editor = extractEditor();
    const released = readManifests();
    const targets = [{ version: 'current', ...extractPlugin(sourceAt()) }, ...released];
    const gates = existsSync(GATES_FILE) ? JSON.parse(readFileSync(GATES_FILE, 'utf8')).gates ?? {} : {};
    const problems = findProblems({ editor, targets, gates });
    if (problems.length) {
      console.error(`editor compatibility: ${problems.length} problem(s)\n- ${problems.join('\n- ')}`);
      console.error('\nA released plugin keeps running after the cloud editor redeploys. Either keep the op the release has, '
        + 'or gate the new feature on a capability (fixtures/protocol/editor-op-gates.json + hasCapability in the editor). '
        + 'To drop support for a release, delete its manifest in fixtures/protocol/released/.');
      return 1;
    }
    console.log(`editor compatibility ok: ${editor.ops.length} ops, protocol [${editor.protocolVersions}] against current + ${released.map((m) => m.version).join(', ') || 'no releases'}`);
    return 0;
  }
  console.error('usage: editor-compat.mjs snapshot <version> [--ref <git-ref>] [--check] | check');
  return 2;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  }
}
