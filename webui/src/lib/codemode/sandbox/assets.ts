/**
 * The sandbox's static files (codemode.md section 4.6), fetched on the FIRST run and never before: nothing of the sandbox loads
 * with the editor. They are part of the SPA's build output (`vite.sandbox.ts` emits them under `sandbox/`), so both hosts serve
 * them as plain files with no header and no endpoint of their own.
 *
 *   sandbox/frame.html   the frame document: one inline script and a meta CSP that names its sha256 (the iframe loads this itself)
 *   sandbox/engine.js    QuickJS's wrapper and the engine, one script the frame turns into a blob Worker
 *   sandbox/core.js      the SDK core, one script the engine evaluates inside each fresh guest
 *   sandbox/quickjs.wasm the sync QuickJS build
 *
 * The host fetches the last three and TRANSFERS them into the frame: the frame never fetches (`connect-src 'none'`).
 */
import type { SandboxAssets } from './sandbox';

/** Where the files live: beside `index.html`, under the build's base. */
export function sandboxBase(): string {
  return `${import.meta.env.BASE_URL}sandbox/`;
}

export class SandboxAssetError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SandboxAssetError';
  }
}

const cache = new Map<string, Promise<SandboxAssets>>();

async function get(fetchFn: typeof fetch, url: string): Promise<Response> {
  const response = await fetchFn(url);
  if (!response.ok) throw new SandboxAssetError(`${url}: HTTP ${response.status}`);
  return response;
}

/**
 * The assets under `base`, fetched once per base. A host that falls back to `index.html` for a missing file (the relay's
 * single-page fallback) answers 200 with HTML: the checks below turn that into a clear error instead of a baffling boot failure.
 */
export function loadSandboxAssets(base: string = sandboxBase(), fetchFn: typeof fetch = (input, init) => fetch(input, init)): Promise<SandboxAssets> {
  let loading = cache.get(base);
  if (loading === undefined) {
    loading = (async () => {
      const [wasm, engineSrc, coreSrc] = await Promise.all([
        get(fetchFn, `${base}quickjs.wasm`).then((response) => response.arrayBuffer()),
        get(fetchFn, `${base}engine.js`).then((response) => response.text()),
        get(fetchFn, `${base}core.js`).then((response) => response.text()),
      ]);
      const magic = new Uint8Array(wasm, 0, Math.min(4, wasm.byteLength));
      if (magic.length < 4 || magic[0] !== 0 || magic[1] !== 0x61 || magic[2] !== 0x73 || magic[3] !== 0x6d) throw new SandboxAssetError(`${base}quickjs.wasm is not a WebAssembly module (is the host serving the sandbox's static files?)`);
      if (engineSrc.trimStart().startsWith('<') || coreSrc.trimStart().startsWith('<')) throw new SandboxAssetError(`${base}engine.js or core.js is not JavaScript (is the host serving the sandbox's static files?)`);
      return { frameUrl: `${base}frame.html`, wasm, engineSrc, coreSrc };
    })();
    cache.set(base, loading);
    // a failure is not cached: the next run tries again
    loading.catch(() => cache.delete(base));
  }
  return loading;
}
