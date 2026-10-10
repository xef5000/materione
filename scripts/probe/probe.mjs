#!/usr/bin/env node
// Materione agent probe loop (issue #34): boots a local Paper 26.x probe
// server and a real windowed Minecraft client joined to it, so an agent
// session can stage a scene over the server console (RCON), then SEE the
// result by capturing the client framebuffer via DebugBridge
// (https://github.com/use-ai-for-mc/debugbridge, MIT).
//
// Zero npm dependencies — Node >= 22 (built-in WebSocket).
//
//   node scripts/probe/probe.mjs up        # stage + boot the probe server
//   node scripts/probe/probe.mjs client    # launch the probe client
//   node scripts/probe/probe.mjs join      # join the server (pack pre-accepted)
//   node scripts/probe/probe.mjs shot      # capture the client's view to a file
//   node scripts/probe/probe.mjs rcon give Probe minecraft:diamond 1
//   node scripts/probe/probe.mjs boot      # up + client + join + shot in one go
//
// See docs/probe.md for the full agent recipe. Everything lands under
// harness/build/probe/ (build dir — a stray kill can never dirty the repo).

import { spawn, spawnSync } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const PROBE = path.join(ROOT, 'harness', 'build', 'probe');
const SERVER_DIR = path.join(PROBE, 'server');
const CLIENT_DIR = path.join(PROBE, 'client');
const SHOTS_DIR = path.join(PROBE, 'shots');
const DL_DIR = path.join(PROBE, 'dl');
const CATALOG = path.join(ROOT, 'gradle', 'libs.versions.toml');
const FIXTURE_PACKS = path.join(ROOT, 'fixtures', 'packs');

const SERVER_PORT = 25599;
const RCON_PORT = 25590;
const RCON_PASSWORD = 'materione-probe';
const BRIDGE_PORT = 9876; // DebugBridge scans 9876-9886
const PROBE_PLAYER = 'Probe';
const BOOT_TIMEOUT_MS = 300_000;

const args = process.argv.slice(2);
const cmd = args.shift();

try {
  switch (cmd) {
    case 'up': await cmdUp(); break;
    case 'down': await cmdDown(); break;
    case 'restart': await cmdDown(); await cmdUp(); break;
    case 'client': await cmdClient(); break;
    case 'join': await cmdJoin(); break;
    case 'shot': await cmdShot(); break;
    case 'exec': await cmdExec(); break;
    case 'screen': await bridged((call) => call('screenInspect', {}), printResult); break;
    case 'state': await bridged((call) => call('snapshot', {}), printResult); break;
    case 'chat': await bridged((call) => call('chatHistory', { limit: intOpt('limit', 20) }), printResult); break;
    case 'command': await cmdRunCommand(); break;
    case 'quit': await cmdQuit(); break;
    case 'rcon': await cmdRcon(); break;
    case 'boot': await cmdBoot(); break;
    default: usage();
  }
} catch (e) {
  fail(e.message ?? String(e));
}

function usage() {
  process.stdout.write(`probe — Materione agent probe loop (docs/probe.md)

Server (Paper 26.x, superflat, offline, RCON):
  up                        stage + boot the probe server, wait for ready markers
  down                      stop it (graceful "stop" via RCON)
  restart                   down + up
  rcon <command...>         send a server-console command, print the reply
Client (real windowed Minecraft + DebugBridge):
  client                    ensure runtime, launch the windowed probe client
  join                      join the probe server (resource pack pre-accepted)
  shot [--out f] [--scale n] [--quality n]
                            capture the client framebuffer to harness/build/probe/shots/
  command '<cmd>'           run a command AS THE PLAYER (/ prefix optional)
  exec '<groovy>'           run Groovy inside the client JVM
  state | screen | chat     snapshot / GUI inspect / chat history
  quit                      quit the client
All-in-one:
  boot                      up + client + join + sanity shot
`);
}

// ---------------------------------------------------------------- pins ----

