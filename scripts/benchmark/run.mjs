#!/usr/bin/env node
import { execFileSync, spawn } from 'node:child_process';
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

const args = process.argv.slice(2);
let keepAwake = null;
if (process.platform === 'win32' && !args.includes('--mock') && !args.includes('--help') && !args.includes('-h')) {
  const psScript = [
    'Add-Type -TypeDefinition @"',
    'using System;',
    'using System.Runtime.InteropServices;',
    'public static class KeepAwake {',
    '  [DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);',
    '  public static void Enable() { SetThreadExecutionState(0x80000001u); }',
    '}',
    '"@',
    '[KeepAwake]::Enable()',
    `while (Get-Process -Id ${process.pid} -ErrorAction SilentlyContinue) { [KeepAwake]::Enable(); Start-Sleep -Seconds 15 }`,
  ].join('\n');
  keepAwake = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', psScript], {
    stdio: 'ignore',
    windowsHide: true,
  });
}

try {
  execFileSync(process.execPath, [OUT_FILE, ...args], {
    cwd: REPO_ROOT,
    stdio: 'inherit',
    env: process.env,
  });
} finally {
  try {
    keepAwake?.kill();
  } catch {
    /* ignore */
  }
}
