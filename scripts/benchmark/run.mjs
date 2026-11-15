#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..', '..');
const WEBUI_DIR = join(REPO_ROOT, 'webui');
const OUT_FILE = join(WEBUI_DIR, 'build', 'benchmark-cli.mjs');

const require = createRequire(join(WEBUI_DIR, 'package.json'));
const esbuild = require('esbuild');

mkdirSync(dirname(OUT_FILE), { recursive: true });

await esbuild.build({
  entryPoints: [join(WEBUI_DIR, 'benchmark', 'cli.ts')],
  outfile: OUT_FILE,
  bundle: true,
  platform: 'node',
  format: 'esm',
  target: 'node20',
  packages: 'external',
  sourcemap: 'inline',
  logLevel: 'error',
});

execFileSync(process.execPath, [OUT_FILE, ...process.argv.slice(2)], {
  cwd: REPO_ROOT,
  stdio: 'inherit',
  env: process.env,
});
