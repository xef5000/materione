#!/usr/bin/env node
// Repo rules that CLAUDE.md states and a script can decide. Pure Node, no
// install; the same command runs locally and in CI (job `rules`).
//
//   node scripts/checks/run.mjs                    run every check
//   node scripts/checks/run.mjs --next-mtr         print the next free MTR code
//   node scripts/checks/run.mjs --update-baselines rewrite the comment ratchet (only shrinks are reviewed as routine)
//   node scripts/checks/run.mjs --pin-blockbench   re-pin the vendored Blockbench build after an upstream bump
//   node scripts/checks/run.mjs --spec-report BASE list spec files a branch changes (CI job summary)
//
// Rules, what they catch and why: scripts/checks/README.md.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { TextDecoder } from 'node:util';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const BASELINE = path.join(ROOT, 'scripts/checks/baselines/milestone-comments.json');
const RESERVED = path.join(ROOT, 'scripts/checks/mtr-reserved.json');
const BLOCKBENCH_MANIFEST = path.join(ROOT, 'scripts/checks/baselines/blockbench.blobs.json');
const BLOCKBENCH_DIR = 'webui/public/blockbench/';
const DIAGNOSTIC_CODES = 'api/src/main/java/dev/materione/api/diagnostics/DiagnosticCodes.java';
const BINARY_EXTENSIONS = new Set([
  'bin', 'class', 'dat', 'gif', 'gz', 'ico', 'jar', 'jpeg', 'jpg', 'nbt', 'ogg',
  'otf', 'pdf', 'png', 'ttf', 'wav', 'webp', 'woff', 'woff2', 'zip',
]);

export const SPEC_FILES = ['CLAUDE.md', 'AGENTS.md', 'docs/architecture.md', 'docs/roadmap.md', 'docs/architecture/primitives.md'];

// ---------------------------------------------------------------- markdown

