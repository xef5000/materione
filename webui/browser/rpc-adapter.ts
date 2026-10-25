import type { AssetRef, ResourceRef, RpcChannel, TypeInfo } from '../src/lib/api';
import type { PushFrame } from '../src/lib/events';
import type { Response } from '../src/lib/protocol/wire';
import type { BrowserTestControls, RecordedWrite } from './contract';

interface FixtureDocument {
  pack: string;
  path: string;
  rev: number;
  raw: string;
  parsed: Record<string, Record<string, unknown>>;
}

interface FixtureAsset {
  path: string;
  kind: string;
  data: string;
  bytes: number;
}

interface FieldEdit {
  field: string[];
  value: unknown;
}

const PACK = 'fixture_pack';
const PIXEL_PNG =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1WYAAAAASUVORK5CYII=';

const ITEM_TYPE: TypeInfo = {
  name: 'item',
  directory: 'items',
  displayName: 'Items',
  description: 'Deterministic browser regression resources.',
  parameters: [
    {
      id: 'display_name',
      type: 'string',
      required: true,
      label: 'Display name',
      section: 'identity',
      uiHint: 'text',
    },
    {
      id: 'material',
      type: 'string',
      required: true,
      label: 'Material',
      section: 'identity',
      uiHint: 'select',
      allowedValues: ['DIAMOND_SWORD', 'BLAZE_ROD', 'STICK'],
    },
    {
      id: 'texture',
      type: 'string',
      required: false,
      label: 'Texture',
      section: 'appearance',
      uiHint: 'texture',
    },
    {
      id: 'model',
      type: 'string',
      required: false,
      label: 'Model',
      section: 'appearance',
      uiHint: 'model',
    },
    {
      id: 'lore',
      type: 'list',
      required: false,
      label: 'Lore',
      section: 'gameplay',
      uiHint: 'list',
    },
    {
      id: 'power',
      type: 'decimal',
      required: false,
      label: 'Power',
      section: 'gameplay',
      example: '1.5',
    },
    {
      id: 'custom_model_data',
      type: 'integer',
      required: false,
      label: 'Custom model data',
      section: 'advanced',
      advanced: true,
    },
  ],
};

const RESOURCES: ResourceRef[] = [
  { pack: PACK, type: 'items', path: 'items/ruby_blade.yml', id: 'ruby_blade' },
  { pack: PACK, type: 'items', path: 'items/shared_gems.yml', id: 'ruby_a' },
  { pack: PACK, type: 'items', path: 'items/shared_gems.yml', id: 'ruby_b' },
];

const DIAGNOSTICS = [
  {
    code: 'MTR-9001',
    severity: 'ERROR',
    message: 'Fixture diagnostic with a stable source.',
    resource: 'fixture_pack:ruby_blade',
    location: 'C:\\server\\plugins\\Materione\\content\\packs\\fixture_pack\\items\\ruby_blade.yml:3:3',
    hint: 'This entry is only returned by the browser fixture.',
  },
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function stringValue(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function numberValue(value: unknown, fallback = Number.NaN): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function stringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((entry: unknown) => typeof entry === 'string');
}

function parseEdits(value: unknown): FieldEdit[] | null {
  if (!Array.isArray(value)) return null;
  const edits: FieldEdit[] = [];
  for (const entry of value) {
    if (!isRecord(entry) || !stringArray(entry.field) || entry.field.length < 2) return null;
    edits.push({ field: entry.field, value: entry.value });
  }
  return edits;
}

function parseScalar(value: string): unknown {
  const trimmed = value.trim();
  if (trimmed === 'true') return true;
  if (trimmed === 'false') return false;
  if (trimmed === 'null' || trimmed === '~') return null;
  if (/^-?\d+(?:\.\d+)?$/.test(trimmed)) return Number(trimmed);
  if (trimmed.startsWith('"') || trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      const parsed: unknown = JSON.parse(trimmed);
      return parsed;
    } catch {
      return trimmed;
    }
  }
  if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
    return trimmed.slice(1, -1).replaceAll("''", "'");
  }
  return trimmed;
}

