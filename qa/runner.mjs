#!/usr/bin/env node
// The in-game QA suite runner (issue #38) — trackable, isolating, fixable
// in-game testing on top of the probe loop (#34).
//
//   node qa/runner.mjs check              # catalog integrity (CI-safe, no Minecraft)
//   node qa/runner.mjs plan               # what needs testing at HEAD (ledger-driven)
//   node qa/runner.mjs run [selectors]    # execute: ids, suite:<name>, all, --all,
//                                         #          --no-build, --keep-server, --file
//   node qa/runner.mjs report [runId]     # print a run summary
//   node qa/runner.mjs file <runId> <id>  # file a GitHub issue from a failure dossier
//   node qa/runner.mjs mark <id> pass|fail [--note s] [--shot p] [--by who]
//                                         # close a needs-eyes verdict
//
// Zero npm dependencies. The suite runs LOCALLY through the probe agent
// (`scripts/probe/probe.mjs agent`) — deliberately not CI (issue #34/#38);
// the only CI step is `check`. See qa/README.md for the charter.

import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import readline from 'node:readline';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { redactEvidence } from './evidence-redaction.mjs';
import { createQaBrowserAdapter } from '../webui/test-support/qa-browser.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const QA = path.join(ROOT, 'qa');
const CATALOG_DIR = path.join(QA, 'catalog');
const LEDGER_DIR = path.join(QA, 'ledger');
const RESULTS_DIR = path.join(QA, 'results');
const EVIDENCE_DIR = path.join(QA, 'evidence');
const PROBE = path.join(ROOT, 'scripts', 'probe', 'probe.mjs');
const SERVER_LOG = path.join(ROOT, 'harness', 'build', 'probe', 'server', 'logs', 'latest.log');
const CLIENT_LOG = path.join(ROOT, 'harness', 'build', 'probe', 'client', 'client.log');
const VERSIONS_TOML = path.join(ROOT, 'gradle', 'libs.versions.toml');

// Probe pins whose change invalidates every recorded result (a new server or
// bridge build is a new test environment, not a diff-sized change).
const ENV_PINS = [
  'paper-probe-version', 'paper-probe-build', 'paper-probe-url', 'paper-probe-sha256',
  'probe-debugbridge-url', 'probe-debugbridge-sha1', 'probe-fabric-loader',
];

const GH_FALLBACK = 'C:\\Program Files\\GitHub CLI\\gh.exe';
const PROJECT_NUMBER = '1';
const PROJECT_OWNER = 'xef5000';
const PROJECT_ID = process.env.MTR_PROJECT_ID ?? 'PVT_kwHOA9Lzdc4Bmaqo';

class QaError extends Error {}
class QaAssertionError extends Error {
  constructor(message, { operator, expected, actual }) {
    super(message);
    this.operator = operator;
    this.expected = expected;
    this.actual = actual;
  }
}

main(process.argv.slice(2)).catch((e) => {
  process.stderr.write(`qa: ${redactEvidence(e.message ?? String(e))}\n`);
  process.exit(e instanceof QaError ? 1 : 1);
});

async function main(argv) {
  const command = argv.shift();
  switch (command) {
    case 'check': return cmdCheck();
    case 'plan': return cmdPlan(argv);
    case 'run': return cmdRun(argv);
    case 'report': return cmdReport(argv);
    case 'file': return cmdFile(argv);
    case 'mark': return cmdMark(argv);
    default: usage();
  }
}

function usage() {
  process.stdout.write(`qa — in-game QA suite (issue #38; charter: qa/README.md)

  check                     validate the catalog (unique ids, acyclic deps,
                            covers globs) — cheap, CI-safe, no Minecraft
  plan [--json]             what needs testing at HEAD: never-run, stale
                            (covers touched / env bump / def changed), failed,
                            needs-eyes, blocked
  run [selectors...]        run tests through the probe agent. Selectors: test
                            ids, suite:<name>, all. No selector = the due set
                            from plan. Flags: --all, --no-build, --keep-server,
                            --file (auto-file issues for failures)
  report [runId]            print a run's summary
  file <runId> <testId>     promote a failure's evidence into qa/evidence/,
                            commit+push it, then file a GitHub issue whose
                            links are permanent repo URLs. If the test already
                            has an open issue: comment the new evidence there
                            instead of filing a duplicate (--force overrides).
                            Flags: --dry-run, --force
  mark <testId> pass|fail   record a visual verdict [--note s] [--by who]
`);
}

// ================================================================ catalog ==

async function loadCatalog() {
  const files = fs.existsSync(CATALOG_DIR)
    ? fs.readdirSync(CATALOG_DIR).filter((f) => f.endsWith('.mjs') && !f.startsWith('_')).sort()
    : [];
  if (!files.length) throw new QaError(`no catalog files under ${path.relative(ROOT, CATALOG_DIR)}/`);
  const tests = [];
  for (const file of files) {
    const suite = file.replace(/\.mjs$/, '');
    const mod = await import(pathToFileURL(path.join(CATALOG_DIR, file)).href);
    const defs = mod.default;
    if (!Array.isArray(defs)) throw new QaError(`${path.relative(ROOT, path.join(CATALOG_DIR, file))} must default-export an array of tests`);
    for (const def of defs) {
      tests.push(normalizeTest(def, suite, path.join(CATALOG_DIR, file)));
    }
  }
  validateCatalog(tests);
  return tests;
}

function normalizeTest(def, suite, file) {
  const test = {
    mode: 'auto',
    dependsOn: [],
    covers: [],
    timeoutMs: 180_000,
    ...def,
    suite,
    file,
  };
  if (typeof test.id !== 'string' || !/^[a-z0-9]+(\.[a-z0-9-]+)+$/.test(test.id)) {
    throw new QaError(`${file}: bad test id "${test.id}" (expected <suite>.<slug> lowercase)`);
  }
  if (!test.id.startsWith(`${suite}.`)) {
    throw new QaError(`${file}: test id "${test.id}" must be namespaced by its suite ("${suite}.").`);
  }
  if (typeof test.title !== 'string' || test.title.length < 12) {
    throw new QaError(`${file}: ${test.id} — title must name the concrete behavior (and content id) being verified`);
  }
  if (typeof test.steps !== 'function') {
    throw new QaError(`${file}: ${test.id} — steps must be an async function`);
  }
  if (!['auto', 'visual'].includes(test.mode)) {
    throw new QaError(`${file}: ${test.id} — mode must be "auto" or "visual"`);
  }
  if (!Array.isArray(test.covers) || test.covers.length === 0) {
    throw new QaError(`${file}: ${test.id} — covers must list at least one source glob (what this test exercises)`);
  }
  if (test.mode === 'visual' && test.dependsOn.length === 0) {
    // Not a hard rule — but a visual test almost always builds on an auto
    // test that proves the content exists. Force the author to think.
    throw new QaError(`${file}: ${test.id} — visual tests must dependOn the auto test that stages their content`);
  }
  test.defHash = sha256(JSON.stringify({
    id: test.id, title: test.title, mode: test.mode, dependsOn: test.dependsOn,
    covers: test.covers, timeoutMs: test.timeoutMs, steps: String(test.steps),
  }));
  return test;
}

