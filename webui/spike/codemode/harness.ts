// SPIKE (#340) - throwaway. The harness page: plants canaries the sandbox must never see, and exposes
// window.spike to the Playwright specs.
import { Sandbox, TsClient } from './host';

// Canaries standing in for the signing key, session capability and decrypted content (codemode.md 4.1).
try { localStorage.setItem('canary', 'LOCALSTORAGE-SECRET'); } catch { /* ignore */ }
document.cookie = 'canary=COOKIE-SECRET; path=/';
(window as any).__secret = 'WINDOW-SECRET';
indexedDB.open('canary-db', 1);
// A BroadcastChannel the editor listens on; code in the sandbox must not be able to reach it.
(window as any).__bc = [] as string[];
new BroadcastChannel('canary').onmessage = (e) => (window as any).__bc.push(String(e.data));

const w = window as any;
w.spike = {
  Sandbox,
  TsClient,
  frameUrl: '/sandbox.html',
  wasmUrl: '/quickjs.wasm',
  tsWorkerUrl: '/tsworker.js',
  created: [] as Sandbox[],
  async make(readDelayMs = 0, mode: 'frame' | 'worker' = 'frame', memMaxMb = 64) {
    const ts = w.spike.ts ?? (w.spike.ts = new TsClient(w.spike.tsWorkerUrl));
    const frameUrl = mode === 'worker' ? '/sandbox-worker.html' : w.spike.frameUrl;
    const s = await Sandbox.create({ frameUrl, wasmUrl: w.spike.wasmUrl, readDelayMs, mode, memMaxMb }, ts);
    w.spike.created.push(s);
    return s;
  },
};
document.title = 'spike ready';
