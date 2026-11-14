// SPIKE (#340) - throwaway. The same "what can escaped code reach" probe, but from inside the blob:
// Worker the sandboxed iframe spawns (MODE "worker"): does the worker inherit the opaque origin and CSP?
const WORKER_SRC = `
const out = {};
const tryIt = async (n, f) => { try { await f(); out[n] = 'ALLOWED'; } catch (e) { out[n] = 'blocked: ' + (e && e.name || e); } };
self.onmessage = async (ev) => {
  const base = ev.data.base;
  out.origin = String(self.origin);
  await tryIt('fetch', async () => { await fetch(base + '/__hit/w-fetch'); });
  await tryIt('fetch no-cors', async () => { await fetch(base + '/__hit/w-fetch-nc', { mode: 'no-cors' }); });
  await tryIt('XMLHttpRequest', () => new Promise((res, rej) => { const x = new XMLHttpRequest(); x.open('GET', base + '/__hit/w-xhr'); x.onload = () => res(1); x.onerror = () => rej(new Error('xhr error')); x.send(); }));
  await tryIt('WebSocket', () => new Promise((res, rej) => { const s = new WebSocket(base.replace(/^http/, 'ws') + '/__hit/w-ws'); s.onopen = () => res(1); s.onerror = () => rej(new Error('ws error')); }));
  await tryIt('importScripts', () => importScripts(base + '/__hit/w-imp.js'));
  await tryIt('import(data:)', () => import('data:text/javascript,export default 1'));
  await tryIt('nested Worker', () => new Promise((res, rej) => { try { const w = new Worker(base + '/__hit/w-nested.js'); w.onerror = () => rej(new Error('nested worker error')); setTimeout(() => res(1), 300); } catch (e) { rej(e); } }));
  await tryIt('indexedDB (editor db)', () => new Promise((res, rej) => { try { const r = indexedDB.open('canary-db'); r.onsuccess = () => res(1); r.onerror = () => rej(new Error('idb error')); } catch (e) { rej(e); } }));
  await tryIt('caches', async () => { await caches.open('x'); });
  const bc = new BroadcastChannel('canary'); bc.postMessage('from-worker'); out.broadcast = 'posted';
  postMessage({ t: 'probe-result', out });
};
`;
const url = URL.createObjectURL(new Blob([WORKER_SRC], { type: 'text/javascript' }));
const w = new Worker(url);
w.onmessage = (e) => window.parent.postMessage(e.data, '*');
w.onerror = (e) => window.parent.postMessage({ t: 'probe-result', out: { error: 'worker error ' + e.message } }, '*');
w.postMessage({ base: location.href.replace(/\/[^/]*$/, '') });