function validateCatalog(tests) {
  const byId = new Map();
  for (const t of tests) {
    if (byId.has(t.id)) throw new QaError(`duplicate test id "${t.id}" (${t.file} and ${byId.get(t.id).file})`);
    byId.set(t.id, t);
  }
  for (const t of tests) {
    for (const dep of t.dependsOn) {
      if (!byId.has(dep)) throw new QaError(`${t.file}: ${t.id} depends on unknown test "${dep}"`);
    }
  }
  const state = new Map(); // id -> visiting | done
  for (const t of tests) visit(t.id);
  function visit(id) {
    if (state.get(id) === 'done') return;
    if (state.get(id) === 'visiting') throw new QaError(`dependency cycle involving "${id}"`);
    state.set(id, 'visiting');
    for (const dep of byId.get(id).dependsOn) visit(dep);
    state.set(id, 'done');
  }
  return byId;
}

// ================================================================= ledger ==

function ledgerPath(suite) { return path.join(LEDGER_DIR, `${suite}.json`); }

function readLedger(suite) {
  try { return JSON.parse(fs.readFileSync(ledgerPath(suite), 'utf8')); } catch { return {}; }
}

function writeLedgerEntry(test, entry) {
  const ledger = readLedger(test.suite);
  ledger[test.id] = entry;
  atomicWrite(ledgerPath(test.suite), `${JSON.stringify(ledger, null, 2)}\n`);
}