function readPins() {
  const toml = fs.readFileSync(CATALOG, 'utf8');
  const block = toml.slice(toml.indexOf('[versions]'));
  const pins = {};
  for (const m of block.matchAll(/^([\w-]+) = "(.+)"$/gm)) pins[m[1]] = m[2];
  for (const key of [
    'paper-probe-version', 'paper-probe-build', 'paper-probe-url', 'paper-probe-sha256',
    'probe-debugbridge-url', 'probe-debugbridge-sha1', 'probe-fabric-loader',
  ]) {
    if (!pins[key]) fail(`gradle/libs.versions.toml is missing the "${key}" pin`);
  }
  return pins;
}

// ------------------------------------------------------------- helpers ----

function fail(message) {
  process.stderr.write(`probe: ${message}\n`);
  process.exit(1);
}

function log(msg) { process.stdout.write(`${msg}\n`); }

function intOpt(name, fallback) {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? Number(args[i + 1]) : fallback;
}

function strOpt(name, fallback) {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}

async function downloadVerified(file, url, expected, algo) {
  if (fs.existsSync(file) && hashOf(file, algo) === expected) return file;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const res = await fetch(url);
  if (!res.ok) fail(`GET ${url} -> ${res.status}`);
  const tmp = `${file}.part`;
  fs.writeFileSync(tmp, Buffer.from(await res.arrayBuffer()));
  const actual = hashOf(tmp, algo);
  if (actual !== expected) {
    fail(`${path.basename(file)}: ${algo} mismatch\n  expected ${expected}\n  actual   ${actual}`);
  }
  fs.renameSync(tmp, file);
  return file;
}

function hashOf(file, algo) {
  return crypto.createHash(algo).update(fs.readFileSync(file)).digest('hex');
}

async function fetchText(url) {
  const res = await fetch(url);
  if (!res.ok) fail(`GET ${url} -> ${res.status}`);
  return res.text();
}

function pidAlive(pid) {
  if (!pid) return false;
  try { return process.kill(pid, 0); } catch { return false; }
}

function readPid(name) {
  try { return Number(fs.readFileSync(path.join(PROBE, `${name}.pid`), 'utf8').trim()); } catch { return 0; }
}

function writePid(name, pid) {
  fs.mkdirSync(PROBE, { recursive: true });
  fs.writeFileSync(path.join(PROBE, `${name}.pid`), String(pid));
}

async function waitFor(desc, timeoutMs, probe) {
  const start = Date.now();
  for (;;) {
    let ok = false;
    try { ok = await probe(); } catch { ok = false; }
    if (ok) return;
    if (Date.now() - start > timeoutMs) fail(`timed out after ${Math.round(timeoutMs / 1000)}s waiting for ${desc}`);
    await sleep(500);
  }
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function javaExecutable() {
  const explicit = strOpt('java', process.env.MTR_PROBE_JAVA);
  const candidates = [];
  if (explicit) candidates.push(explicit);
  for (const home of [
    path.join(process.env.USERPROFILE ?? '', '.jdks'),
    path.join(process.env.USERPROFILE ?? '', '.gradle', 'jdks'),
  ]) {
    try { for (const entry of fs.readdirSync(home)) candidates.push(path.join(home, entry)); } catch { /* absent */ }
  }
  candidates.push(process.env.JAVA_HOME ?? 'java', 'java');
  for (const candidate of candidates) {
    const exe = toJavaExe(candidate);
    if (exe && (await javaMajor(exe)) >= 25) return exe;
  }
  fail('no JDK >= 25 found (Paper 26.x and the 26.x client need one). Install JDK 25 or pass --java <path-to-java>/<home>.');
}

function toJavaExe(candidate) {
  const exe = process.platform === 'win32' ? 'java.exe' : 'java';
  const stat = (() => { try { return fs.statSync(candidate); } catch { return null; } })();
  if (stat?.isFile()) return path.basename(candidate) === exe ? candidate : null;
  if (stat?.isDirectory()) {
    const underBin = path.join(candidate, 'bin', exe);
    if (fs.existsSync(underBin)) return underBin;
  }
  return candidate === 'java' ? exe : null;
}

function javaMajor(exe) {
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(exe, ['-version'], { stdio: ['ignore', 'pipe', 'pipe'] });
    } catch {
      resolve(0);
      return;
    }
    let out = '';
    child.stderr.on('data', (c) => { out += c; });
    child.on('error', () => resolve(0));
    child.on('close', () => {
      const m = out.match(/version "(\d+)/);
      resolve(m ? Number(m[1]) : 0);
    });
  });
}