// GitHub's heading anchor: lowercase, punctuation dropped, spaces to hyphens,
// repeats suffixed -1, -2, ...
export function githubSlugs(markdown) {
  const seen = new Map();
  const slugs = new Set();
  const lines = stripCode(markdown).split('\n');
  for (let i = 0; i < lines.length; i++) {
    const atx = /^ {0,3}#{1,6}\s+(.*?)(?:\s+#+)?\s*$/.exec(lines[i]);
    const setext = !atx && lines[i].trim() && /^ {0,3}(=+|-+)\s*$/.test(lines[i + 1] ?? '') && !/^\s*([-*+]|\d+\.)\s/.test(lines[i]) ? lines[i].trim() : null;
    if (!atx && setext === null) continue;
    const text = (atx ? atx[1] : setext)
      .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/<[^>]+>/g, '')
      .replace(/`/g, '');
    const base = text.toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, '').replace(/ /g, '-');
    const n = seen.get(base) ?? 0;
    seen.set(base, n + 1);
    slugs.add(n === 0 ? base : `${base}-${n}`);
  }
  for (const m of markdown.matchAll(/<a\s+(?:name|id)="([^"]+)"/g)) slugs.add(m[1]);
  return slugs;
}

function stripCode(markdown) {
  return markdown.replace(/^( {0,3})(```|~~~)[\s\S]*?^\1\2.*$/gm, '').replace(/`[^`\n]*`/g, '');
}

export function markdownLinks(markdown) {
  const links = [];
  const text = stripCode(markdown);
  for (const m of text.matchAll(/!?\[(?:[^\]\[]|\[[^\]]*\])*\]\(\s*(?:<([^>\n]+)>|((?:[^()\s]|\([^()\s]*\))+))(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*\)/g)) links.push(m[1] ?? m[2]);
  for (const m of text.matchAll(/<(?:a|img)\s[^>]*?\b(?:href|src)=["']([^"']+)["']/gi)) links.push(m[1]);
  for (const m of text.matchAll(/^\s*\[[^\]]+\]:\s*(\S+)/gm)) links.push(m[1]);
  return links;
}

export function checkLinks(files, read, exists) {
  const problems = [];
  const slugCache = new Map();
  const slugsOf = (f) => {
    if (!slugCache.has(f)) slugCache.set(f, githubSlugs(read(f)));
    return slugCache.get(f);
  };
  for (const file of files) {
    for (const link of markdownLinks(read(file))) {
      if (/^[a-z][a-z0-9+.-]*:/i.test(link) || link.startsWith('//')) continue;
      const [rawTarget, anchor] = link.split('#');
      let target = file;
      if (rawTarget) {
        target = path.posix.normalize(path.posix.join(path.posix.dirname(file), decodeURIComponent(rawTarget)));
        if (rawTarget.startsWith('/')) target = decodeURIComponent(rawTarget).slice(1);
        if (!exists(target)) {
          problems.push(`${file}: link to missing file ${link}`);
          continue;
        }
      }
      if (anchor && target.endsWith('.md') && !/^L\d+/.test(anchor) && !slugsOf(target).has(decodeURIComponent(anchor).toLowerCase())) {
        problems.push(`${file}: link to missing heading ${link}`);
      }
    }
  }
  return problems;
}

// ---------------------------------------------------------------- naming

export function checkNaming(files, read) {
  // The files that state the rule, implement it, or test it.
  const allowed = new Set(['CLAUDE.md', 'scripts/checks/README.md', 'scripts/checks/run.mjs', 'scripts/checks/run.test.mjs']);
  const problems = [];
  for (const file of files) {
    if (allowed.has(file)) continue;
    const lines = read(file).split('\n');
    lines.forEach((line, i) => {
      if (/mater[\s_-]?yard/i.test(line)) problems.push(`${file}:${i + 1}: the old name "MaterYard" (CLAUDE.md: naming is "Materione" everywhere)`);
    });
  }
  return problems;
}

// ---------------------------------------------------------------- diagnostics

export function definedCodes(javaSource) {
  return new Set([...javaSource.matchAll(/new DiagnosticCode\(\s*"(MTR-\d{4})"/g)].map((m) => m[1]));
}

export function expandReserved(reserved) {
  const codes = new Map();
  for (const entry of reserved) {
    const [from, to = from] = entry.codes.split('..');
    const a = Number(from.slice(4));
    const b = Number(to.slice(4));
    for (let n = a; n <= b; n++) codes.set(`MTR-${String(n).padStart(4, '0')}`, entry.reason);
  }
  return codes;
}

export function checkDocCodes(docFiles, read, defined, reserved) {
  const problems = [];
  for (const file of docFiles) {
    read(file).split('\n').forEach((line, i) => {
      for (const m of line.matchAll(/MTR-\d{4}/g)) {
        if (!defined.has(m[0]) && !reserved.has(m[0])) {
          problems.push(`${file}:${i + 1}: ${m[0]} is neither defined in ${DIAGNOSTIC_CODES} nor reserved in scripts/checks/mtr-reserved.json`);
        }
      }
    });
  }
  return [...new Set(problems)];
}

export function nextFreeCode(defined, reserved) {
  let max = 0;
  for (const c of [...defined, ...reserved.keys()]) {
    const n = Number(c.slice(4));
    if (n < 1000) max = Math.max(max, n);
  }
  return `MTR-${String(max + 1).padStart(4, '0')}`;
}

// ---------------------------------------------------------------- comment ratchet

// Comments that name a milestone ("inert until M7", "M2 ships the machinery")
// are true the day they are written and wrong once the roadmap moves. The docs
// own milestone state; code comments may not grow new references.
const MILESTONE = /\bM\d(?:\.\d)?\b/g;

export function isRatchetedSource(file) {
  if (!/\.(java|kt|kts|ts|tsx|mjs|js)$/.test(file)) return false;
  if (/(^|\/)(test|tests|__tests__|fixtures?)\//.test(file) || /\.(test|spec)\.[a-z]+$/.test(file)) return false;
  return file.includes('/src/main/') || /^(webui|cloud\/[^/]+)\/src\//.test(file);
}

// Lexes Java/Kotlin/TS/JS just enough to know which characters are comment:
// strings (incl. Java text blocks and template literals), regex literals,
// line and block comments wherever they start.
export function commentText(source) {
  const out = source.split('').map((c) => (c === '\n' ? '\n' : ' '));
  let i = 0;
  let prev = '';
  const n = source.length;
  const regexAllowedAfter = new Set(['', '(', ',', '=', ':', '[', '!', '&', '|', '?', '{', '}', ';', '+', '-', '*', '%', '<', '>', '~', '^']);
  while (i < n) {
    const c = source[i];
    const d = source[i + 1];
    if (c === '/' && d === '/') {
      while (i < n && source[i] !== '\n') out[i] = source[i++];
    } else if (c === '/' && d === '*') {
      const close = source.indexOf('*/', i + 2);
      const stop = close === -1 ? n : close + 2;
      for (; i < stop; i++) out[i] = source[i];
      prev = ' ';
    } else if (c === '"' && source.startsWith('"""', i)) {
      const close = source.indexOf('"""', i + 3);
      i = close === -1 ? n : close + 3;
      prev = 'x';
    } else if (c === '"' || c === "'" || c === '`') {
      i++;
      while (i < n && source[i] !== c) {
        if (source[i] === '\\') i++;
        else if (c !== '`' && source[i] === '\n') break;
        i++;
      }
      i++;
      prev = 'x';
    } else if (c === '/' && regexAllowedAfter.has(prev)) {
      i++;
      let inClass = false;
      while (i < n && source[i] !== '\n') {
        if (source[i] === '\\') i++;
        else if (source[i] === '[') inClass = true;
        else if (source[i] === ']') inClass = false;
        else if (source[i] === '/' && !inClass) break;
        i++;
      }
      i++;
      prev = 'x';
    } else {
      if (!/\s/.test(c)) prev = /[A-Za-z0-9_$)\].]/.test(c) ? 'x' : c;
      i++;
    }
  }
  return out.join('');
}

