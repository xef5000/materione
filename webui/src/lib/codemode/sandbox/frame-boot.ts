/**
 * The sandbox document's one inline script (codemode.md section 4.6): it runs in the opaque-origin iframe, under the CSP the
 * build computes from its own text. It receives exactly one `init` from its parent (the wasm bytes, the engine's source text and
 * the core's, transferred in: the frame never fetches, `connect-src 'none'`), spawns the engine in a Worker from a `blob:` URL
 * and hands it the private port. After that the frame's main thread is idle and everything travels on the port.
 *
 * It is kept this small on purpose: it is the only code that runs in the frame itself.
 */
import { isInit } from './protocol';

let started = false;

window.addEventListener('message', (event: MessageEvent) => {
  // one init, from our parent window, carrying the port
  if (started || event.source !== window.parent) return;
  const init: unknown = event.data;
  if (!isInit(init) || event.ports.length !== 1) return;
  started = true;
  const port = event.ports[0]!;
  try {
    const url = URL.createObjectURL(new Blob([init.engineSrc], { type: 'text/javascript' }));
    const worker = new Worker(url);
    worker.onerror = (failure) => port.postMessage({ t: 'boot-error', message: String(failure.message) });
    worker.postMessage({ port, wasm: init.wasm, coreSrc: init.coreSrc, memoryMb: init.memoryMb }, [port, init.wasm]);
  } catch (error) {
    port.postMessage({ t: 'boot-error', message: String((error as Error)?.message ?? error) });
  }
});