// -------------------------------------------------------------- server ----

function serverProperties() {
  // Mirror harness ServerWorkspace (offline superflat) + probe determinism
  // (peaceful, fixed seed) + RCON so any later process can drive the console.
  return [
    'server-port=25599',
    'online-mode=false',
    'level-type=minecraft\\:flat',
    'generate-structures=false',
    'view-distance=2',
    'simulation-distance=2',
    'spawn-protection=0',
    'max-players=4',
    'motd=MaterioneProbe',
    'level-seed=materione-probe',
    'difficulty=peaceful',
    'enforce-secure-profile=false',
    'enable-rcon=true',
    `rcon.port=${RCON_PORT}`,
    `rcon.password=${RCON_PASSWORD}`,
    'broadcast-rcon-to-ops=false',
    '',
  ].join('\n');
}

async function cmdUp() {
  const pins = readPins();
  if (pidAlive(readPid('server'))) fail('probe server already running — use: probe restart (or down)');

  fs.mkdirSync(SERVER_DIR, { recursive: true });
  const jar = await ensurePluginJar(!args.includes('--no-build'));
  const paperJar = await downloadVerified(
    path.join(DL_DIR, `paper-${pins['paper-probe-version']}-${pins['paper-probe-build']}.jar`),
    pins['paper-probe-url'],
    pins['paper-probe-sha256'],
    'sha256',
  );

  fs.writeFileSync(path.join(SERVER_DIR, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(SERVER_DIR, 'server.properties'), serverProperties());
  // Fresh world + fresh log every `up` (deterministic baseline: no scene
  // leftovers from the last session). --keep-world opts out for debugging.
  if (!args.includes('--keep-world')) {
    for (const stale of ['world', 'world_nether', 'world_the_end', 'logs']) {
      fs.rmSync(path.join(SERVER_DIR, stale), { recursive: true, force: true });
    }
  }
  fs.mkdirSync(path.join(SERVER_DIR, 'logs'), { recursive: true });
  const plugins = path.join(SERVER_DIR, 'plugins');
  fs.mkdirSync(plugins, { recursive: true });
  fs.copyFileSync(jar, path.join(plugins, 'Materione.jar'));
  const packs = path.join(plugins, 'Materione', 'content', 'packs');
  fs.mkdirSync(packs, { recursive: true });
  for (const pack of fs.readdirSync(FIXTURE_PACKS)) {
    fs.cpSync(path.join(FIXTURE_PACKS, pack), path.join(packs, pack), { recursive: true });
  }

  const java = await javaExecutable();
  log(`booting Paper ${pins['paper-probe-version']}-${pins['paper-probe-build']} with ${java} ...`);
  const stdout = fs.openSync(path.join(SERVER_DIR, 'stdout.log'), 'a');
  const child = spawn(java, ['-Xmx2G', '-DIReallyKnowWhatIAmDoingISwear', '-jar', paperJar, '--nogui'], {
    cwd: SERVER_DIR, stdio: ['ignore', stdout, stdout], detached: true,
  });
  child.unref();
  writePid('server', child.pid);

  // The workspace (world + logs) was cleared above unless --keep-world, so
  // any marker in latest.log is from THIS boot.
  await waitFor('MATERIONE-BOOT-OK (logs: harness/build/probe/server/logs/latest.log)', BOOT_TIMEOUT_MS, async () => {
    const modeLine = await lastLine(/MATERIONE-REGISTRY-OK.*mode=(\w+)/);
    if (modeLine?.includes('mode=datapack')) {
      fail('probe server booted in mode=datapack — a 26.x pin must yield mode=registry; check the pin and plugins/Materione.jar');
    }
    if (await lastLine(/MATERIONE-BOOT-FAIL/)) fail('plugin failed to boot — see harness/build/probe/server/logs/latest.log');
    return Boolean(await lastLine(/MATERIONE-BOOT-OK/));
  });
  const mode = (await lastLine(/MATERIONE-REGISTRY-OK.*mode=(\w+)/))?.match(/mode=(\w+)/)?.[1] ?? '?';
  log(`server ready (pid ${child.pid}, registry mode=${mode}).`);

  // RCON binds after the plugin's BOOT-OK marker — wait for the port.
  await waitFor('the RCON listener', 60_000, () => tcpOpen(RCON_PORT));

  // Deterministic scene: frozen noon sun, no mobs, no weather, no random
  // ticks. Rule IDs are the 1.21.11+ snake_case registry names (26.x); the
  // camelCase names are gone. doFireTick was removed outright in 25w44a.
  for (const command of [
    'gamerule spawn_mobs false', 'gamerule spawn_phantoms false', 'gamerule spawn_patrols false',
    'gamerule spawn_wandering_traders false', 'gamerule spawn_wardens false',
    'gamerule advance_weather false', 'gamerule advance_time false',
    'gamerule random_tick_speed 0', 'gamerule mob_griefing false',
    'time set 6000', 'weather clear', 'difficulty peaceful',
  ]) {
    await rconCommand(command);
  }
  log('determinism gamerules applied (frozen noon, no mobs/weather/random ticks). Next: probe client.');
}

async function lastLine(regex, fromOffset = 0) {
  try {
    const text = fs.readFileSync(path.join(SERVER_DIR, 'logs', 'latest.log'), 'utf8');
    return text.slice(fromOffset).split('\n').filter((l) => regex.test(l)).at(-1) ?? null;
  } catch { return null; }
}

async function ensurePluginJar(build) {
  const libs = path.join(ROOT, 'plugin', 'build', 'libs');
  const jars = fs.existsSync(libs)
    ? fs.readdirSync(libs).filter((f) => /^Materione-\d.*\.jar$/.test(f))
    : [];
  if (jars.length && !build) return path.join(libs, jars[0]);
  if (!jars.length && !build) fail('no plugin jar under plugin/build/libs — run again without --no-build');
  log('building :plugin:shadowJar ...');
  await runGradle([':plugin:shadowJar']);
  const built = fs.readdirSync(libs).filter((f) => /^Materione-\d.*\.jar$/.test(f));
  if (!built.length) fail('gradle did not produce plugin/build/libs/Materione-<version>.jar');
  return path.join(libs, built[0]);
}

function runGradle(tasks) {
  return new Promise((resolve, reject) => {
    const wrapper = process.platform === 'win32' ? 'gradlew.bat' : 'gradlew';
    const child = spawn(path.join(ROOT, wrapper), tasks, { cwd: ROOT, stdio: 'inherit', shell: true });
    child.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`gradlew ${tasks.join(' ')} exited ${code}`))));
  });
}