export function milestoneCommentLines(source) {
  const sourceLines = source.split('\n');
  const hits = [];
  commentText(source).split('\n').forEach((comment, i) => {
    const n = (comment.match(MILESTONE) ?? []).length;
    if (n) hits.push({ line: i + 1, count: n, text: sourceLines[i].trim() });
  });
  return hits;
}

export function checkRatchet(files, read, baseline) {
  const problems = [];
  const counts = {};
  for (const file of files.filter(isRatchetedSource)) {
    const hits = milestoneCommentLines(read(file));
    const total = hits.reduce((a, h) => a + h.count, 0);
    if (total) counts[file] = total;
    const allowed = baseline[file] ?? 0;
    if (total > allowed) {
      const sample = hits.slice(0, 3).map((h) => `    ${file}:${h.line}: ${h.text.slice(0, 120)}`).join('\n');
      problems.push(`${file}: ${total} milestone reference(s) in comments, baseline ${allowed}. Say what the code does now; milestone state lives in docs/roadmap.md.\n${sample}`);
    }
  }
  return { problems, counts };
}

// ---------------------------------------------------------------- vendored Blockbench

// Blockbench ships unmodified (CLAUDE.md, D9); the folder is upstream build
// output pinned in webui/public/blockbench/PIN.md. Any byte change outside
// PIN.md has to come with a re-pin (--pin-blockbench), which the spec report
// surfaces for review.
export function blockbenchDrift(files, hashOf, manifest) {
  const vendored = files.filter((f) => f.startsWith(BLOCKBENCH_DIR) && f !== `${BLOCKBENCH_DIR}PIN.md`);
  const problems = [];
  for (const f of vendored) {
    if (!(f in manifest)) problems.push(`${f}: added to the vendored Blockbench build without a re-pin`);
    else if (manifest[f] !== hashOf(f)) problems.push(`${f}: differs from the pinned upstream build (Blockbench stays unmodified; integrate through the Blockbench plugin)`);
  }
  for (const f of Object.keys(manifest)) if (!vendored.includes(f)) problems.push(`${f}: removed from the vendored Blockbench build without a re-pin`);
  return problems;
}

// Git blob ids, not working-tree hashes: identical on every platform whatever
// the line-ending conversion.
let blobIds;
function blobId(file) {
  blobIds ??= new Map(git('ls-files', '-s', '--', BLOCKBENCH_DIR).split('\n').filter(Boolean).map((l) => {
    const [meta, name] = l.split('\t');
    return [name, meta.split(' ')[1]];
  }));
  return blobIds.get(file);
}

// ---------------------------------------------------------------- runner