function atomicWrite(file, content) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp-${process.pid}`;
  fs.writeFileSync(tmp, redactEvidence(content));
  fs.renameSync(tmp, file);
}

function envKey() {
  const toml = fs.readFileSync(VERSIONS_TOML, 'utf8');
  const block = toml.slice(toml.indexOf('[versions]'));
  const pins = {};
  for (const m of block.matchAll(/^([\w-]+) = "(.+)"$/gm)) pins[m[1]] = m[2];
  const relevant = {};
  for (const key of ENV_PINS) {
    if (!pins[key]) throw new QaError(`gradle/libs.versions.toml is missing the "${key}" probe pin`);
    relevant[key] = pins[key];
  }
  return sha256(JSON.stringify(relevant)).slice(0, 12);
}

function sha256(text) { return crypto.createHash('sha256').update(text).digest('hex'); }

// ==================================================================== git ==

function git(...args) {
  return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8' }).trim();
}

function headSha() { return git('rev-parse', 'HEAD'); }
function isDirty() { return git('status', '--porcelain').length > 0; }
function isAncestor(sha) {
  try { git('merge-base', '--is-ancestor', sha, 'HEAD'); return true; } catch { return false; }
}
function diffNames(from) {
  try {
    return git('diff', '--name-only', `${from}`, 'HEAD').split('\n').filter(Boolean);
  } catch { return null; }
}

function globToRegex(glob) {
  const escaped = glob
    .replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .split('**').join('\0')
    .replace(/\*/g, '[^/]*')
    .replace(/\?/g, '[^/]')
    .split('\0').join('.*');
  return new RegExp(`^${escaped}$`);
}

function coversTouched(test, changed) {
  return test.covers
    .map(globToRegex)
    .flatMap((re) => changed.filter((f) => re.test(f)));
}

// ================================================================= status ==

// Status of a test relative to HEAD. `due` = the suite should run it.
function testStatus(test, ledger, head, env, dirty) {
  const entry = ledger[test.id];
  if (!entry) return { state: 'never', due: true, reason: 'never run' };
  if (entry.def !== test.defHash) return { state: 'changed', due: true, reason: 'test definition changed since it last ran' };
  if (entry.env !== env) return { state: 'env-changed', due: true, reason: 'probe pins changed since it last ran' };
  if (entry.result === 'fail') return { state: 'failed', due: true, reason: `failed at ${short(entry.sha)}${entry.issue ? ` (issue #${entry.issue})` : ''}` };
  if (entry.result === 'visual') return { state: 'needs-eyes', due: true, reason: 'screenshot evidence awaits a verdict (runner mark)' };
  if (entry.result !== 'pass') return { state: 'unknown', due: true, reason: `ledger has result "${entry.result}"` };

  // A recorded pass is fresh until (a) the tree that produced it was dirty,
  // (b) it was recorded off the current line of history, (c) the environment
  // changed (checked above), or (d) the diff since touches its covers globs.
  if (entry.dirty) return { state: 'recorded-dirty', due: true, reason: `recorded on a dirty tree at ${short(entry.sha)}` };
  if (!isAncestor(entry.sha)) return { state: 'off-main', due: true, reason: `recorded at ${short(entry.sha)}, which is not an ancestor of HEAD (bless on main)` };
  if (entry.sha === head && !dirty) return { state: 'fresh', due: false, reason: `pass at HEAD` };
  const changed = diffNames(entry.sha);
  if (changed === null) return { state: 'off-main', due: true, reason: `cannot diff ${short(entry.sha)}..HEAD` };
  const touched = coversTouched(test, changed);
  if (touched.length) {
    return { state: 'stale', due: true, reason: `covered code changed since the pass at ${short(entry.sha)}: ${[...new Set(touched)].slice(0, 3).join(', ')}` };
  }
  return { state: 'fresh', due: false, reason: `pass at ${short(entry.sha)}; covered code untouched since` };
}

function short(sha) { return (sha ?? '?').slice(0, 7); }

// Blocking: a test runs only if every dependency is a recorded pass that is
// fresh at HEAD. Anything else upstream blocks the dependent (the evidence a
// dependent would produce is not interpretable while its precondition is
// unverified or failing).
function blockedBy(test, statuses) {
  return test.dependsOn.filter((dep) => {
    const s = statuses.get(dep);
    return !s || s.state !== 'fresh';
  });
}

// =================================================================== plan ==

async function cmdPlan(argv) {
  const json = argv.includes('--json');
  const tests = await loadCatalog();
  const head = headSha();
  const env = envKey();
  const dirty = isDirty();
  const statuses = new Map();
  for (const t of tests) {
    statuses.set(t.id, testStatus(t, readLedger(t.suite), head, env, dirty));
  }
  const rows = tests.map((t) => {
    const status = statuses.get(t.id);
    const blocked = blockedBy(t, statuses);
    return {
      id: t.id,
      mode: t.mode,
      state: blocked.length ? 'blocked' : status.state,
      due: blocked.length ? false : status.due,
      reason: blocked.length
        ? `dependency not passing at HEAD: ${blocked.join(', ')}`
        : status.reason,
    };
  });
  if (json) {
    process.stdout.write(`${JSON.stringify({ head, dirty, env, tests: rows }, null, 2)}\n`);
    return;
  }
  const width = Math.max(...rows.map((r) => r.id.length), 20);
  process.stdout.write(`QA plan at ${short(head)}${dirty ? ' (DIRTY tree — results will be marked recorded-dirty)' : ''}, env ${env}\n\n`);
  for (const r of rows) {
    process.stdout.write(`${r.id.padEnd(width)}  ${r.mode.padEnd(7)}  ${r.state.padEnd(14)}  ${r.reason}\n`);
  }
  const due = rows.filter((r) => r.due).length;
  process.stdout.write(`\n${due} of ${rows.length} due. Run: node qa/runner.mjs run\n`);
}

// ==================================================================== run ==

async function cmdRun(argv) {
  const flags = {
    all: argv.includes('--all'),
    noBuild: argv.includes('--no-build'),
    keepServer: argv.includes('--keep-server'),
    file: argv.includes('--file'),
  };
  const selectors = argv.filter((a) => !a.startsWith('--'));
  const tests = await loadCatalog();
  const byId = validateCatalog(tests);
  const head = headSha();
  const env = envKey();
  const dirty = isDirty();
  if (dirty) {
    process.stderr.write('qa: WARNING — the working tree is dirty; passes recorded this run are marked recorded-dirty and will not count as fresh.\n');
  }

  const statuses = new Map();
  for (const t of tests) statuses.set(t.id, testStatus(t, readLedger(t.suite), head, env, dirty));

  // Selection: explicit selectors, or the due set. Dependencies of selected
  // tests join the run when they are due themselves; fresh deps stay skipped
  // (that is the point of the ledger).
  let selected;
  if (flags.all || selectors.includes('all')) {
    selected = tests.map((t) => t.id);
  } else if (selectors.length) {
    selected = [];
    for (const sel of selectors) {
      if (sel.startsWith('suite:')) {
        const suite = sel.slice('suite:'.length);
        const matches = tests.filter((t) => t.suite === suite);
        if (!matches.length) throw new QaError(`no tests in suite "${suite}"`);
        selected.push(...matches.map((t) => t.id));
      } else if (!byId.has(sel)) {
        throw new QaError(`unknown test id "${sel}" (see: node qa/runner.mjs plan)`);
      } else {
        selected.push(sel);
      }
    }
  } else {
    selected = tests.filter((t) => statuses.get(t.id).due).map((t) => t.id);
  }
  if (!selected.length) {
    process.stdout.write('Nothing is due — every test is a fresh pass at HEAD (see: node qa/runner.mjs plan).\n');
    return;
  }

  // Expand with due dependencies, topologically ordered (deps first).
  const order = [];
  const visiting = new Set();
  const addToOrder = (id) => {
    if (order.includes(id) || visiting.has(id)) return;
    visiting.add(id);
    for (const dep of byId.get(id).dependsOn) addToOrder(dep);
    visiting.delete(id);
    order.push(id);
  };
  for (const id of selected) addToOrder(id);

  const runId = `run-${new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)}`;
  const runDir = path.join(RESULTS_DIR, runId);
  fs.mkdirSync(runDir, { recursive: true });
  const selectedExplicit = new Set(selected);

  process.stdout.write(`QA run ${runId} at ${short(head)}${dirty ? ' (dirty)' : ''} — ${order.length} test(s) queued\n`);
  const runLog = [];
  const line = (text) => { process.stdout.write(`${text}\n`); runLog.push(text); };

  const agent = spawnAgent();
  const outcomes = new Map(); // id -> {outcome, ms, error?, dossier?}
  let infra = false;
  try {
    // Clean slate: anything a previous run left behind goes first.
    const status = await agent.call('status', {}, 30_000);
    if (status.server) {
      line('stopping a leftover probe server from an earlier session ...');
      await agent.call('down', {}, 120_000);
    }
    if (status.bridge) {
      line('stopping a leftover probe client ...');
      await agent.call('quit', {}, 120_000);
    }
    line('booting the probe server (fresh world, all fixture packs) ...');
    await agent.call('up', { build: !flags.noBuild }, 600_000);
    line('launching the probe client ...');
    await agent.call('client', {}, 360_000);
    line('joining (resource pack pre-accepted) ...');
    await agent.call('join', {}, 180_000);
    line('in-world. Running the DAG.\n');

    for (const id of order) {
      const test = byId.get(id);
      // Dependency expansion joins due deps into the run; a dep that is
      // already a fresh pass at HEAD (and was not explicitly selected) is
      // the ledger doing its job — skip it.
      if (!selectedExplicit.has(id) && statuses.get(id).state === 'fresh') {
        outcomes.set(id, { outcome: 'fresh-skip', ms: 0 });
        line(`· ${id.padEnd(38)} FRESH   — already a fresh pass at HEAD, skipped`);
        continue;
      }
      const blocking = blockedBy(test, currentStatuses());
      if (blocking.length) {
        outcomes.set(id, { outcome: 'blocked', ms: 0, error: `dependency not passing: ${blocking.join(', ')}` });
        line(`■ ${id.padEnd(38)} BLOCKED — ${blocking.join(', ')}`);
        continue;
      }
      const started = Date.now();
      try {
        const result = await runTest(test, agent, runDir, { head, dirty });
        outcomes.set(id, { outcome: result.outcome, ms: Date.now() - started, ...result.meta });
        const icon = result.outcome === 'pass' ? '✔' : result.outcome === 'visual' ? '👁' : '✘';
        line(`${icon} ${id.padEnd(38)} ${result.outcome.toUpperCase().padEnd(7)} (${((Date.now() - started) / 1000).toFixed(1)}s)${result.meta?.dossier ? ` → ${result.meta.dossier}` : ''}${result.outcome === 'visual' ? ` → close with: runner mark ${id} pass|fail` : ''}`);
      } catch (e) {
        if (e instanceof TestFailure) {
          outcomes.set(id, { outcome: 'fail', ms: Date.now() - started, error: e.message, dossier: e.dossier });
          line(`✘ ${id.padEnd(38)} FAIL    (${((Date.now() - started) / 1000).toFixed(1)}s) → ${e.dossier ?? e.message}`);
        } else {
          // Anything else is the environment (probe/bridge/boot) — continuing
          // would produce noise, not evidence. Abort the run as infra.
          infra = true;
          outcomes.set(id, { outcome: 'infra', ms: Date.now() - started, error: e.message });
          line(`⚡ ${id.padEnd(38)} INFRA   — ${redactEvidence(e.message)}`);
          break;
        }
      }
    }
  } catch (e) {
    infra = true;
    line(`⚡ INFRA: ${redactEvidence(e.message)}`);
  } finally {
    if (!flags.keepServer) {
      try { await agent.call('quit', {}, 60_000); } catch { /* already gone */ }
      try { await agent.call('down', {}, 120_000); } catch { /* already gone */ }
    }
    agent.kill();
  }

  const summary = writeRunSummary({ runId, runDir, order, outcomes, head, env, dirty, infra, flags, runLog });
  for (const l of summary.lines) process.stdout.write(`${l}\n`);
  const failed = [...outcomes.values()].filter((o) => o.outcome === 'fail').length;
  const blocked = [...outcomes.values()].filter((o) => o.outcome === 'blocked').length;
  process.exit(infra ? 2 : failed + blocked > 0 ? 1 : 0);

  // statuses this run (fresh deps count as passing; failures block).
  function currentStatuses() {
    const map = new Map();
    for (const t of tests) {
      const o = outcomes.get(t.id);
      if (o && (o.outcome === 'fail' || o.outcome === 'blocked')) {
        map.set(t.id, { state: `run-${o.outcome}` });
      } else if (o && (o.outcome === 'pass' || o.outcome === 'visual')) {
        map.set(t.id, { state: 'fresh' });
      } else {
        map.set(t.id, statuses.get(t.id));
      }
    }
    return map;
  }
}

async function runTest(test, agent, runDir, { head, dirty }) {
  const testDir = path.join(runDir, test.id);
  fs.mkdirSync(testDir, { recursive: true });
  const transcript = [];
  const logOffset = fs.existsSync(SERVER_LOG) ? fs.statSync(SERVER_LOG).size : 0;
  const ctx = makeCtx({ test, agent, testDir, transcript, logOffset });

  transcript.push({ t: 0, kind: 'note', msg: `test start: ${test.id} (${test.mode})` });
  let stepsError = null;
  try {
    await ctx.reset();
    await withTimeout(test.timeoutMs, test.steps(ctx), `${test.id} steps`);
    if (test.mode === 'visual' && !ctx.eyesNote) {
      throw new QaError(`${test.id}: mode is "visual" but steps never called ctx.needsEyes(...)`);
    }
    if (test.mode === 'visual' && ctx.shots.length === 0) {
      throw new QaError(`${test.id}: mode is "visual" but steps never captured a screenshot (ctx.shot or ctx.browser.browserShot)`);
    }
  } catch (e) {
    stepsError = e;
  } finally {
    await ctx.browser.close();
  }

  const ms = Date.now();
  if (!stepsError) {
    // head/dirty are the run-start snapshots: the run's own ledger writes
    // must never pollute the dirty flag of the entries it records.
    // An open issue link carries forward across fail/visual re-records — it
    // belongs to the test until a pass (the fix PR closes the issue).
    const previous = readLedger(test.suite)[test.id];
    const entry = {
      sha: head, dirty, env: envKey(), def: test.defHash,
      result: test.mode === 'visual' ? 'visual' : 'pass',
      at: new Date(ms).toISOString(), run: path.basename(runDir),
      eyes: ctx.eyesNote,
      // No evidence paths here: scratch shots stay machine-local until a
      // failure is filed (then qa/evidence/ repo-relative paths land in the
      // ledger via `file`). A pass needs only its run id.
    };
    if (entry.result !== 'pass' && previous?.issue) {
      entry.issue = previous.issue;
      if (previous.issueUrl) entry.issueUrl = previous.issueUrl;
    }
    writeLedgerEntry(test, entry);
    transcript.push({ t: Date.now() - ms, kind: 'note', msg: `outcome: ${entry.result}` });
    writeTranscript(testDir, transcript);
    return { outcome: entry.result, meta: { evidence: ctx.shots } };
  }

  // Failure: classify by environment health — a healthy environment means the
  // observable misbehaved (product or test bug → dossier); a broken one means
  // the run is noise (infra → abort). The error type does not decide this: a
  // hung product call in a healthy environment is still a finding.
  const healthy = await checkHealth(agent);
  const meta = {
    operator: stepsError.operator, expected: stepsError.expected, actual: stepsError.actual,
    health: healthy ? 'ok' : 'broken', logOffset, eyesNote: ctx.eyesNote,
  };
  writeTranscript(testDir, transcript);
  if (!healthy) {
    throw new QaError(`environment broke during ${test.id}: ${failureMessage(stepsError)}`);
  }
  const dossier = writeDossier({ test, testDir, transcript, error: stepsError, meta, ctx });
  writeLedgerEntry(test, {
    sha: head, dirty, env: envKey(), def: test.defHash,
    result: 'fail', at: new Date(ms).toISOString(), run: path.basename(runDir),
    dossier: path.relative(ROOT, dossier),
  });
  const failure = new TestFailure(failureMessage(stepsError));
  failure.dossier = path.relative(ROOT, dossier);
  throw failure;
}

class TestFailure extends Error {}

function failureMessage(error) {
  if (error instanceof QaAssertionError) {
    return redactEvidence(`${error.message} [${error.operator}] expected=${trim(error.expected)} actual=${trim(error.actual)}`);
  }
  return redactEvidence(error.message ?? String(error));
}

function trim(v) {
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  return s === undefined ? 'undefined' : s.length > 200 ? `${s.slice(0, 200)}…` : s;
}

async function withTimeout(ms, promise, what) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new QaError(`${what} timed out after ${Math.round(ms / 1000)}s`)), ms);
  });
  try {
    return await Promise.race([promise, timeout]);
  } finally {
    clearTimeout(timer);
  }
}

async function checkHealth(agent) {
  try {
    await agent.call('ping', {}, 15_000);
    await agent.call('rcon', { command: 'materione version' }, 30_000);
    return true;
  } catch { return false; }
}

// ================================================================== agent ==

function spawnAgent() {
  const child = spawn(process.execPath, [PROBE, 'agent'], { stdio: ['pipe', 'pipe', 'inherit'] });
  const pending = new Map();
  let nextId = 1;
  const rl = readline.createInterface({ input: child.stdout, terminal: false });
  rl.on('line', (l) => {
    let msg;
    try { msg = JSON.parse(l); } catch { return; }
    const waiter = pending.get(msg.id);
    if (!waiter) return;
    pending.delete(msg.id);
    clearTimeout(waiter.timer);
    if (msg.ok) waiter.resolve(msg.result);
    else {
      const err = new QaError(`probe agent ${waiter.cmd}: ${msg.error}`);
      err.probe = Boolean(msg.probe);
      waiter.reject(err);
    }
  });
  child.on('exit', (code) => {
    for (const [, waiter] of pending) {
      clearTimeout(waiter.timer);
      waiter.reject(new QaError(`probe agent exited (code ${code}) during ${waiter.cmd}`));
    }
    pending.clear();
  });
  // A dead agent's stdin write must not crash the runner with EPIPE — the
  // pending-request rejection above already reports the real failure.
  child.stdin.on('error', () => {});
  return {
    call(cmd, args = {}, timeoutMs = 120_000) {
      return new Promise((resolve, reject) => {
        const id = nextId++;
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(new QaError(`probe agent ${cmd} timed out after ${Math.round(timeoutMs / 1000)}s`));
        }, timeoutMs);
        pending.set(id, { cmd, resolve, reject, timer });
        child.stdin.write(`${JSON.stringify({ id, cmd, args })}\n`);
      });
    },
    kill() {
      try { child.kill(); } catch { /* already gone */ }
    },
  };
}

// ==================================================================== ctx ==

function makeCtx({ test, agent, testDir, transcript, logOffset }) {
  const started = Date.now();
  const shots = [];
  const browserShots = new Set();
  let eyesNote = null;
  const record = (kind, req, res) => {
    transcript.push({ t: Date.now() - started, kind, req, res });
    return res;
  };
  const agentCall = async (kind, cmd, args, pick, timeoutMs) => {
    const res = await agent.call(cmd, args, timeoutMs);
    return record(kind, args, res && pick ? pick(res) : res);
  };
  const anchor = (process.env.MTR_QA_ANCHOR ?? '0,-60,3').split(',').map(Number);
  // Console replies echo capability URLs and approval nonces; keep these calls out of transcripts.
  const privateConsole = async (command) => {
    try {
      await agent.call('rcon', { command }, 60_000);
    } catch {
      throw new QaError('private editor console request failed');
    }
  };
  const browser = createQaBrowserAdapter({
    testDir,
    openSession: async () => {
      await privateConsole('materione editor local');
      // waitForServerLog is scoped to this test's start offset; take the listener's logged port.
      const marker = await ctx.waitForServerLog(/MATERIONE-EDITOR http:\/\/127\.0\.0\.1:\d+\/\?s=/);
      const capabilityUrl = marker.match(/MATERIONE-EDITOR (http:\/\/127\.0\.0\.1:\d+\/\?s=[A-Za-z0-9_-]+#[A-Za-z0-9_-]+)/)?.[1];
      if (!capabilityUrl) throw new QaError('local editor capability marker was unavailable');
      return capabilityUrl;
    },
    approveNonce: (nonce) => privateConsole(`materione editor approve ${nonce}`),
    onScreenshot: (name, file) => {
      shots.push(file);
      browserShots.add(file);
      record('browser-shot', { name }, { path: file });
    },
  });
  const ctx = {
    test,
    shots,
    browser,
    get eyesNote() { return eyesNote; },
    rcon: async (command) => {
      const reply = await agentCall('rcon', 'rcon', { command }, (r) => r.reply, 60_000);
      // Adventure serializes with legacy § codes over RCON; assertions should
      // never deal with format noise.
      return reply.replace(/§./g, '');
    },
    exec: (code, timeoutMs = 10_000) => agentCall('exec', 'exec', { code, timeoutMs }, (r) => r.value, timeoutMs + 30_000),
    command: (command) => agentCall('command', 'command', { command }, (r) => r.value, 60_000),
    // Full rejoin: bridge quit terminates the client process, so this
    // relaunches it (as probe up does) and rejoins. Every join-time behavior
    // — pack delivery, the auto-updater's join-time scan — reruns; the join
    // call only returns once the player is in-world again.
    rejoin: async () => {
      await agentCall('quit', 'quit', {}, null, 120_000);
      // the dying client's DebugBridge port lingers briefly; relaunching
      // too early trips cmdClient's already-running guard
      await new Promise((r) => setTimeout(r, 5000));
      await agentCall('client', 'client', {}, null, 360_000);
      await agentCall('join', 'join', {}, null, 180_000);
    },
    state: () => agentCall('state', 'state', {}, null, 60_000),
    chat: async (limit = 30) => {
      // DebugBridge chatHistory returns {messages: [{plain, addedTime}], count} —
      // normalize to plain strings before tests see it.
      const res = await agentCall('chat', 'chat', { limit }, (r) => r.lines, 60_000);
      const lines = Array.isArray(res) ? res : Array.isArray(res?.messages) ? res.messages : [];
      return lines.map(chatText);
    },
    sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
    log: (msg) => record('note', { msg }, null),
    shot: async (name) => {
      const safe = String(name).replace(/[^a-z0-9_-]+/gi, '-');
      const out = path.join(testDir, `${safe}.jpg`);
      const res = await agent.call('shot', { out }, 60_000);
      shots.push(res.path);
      return record('shot', { name }, res).path;
    },
    needsEyes: (what) => { eyesNote = what; },
    expect: makeExpect,
    reset: async () => {
      // Fail-soft baseline: each of these may legitimately no-op (empty
      // inventory, no effects, no drops).
      for (const command of ['clear Probe', 'effect clear Probe', 'kill @e[type=item]']) {
        try { await ctx.rcon(command); } catch { /* nothing to clear */ }
      }
      await ctx.rcon(`tp Probe ${anchor[0]} ${anchor[1]} ${anchor[2]}`);
      await ctx.sleep(400);
    },
    serverLogText: (regex, { sinceTest = true } = {}) => {
      const text = fs.readFileSync(SERVER_LOG, 'utf8');
      const scan = sinceTest ? text.slice(Math.min(logOffset, text.length)) : text;
      const lines = scan.split('\n').filter((l) => regex.test(l));
      return lines.length ? lines.at(-1) : null;
    },
    serverLogLine: (regex, opts) => {
      const line = ctx.serverLogText(regex, opts);
      if (!line) throw new QaAssertionError(`server log has no line matching ${regex} since this test started`, { operator: 'serverLogLine', expected: String(regex), actual: '(no match)' });
      return line;
    },
    waitForServerLog: async (regex, { timeoutMs = 20_000, poll = 400 } = {}) => {
      const deadline = Date.now() + timeoutMs;
      for (;;) {
        const line = ctx.serverLogText(regex);
        if (line) return line;
        if (Date.now() > deadline) {
          throw new QaAssertionError(`server log never matched ${regex} within ${timeoutMs}ms`, { operator: 'waitForServerLog', expected: String(regex), actual: '(no match)' });
        }
        await ctx.sleep(poll);
      }
    },
    waitForChat: async (regex, { timeoutMs = 20_000, poll = 500 } = {}) => {
      const deadline = Date.now() + timeoutMs;
      let lastLines = [];
      for (;;) {
        lastLines = await ctx.chat();
        const hit = lastLines.find((l) => regex.test(l));
        if (hit) return hit;
        if (Date.now() > deadline) {
          throw new QaAssertionError(`chat never matched ${regex} within ${timeoutMs}ms`, { operator: 'waitForChat', expected: String(regex), actual: lastLines.slice(-8) });
        }
        await ctx.sleep(poll);
      }
    },
  };
  ctx.browserShots = browserShots;
  return ctx;
}

function chatText(line) {
  if (typeof line === 'string') return line;
  return line?.plain ?? line?.text ?? line?.message ?? line?.content ?? JSON.stringify(line);
}

function makeExpect(actual) {
  const fail = (operator, expected, message) => {
    throw new QaAssertionError(message, { operator, expected, actual });
  };
  return {
    toBe(expected) {
      if (actual !== expected) fail('toBe', expected, `expected ${trim(expected)}, got ${trim(actual)}`);
    },
    toEqual(expected) {
      if (JSON.stringify(actual) !== JSON.stringify(expected)) fail('toEqual', expected, `expected ${trim(expected)}, got ${trim(actual)}`);
    },
    toContain(sub) {
      if (!String(actual).includes(sub)) fail('toContain', sub, `expected output to contain ${trim(sub)}, got ${trim(actual)}`);
    },
    toMatch(pattern) {
      const re = pattern instanceof RegExp ? pattern : new RegExp(pattern);
      if (!re.test(String(actual))) fail('toMatch', String(pattern), `expected output to match ${pattern}, got ${trim(actual)}`);
    },
    toBeGreaterThan(n) {
      if (!(Number(actual) > n)) fail('toBeGreaterThan', n, `expected ${trim(actual)} > ${n}`);
    },
    toBeLessThan(n) {
      if (!(Number(actual) < n)) fail('toBeLessThan', n, `expected ${trim(actual)} < ${n}`);
    },
    toBeTruthy() {
      if (!actual) fail('toBeTruthy', true, `expected truthy, got ${trim(actual)}`);
    },
  };
}

// =============================================================== evidence ==

function writeTranscript(testDir, transcript) {
  atomicWrite(
    path.join(testDir, 'transcript.jsonl'),
    transcript.map((e) => JSON.stringify(e)).join('\n') + '\n',
  );
}

function writeDossier({ test, testDir, transcript, error, meta, ctx }) {
  const rel = (p) => path.relative(ROOT, p);
  const shots = ctx.shots.map((p) => `- \`${rel(p)}\` (${ctx.browserShots.has(p) ? 'browser App' : 'Minecraft client'})`).join('\n') || '- (none captured)';
  const chatDump = transcript.filter((e) => e.kind === 'chat').at(-1)?.res;
  const chatLines = Array.isArray(chatDump) ? chatDump.map(chatText).slice(-15) : [];
  const serverTail = tailLines(SERVER_LOG, meta.logOffset, 40);
  const clientTail = tailLines(CLIENT_LOG, 0, 15);
  const head = headSha();
  const expectedSection = error instanceof QaAssertionError
    ? `
- operator: \`${meta.operator ?? 'error'}\`
- expected: \`${trim(meta.expected)}\`
- actual: \`${trim(meta.actual)}\`
`
    : `
This was a thrown error, not an assertion failure.
`;

  const md = `# [qa:${test.suite}] ${test.title}

**Outcome:** FAIL — ${failureMessage(error)}
**Test:** \`${test.id}\` (${test.mode}) · **Suite:** \`qa/catalog/${path.basename(test.file)}\`
**Recorded at:** ${head}${meta.health === 'broken' ? ' · **health check FAILED** (the environment broke during this test — treat with suspicion)' : ''}
**Repro:** \`node qa/runner.mjs run ${test.id}\`

## What was expected
${expectedSection}
Error:

\`\`\`
${error.stack ?? error.message}
\`\`\`

## Evidence

Screenshots (JPEG/PNG, read directly from disk; browser captures are scoped to the editor App):

${shots}

Chat history (newest last):

\`\`\`
${chatLines.length ? chatLines.join('\n') : '(no chat captured)'}
\`\`\`

Server log tail (${rel(SERVER_LOG)}):

\`\`\`
${serverTail.join('\n') || '(unavailable)'}
\`\`\`

Client log tail (${rel(CLIENT_LOG)}):

\`\`\`
${clientTail.join('\n') || '(unavailable)'}
\`\`\`

Full call transcript: \`${rel(path.join(testDir, 'transcript.jsonl'))}\`

## Test definition

\`\`\`js
id: ${JSON.stringify(test.id)}
title: ${JSON.stringify(test.title)}
mode: ${JSON.stringify(test.mode)}
dependsOn: ${JSON.stringify(test.dependsOn)}
covers: ${JSON.stringify(test.covers)}

steps:
${String(test.steps)}
\`\`\`

## Environment

- probe pins: Paper ${pin('paper-probe-version')}-${pin('paper-probe-build')}, DebugBridge sha1 ${pin('probe-debugbridge-sha1').slice(0, 10)}…, fabric-loader ${pin('probe-fabric-loader')}
- registry mode: registry (probe boots 26.x only)
<!-- qa-test: ${test.id} @ ${head} -->
`;
  const mdPath = path.join(testDir, 'dossier.md');
  atomicWrite(mdPath, md);
  atomicWrite(path.join(testDir, 'dossier.json'), `${JSON.stringify({
    id: test.id, suite: test.suite, title: test.title, file: rel(test.file),
    message: failureMessage(error), meta, head, chatLines, shots: ctx.shots,
    transcript: rel(path.join(testDir, 'transcript.jsonl')),
  }, null, 2)}\n`);
  return mdPath;
}

