// SPIKE (#340) - throwaway. The iframe document's only code in MODE "worker": it receives the worker
// source (text, from the host - the frame cannot fetch under connect-src 'none'), spawns the worker
// from a blob: URL and hands it the port. After that the iframe's main thread is idle.
let started = false;
window.addEventListener('message', (e) => {
  if (started || e.source !== window.parent) return;
  const d = e.data as { t?: string; wasm?: ArrayBuffer; workerSrc?: string; memMaxMb?: number };
  if (d?.t !== 'init' || !(d.wasm instanceof ArrayBuffer) || typeof d.workerSrc !== 'string' || e.ports.length !== 1) return;
  started = true;
  const port = e.ports[0]!;
  try {
    const url = URL.createObjectURL(new Blob([d.workerSrc], { type: 'text/javascript' }));
    const w = new Worker(url);
    w.onerror = (ev) => port.postMessage({ t: 'boot-error', message: String(ev.message) });
    w.postMessage({ port, wasm: d.wasm, memMaxMb: Number(d.memMaxMb) || 64 }, [port, d.wasm]);
  } catch (err) {
    port.postMessage({ t: 'boot-error', message: String((err as Error).message ?? err) });
  }
});