async function cmdDown() {
  const pid = readPid('server');
  if (!pidAlive(pid)) { log('server is not running.'); return; }
  try {
    await rconCommand('stop');
    await waitFor('server process to exit', 30_000, async () => !pidAlive(pid));
  } catch {
    log('RCON stop failed; killing the process.');
    try { process.kill(pid); } catch { /* gone */ }
  }
  log('server stopped.');
}

// ---------------------------------------------------------------- rcon ----

// Classic Source RCON: [len i32le][id i32le][type i32le][payload][00 00].
// Long replies arrive as several packets sharing the id — accumulate until
// a short silence, then resolve.
function rconCommand(command) {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: '127.0.0.1', port: RCON_PORT });
    let buffer = Buffer.alloc(0);
    let id = 0;
    let commandId = 0;
    let reply = '';
    let settleTimer = null;
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      clearTimeout(settleTimer);
      socket.destroy();
      resolve(reply);
    };
    const abort = (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(settleTimer);
      socket.destroy();
      reject(err);
    };
    socket.on('error', abort);
    // `stop` tears the connection down before any silence timer runs.
    socket.on('close', finish);
    socket.setTimeout(15_000, () => abort(new Error('RCON timeout')));
    socket.on('connect', () => send(++id, 3, RCON_PASSWORD));
    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      for (;;) {
        if (buffer.length < 4) return;
        const len = buffer.readInt32LE(0);
        if (buffer.length < 4 + len) return;
        const pkt = buffer.subarray(4, 4 + len);
        buffer = buffer.subarray(4 + len);
        const pktId = pkt.readInt32LE(0);
        const type = pkt.readInt32LE(4);
        const body = pkt.subarray(8, pkt.length - 2).toString('utf8');
        if (type === 2 && pktId === -1) abort(new Error('RCON auth failed'));
        if (type === 2) { // auth ok — send the command
          commandId = ++id;
          send(commandId, 2, command);
        }
        if (type === 0 && pktId === commandId) {
          reply += body;
          clearTimeout(settleTimer);
          settleTimer = setTimeout(finish, 250);
        }
      }
    });
    function send(pid, type, payload) {
      const body = Buffer.from(payload, 'ascii');
      const pkt = Buffer.alloc(12 + body.length + 2);
      pkt.writeInt32LE(4 + 4 + body.length + 2, 0);
      pkt.writeInt32LE(pid, 4);
      pkt.writeInt32LE(type, 8);
      body.copy(pkt, 12);
      socket.write(pkt);
    }
  }).then((full) => {
    const text = full.replace(/\n+$/, '').trim();
    log(`[rcon] ${text || '(ok)'}`);
    return text;
  });
}