function parseFixtureYaml(raw: string): Record<string, Record<string, unknown>> {
  const parsed: Record<string, Record<string, unknown>> = {};
  let root: string | null = null;
  let listField: string | null = null;

  for (const line of raw.split(/\r?\n/)) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    const rootMatch = /^([A-Za-z0-9_-]+):\s*$/.exec(line);
    if (rootMatch) {
      root = rootMatch[1] ?? null;
      if (root) parsed[root] = {};
      listField = null;
      continue;
    }
    const fieldMatch = /^  ([A-Za-z0-9_-]+):(?:\s*(.*))?$/.exec(line);
    if (root && fieldMatch) {
      const key = fieldMatch[1];
      const scalar = fieldMatch[2] ?? '';
      if (!key) continue;
      if (!scalar) {
        parsed[root]![key] = [];
        listField = key;
      } else {
        parsed[root]![key] = parseScalar(scalar);
        listField = null;
      }
      continue;
    }
    const itemMatch = /^    -\s*(.*)$/.exec(line);
    if (root && listField && itemMatch) {
      const list = parsed[root]![listField];
      if (Array.isArray(list)) list.push(parseScalar(itemMatch[1] ?? ''));
    }
  }
  return parsed;
}

function yamlScalar(value: unknown): string {
  if (typeof value === 'string') {
    if (/^[A-Za-z0-9_./:@+-]+$/.test(value) && !/^(true|false|null)$/i.test(value)) {
      return value;
    }
    return JSON.stringify(value);
  }
  if (value === null) return 'null';
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

function serializeFixtureYaml(parsed: Record<string, Record<string, unknown>>): string {
  const lines: string[] = [];
  for (const [root, fields] of Object.entries(parsed)) {
    lines.push(`${root}:`);
    for (const [key, value] of Object.entries(fields)) {
      if (Array.isArray(value)) {
        lines.push(`  ${key}:`);
        for (const entry of value) lines.push(`    - ${yamlScalar(entry)}`);
      } else {
        lines.push(`  ${key}: ${yamlScalar(value)}`);
      }
    }
  }
  return `${lines.join('\n')}\n`;
}

function assignPath(target: Record<string, unknown>, path: string[], value: unknown): boolean {
  if (path.length === 0) return false;
  let current = target;
  for (const segment of path.slice(0, -1)) {
    const child = current[segment];
    if (!isRecord(child)) return false;
    current = child;
  }
  const last = path[path.length - 1];
  if (!last) return false;
  current[last] = structuredClone(value);
  return true;
}

function makeDocument(
  path: string,
  rev: number,
  parsed: Record<string, Record<string, unknown>>,
): FixtureDocument {
  return { pack: PACK, path, rev, parsed, raw: serializeFixtureYaml(parsed) };
}

function initialDocuments(): Map<string, FixtureDocument> {
  const blade = makeDocument('items/ruby_blade.yml', 7, {
    ruby_blade: {
      display_name: 'Ruby Blade',
      material: 'DIAMOND_SWORD',
      model: '',
      lore: ['A deterministic browser fixture'],
      power: 1,
      custom_model_data: 41,
      future_flag: 'preserved',
    },
  });
  blade.raw = blade.raw.replaceAll('\n', '\r\n');
  const shared = makeDocument('items/shared_gems.yml', 3, {
    ruby_a: {
      display_name: 'Ruby A',
      material: 'BLAZE_ROD',
      lore: [],
      future_flag: 'left-root',
    },
    ruby_b: {
      display_name: 'Ruby B',
      material: 'STICK',
      lore: [],
      future_flag: 'right-root',
    },
  });
  const documents = new Map<string, FixtureDocument>();
  documents.set(`${PACK}/${blade.path}`, blade);
  documents.set(`${PACK}/${shared.path}`, shared);
  return documents;
}

function assetPath(pack: string, path: string): string {
  return `${pack}/${path}`;
}

function isWriteOperation(op: string): boolean {
  return (
    op.startsWith('edit.') ||
    op.startsWith('authoring.') ||
    op === 'assets.put' ||
    op === 'assets.delete' ||
    op === 'assets.move' ||
    op === 'delivery.publish' ||
    op === 'admin.settings.put'
  );
}

export class DeterministicRpcAdapter implements RpcChannel, BrowserTestControls {
  readonly #documents = initialDocuments();
  readonly #assets = new Map<string, FixtureAsset>([
    [
      assetPath(PACK, 'assets/textures/items/ruby_blade.png'),
      {
        path: 'assets/textures/items/ruby_blade.png',
        kind: 'png',
        data: PIXEL_PNG,
        bytes: 68,
      },
    ],
  ]);
  readonly #writes: RecordedWrite[] = [];
  #pushHandler: ((frame: PushFrame) => void) | null = null;
  #disconnectHandler: (() => void) | null = null;
  #nextResponseId = 0;
  #nextWriteDelay = 0;
  #conflictNext = false;
  #disconnectAfterNextWrite = false;
  #disconnected = false;
  #candidateSha256 = 'a'.repeat(64);
  #publishedSha256 = '0'.repeat(64);

  setPushHandler(handler: (frame: PushFrame) => void): void {
    this.#pushHandler = handler;
  }

  setDisconnectHandler(handler: () => void): void {
    this.#disconnectHandler = handler;
  }

  async call(op: string, payload: Record<string, unknown> = {}): Promise<Response> {
    if (this.#disconnected) throw new Error('fixture channel is disconnected');
    const write: RecordedWrite | null = isWriteOperation(op)
      ? {
          op,
          payload: structuredClone(payload),
          outcome: 'pending',
        }
      : null;
    if (write) this.#writes.push(write);

    if (write && this.#nextWriteDelay > 0) {
      const delay = this.#nextWriteDelay;
      this.#nextWriteDelay = 0;
      await new Promise((resolve) => setTimeout(resolve, delay));
    }

    if (write && this.#conflictNext) {
      this.#conflictNext = false;
      write.outcome = 'conflict';
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }

    const response = this.dispatch(op, payload);
    if (write) {
      write.outcome = response.ok ? 'saved' : response.error.code === 'MTR-0034' ? 'conflict' : 'rejected';
      if (response.ok && this.#disconnectAfterNextWrite) {
        this.#disconnectAfterNextWrite = false;
        this.#disconnected = true;
        write.outcome = 'response-lost';
        this.#disconnectHandler?.();
        throw new Error('connection closed after the write was submitted');
      }
    }
    return response;
  }

  writes(): readonly RecordedWrite[] {
    return structuredClone(this.#writes);
  }

  clearWrites(): void {
    this.#writes.length = 0;
  }

  delayNextWrite(milliseconds: number): void {
    this.#nextWriteDelay = Math.max(0, Math.min(milliseconds, 10_000));
  }

  conflictNextWrite(): void {
    this.#conflictNext = true;
  }

  externalEdit(pack: string, path: string): void {
    const document = this.#documents.get(`${pack}/${path}`);
    if (!document) throw new Error('fixture document does not exist');
    const root = Object.keys(document.parsed)[0];
    if (!root) throw new Error('fixture document has no resource root');
    const body = document.parsed[root];
    if (!body) throw new Error('fixture root is missing');
    body.display_name = `${stringValue(body.display_name, root)} (external edit)`;
    document.rev += 1;
    document.raw = serializeFixtureYaml(document.parsed);
    this.emitPush('push.document.changed', { pack, path, deleted: false });
  }

  disconnectAfterNextWrite(): void {
    this.#disconnectAfterNextWrite = true;
  }

  simulateDisconnect(): void {
    this.#disconnected = true;
    this.#disconnectHandler?.();
  }

  setCandidateSha256(value: string): void {
    this.#candidateSha256 = value;
  }

  deliveryState(): { candidateSha256: string; publishedSha256: string } {
    return {
      candidateSha256: this.#candidateSha256,
      publishedSha256: this.#publishedSha256,
    };
  }

  emitPush(op: string, payload: Record<string, unknown>): void {
    this.#pushHandler?.({ op, payload: structuredClone(payload) });
  }

  emitReload(payload: Record<string, unknown>): void {
    this.emitPush('push.reload', payload);
  }

  private success(op: string, data: unknown): Response {
    return {
      op,
      id: `fixture-${++this.#nextResponseId}`,
      ok: true,
      data,
    };
  }

  private failure(op: string, code: string, message: string): Response {
    return {
      op,
      id: `fixture-${++this.#nextResponseId}`,
      ok: false,
      error: { code, message },
    };
  }

  private dispatch(op: string, payload: Record<string, unknown>): Response {
    switch (op) {
      case 'session.info':
        return this.success(op, {
          serverVersion: '1.21.4',
          protocolVersion: 1,
          editorRunning: true,
          editorPort: 8089,
        });
      case 'browse.packs':
        return this.success(op, { packs: [PACK] });
      case 'browse.types':
        return this.success(op, { types: [structuredClone(ITEM_TYPE)] });
      case 'browse.resources': {
        const pack = stringValue(payload.pack);
        return this.success(op, {
          resources: RESOURCES.filter((resource) => resource.pack === pack).map((resource) =>
            structuredClone(resource),
          ),
        });
      }
      case 'browse.folders':
        return this.success(op, { folders: [] });
      case 'browse.get':
        return this.getDocument(op, payload);
      case 'edit.put':
        return this.putDocument(op, payload);
      case 'authoring.setFields':
        return this.setFields(op, payload);
      case 'authoring.saveModel':
        return this.saveModel(op, payload);
      case 'assets.list':
        return this.listAssets(op, payload);
      case 'assets.get':
        return this.getAsset(op, payload);
      case 'assets.put':
        return this.putAsset(op, payload);
      case 'assets.delete':
        return this.deleteAsset(op, payload);
      case 'assets.move':
        return this.moveAsset(op, payload);
      case 'admin.diagnostics.recent':
        return this.success(op, { diagnostics: structuredClone(DIAGNOSTICS) });
      case 'delivery.status':
        return this.success(op, {
          candidateSha256: this.#candidateSha256,
          publishedSha256: this.#publishedSha256,
        });
      case 'delivery.publish':
        return this.publish(op, payload);
      case 'pipeline.reload':
        return this.success(op, { accepted: true, itemsLive: 1, quarantined: 0 });
      case 'validate.dryRun':
        return this.success(op, { valid: true, diagnostics: [] });
      case 'admin.audit.recent':
        return this.success(op, { entries: [] });
      case 'admin.settings.get':
        return this.success(op, { raw: 'editor:\n  enabled: true\n', sha256: 'fixture-settings' });
      case 'admin.settings.put':
        return this.success(op, { sha256: 'fixture-settings-updated' });
      default:
        return this.failure(op, 'MTR-0001', `unsupported fixture operation: ${op}`);
    }
  }

  private getDocument(op: string, payload: Record<string, unknown>): Response {
    const document = this.findDocument(payload);
    if (!document) return this.failure(op, 'MTR-0009', 'fixture document not found');
    return this.success(op, {
      pack: document.pack,
      path: document.path,
      rev: document.rev,
      raw: document.raw,
      parsed: structuredClone(document.parsed),
      computed: {},
    });
  }

  private putDocument(op: string, payload: Record<string, unknown>): Response {
    const document = this.findDocument(payload);
    if (!document) return this.failure(op, 'MTR-0009', 'fixture document not found');
    if (numberValue(payload.rev) !== document.rev) {
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }
    const raw = stringValue(payload.content);
    const parsed = parseFixtureYaml(raw);
    if (Object.keys(parsed).length === 0) {
      return this.failure(op, 'MTR-0012', 'fixture YAML has no resource root');
    }
    document.raw = raw;
    document.parsed = parsed;
    document.rev += 1;
    return this.success(op, {
      rev: document.rev,
      raw: document.raw,
      parsed: structuredClone(document.parsed),
    });
  }

  private setFields(op: string, payload: Record<string, unknown>): Response {
    const document = this.findDocument(payload);
    if (!document) return this.failure(op, 'MTR-0009', 'fixture document not found');
    if (numberValue(payload.rev) !== document.rev) {
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }
    const edits = parseEdits(payload.edits);
    if (!edits) return this.failure(op, 'MTR-0012', 'fixture field edits are malformed');
    const parsed = structuredClone(document.parsed);
    for (const edit of edits) {
      const root = edit.field[0];
      if (!root || !parsed[root]) {
        return this.failure(op, 'MTR-0009', 'fixture resource root not found');
      }
      if (!assignPath(parsed[root], edit.field.slice(1), edit.value)) {
        return this.failure(op, 'MTR-0012', 'fixture field path is malformed');
      }
    }
    document.parsed = parsed;
    document.rev += edits.length > 0 ? 1 : 0;
    document.raw = serializeFixtureYaml(document.parsed);
    return this.success(op, { rev: document.rev });
  }

  private saveModel(op: string, payload: Record<string, unknown>): Response {
    const assets = Array.isArray(payload.assets) ? payload.assets : [];
    const stagedAssets: { pack: string; path: string; data: string }[] = [];
    for (const asset of assets) {
      if (!isRecord(asset)) return this.failure(op, 'MTR-0012', 'fixture model asset is malformed');
      const path = stringValue(asset.path);
      const data = stringValue(asset.data);
      if (!path) return this.failure(op, 'MTR-0012', 'fixture model asset path is missing');
      stagedAssets.push({ pack: stringValue(payload.pack, PACK), path, data });
    }

    const ownerPath = stringValue(payload.ownerPath);
    const document = ownerPath
      ? this.#documents.get(`${stringValue(payload.pack, PACK)}/${ownerPath}`)
      : undefined;
    if (ownerPath && !document) return this.failure(op, 'MTR-0009', 'fixture model owner not found');
    if (document && numberValue(payload.rev, document.rev) !== document.rev) {
      return this.failure(op, 'MTR-0034', 'document revision no longer matches');
    }

    const field = stringArray(payload.field) ? payload.field : [];
    const modelRef = stringValue(payload.modelRef);
    let updatedDocument: Record<string, Record<string, unknown>> | null = null;
    if (document && field.length > 1) {
      const root = field[0];
      if (!root || !document.parsed[root]) {
        return this.failure(op, 'MTR-0009', 'fixture model resource root not found');
      }
      updatedDocument = structuredClone(document.parsed);
      if (!assignPath(updatedDocument[root]!, field.slice(1), modelRef)) {
        return this.failure(op, 'MTR-0012', 'fixture model field path is malformed');
      }
    }

    for (const asset of stagedAssets) this.storeAsset(asset.pack, asset.path, asset.data);
    if (!document) return this.success(op, { rev: 0 });

    if (updatedDocument) {
      document.parsed = updatedDocument;
      document.rev += 1;
      document.raw = serializeFixtureYaml(document.parsed);
    }
    return this.success(op, { rev: document.rev });
  }

  private listAssets(op: string, payload: Record<string, unknown>): Response {
    const pack = stringValue(payload.pack);
    const assets: AssetRef[] = [...this.#assets.values()]
      .filter((asset) => this.#assets.has(assetPath(pack, asset.path)))
      .map((asset) => ({ path: asset.path, kind: asset.kind, bytes: asset.bytes }));
    return this.success(op, { assets });
  }

  private getAsset(op: string, payload: Record<string, unknown>): Response {
    const asset = this.#assets.get(
      assetPath(stringValue(payload.pack), stringValue(payload.path)),
    );
    if (!asset) return this.failure(op, 'MTR-0009', 'fixture asset not found');
    return this.success(op, { path: asset.path, data: asset.data, bytes: asset.bytes });
  }

  private putAsset(op: string, payload: Record<string, unknown>): Response {
    const pack = stringValue(payload.pack);
    const path = stringValue(payload.path);
    if (!pack || !path) return this.failure(op, 'MTR-0012', 'fixture asset path is missing');
    const asset = this.storeAsset(pack, path, stringValue(payload.data));
    return this.success(op, { path: asset.path, bytes: asset.bytes });
  }

  private deleteAsset(op: string, payload: Record<string, unknown>): Response {
    const key = assetPath(stringValue(payload.pack), stringValue(payload.path));
    if (!this.#assets.delete(key)) return this.failure(op, 'MTR-0009', 'fixture asset not found');
    return this.success(op, { deleted: true });
  }

  private moveAsset(op: string, payload: Record<string, unknown>): Response {
    const pack = stringValue(payload.pack);
    const from = stringValue(payload.from);
    const to = stringValue(payload.to);
    const oldKey = assetPath(pack, from);
    const asset = this.#assets.get(oldKey);
    if (!asset) return this.failure(op, 'MTR-0009', 'fixture asset not found');
    this.#assets.delete(oldKey);
    this.#assets.set(assetPath(pack, to), { ...asset, path: to });
    return this.success(op, { from, to });
  }

  private publish(op: string, payload: Record<string, unknown>): Response {
    const expected = stringValue(payload.expectedCandidateSha256);
    if (!expected || expected !== this.#candidateSha256) {
      return this.failure(op, 'MTR-0042', 'candidate hash changed; refresh status before publishing');
    }
    this.#publishedSha256 = expected;
    this.#candidateSha256 = '';
    return this.success(op, { publishedSha256: this.#publishedSha256 });
  }

  private findDocument(payload: Record<string, unknown>): FixtureDocument | undefined {
    return this.#documents.get(
      `${stringValue(payload.pack)}/${stringValue(payload.path)}`,
    );
  }

  private storeAsset(pack: string, path: string, data: string): FixtureAsset {
    const asset: FixtureAsset = {
      path,
      kind: path.split('.').pop() ?? 'bin',
      data,
      bytes: Math.floor((data.length * 3) / 4),
    };
    this.#assets.set(assetPath(pack, path), asset);
    return asset;
  }
}
