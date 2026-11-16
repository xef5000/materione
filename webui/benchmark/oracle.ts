import { execFileSync, spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import readline from 'node:readline';
import { parse as parseYaml, stringify as stringifyYaml } from 'yaml';
import type { ReadReply } from '../src/lib/codemode/core';
import { parseReport, type TransactionTransport } from '../src/lib/codemode/host/transport';
import { createOpHost, type SandboxHost } from '../src/lib/codemode/sandbox';
import { CodemodeFixture, type FixtureDoc } from '../browser/codemode-fixture';

export interface BenchmarkWorldSpec {
  readonly packs?: readonly string[];
  readonly files?: Readonly<Record<string, string>>;
  readonly documents?: Readonly<Record<string, unknown>>;
  readonly assets?: Readonly<Record<string, readonly string[]>>;
}

export interface OracleRpcReply {
  readonly ok: boolean;
  readonly data?: unknown;
  readonly error?: { readonly code?: string; readonly message?: string };
}

export interface BenchmarkOracle {
  readonly kind: 'jvm' | 'memory';
  resetWorld(world?: BenchmarkWorldSpec): Promise<{ packs: string[] }>;
  describe(): Promise<{ capabilities: string[]; describe: Record<string, unknown> }>;
  rpc(op: string, payload: Record<string, unknown>): Promise<OracleRpcReply>;
  close(): Promise<void>;
}

export function createOracleSandboxHost(oracle: BenchmarkOracle): SandboxHost {
  return createOpHost(async (op, payload) => (await oracle.rpc(op, payload)) as ReadReply);
}

export function createOracleTransactionTransport(oracle: BenchmarkOracle): TransactionTransport {
  return {
    async transaction(call) {
      const payload: Record<string, unknown> = {
        pack: call.pack,
        operations: call.operations,
        dryRun: call.dryRun,
      };
      if (call.origin !== undefined) payload.origin = call.origin;
      if (call.approval !== undefined) payload.approval = call.approval;
      if (call.program !== undefined) payload.program = call.program;
      const reply = await oracle.rpc('authoring.transaction', payload);
      if (!reply.ok) {
        throw new Error(reply.error?.message ?? 'authoring.transaction RPC failed');
      }
      return parseReport(reply.data);
    },
    async revert(call) {
      const reply = await oracle.rpc('authoring.revertTransaction', {
        transactionId: call.transactionId,
        dryRun: call.dryRun,
      });
      if (!reply.ok) {
        throw new Error(reply.error?.message ?? 'authoring.revertTransaction RPC failed');
      }
      return parseReport(reply.data);
    },
  };
}

interface PendingRequest {
  resolve: (value: OracleRpcReply) => void;
  reject: (reason: Error) => void;
}

export class JvmBenchmarkOracle implements BenchmarkOracle {
  readonly kind = 'jvm' as const;
  private readonly repoRoot: string;
  private child: ChildProcessWithoutNullStreams | null = null;
  private nextId = 1;
  private readonly pending = new Map<number, PendingRequest>();
  private readonly stderrTail: string[] = [];
  private closed = false;
  private resolvedClasspath: string | null = null;
  private lastWorld: BenchmarkWorldSpec | null = null;

  constructor(repoRoot: string) {
    this.repoRoot = repoRoot;
  }

  private resolveClasspath(): string {
    if (this.resolvedClasspath !== null) return this.resolvedClasspath;
    const isWin = process.platform === 'win32';
    const cmd = isWin ? 'cmd.exe' : './gradlew';
    const gradleArgs = [
      ':plugin:writeBenchmarkOracleClasspath',
      '-x',
      ':webui:npmInstall',
      '-x',
      ':webui:npm_run_build',
      '-q',
      '--console=plain',
    ];
    const args = isWin ? ['/c', 'gradlew.bat', ...gradleArgs] : gradleArgs;
    execFileSync(cmd, args, {
      cwd: this.repoRoot,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });
    const cpPath = join(this.repoRoot, 'plugin', 'build', 'benchmark-oracle.classpath');
    this.resolvedClasspath = readFileSync(cpPath, 'utf8').trim();
    return this.resolvedClasspath;
  }

  private resolveJavaExe(): string {
    const javaHome = process.env.JAVA_HOME?.trim();
    if (javaHome) {
      const candidate = join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
      if (existsSync(candidate)) return candidate;
    }
    return 'java';
  }

  private killChild(): void {
    const child = this.child;
    this.child = null;
    const err = new Error('JvmBenchmarkOracle restarted');
    for (const [, req] of this.pending) {
      req.reject(err);
    }
    this.pending.clear();
    if (child !== null) {
      try {
        child.stdin.destroy();
      } catch {
        /* ignore */
      }
      try {
        child.stdout.destroy();
      } catch {
        /* ignore */
      }
      try {
        child.stderr.destroy();
      } catch {
        /* ignore */
      }
      try {
        child.kill();
      } catch {
        /* ignore */
      }
    }
  }

  private async ensureStarted(): Promise<void> {
    if (this.child !== null) return;
    if (this.closed) throw new Error('JvmBenchmarkOracle is closed');

    const classpath = this.resolveClasspath();
    const javaExe = this.resolveJavaExe();
    const args = [
      '-Xmx1536m',
      `-Dmaterione.fixtures.root=${join(this.repoRoot, 'fixtures')}`,
      'dev.materione.plugin.runtime.CodemodeBenchmarkOracleMain',
    ];

    const child = spawn(javaExe, args, {
      cwd: this.repoRoot,
      env: { ...process.env, CLASSPATH: classpath },
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    this.child = child;

    const rl = readline.createInterface({ input: child.stdout, crlfDelay: Infinity });
    rl.on('line', (line) => {
      const trimmed = line.trim();
      if (!trimmed.startsWith('{')) return;
      try {
        const msg = JSON.parse(trimmed) as { id?: number; ok: boolean; data?: unknown; error?: { code?: string; message?: string } };
        if (typeof msg.id === 'number') {
          const entry = this.pending.get(msg.id);
          if (entry) {
            this.pending.delete(msg.id);
            entry.resolve({ ok: msg.ok, data: msg.data, error: msg.error });
          }
        }
      } catch {
        /* ignore non-JSON lines */
      }
    });

    const errRl = readline.createInterface({ input: child.stderr, crlfDelay: Infinity });
    errRl.on('line', (line) => {
      this.stderrTail.push(line);
      if (this.stderrTail.length > 50) this.stderrTail.shift();
    });

    child.on('exit', (code) => {
      if (this.child === child) {
        this.child = null;
      }
      const err = new Error(`JvmBenchmarkOracle exited (code ${code ?? 'null'}): ${this.stderrTail.slice(-10).join('\n')}`);
      for (const [, req] of this.pending) {
        req.reject(err);
      }
      this.pending.clear();
    });

    const ping = await this.send({ cmd: 'ping' }, 180_000);
    if (!ping.ok) {
      throw new Error(`JvmBenchmarkOracle ping failed: ${ping.error?.message ?? 'unknown'}`);
    }
  }

  private send(payload: Record<string, unknown>, timeoutMs = 180_000): Promise<OracleRpcReply> {
    if (this.child === null) {
      return Promise.reject(new Error('JvmBenchmarkOracle process is not running'));
    }
    const id = this.nextId++;
    const line = JSON.stringify({ id, ...payload }) + '\n';
    return new Promise<OracleRpcReply>((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pending.delete(id)) {
          reject(new Error(`JvmBenchmarkOracle request ${id} (${String(payload.cmd)}) timed out after ${timeoutMs}ms`));
        }
      }, timeoutMs);
      this.pending.set(id, {
        resolve: (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        reject: (err) => {
          clearTimeout(timer);
          reject(err);
        },
      });
      this.child!.stdin.write(line, 'utf8');
    });
  }

  private async sendResetWorld(world: BenchmarkWorldSpec): Promise<OracleRpcReply> {
    await this.ensureStarted();
    return this.send(
      {
        cmd: 'resetWorld',
        packs: world.packs ?? [],
        files: world.files ?? {},
        assets: world.assets ?? {},
      },
      180_000,
    );
  }

  async resetWorld(world: BenchmarkWorldSpec = {}): Promise<{ packs: string[] }> {
    this.lastWorld = world;
    let reply: OracleRpcReply;
    try {
      reply = await this.sendResetWorld(world);
    } catch (err) {
      if (this.closed) throw err;
      process.stderr.write(`[oracle] JVM oracle resetWorld failed/timed out; restarting JVM...\n`);
      this.killChild();
      reply = await this.sendResetWorld(world);
    }
    if (!reply.ok) {
      throw new Error(`resetWorld failed: ${reply.error?.message ?? 'unknown error'}`);
    }
    const data = (reply.data ?? {}) as { packs?: string[] };
    return { packs: Array.isArray(data.packs) ? data.packs : [] };
  }

  async describe(): Promise<{ capabilities: string[]; describe: Record<string, unknown> }> {
    const reply = await this.rpc('authoring.describe', {});
    if (!reply.ok) {
      throw new Error(`authoring.describe failed: ${reply.error?.message ?? 'unknown error'}`);
    }
    const sess = await this.rpc('session.info', {});
    const caps =
      sess.ok && typeof sess.data === 'object' && sess.data !== null && Array.isArray((sess.data as { capabilities?: unknown[] }).capabilities)
        ? ((sess.data as { capabilities: unknown[] }).capabilities.filter((c): c is string => typeof c === 'string'))
        : ['codemode', 'transaction', 'logic'];
    return { capabilities: caps, describe: (reply.data ?? {}) as Record<string, unknown> };
  }

  async rpc(op: string, payload: Record<string, unknown>): Promise<OracleRpcReply> {
    try {
      await this.ensureStarted();
      return await this.send({ cmd: 'rpc', op, payload });
    } catch (err) {
      if (this.closed) throw err;
      process.stderr.write(`[oracle] JVM oracle RPC (${op}) failed/timed out; restarting JVM and replaying world...\n`);
      this.killChild();
      await this.ensureStarted();
      if (this.lastWorld !== null) {
        await this.resetWorld(this.lastWorld);
      }
      return await this.send({ cmd: 'rpc', op, payload });
    }
  }

  async close(): Promise<void> {
    this.closed = true;
    const child = this.child;
    if (child === null) return;
    try {
      await this.send({ cmd: 'close' }, 10_000);
    } catch {
      /* ignore */
    } finally {
      try {
        child.stdin.end();
      } catch {
        /* ignore */
      }
      child.kill();
      this.child = null;
    }
  }
}

export class MemoryBenchmarkOracle implements BenchmarkOracle {
  readonly kind = 'memory' as const;
  private readonly registry: { capabilities: string[]; describe: Record<string, unknown> };
  private packs = new Set<string>(['sample', 'world']);
  private readonly docs = new Map<string, Map<string, FixtureDoc>>();
  private readonly assets = new Map<string, string[]>();
  private readonly fixtures = new Map<string, CodemodeFixture>();

  constructor(registryPath: string) {
    this.registry = JSON.parse(readFileSync(registryPath, 'utf8')) as {
      capabilities: string[];
      describe: Record<string, unknown>;
    };
    this.resetSync({});
  }

  private resetSync(world: BenchmarkWorldSpec): void {
    this.packs = new Set(['sample', 'world', ...(world.packs ?? [])]);
    this.docs.clear();
    this.assets.clear();
    this.fixtures.clear();

    for (const pack of this.packs) {
      this.ensurePack(pack);
    }
    for (const [rel, raw] of Object.entries(world.files ?? {})) {
      const slash = rel.indexOf('/');
      if (slash <= 0) continue;
      const pack = rel.slice(0, slash);
      const docPath = rel.slice(slash + 1);
      if (docPath === 'pack.yml') {
        this.ensurePack(pack);
        continue;
      }
      this.ensurePack(pack);
      const parsed = (parseYaml(raw) ?? {}) as Record<string, Record<string, unknown>>;
      this.docs.get(pack)!.set(docPath, { pack, path: docPath, rev: 0, raw, parsed });
    }
    for (const [pack, list] of Object.entries(world.assets ?? {})) {
      this.ensurePack(pack);
      this.assets.set(pack, [...list]);
    }
  }

  private ensurePack(pack: string): void {
    this.packs.add(pack);
    if (!this.docs.has(pack)) this.docs.set(pack, new Map());
    if (!this.assets.has(pack)) this.assets.set(pack, []);
    if (!this.fixtures.has(pack)) {
      const docsMap = this.docs.get(pack)!;
      this.fixtures.set(
        pack,
        new CodemodeFixture({
          pack,
          get: (path) => docsMap.get(path),
          put: (path, doc) => {
            if (doc === null) docsMap.delete(path);
            else docsMap.set(path, { ...doc, pack, path });
          },
          serialize: (parsed) => stringifyYaml(parsed),
          audit: () => {},
        }),
      );
    }
  }

  async resetWorld(world: BenchmarkWorldSpec = {}): Promise<{ packs: string[] }> {
    this.resetSync(world);
    return { packs: [...this.packs] };
  }

  async describe(): Promise<{ capabilities: string[]; describe: Record<string, unknown> }> {
    return this.registry;
  }

  async rpc(op: string, payload: Record<string, unknown>): Promise<OracleRpcReply> {
    switch (op) {
      case 'session.info':
        return { ok: true, data: { capabilities: this.registry.capabilities } };
      case 'authoring.describe':
        return { ok: true, data: this.registry.describe };
      case 'browse.packs':
        return { ok: true, data: { packs: [...this.packs] } };
      case 'authoring.summaries':
      case 'browse.resources': {
        const pack = String(payload.pack ?? '');
        const docsMap = this.docs.get(pack);
        if (!docsMap) return { ok: false, error: { code: 'MTR-0009', message: `no such pack '${pack}'` } };
        const offset = typeof payload.offset === 'number' ? payload.offset : 0;
        const limit = typeof payload.limit === 'number' ? payload.limit : 100;
        if (op === 'authoring.summaries' && (!Number.isInteger(offset) || offset < 0 || !Number.isInteger(limit) || limit < 1 || limit > 200)) {
          return {
            ok: false,
            error: { code: 'MTR-0007', message: 'summary page requires offset >= 0 and limit between 1 and 200' },
          };
        }
        const typeFilter = typeof payload.type === 'string' ? payload.type.toLowerCase() : undefined;
        const query = typeof payload.query === 'string' ? payload.query.toLowerCase() : undefined;
        const rows = [...docsMap.entries()]
          .map(([path, doc]) => {
            const slash = path.indexOf('/');
            const type = slash > 0 ? path.slice(0, slash) : 'items';
            const id = Object.keys(doc.parsed)[0] ?? path.replace(/^.*\//, '').replace(/\.yml$/, '');
            return { pack, type, path, id, name: id, health: 'ok' };
          })
          .filter((r) => typeFilter === undefined || r.type.toLowerCase() === typeFilter)
          .filter((r) => query === undefined || r.id.toLowerCase().includes(query));
        if (op === 'authoring.summaries') {
          const end = Math.min(offset + limit, rows.length);
          return {
            ok: true,
            data: {
              resources: rows.slice(Math.min(offset, rows.length), end),
              total: rows.length,
              ...(end < rows.length ? { nextOffset: end } : {}),
            },
          };
        }
        return { ok: true, data: { resources: rows, total: rows.length } };
      }
      case 'browse.get': {
        const pack = String(payload.pack ?? '');
        const path = String(payload.path ?? '');
        const doc = this.docs.get(pack)?.get(path);
        if (!doc) return { ok: false, error: { code: 'MTR-0009', message: `no document ${path}` } };
        return { ok: true, data: { raw: doc.raw, sha256: 'memory', rev: doc.rev, parsed: doc.parsed } };
      }
      case 'assets.list': {
        const pack = String(payload.pack ?? '');
        const list = this.assets.get(pack) ?? [];
        return { ok: true, data: { assets: list.map((path) => ({ path, bytes: 1, kind: 'texture' })) } };
      }
      case 'authoring.references':
        return { ok: true, data: { hits: [] } };
      case 'browse.history':
        return { ok: true, data: { revisions: [] } };
      case 'admin.diagnostics.recent':
        return { ok: true, data: { diagnostics: [] } };
      case 'authoring.transaction': {
        const pack = String(payload.pack ?? '');
        const fixture = this.fixtures.get(pack);
        if (!fixture) {
          return {
            ok: true,
            data: {
              accepted: false,
              applied: false,
              dryRun: payload.dryRun === true,
              transactionId: null,
              documents: [],
              diagnostics: [{ code: 'MTR-0009', severity: 'error', message: `no such pack '${pack}'`, operation: -1 }],
              revertible: false,
              revertNote: null,
              revertOf: null,
            },
          };
        }
        return { ok: true, data: fixture.transaction(payload) };
      }
      case 'authoring.revertTransaction': {
        const pack = String(payload.pack ?? [...this.packs][0] ?? 'sample');
        const fixture = this.fixtures.get(pack);
        if (!fixture) return { ok: false, error: { code: 'MTR-0104', message: 'no fixture' } };
        return { ok: true, data: fixture.revert(payload) };
      }
      default:
        return { ok: false, error: { code: 'MTR-0008', message: `MemoryBenchmarkOracle does not support ${op}` } };
    }
  }

  async close(): Promise<void> {}
}

export function defaultRegistrySnapshotPath(repoRoot: string): string {
  return join(repoRoot, 'fixtures', 'codemode', 'registry', 'plugin-current.json');
}
