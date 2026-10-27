import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { redactEvidence } from '../qa/evidence-redaction.mjs';

const MAX_FILE = 256 * 1024;
const MAX_BUNDLE = 8 * 1024 * 1024;
const MAX_FILES = 128;

function filesUnder(root) {
  if (!fs.existsSync(root)) return [];
  return fs.readdirSync(root, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(root, entry.name);
    if (entry.isSymbolicLink()) return [];
    return entry.isDirectory() ? filesUnder(file) : [file];
  });
}

function xmlText(value) {
  return value.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, number) => String.fromCodePoint(Number(number)))
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
}

export function junitSummary(xml) {
  const header = xml.match(/<testsuite\b([^>]*)>/);
  if (!header) throw new Error('Expected a JUnit testsuite');
  const attrs = Object.fromEntries([...header[1].matchAll(/([\w-]+)="([^"]*)"/g)]
    .map(([, key, value]) => [key, xmlText(value)]));
  const failures = [...xml.matchAll(/<(?:failure|error)\b[^>]*>([\s\S]*?)<\/(?:failure|error)>/g)]
    .map(([, body]) => redactEvidence(xmlText(body)));
  const output = [...xml.matchAll(/<system-out>([\s\S]*?)<\/system-out>/g)]
    .map(([, body]) => redactEvidence(xmlText(body))).join('\n');
  return { ...attrs, failureDetails: failures, output };
}

function workspaceEvidence(root) {
  if (!fs.existsSync(root)) return [];
  if (fs.existsSync(path.join(root, 'server.properties'))) {
    return [
      path.join(root, 'stdout.log'),
      ...['logs', 'config', 'crash-reports', path.join('plugins', 'Materione', 'settings')]
        .flatMap(dir => filesUnder(path.join(root, dir))),
    ].filter(file => fs.existsSync(file) && /\.(log|yml(?:\.broken-\d+)?|txt)$/.test(file));
  }
  return fs.readdirSync(root, { withFileTypes: true })
    .filter(entry => entry.isDirectory() && !entry.isSymbolicLink())
    .flatMap(entry => workspaceEvidence(path.join(root, entry.name)));
}

export function reportCi(root, summaryPath) {
  const reports = fs.readdirSync(root, { withFileTypes: true })
    .filter(entry => entry.isDirectory() && !entry.isSymbolicLink())
    .flatMap(entry => filesUnder(path.join(root, entry.name, 'build', 'test-results')))
    .filter(file => file.endsWith('.xml')).sort();
  const results = reports.map(file => ({ file, ...junitSummary(fs.readFileSync(file, 'utf8')) }));
  const lines = [
    '## JVM test results',
    '',
    'JUnit reports can be restored by Gradle. See task outcomes for actual execution versus FROM-CACHE.',
    '',
    '| Suite | Tests | Failures/errors | Skipped | Seconds |',
    '|---|---:|---:|---:|---:|',
    ...results.map(result => `| ${result.name.replaceAll('|', '\\|')} | ${result.tests} | ${
      Number(result.failures ?? 0) + Number(result.errors ?? 0)} | ${result.skipped ?? 0} | ${result.time} |`),
  ];
  if (!results.length) lines.push('', 'No JUnit reports available; the build may have failed before running tests.');
  for (const result of results) {
    if (result.failureDetails.length) {
      lines.push('', `### ${result.name}`, '', '```text',
        result.failureDetails.join('\n').slice(0, 16 * 1024).replaceAll('```', "'''"), '```');
      if (result.output) {
        lines.push('', '```text', result.output.slice(-16 * 1024).replaceAll('```', "'''"), '```');
      }
    }
    const phases = result.output.split('\n').filter(line =>
      /MTR-HARNESS (?:phase=(?:workspace|cache-restore|boot|shutdown|editor-save)\b|result=)/.test(line));
    if (phases.length) lines.push('', `### ${result.name} phases`, '', '```text', ...phases, '```');
  }
  const summary = redactEvidence(lines.join('\n')) + '\n';
  // Console first: artifact quota exhaustion must not hide the assertion.
  console.log(summary);
  if (summaryPath) fs.appendFileSync(summaryPath, summary);

  const bundle = path.join(root, 'harness', 'build', 'ci-debug');
  fs.rmSync(bundle, { recursive: true, force: true });
  fs.mkdirSync(bundle, { recursive: true });
  const evidence = [
    ...results.filter(result => result.failureDetails.length).map(result => result.file),
    ...workspaceEvidence(path.join(root, 'harness', 'build', 'work')),
    ...reports,
  ];
  let bytes = 0;
  let count = 0;
  const notes = [];
  for (const file of new Set(evidence)) {
    if (count >= MAX_FILES || bytes >= MAX_BUNDLE) {
      notes.push('Remaining evidence omitted: bundle size/file-count limit reached.');
      break;
    }
    const size = fs.statSync(file).size;
    const limit = Math.min(MAX_FILE, MAX_BUNDLE - bytes);
    const data = Buffer.alloc(Math.min(size, limit));
    const handle = fs.openSync(file, 'r');
    try {
      fs.readSync(handle, data, 0, data.length, 0);
    } finally {
      fs.closeSync(handle);
    }
    const relative = path.relative(root, file);
    const redacted = Buffer.from(redactEvidence(data.toString('utf8')));
    const truncated = size > data.length || redacted.length > limit;
    const target = path.join(bundle, relative + (truncated ? '.truncated.txt' : ''));
    const text = redacted.subarray(0, limit);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, text);
    bytes += text.length;
    count++;
    if (truncated) notes.push(`${relative}: truncated to ${data.length} bytes.`);
  }
  fs.writeFileSync(path.join(bundle, 'bundle.txt'),
    `Bounded, redacted diagnostics: ${count} files, ${bytes} bytes.\n${notes.join('\n')}\n`);
  return summary;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  reportCi(process.cwd(), process.env.GITHUB_STEP_SUMMARY);
}