async function cmdRcon() {
  const command = args.join(' ');
  if (!command) fail('usage: probe rcon <server-console-command>');
  await rconCommand(command);
}

// -------------------------------------------------------------- bridge ----

async function tcpOpen(port) {
  return new Promise((resolve) => {
    const socket = net.connect({ host: '127.0.0.1', port });
    socket.once('connect', () => { socket.destroy(); resolve(true); });
    socket.once('error', () => resolve(false));
    socket.setTimeout(300, () => { socket.destroy(); resolve(false); });
  });
}

async function bridgePort() {
  for (let port = BRIDGE_PORT; port <= BRIDGE_PORT + 10; port++) {
    if (await tcpOpen(port)) return port;
  }
  return 0;
}

// Opens one bridge connection and hands the caller a request fn. The request
// fn must not outlive the callback — the socket closes when it returns.
async function bridged(use, onResult) {
  const port = await bridgePort();
  if (!port) fail('client/DebugBridge is not running — run: probe client');
  const ws = new WebSocket(`ws://127.0.0.1:${port}`);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve);
    ws.addEventListener('error', () => reject(new Error(`bridge connect failed on 127.0.0.1:${port}`)));
  });
  let nextId = 1;
  const pending = new Map();
  ws.addEventListener('message', (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }
    const cb = pending.get(msg.id);
    if (cb) { pending.delete(msg.id); cb(msg); }
  });
  const call = (type, payload = {}, timeoutMs = 20_000) => new Promise((resolve, reject) => {
    const id = String(nextId++);
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`bridge ${type} timed out after ${timeoutMs}ms`));
    }, timeoutMs);
    pending.set(id, (msg) => { clearTimeout(timer); resolve(msg); });
    ws.send(JSON.stringify({ id, type, payload }));
  });
  try {
    onResult(await use(call));
  } finally {
    ws.close();
  }
}

// Groovy results arrive wrapped as {type, value} bridge cells; unwrap them.
function unwrapBridgeValue(v) {
  if (v === null || v === undefined) return v;
  if (Array.isArray(v)) return v.map(unwrapBridgeValue);
  if (typeof v === 'object') {
    if (Object.hasOwn(v, 'type') && Object.hasOwn(v, 'value')) return unwrapBridgeValue(v.value);
    return Object.fromEntries(Object.entries(v).map(([k, value]) => [k, unwrapBridgeValue(value)]));
  }
  return v;
}

function printResult(msg) {
  if (!msg.success) fail(`bridge call failed: ${msg.error ?? '(no error)'}`);
  process.stdout.write(`${JSON.stringify(unwrapBridgeValue(msg.result), null, 2)}\n`);
}

// -------------------------------------------------------------- client ----

