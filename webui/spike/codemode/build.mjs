// SPIKE (#340) - throwaway. Bundles the spike into build/spike-codemode/ (gitignored, never part of
// `npm run build`'s SPA output) and records bundle sizes.
import { build } from 'esbuild';
import { readFileSync, writeFileSync, mkdirSync, copyFileSync, readdirSync } from 'node:fs';
import { gzipSync, brotliCompressSync } from 'node:zlib';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const out = join(root, 'build/spike-codemode');
mkdirSync(out, { recursive: true });

const tsLib = join(root, 'node_modules/typescript/lib');
const libPlugin = {
  name: 'tslibs',
  setup(b) {
    b.onResolve({ filter: /^virtual:tslibs$/ }, () => ({ path: 'tslibs', namespace: 'tslibs' }));
    b.onLoad({ filter: /.*/, namespace: 'tslibs' }, () => {
      const names = readdirSync(tsLib).filter((f) => /^lib\.(es5|es20\d\d.*|decorators.*)\.d\.ts$/.test(f));
      const o = {};
      for (const n of names) o[n] = readFileSync(join(tsLib, n), 'utf8');
      return { contents: 'export default ' + JSON.stringify(o), loader: 'js' };
    });
  },
};

const common = { bundle: true, minify: true, format: 'iife', target: 'es2022', legalComments: 'none', logLevel: 'warning' };
const sizes = {};
async function one(entry, outfile, extra = {}) {
  await build({ ...common, entryPoints: [join(here, entry)], outfile: join(out, outfile), ...extra });
  const buf = readFileSync(join(out, outfile));
  sizes[outfile] = { raw: buf.length, gzip: gzipSync(buf).length, brotli: brotliCompressSync(buf).length };
}
await one('frame.ts', 'frame.js', { define: { 'import.meta.url': '"http://localhost/frame.js"' } });
await one('probe.ts', 'probe.js');
await one('probe-worker.ts', 'probe-worker.js');
await one('frame-boot.ts', 'frame-boot.js');
await one('worker-entry.ts', 'engine-worker.js', { define: { 'import.meta.url': '"http://localhost/engine-worker.js"' } });
await one('harness.ts', 'harness.js');
await one('tsworker.ts', 'tsworker.js', {
  plugins: [libPlugin],
  define: { 'process.env.NODE_ENV': '"production"' },
  platform: 'browser',
  external: ['fs', 'path', 'os', 'crypto', 'util', 'perf_hooks', 'inspector', 'module', 'url', 'buffer', 'stream', 'child_process', 'tty', 'net', 'events', 'assert', 'readline'],
});

const wasm = readFileSync(join(root, 'node_modules/@jitl/quickjs-wasmfile-release-sync/dist/emscripten-module.wasm'));
writeFileSync(join(out, 'quickjs.wasm'), wasm);
const sz = (b) => ({ raw: b.length, gzip: gzipSync(b).length, brotli: brotliCompressSync(b).length });
sizes['quickjs.wasm'] = sz(wasm);
sizes['(asyncify variant wasm, not shipped)'] = sz(readFileSync(join(root, 'node_modules/@jitl/quickjs-wasmfile-release-asyncify/dist/emscripten-module.wasm')));
// The sandbox documents carry their one script INLINE and the CSP names its sha256 instead of 'self':
// then no URL is a script source at all (importScripts / import() of our own static host is refused too).
for (const f of ['sandbox.html', 'sandbox-worker.html', 'probe.html', 'probe-worker.html']) {
  let html = readFileSync(join(here, f), 'utf8');
  const src = /<script src="([^"]+)"><\/script>/.exec(html)[1];
  const js = readFileSync(join(out, src), 'utf8').replace(/<\/script/gi, '<\\/script');
  const hash = createHash('sha256').update(js).digest('base64');
  html = html.replace(/<script src="[^"]+"><\/script>/, () => `<script>${js}</script>`).replace("script-src 'self'", `script-src 'sha256-${hash}'`);
  writeFileSync(join(out, f), html);
}
for (const f of ['probe-nocsp.html', 'harness.html']) copyFileSync(join(here, f), join(out, f));
writeFileSync(join(out, 'sizes.json'), JSON.stringify(sizes, null, 2));
console.log(JSON.stringify(sizes, null, 2));
