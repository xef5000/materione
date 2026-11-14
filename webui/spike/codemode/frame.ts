// SPIKE (#340) - throwaway. MODE "frame": the engine runs on the iframe's main thread.
// Accepts exactly one init, from our parent, carrying the port; everything after travels on the port.
import { startEngine } from './engine';

let started = false;
window.addEventListener('message', (e) => {
  if (started || e.source !== window.parent) return;
  const d = e.data as { t?: string; wasm?: ArrayBuffer; memMaxMb?: number };
  if (d?.t !== 'init' || !(d.wasm instanceof ArrayBuffer) || e.ports.length !== 1) return;
  started = true;
  void startEngine(e.ports[0]!, d.wasm, Number(d.memMaxMb) || 64);
});