function bridgeConfig() {
  // DebugBridge gates the endpoints the loop needs (join/quit/runCommand) and
  // shows a one-time developer warning screen unless pre-accepted. Loopback
  // only — see the mod's security model.
  return JSON.stringify({
    developer_mode_accepted: true,
    run_command_enabled: true,
    session_control_enabled: true,
  }, null, 2);
}

async function cmdClient() {
  const pins = readPins();
  if (await bridgePort()) fail('a probe client is already running (DebugBridge port open) — use: probe quit');
  const mc = pins['paper-probe-version']; // the client tracks the server pin
  const vanillaDir = strOpt('vanilla-dir', path.join(process.env.APPDATA ?? '', '.minecraft'));
  const versionJsonPath = path.join(vanillaDir, 'versions', mc, `${mc}.json`);
  if (!fs.existsSync(versionJsonPath)) {
    fail(`no vanilla ${mc} install under ${vanillaDir} — install it once with the vanilla launcher, or pass --vanilla-dir`);
  }
  const versionJson = JSON.parse(fs.readFileSync(versionJsonPath, 'utf8'));
  const clientJar = path.join(vanillaDir, 'versions', mc, `${mc}.jar`);
  if (!fs.existsSync(clientJar)) fail(`missing ${clientJar}`);

  // Fabric loader side — the profile json lists exactly the launch classpath
  // (KnotClient + loader + asm + mixin); jars are sha1-verified against the
  // maven sidecars.
  const profileUrl = `https://meta.fabricmc.net/v2/versions/loader/${mc}/${pins['probe-fabric-loader']}/profile/json`;
  const profile = JSON.parse(await fetchText(profileUrl));
  const fabricJars = [];
  for (const lib of profile.libraries) {
    const [group, artifact, version] = lib.name.split(':');
    const repo = lib.url ?? 'https://libraries.minecraft.net/';
    const rel = `${group.replace(/\./g, '/')}/${artifact}/${version}/${artifact}-${version}.jar`;
    const sha1 = (await fetchText(`${repo}${rel}.sha1`)).trim();
    fabricJars.push(await downloadVerified(path.join(DL_DIR, 'fabric', rel), `${repo}${rel}`, sha1, 'sha1'));
  }

  const modsDir = path.join(CLIENT_DIR, 'mods');
  fs.mkdirSync(modsDir, { recursive: true });
  for (const stale of fs.readdirSync(modsDir)) fs.rmSync(path.join(modsDir, stale), { recursive: true, force: true });
  await downloadVerified(
    path.join(modsDir, 'debugbridge.jar'),
    pins['probe-debugbridge-url'],
    pins['probe-debugbridge-sha1'],
    'sha1',
  );
  // DebugBridge's manifest pins `minecraft = 26.1` exactly, so 26.1.2 fails
  // resolution — yet the build officially covers 26.1.x (Modrinth lists
  // 26.1/26.1.1/26.1.2 for this jar). Relax that one constraint through
  // fabric-loader's dependency-override file (REPLACE the `minecraft`
  // depends entry), keeping the DebugBridge jar itself byte-identical.
  fs.mkdirSync(path.join(CLIENT_DIR, 'config'), { recursive: true });
  fs.writeFileSync(path.join(CLIENT_DIR, 'config', 'fabric_loader_dependencies.json'), JSON.stringify({
    version: 1,
    overrides: {
      debugbridge: { depends: { minecraft: '>=26.1' } },
    },
  }, null, 2));
  fs.mkdirSync(path.join(CLIENT_DIR, 'config'), { recursive: true });
  fs.writeFileSync(path.join(CLIENT_DIR, 'config', 'debugbridge.json'), bridgeConfig());
  // pauseOnLostFocus:false — the agent works in a terminal; a paused game
  // renders nothing new. Brightness is the 26.x 0..1 scale; 1 = fullbright.
  fs.writeFileSync(path.join(CLIENT_DIR, 'options.txt'), [
    'pauseOnLostFocus:false', 'gamma:1.0', 'musicMasterVolume:0.0',
  ].join('\n'));

  const classpath = [...vanillaLibrariesForPlatform(versionJson), clientJar, ...fabricJars];
  const natives = nativesDirFor(versionJson);
  const argFile = path.join(CLIENT_DIR, 'classpath.argfile');
  fs.writeFileSync(argFile, `-cp "${classpath.map((p) => p.replace(/\\/g, '/')).join(';')}"\n`);

  const java = await javaExecutable();
  log(`launching the ${mc} probe client (${classpath.length} classpath entries) ...`);
  const clientLog = fs.openSync(path.join(CLIENT_DIR, 'client.log'), 'a');
  const child = spawn(java, [
    '-Xmx2G', `@${argFile.replace(/\\/g, '/')}`,
    `-Dorg.lwjgl.librarypath=${natives.replace(/\\/g, '/')}`,
    `-Djava.library.path=${natives.replace(/\\/g, '/')}`,
    'net.fabricmc.loader.impl.launch.knot.KnotClient',
    '--gameDir', CLIENT_DIR,
    '--assetsDir', path.join(vanillaDir, 'assets'),
    '--assetIndex', versionJson.assetIndex.id,
    '--username', PROBE_PLAYER,
    '--version', mc,
    '--accessToken', '0',
    '--width', String(intOpt('width', 1280)),
    '--height', String(intOpt('height', 720)),
  ], { cwd: CLIENT_DIR, stdio: ['ignore', clientLog, clientLog], detached: true });
  child.unref();
  writePid('client', child.pid);

  await waitFor('DebugBridge to open its port (log: harness/build/probe/client/client.log)', 240_000, bridgePort);
  log(`client launched (pid ${child.pid}, DebugBridge on 127.0.0.1:${await bridgePort()}). Next: probe join.`);
}