function tailLines(file, fromOffset, count) {
  try {
    const text = fs.readFileSync(file, 'utf8');
    return text.slice(Math.min(fromOffset, text.length)).split('\n').filter(Boolean).slice(-count);
  } catch { return []; }
}

function pin(key) {
  const toml = fs.readFileSync(VERSIONS_TOML, 'utf8');
  const block = toml.slice(toml.indexOf('[versions]'));
  return block.match(new RegExp(`^${key} = "(.+)"$`, 'm'))?.[1] ?? '?';
}

function writeRunSummary({ runId, runDir, order, outcomes, head, env, dirty, infra, flags, runLog }) {
  const lines = [
    '', `QA run ${runId} — ${short(head)}${dirty ? ' (dirty)' : ''} env ${env}`, '',
    ...order.map((id) => {
      const o = outcomes.get(id) ?? { outcome: 'skipped' };
      const note = o.dossier ? ` → ${o.dossier}` : o.error ? ` — ${o.error}` : '';
      return `  ${o.outcome.toUpperCase().padEnd(8)} ${id}${note}`;
    }),
    '',
  ];
  const counts = {};
  for (const o of outcomes.values()) counts[o.outcome] = (counts[o.outcome] ?? 0) + 1;
  lines.push(`Outcomes: ${JSON.stringify(counts)}${infra ? ' — run aborted: environment failure' : ''}`);
  const failures = order.filter((id) => outcomes.get(id)?.outcome === 'fail');
  if (failures.length) {
    lines.push('', 'Next steps:');
    for (const id of failures) {
      lines.push(flags.file
        ? `  filed: ${outcomes.get(id).dossier}`
        : `  node qa/runner.mjs file ${runId} ${id}`);
    }
  }
  atomicWrite(path.join(runDir, 'summary.md'), `${lines.join('\n')}\n`);
  atomicWrite(path.join(runDir, 'summary.json'), `${JSON.stringify({
    runId, head, env, dirty, infra, outcomes: Object.fromEntries(outcomes),
  }, null, 2)}\n`);
  if (runLog.length) atomicWrite(path.join(runDir, 'run.log'), `${runLog.join('\n')}\n`);
  return { lines };
}