function git(...args) {
  return execFileSync('git', ['-C', ROOT, ...args], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

function trackedFiles() {
  return git('ls-files', '-z').split('\0').filter(Boolean);
}

function readRepo(file) {
  return fs.readFileSync(path.join(ROOT, file), 'utf8');
}

function readRepoBytes(file) {
  return fs.readFileSync(path.join(ROOT, file));
}

function existsRepo(file) {
  return fs.existsSync(path.join(ROOT, file));
}

function loadJson(file, fallback) {
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : fallback;
}

function annotate(level, message) {
  if (process.env.GITHUB_ACTIONS) console.log(`::${level}::${message.split('\n')[0].replace(/%/g, '%25')}`);
}

export function checkEncoding(files, read) {
  const problems = [];
  const decoder = new TextDecoder('utf-8', { fatal: true });
  for (const file of files) {
    const extension = path.posix.extname(file).slice(1).toLowerCase();
    if (BINARY_EXTENSIONS.has(extension)) continue;
    const bytes = read(file);
    if (bytes.includes(0)) continue;
    try {
      decoder.decode(bytes);
    } catch {
      problems.push(`${file}: not valid UTF-8`);
    }
  }
  return problems;
}

function specReport(base) {
  const changed = git('diff', '--name-only', `${base}...HEAD`).split('\n').filter(Boolean);
  const spec = changed.filter((f) => SPEC_FILES.includes(f) || f.startsWith('docs/architecture/') || f === 'scripts/checks/baselines/blockbench.blobs.json' || f === 'scripts/checks/mtr-reserved.json');
  const before = (() => {
    try {
      return JSON.parse(execFileSync('git', ['-C', ROOT, 'show', `${base}:scripts/checks/baselines/milestone-comments.json`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }));
    } catch {
      return null; // the base predates the ratchet
    }
  })();
  const sum = (o) => Object.values(o).reduce((a, b) => a + b, 0);
  const grew = before ? sum(loadJson(BASELINE, {})) - sum(before) : 0;
  const lines = [];
  if (spec.length) lines.push('### Spec files changed', '', 'Review these first; they bind every later change.', '', ...spec.map((f) => `- \`${f}\``), '');
  if (grew > 0) lines.push(`### Comment ratchet grew by ${grew}`, '', 'The milestone-comment baseline went up. It is meant to only shrink.', '');
  if (!lines.length) lines.push('No spec files changed; comment ratchet did not grow.');
  const text = lines.join('\n');
  console.log(text);
  if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${text}\n`);
  if (spec.length) annotate('warning', `Spec files changed: ${spec.join(', ')}`);
  if (grew > 0) annotate('warning', `Milestone-comment baseline grew by ${grew}`);
}

function main(argv) {
  const files = trackedFiles();
  const markdown = files.filter((f) => f.endsWith('.md') && !f.startsWith('nexo-reference/') && !f.startsWith('qa/evidence/'));
  const defined = definedCodes(readRepo(DIAGNOSTIC_CODES));
  const reserved = expandReserved(loadJson(RESERVED, []));

  if (argv.includes('--next-mtr')) {
    console.log(nextFreeCode(defined, reserved));
    return 0;
  }
  const specIdx = argv.indexOf('--spec-report');
  if (specIdx >= 0) {
    specReport(argv[specIdx + 1] ?? 'origin/main');
    return 0;
  }
  if (argv.includes('--pin-blockbench')) {
    const manifest = Object.fromEntries(files.filter((f) => f.startsWith(BLOCKBENCH_DIR) && f !== `${BLOCKBENCH_DIR}PIN.md`).sort().map((f) => [f, blobId(f)]));
    fs.writeFileSync(BLOCKBENCH_MANIFEST, `${JSON.stringify(manifest, null, 2)}
`);
    console.log(`pinned ${Object.keys(manifest).length} vendored Blockbench files`);
    return 0;
  }
  if (argv.includes('--update-baselines')) {
    const { counts } = checkRatchet(files, readRepo, {});
    const sorted = Object.fromEntries(Object.entries(counts).sort(([a], [b]) => a.localeCompare(b)));
    fs.mkdirSync(path.dirname(BASELINE), { recursive: true });
    fs.writeFileSync(BASELINE, `${JSON.stringify(sorted, null, 2)}\n`);
    console.log(`wrote ${path.relative(ROOT, BASELINE)}: ${Object.values(counts).reduce((a, b) => a + b, 0)} references in ${Object.keys(counts).length} files`);
    return 0;
  }

  const checks = [
    ['UTF-8 encoding', () => checkEncoding(files, readRepoBytes)],
    ['docs links and anchors', () => checkLinks(markdown, readRepo, existsRepo)],
    ['naming', () => checkNaming(files.filter((f) => /\.(md|java|kt|kts|ts|tsx|mjs|js|json|ya?ml|gradle)$/.test(f) && !f.startsWith('nexo-reference/')), readRepo)],
    ['diagnostic codes in docs', () => checkDocCodes(markdown, readRepo, defined, reserved)],
    ['vendored Blockbench unmodified', () => blockbenchDrift(files, blobId, loadJson(BLOCKBENCH_MANIFEST, {}))],
    ['milestone references in code comments', () => checkRatchet(files, readRepo, loadJson(BASELINE, {})).problems],
  ];
  let failed = 0;
  for (const [name, run] of checks) {
    const problems = run();
    if (problems.length) {
      failed++;
      console.log(`FAIL ${name} (${problems.length})`);
      for (const p of problems) {
        console.log(`  ${p}`);
        annotate('error', `${name}: ${p}`);
      }
    } else console.log(`ok   ${name}`);
  }
  return failed ? 1 : 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exit(main(process.argv.slice(2)));
