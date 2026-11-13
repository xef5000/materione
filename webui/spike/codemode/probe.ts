// SPIKE (#340) - throwaway. Runs NATIVELY in the sandboxed iframe (the outer layer only): what can
// code that has ALREADY escaped QuickJS reach? Each probe reports the failure it got, or ALLOWED.
// The server records any request that actually arrives under /__hit/.
const out: Record<string, string> = {};
const tryIt = async (name: string, f: () => unknown | Promise<unknown>) => {
  try { await f(); out[name] = 'ALLOWED'; } catch (e) { out[name] = 'blocked: ' + ((e as Error)?.name ?? String(e)); }
};

(async () => {
  out.origin = String(window.origin); // the SECURITY origin: "null" when opaque (location.origin is just the URL's)
  out.opaque = String(window.origin === 'null');
  await tryIt('parent.document', () => window.parent.document.title);
  await tryIt('parent.localStorage', () => (window.parent as any).localStorage.getItem('canary'));
  await tryIt('parent.__secret', () => { const v = (window.parent as any).__secret; if (v) return v; throw new Error('undefined'); });
  await tryIt('top.location.href', () => window.top!.location.href);
  await tryIt('own localStorage', () => localStorage.getItem('canary'));
  await tryIt('own sessionStorage', () => sessionStorage.getItem('canary'));
  await tryIt('document.cookie', () => { const c = document.cookie; if (c.includes('COOKIE-SECRET')) return; throw new Error('empty'); });
  await tryIt('indexedDB', () => new Promise((res, rej) => { try { const r = indexedDB.open('canary-db'); r.onsuccess = () => res(1); r.onerror = () => rej(new Error('err')); } catch (e) { rej(e); } }));
  await tryIt('fetch same-origin', async () => { await fetch('/__hit/fetch'); });
  await tryIt('fetch absolute', async () => { await fetch(location.href.replace(/\/[^/]*$/, '') + '/__hit/fetch-abs', { mode: 'no-cors' }); });
  await tryIt('XMLHttpRequest', () => new Promise((res, rej) => { const x = new XMLHttpRequest(); x.open('GET', '/__hit/xhr'); x.onload = () => res(1); x.onerror = () => rej(new Error('xhr error')); x.send(); }));
  await tryIt('WebSocket', () => new Promise((res, rej) => { const s = new WebSocket(location.href.replace(/^http/, 'ws').replace(/\/[^/]*$/, '') + '/__hit/ws'); s.onopen = () => res(1); s.onerror = () => rej(new Error('ws error')); }));
  await tryIt('sendBeacon', () => { if (!navigator.sendBeacon('/__hit/beacon', 'x')) throw new Error('refused'); });
  await tryIt('img load', () => new Promise((res, rej) => { const i = new Image(); i.onload = () => res(1); i.onerror = () => rej(new Error('img error')); i.src = '/__hit/img'; }));
  await tryIt('import(data:)', () => import(['data:text/javascript,', 'export default 1'].join('')));
  await tryIt('import(same-origin url)', () => import(location.href.replace(/\/[^/]*$/, '') + '/__hit/imp.js'));
  await tryIt('<script src> injection', () => new Promise((res, rej) => { const s = document.createElement('script'); s.src = '/__hit/script.js'; s.onload = () => res(1); s.onerror = () => rej(new Error('script blocked or failed')); document.head.appendChild(s); }));
  await tryIt('new Worker(same-origin url)', () => new Promise((res, rej) => { try { const w = new Worker('/__hit/worker.js'); w.onerror = () => rej(new Error('worker error')); setTimeout(() => res(1), 300); } catch (e) { rej(e); } }));
  await tryIt('eval()', () => (0, eval)('1+1'));
  await tryIt('new Function', () => new Function('return 1')());
  await tryIt('nested iframe', () => { const f = document.createElement('iframe'); f.src = '/__hit/iframe'; document.body.appendChild(f); });
  await tryIt('window.open', () => { const w = window.open('/__hit/open'); if (!w) throw new Error('blocked'); });
  await tryIt('top navigation', () => { (window.top as any).location = '/__hit/nav'; });
  window.parent.postMessage({ t: 'probe-result', out }, '*');
})();