// ================================================================= report ==

async function cmdReport(argv) {
  const runId = argv[0] ?? latestRunId();
  const summary = path.join(RESULTS_DIR, runId, 'summary.md');
  if (!fs.existsSync(summary)) throw new QaError(`no summary for run "${runId}"`);
  process.stdout.write(fs.readFileSync(summary, 'utf8'));
}

function latestRunId() {
  const runs = fs.existsSync(RESULTS_DIR)
    ? fs.readdirSync(RESULTS_DIR).filter((d) => d.startsWith('run-')).sort()
    : [];
  if (!runs.length) throw new QaError('no runs recorded yet (qa/results/ is empty)');
  return runs.at(-1);
}

// =================================================================== file ==

// Evidence must outlive the machine that captured it (issue #41): `file`
// promotes a failure's dossier + transcript + screenshots from the gitignored
// scratch dir into the tracked qa/evidence/ tree, commits and pushes BEFORE
// touching GitHub, and rewrites every path in the issue body to a permanent
// /raw/main/ URL — screenshots inline-render. Local paths never reach GitHub.
async function cmdFile(argv) {
  const positional = [];
  const flags = { dry: false, force: false };
  for (const a of argv) {
    if (a === '--dry-run') flags.dry = true;
    else if (a === '--force') flags.force = true;
    else positional.push(a);
  }
  const [runId, testId] = positional;
  if (!runId || !testId) throw new QaError('usage: node qa/runner.mjs file <runId> <testId> [--dry-run] [--force]');
  const tests = await loadCatalog();
  const test = tests.find((t) => t.id === testId);
  if (!test) throw new QaError(`unknown test "${testId}"`);
  const dossierPath = path.join(RESULTS_DIR, runId, testId, 'dossier.md');
  if (!fs.existsSync(dossierPath)) {
    throw new QaError(`no dossier at ${path.relative(ROOT, dossierPath)} — only failed tests can be filed`);
  }

  const promoted = promoteEvidence(runId, testId, { dry: flags.dry });
  // Build the body from the PROMOTED text (scratch paths rewritten to
  // qa/evidence/) — otherwise issue links would point at gitignored paths.
  const promotedMd = promoteRewrite(fs.readFileSync(dossierPath, 'utf8'), runId, testId);
  const body = inlineEvidence(promotedMd.trim(), originRawBase());
  const ledger = readLedger(test.suite);
  const entry = ledger[testId] ?? {};

  if (flags.dry) {
    process.stdout.write(`--- would promote ---\n${promoted.files.join('\n')}\n--- issue/comment body ---\n${body}\n`);
    return;
  }

  // Push the evidence FIRST: an issue whose image links 404 is exactly the
  // failure mode this command exists to prevent.
  commitAndPushEvidence();

  const gh = ghExe();
  if (entry.issue && !flags.force) {
    const comment = `${body}\n\n---\n_Re-failure observed in \`${runId}\` — evidence promoted to \`qa/evidence/${runId}/${testId}/\`. Comment posted by \`node qa/runner.mjs file ${runId} ${testId}\`._\n`;
    const commentFile = path.join(RESULTS_DIR, runId, testId, 'comment-body.md');
    fs.writeFileSync(commentFile, comment);
    execFileSync(gh, ['issue', 'comment', String(entry.issue), '--body-file', commentFile], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    process.stdout.write(`commented on #${entry.issue} (evidence: ${promoted.dir}/)\n`);
  } else {
    ensureLabel(gh, 'qa-failure', 'In-game QA suite failure dossier (qa/)', 'D93F0B');
    const url = execFileSync(gh, ['issue', 'create', '--title', `[qa:${test.suite}] ${test.title}`, '--body-file', toTempBody(runId, testId, body), '--label', 'qa-failure'], { encoding: 'utf8' }).trim();
    const number = Number(url.match(/\/issues\/(\d+)/)?.[1]);
    process.stdout.write(`filed #${number}: ${url}\n`);
    entry.issue = number;
    entry.issueUrl = url;
    try {
      execFileSync(gh, ['project', 'item-add', PROJECT_NUMBER, '--owner', PROJECT_OWNER, '--url', url], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
      execFileSync(gh, ['project', 'item-edit', PROJECT_NUMBER, '--owner', PROJECT_OWNER, '--project-id', PROJECT_ID, '--url', url, '--field', 'Status', '--value', 'Todo'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
      process.stdout.write(`added to the board (Status: Todo)\n`);
    } catch (e) {
      process.stdout.write(`(board update failed — add manually: ${e.message})\n`);
    }
  }

  // The ledger stores repo-relative promoted paths only — never machine paths.
  writeLedgerEntry(test, { ...entry, evidence: promoted.files });
}

function promoteEvidence(runId, testId, { dry = false } = {}) {
  const srcDir = path.join(RESULTS_DIR, runId, testId);
  if (!fs.existsSync(srcDir)) throw new QaError(`no scratch evidence at qa/results/${runId}/${testId} — it may have been cleaned up; re-run the test`);
  const files = fs.readdirSync(srcDir).filter((f) => f === 'dossier.md' || f === 'transcript.jsonl' || /\.(jpg|jpeg|png)$/i.test(f));
  if (dry) {
    return { dir: `qa/evidence/${runId}/${testId}`, files: files.map((f) => `qa/evidence/${runId}/${testId}/${f}`) };
  }
  const dstDir = path.join(EVIDENCE_DIR, runId, testId);
  fs.mkdirSync(dstDir, { recursive: true });
  for (const file of files) {
    let content = fs.readFileSync(path.join(srcDir, file));
    if (file === 'dossier.md') {
      content = Buffer.from(promoteRewrite(content.toString('utf8'), runId, testId), 'utf8');
    } else if (file === 'transcript.jsonl') {
      content = Buffer.from(redactEvidence(content.toString('utf8')), 'utf8');
    }
    fs.writeFileSync(path.join(dstDir, file), content);
  }
  return { dir: `qa/evidence/${runId}/${testId}`, files: files.map((f) => `qa/evidence/${runId}/${testId}/${f}`) };
}

// The dossier references its evidence by scratch-relative paths; after
// promotion those live under qa/evidence/.
function promoteRewrite(md, runId, testId) {
  const promoted = `qa/evidence/${runId}/${testId}`;
  return redactEvidence(md)
    .split(`qa\\results\\${runId}\\${testId}`).join(promoted)
    .split(`qa/results/${runId}/${testId}`).join(promoted);
}

// Turn repo-relative qa/evidence/ references into permanent raw URLs; images
// become inline markdown so GitHub renders them in the issue. Anything that
// still references scratch (qa/results/) is a bug — it would 404.
function inlineEvidence(md, rawBase) {
  return md.replace(/[`']?(qa[\\/]evidence[\\/][^`'()\s]+)[`']?/g, (full, relPath) => {
    const rel = relPath.replace(/\\/g, '/');
    const url = `${rawBase}/${rel}`;
    if (/\.(jpg|jpeg|png)$/i.test(rel)) return `![evidence: ${path.basename(rel)}](${url})`;
    return `[${rel}](${url})`;
  });
}

function originRawBase() {
  if (process.env.MTR_RAW_BASE) return process.env.MTR_RAW_BASE.replace(/\/$/, '');
  let remote;
  try { remote = git('remote', 'get-url', 'origin'); } catch { throw new QaError('no git remote "origin" — cannot build evidence URLs (or set MTR_RAW_BASE)'); }
  const https = remote.match(/github\.com[/:](.+?)\.git$/i) ?? remote.match(/github\.com[/:](.+?)$/i);
  if (!https) throw new QaError(`cannot parse origin remote "${remote}" (or set MTR_RAW_BASE)`);
  return `https://github.com/${https[1]}/raw/main`;
}

function toTempBody(runId, testId, body) {
  const file = path.join(RESULTS_DIR, runId, testId, 'issue-body.md');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${body}\n`);
  return file;
}

function commitAndPushEvidence() {
  const pending = git('status', '--porcelain', '--', 'qa/evidence');
  if (pending.length) {
    git('add', 'qa/evidence');
    git('commit', '-m', `qa: promote failure evidence (${short(headSha())})`, '--', 'qa/evidence');
  }
  git('push');
}

function ghExe() {
  if (process.env.MTR_GH) return process.env.MTR_GH;
  try { execFileSync('gh', ['--version'], { stdio: 'ignore' }); return 'gh'; } catch { /* not on PATH */ }
  if (fs.existsSync(GH_FALLBACK)) return GH_FALLBACK;
  throw new QaError('gh CLI not found (set MTR_GH or install GitHub CLI)');
}

function ensureLabel(gh, name, description, color) {
  try {
    execFileSync(gh, ['label', 'create', name, '--description', description, '--color', color, '--force'], { stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    if (!String(e.stderr ?? '').includes('already exists')) { /* best-effort */ }
  }
}

// =================================================================== mark ==

async function cmdMark(argv) {
  const positional = [];
  let note = null;
  let by = process.env.MTR_MARKED_BY ?? 'user';
  let issue = null;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--note') note = argv[++i];
    else if (argv[i] === '--by') by = argv[++i];
    else if (argv[i] === '--issue') issue = Number(argv[++i]);
    else positional.push(argv[i]);
  }
  const [testId, verdict] = positional;
  if (!testId || !['pass', 'fail'].includes(verdict)) throw new QaError('usage: node qa/runner.mjs mark <testId> pass|fail [--note s] [--by who] [--issue N]');
  const tests = await loadCatalog();
  const test = tests.find((t) => t.id === testId);
  if (!test) throw new QaError(`unknown test "${testId}"`);
  const ledger = readLedger(test.suite);
  const previous = ledger[testId];
  if (!previous || (previous.result !== 'visual' && !process.argv.includes('--force'))) {
    throw new QaError(`${testId} is not awaiting a visual verdict (ledger result: ${previous?.result ?? 'none'}) — use --force to override`);
  }
  const entry = {
    ...previous,
    result: verdict,
    at: new Date().toISOString(),
    sha: headSha(),
    dirty: isDirty(),
    env: envKey(),
    def: test.defHash,
    markedBy: by,
    note,
  };
  // Evidence paths are not recorded here (they would be machine-local);
  // `runner file` promotes the run's evidence into qa/evidence/ and fills
  // the ledger with repo-relative paths.
  if (issue) {
    entry.issue = issue;
    entry.issueUrl = `https://github.com/xef5000/materione/issues/${issue}`;
  }
  writeLedgerEntry(test, entry);
  process.stdout.write(`${testId} marked ${verdict} (by ${by}) at ${short(headSha())}\n`);

  // A visual failure needs the same paper trail an auto failure gets: write
  // a dossier into the originating run dir so `runner file` can turn it into
  // an issue without re-running anything.
  if (verdict === 'fail') {
    const testDir = path.join(RESULTS_DIR, previous.run ?? '', testId);
    const shots = (entry.evidence ?? []).map((p) => `- \`${path.relative(ROOT, p)}\``).join('\n') || '- (none)';
    const md = `# [qa:${test.suite}] ${test.title}

**Outcome:** FAIL — visual verdict by ${by} (test ${testId}, run ${previous.run ?? '?'})
**Verdict note:** ${note ?? '(no note)'}
**Repro:** \`node qa/runner.mjs run ${testId}\`

## What was expected

${previous.eyes ?? 'A visual pass (see the test definition for the eyes criterion).'}

## Evidence

${shots}

## Test definition

\`\`\`js
id: ${JSON.stringify(test.id)}
title: ${JSON.stringify(test.title)}
mode: ${JSON.stringify(test.mode)}
dependsOn: ${JSON.stringify(test.dependsOn)}
covers: ${JSON.stringify(test.covers)}

steps:
${String(test.steps)}
\`\`\`

<!-- qa-test: ${test.id} @ ${headSha()} -->
`;
    fs.mkdirSync(testDir, { recursive: true });
    atomicWrite(path.join(testDir, 'dossier.md'), md);
    process.stdout.write(`dossier: ${path.relative(ROOT, path.join(testDir, 'dossier.md'))}\n`);
  }
}

// ================================================================== check ==

async function cmdCheck() {
  const tests = await loadCatalog();
  const bySuite = new Map();
  for (const t of tests) bySuite.set(t.suite, (bySuite.get(t.suite) ?? 0) + 1);
  for (const [suite, count] of bySuite) {
    process.stdout.write(`${suite.padEnd(20)} ${count} test(s)\n`);
  }
  // Ledger drift: entries referencing unknown ids mean a test was renamed or
  // deleted — the ledger needs a human decision (drop the entry). Evidence
  // integrity: ledger-referenced promoted evidence must exist in the repo,
  // or the issue links built from it are dead.
  let drift = 0;
  if (fs.existsSync(LEDGER_DIR)) {
    for (const f of fs.readdirSync(LEDGER_DIR)) {
      const suite = f.replace(/\.json$/, '');
      const ledger = JSON.parse(fs.readFileSync(path.join(LEDGER_DIR, f), 'utf8'));
      for (const [id, entry] of Object.entries(ledger)) {
        if (!tests.some((t) => t.id === id)) {
          process.stderr.write(`ledger drift: qa/ledger/${f} has entry "${id}" with no catalog test\n`);
          drift++;
        }
        for (const p of entry.evidence ?? []) {
          if (!p.startsWith('qa/evidence/') || !fs.existsSync(path.join(ROOT, p))) {
            process.stderr.write(`evidence drift: qa/ledger/${f} "${id}" references "${p}", which is not a tracked qa/evidence/ path\n`);
            drift++;
          }
        }
      }
      if (!tests.some((t) => t.suite === suite) && Object.keys(ledger).length) {
        process.stderr.write(`ledger drift: qa/ledger/${f} has no matching catalog suite\n`);
        drift++;
      }
    }
  }
  process.stdout.write(`catalog ok: ${tests.length} test(s) in ${bySuite.size} suite(s)${drift ? `, ${drift} ledger drift issue(s)` : ', ledger clean'}\n`);
  if (drift) process.exit(1);
}
