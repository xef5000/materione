/**
 * The engine Worker's entry (the file the build bundles as `sandbox/engine.js`): the frame spawns it from a blob URL and posts
 * it the port, the wasm bytes and the core's source once; after that it serves runs on the port.
 */
import { startEngine } from './engine';

self.onmessage = (event: MessageEvent) => {
  const init = event.data as { port?: MessagePort; wasm?: ArrayBuffer; coreSrc?: string; memoryMb?: number };
  self.onmessage = null;
  if (!(init.port instanceof MessagePort) || !(init.wasm instanceof ArrayBuffer) || typeof init.coreSrc !== 'string') return;
  void startEngine(init.port, { wasm: init.wasm, coreSource: init.coreSrc, memoryMb: Number(init.memoryMb) || 64 });
};
