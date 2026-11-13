// SPIKE (#340) - throwaway. Static server for build/spike-codemode with NO special headers (the
// worst case for the relay's and the plugin's static hosting), plus a /__hit/ recorder that proves
// whether a request left the sandbox.
import http from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../build/spike-codemode');
const port = Number(process.env.PORT ?? 4180);
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.wasm': 'application/wasm' };
let hits = [];
http
  .createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    if (u.pathname === '/__hits') { res.setHeader('content-type', 'application/json'); return res.end(JSON.stringify(hits)); }
    if (u.pathname === '/__reset') { hits = []; return res.end('ok'); }
    if (u.pathname.startsWith('/__hit/')) { hits.push(u.pathname); res.statusCode = 204; return res.end(); }
    const p = u.pathname === '/' ? '/harness.html' : u.pathname;
    const f = join(root, p);
    if (!f.startsWith(root) || !existsSync(f)) { res.statusCode = 404; return res.end('nf'); }
    res.setHeader('content-type', types[extname(f)] ?? 'application/octet-stream');
    res.end(readFileSync(f));
  })
  .on('upgrade', (req, sock) => { hits.push(new URL(req.url, 'http://x').pathname); sock.destroy(); })
  .listen(port, '127.0.0.1', () => console.log('spike server on', port));
