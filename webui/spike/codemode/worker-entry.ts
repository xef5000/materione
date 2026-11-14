// SPIKE (#340) - throwaway. MODE "worker": the engine runs in a Worker spawned by the sandboxed iframe
// from a blob: URL, so a CPU-bound guest never blocks any page's main thread.
import { startEngine } from './engine';

self.onmessage = (e: MessageEvent) => {
  const d = e.data as { port: MessagePort; wasm: ArrayBuffer; memMaxMb: number };
  self.onmessage = null;
  void startEngine(d.port, d.wasm, d.memMaxMb);
};