function vanillaLibrariesForPlatform(versionJson) {
  const vanillaLibs = path.join(process.env.APPDATA ?? '', '.minecraft', 'libraries');
  const jars = [];
  for (const lib of versionJson.libraries) {
    if (lib.rules && !rulesAllow(lib.rules)) continue;
    if (!lib.downloads?.artifact?.path) continue;
    const local = path.join(vanillaLibs, ...lib.downloads.artifact.path.split('/'));
    if (fs.existsSync(local)) jars.push(local);
  }
  return jars;
}

// fabric-loader does no native handling — launchers extract the
// natives-windows jars and point LWJGL at the directory (vanilla launcher
// behavior). One-time per platform; keyed by a marker file.
function nativesDirFor(versionJson) {
  const archSuffix = process.arch === 'arm64' ? '-arm64' : process.arch === 'x86' ? '-x86' : '';
  const classifier = `natives-windows${archSuffix}`;
  const dir = path.join(CLIENT_DIR, 'natives');
  const marker = path.join(dir, '.extracted');
  if (fs.existsSync(marker) && fs.readFileSync(marker, 'utf8') === classifier) return dir;
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  const vanillaLibs = path.join(process.env.APPDATA ?? '', '.minecraft', 'libraries');
  const zipCopy = path.join(CLIENT_DIR, 'natives.tmp.zip');
  let extracted = 0;
  for (const lib of versionJson.libraries) {
    if (lib.rules && !rulesAllow(lib.rules)) continue;
    const rel = lib.downloads?.artifact?.path ?? '';
    if (!rel.endsWith(`${classifier}.jar`)) continue;
    const jar = path.join(vanillaLibs, ...rel.split('/'));
    if (!fs.existsSync(jar)) continue;
    fs.copyFileSync(jar, zipCopy);
    const script = `Expand-Archive -LiteralPath '${zipCopy.replace(/'/g, "''")}' -DestinationPath '${dir.replace(/'/g, "''")}' -Force`;
    const r = spawnSync('powershell', ['-NoProfile', '-Command', script], { stdio: 'pipe' });
    if (r.status !== 0) fail(`native extraction failed for ${jar}: ${r.stderr?.toString().slice(0, 400)}`);
    extracted++;
  }
  fs.rmSync(zipCopy, { force: true });
  if (!extracted) fail('no natives-windows jars found to extract — the probe client would have no GL');
  fs.writeFileSync(marker, classifier);
  return dir;
}

