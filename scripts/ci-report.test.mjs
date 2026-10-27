import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { junitSummary, reportCi } from './ci-report.mjs';

test('JUnit failure details and stdout are decoded and redacted', () => {
  const result = junitSummary(`<testsuite name="Handshake" tests="1" failures="1" time="1.2">
    <testcase><failure message="failed">awaiting &apos;hello&apos;
    http://localhost/?s=capability#secret</failure></testcase>
    <system-out><![CDATA[MATERIONE_NOOP_PERF wallMillis=123]]></system-out>
    </testsuite>`);
  assert.equal(result.name, 'Handshake');
  assert.match(result.failureDetails[0], /awaiting 'hello'/);
  assert.doesNotMatch(result.failureDetails[0], /capability|secret/);
  assert.match(result.output, /wallMillis=123/);
});

test('console/summary evidence survives absent artifacts and includes nested fail-soft logs', t => {
  const printed = [];
  t.mock.method(console, 'log', value => printed.push(value));
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'materione-ci-report-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const workspace = path.join(root, 'harness', 'build', 'work', 'failsoft', 'bootstrap');
  fs.mkdirSync(workspace, { recursive: true });
  fs.writeFileSync(path.join(workspace, 'server.properties'), 'offline');
  fs.writeFileSync(path.join(workspace, 'stdout.log'), 'http://localhost/?s=secret#wrapping');
  const settings = path.join(workspace, 'plugins', 'Materione', 'settings');
  fs.mkdirSync(settings, { recursive: true });
  fs.writeFileSync(path.join(settings, 'settings.yml.broken-1'), '{ malformed');
  fs.mkdirSync(path.join(workspace, 'libraries'));
  fs.writeFileSync(path.join(workspace, 'libraries', 'not-evidence.jar'), 'binary');
  const reports = path.join(root, 'harness', 'build', 'test-results', 'integrationTest');
  fs.mkdirSync(reports, { recursive: true });
  fs.writeFileSync(path.join(reports, 'TEST-failed.xml'),
    '<testsuite name="Boot" tests="1" failures="1" time="1"><testcase><failure>child exited, code 7</failure></testcase></testsuite>');
  const summary = path.join(root, 'summary.txt');
  reportCi(root, summary);
  assert.match(printed.join('\n'), /child exited, code 7/);
  assert.match(fs.readFileSync(summary, 'utf8'), /child exited, code 7/);
  const bundle = path.join(root, 'harness', 'build', 'ci-debug');
  const copied = path.join(bundle, 'harness', 'build', 'work', 'failsoft', 'bootstrap', 'stdout.log');
  assert.match(fs.readFileSync(copied, 'utf8'), /REDACTED/);
  assert.doesNotMatch(fs.readFileSync(copied, 'utf8'), /secret|wrapping/);
  assert.equal(fs.readFileSync(path.join(bundle, 'harness', 'build', 'work', 'failsoft',
    'bootstrap', 'plugins', 'Materione', 'settings', 'settings.yml.broken-1'), 'utf8'), '{ malformed');
  assert.equal(fs.existsSync(path.join(bundle, 'harness', 'build', 'work',
    'failsoft', 'bootstrap', 'libraries')), false);
  t.mock.method(fs, 'mkdirSync', () => { throw new Error('artifact staging unavailable'); });
  assert.throws(() => reportCi(root, summary), /artifact staging unavailable/);
  assert.match(printed.at(-1), /child exited, code 7/);
  assert.match(fs.readFileSync(summary, 'utf8'), /child exited, code 7/);
});

test('oversized evidence is explicitly truncated and does not pretend to be valid XML', t => {
  t.mock.method(console, 'log', () => {});
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'materione-ci-report-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const reports = path.join(root, 'harness', 'build', 'test-results', 'test');
  fs.mkdirSync(reports, { recursive: true });
  fs.writeFileSync(path.join(reports, 'TEST-big.xml'),
    `<testsuite name="Big" tests="1" failures="0" time="1"><system-out>${'a'.repeat(300_000)}</system-out></testsuite>`);
  reportCi(root);
  const bundle = path.join(root, 'harness', 'build', 'ci-debug');
  assert.match(fs.readFileSync(path.join(bundle, 'bundle.txt'), 'utf8'), /truncated/);
  assert.equal(fs.existsSync(path.join(bundle, 'harness', 'build', 'test-results',
    'test', 'TEST-big.xml.truncated.txt')), true);
});

test('bundles obey the total and per-file limits across repeated invocations', t => {
  t.mock.method(console, 'log', () => {});
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'materione-ci-report-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const reports = path.join(root, 'harness', 'build', 'test-results', 'test');
  fs.mkdirSync(reports, { recursive: true });
  for (let i = 0; i < 140; i++) {
    fs.writeFileSync(path.join(reports, `TEST-${i}.xml`),
      `<testsuite name="Suite${i}" tests="1" failures="0" time="1"/>`);
  }
  reportCi(root);
  const bundled = path.join(root, 'harness', 'build', 'ci-debug', 'harness', 'build', 'test-results', 'test');
  assert.equal(fs.readdirSync(bundled).length, 128);
  for (const file of fs.readdirSync(reports)) fs.unlinkSync(path.join(reports, file));
  for (let i = 0; i < 40; i++) {
    fs.writeFileSync(path.join(reports, `TEST-${i}.xml`),
      `<testsuite name="Large${i}" tests="1" failures="0" time="1"><system-out>${'x'.repeat(300_000)}</system-out></testsuite>`);
  }
  reportCi(root);
  const sizes = fs.readdirSync(bundled).map(file => fs.statSync(path.join(bundled, file)).size);
  assert.ok(sizes.every(size => size <= 256 * 1024));
  assert.ok(sizes.reduce((sum, size) => sum + size, 0) <= 8 * 1024 * 1024);
  assert.equal(sizes.length, 32);
});
