import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import type { Plugin } from 'vite';

/**
 * The code-mode sandbox's build step (codemode.md section 4.6): four static files under `sandbox/` in the SPA's output, so the
 * plugin-served editor and the cloud editor both serve them as plain files with no header, endpoint or configuration change.
 *
 *   sandbox/frame.html    the frame document: ONE inline script, and a meta CSP that names that script's sha256. The hash is
 *                         computed here, from the exact bytes that end up in the file, so it cannot drift from the script.
 *   sandbox/engine.js     the QuickJS wrapper and the engine, bundled as one classic script (the frame makes a blob Worker of it)
 *   sandbox/core.js       the SDK core, bundled as one plain-ES2020 script (the engine evaluates it inside each fresh guest)
 *   sandbox/quickjs.wasm  the SYNC QuickJS build, MIT, copied from its package
 *
 * The packages that make these (`quickjs-emscripten-core`, the sync wasm build, esbuild) are devDependencies: they are bundled
 * here, at build time, and nothing of them is resolved at run time, so they need no place in `dependencies`.
 *
 * Used by vite.config.ts and browser/vite.config.ts (the plugin below), and by the specs that build the sandbox on their own.
 */

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = join(HERE, 'src/lib/codemode');

/**
 * The frame's CSP, as a `<meta>` (a header would need a change on both hosts). Everything is `'none'` but the one inline script
 * (by hash: `'self'` would still let the frame `importScripts` or `import()` anything on the editor's own static host), WebAssembly
 * compilation, and `blob:` Workers (the engine's Worker, which inherits this policy and the frame's opaque origin).
 */
export function sandboxCsp(scriptHash: string): string {
  return [
    `default-src 'none'`,
    `script-src 'sha256-${scriptHash}' 'wasm-unsafe-eval'`,
    `connect-src 'none'`,
    `worker-src blob:`,
    `frame-src 'none'`,
    `img-src 'none'`,
    `style-src 'none'`,
    `font-src 'none'`,
    `media-src 'none'`,
    `object-src 'none'`,
    `base-uri 'none'`,
    `form-action 'none'`,
  ].join('; ');
}

export interface SandboxBuild {
  /** Published path (relative to the output directory) to bytes. */
  files: Record<string, string | Uint8Array>;
  /** The sha256 (base64) of the frame's inline script, as named in the CSP. */
  scriptHash: string;
  csp: string;
}

/** The document, with its one inline script. `</script` inside the script is escaped, and the hash is of the escaped text. */
export function frameDocument(script: string): { html: string; hash: string; csp: string } {
  const inline = script.replace(/<\/script/gi, '<\\/script');
  const hash = createHash('sha256').update(inline).digest('base64');
  const csp = sandboxCsp(hash);
  const html = `<!doctype html>\n<meta charset="utf-8">\n<meta http-equiv="Content-Security-Policy" content="${csp}">\n<title>materione-sandbox</title>\n<script>${inline}</script>\n`;
  return { html, hash, csp };
}

const common = { bundle: true, write: false, legalComments: 'none', logLevel: 'error', minifyWhitespace: true, minifySyntax: true } as const;

async function bundle(entry: string, options: Parameters<typeof build>[0] = {}): Promise<string> {
  const out = await build({ ...common, entryPoints: [entry], ...options });
  return out.outputFiles?.[0]?.text ?? '';
}

let memo: Promise<SandboxBuild> | undefined;

/** Builds the sandbox's files. Deterministic: the same sources give the same bytes, so the same hash. Memoized per process. */
export function buildSandbox(): Promise<SandboxBuild> {
  memo ??= (async () => {
    // identifiers are kept (no minifyIdentifiers): the core's stack frames and error names are read by the host
    const core = await bundle(join(SRC, 'core/index.ts'), { format: 'iife', globalName: 'MaterioneCore', target: 'es2020', platform: 'neutral' });
    const engine = await bundle(join(SRC, 'sandbox/worker-entry.ts'), {
      format: 'iife',
      target: 'es2022',
      platform: 'browser',
      minifyIdentifiers: true,
      define: { 'import.meta.url': '"http://localhost/engine.js"', 'process.env.NODE_ENV': '"production"' },
    });
    const frame = await bundle(join(SRC, 'sandbox/frame-boot.ts'), { format: 'iife', target: 'es2022', platform: 'browser', minifyIdentifiers: true });
    const document = frameDocument(frame);
    const wasm = readFileSync(resolve(HERE, 'node_modules/@jitl/quickjs-wasmfile-release-sync/dist/emscripten-module.wasm'));
    return {
      files: { 'sandbox/frame.html': document.html, 'sandbox/engine.js': engine, 'sandbox/core.js': core, 'sandbox/quickjs.wasm': new Uint8Array(wasm) },
      scriptHash: document.hash,
      csp: document.csp,
    };
  })();
  return memo;
}

const TYPES: Record<string, string> = { html: 'text/html; charset=utf-8', js: 'text/javascript; charset=utf-8', wasm: 'application/wasm' };

/**
 * Emits the sandbox beside the SPA in a production build, and serves the same files in the dev server (so the browser-tier
 * fixture and the editor in `npm run dev` find them at the same URLs).
 */
export function sandboxAssets(): Plugin {
  return {
    name: 'materione-sandbox',
    async generateBundle() {
      const built = await buildSandbox();
      for (const [fileName, source] of Object.entries(built.files)) this.emitFile({ type: 'asset', fileName, source });
    },
    configureServer(server) {
      const base = server.config.base.replace(/\/$/, '');
      server.middlewares.use(async (request, response, next) => {
        const path = (request.url ?? '').split('?')[0] ?? '';
        const prefix = `${base}/sandbox/`;
        if (!path.startsWith(prefix)) return next();
        const built = await buildSandbox();
        const name = `sandbox/${path.slice(prefix.length)}`;
        const body = built.files[name];
        if (body === undefined) return next();
        response.setHeader('Content-Type', TYPES[name.slice(name.lastIndexOf('.') + 1)] ?? 'application/octet-stream');
        response.setHeader('Cache-Control', 'no-cache');
        response.end(typeof body === 'string' ? body : Buffer.from(body));
      });
    },
  };
}