function rulesAllow(rules) {
  // Mojang rule os names are "windows"/"osx"/"linux", not process.platform.
  const mojangOs = { win32: 'windows', darwin: 'osx' }[process.platform] ?? 'linux';
  let allowed = false;
  for (const rule of rules) {
    if (!rule.os || rule.os.name === mojangOs) allowed = rule.action === 'allow';
  }
  return allowed;
}

async function cmdJoin() {
  const host = strOpt('host', '127.0.0.1');
  const port = intOpt('port', SERVER_PORT);
  let settled = false;
  await bridged(async (call) => {
    const msg = await call('joinServer', { address: `${host}:${port}`, acceptResourcePacks: true }, 30_000);
    if (!msg.success) fail(`joinServer failed: ${msg.error ?? '(no error)'}`);
    log(`joining ${host}:${port} (resource pack pre-accepted); waiting for the player to settle ...`);
    await waitFor('the player to appear in-world', 120_000, async () => {
      await sleep(2_000);
      const snap = await call('snapshot', {});
      const player = snap?.result?.player;
      settled = Boolean(snap.success && player && Number.isFinite(player.x));
      return settled;
    });
    await sleep(3_000); // chunks + resource-pack reload
  }, () => {});
  log(`joined as ${PROBE_PLAYER}. Next: probe shot (or probe command '...').`);
}

async function cmdShot() {
  fs.mkdirSync(SHOTS_DIR, { recursive: true });
  const payload = {};
  if (intOpt('scale', 0)) payload.scale = intOpt('scale', 0);
  if (intOpt('quality', 0)) payload.quality = intOpt('quality', 0);
  await bridged(async (call) => {
    const msg = await call('screenshot', payload, 30_000);
    if (!msg.success) fail(`screenshot failed: ${msg.error ?? '(no error)'}`);
    const result = unwrapBridgeValue(msg.result) ?? {};
    const outOpt = strOpt('out');
    const target = outOpt ? path.resolve(outOpt) : path.join(SHOTS_DIR, `shot-${Date.now()}.jpg`);
    log(writeCapture(result, target));
  }, () => {});
}

function writeCapture(result, target) {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  if (typeof result.base64 === 'string') {
    fs.writeFileSync(target, Buffer.from(result.base64, 'base64'));
    return `${target} (${result.width}x${result.height ?? '?'})`;
  }
  if (typeof result.path === 'string' && fs.existsSync(result.path)) {
    fs.copyFileSync(result.path, target);
    return `${target} (copied from ${result.path}, ${result.width}x${result.height ?? '?'})`;
  }
  fail(`screenshot returned neither base64 nor a readable path — keys: ${Object.keys(result).join(', ')}`);
}

async function cmdExec() {
  const code = args.filter((a) => !a.startsWith('--')).join(' ');
  if (!code) fail(`usage: probe exec '<groovy code>'`);
  const timeout = intOpt('timeout', 10_000);
  await bridged((call) => call('execute', { code, timeoutMs: timeout }, timeout + 15_000), printResult);
}

async function cmdRunCommand() {
  let command = args.filter((a) => !a.startsWith('--')).join(' ');
  if (!command) fail(`usage: probe command '/give Probe minecraft:diamond 1'`);
  if (!command.startsWith('/')) command = `/${command}`;
  await bridged((call) => call('runCommand', { command }, 30_000), printResult);
}

async function cmdQuit() {
  if (!(await bridgePort())) {
    const pid = readPid('client');
    if (pidAlive(pid)) { try { process.kill(pid); } catch { /* gone */ } log('bridge unreachable; killed the client process.'); }
    else log('client is not running.');
    return;
  }
  await bridged((call) => call('quit', {}), (msg) => {
    if (!msg.success) fail(`quit failed: ${msg.error ?? '(no error)'}`);
    log('client quitting.');
  });
}

// ------------------------------------------------------------ all-in-one ----

async function cmdBoot() {
  await cmdUp();
  await cmdClient();
  await cmdJoin();
  await cmdShot();
  log('probe loop is live. Drive the scene with `probe rcon ...` / `probe command ...`, capture with `probe shot`.');
}
