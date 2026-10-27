/**
 * The demo's server (web.md §6, cloud landing): an in-tab RpcChannel over a
 * recorded snapshot of the real editor ops. The editor above it is the real
 * App/Workbench, unchanged — this class is the only thing the demo swaps,
 * exactly where a live session would put its sealed channel.
 *
 * Reads answer from the snapshot in the server's reply shapes. Writes edit
 * the snapshot in memory with the same revision checks and comment-keeping
 * YAML edits the server makes, then announce a reload like a server does
 * after a save. What cannot run here is the Java content pipeline: after an
 * edit, a resource's name and material are re-derived from its fields, but
 * validation diagnostics and compiled previews keep their recorded values.
 */
import { isMap, parse, parseDocument } from 'yaml';
import type { ResourceRef, ResourceSummary, RpcChannel } from '../api';
import type { PushFrame } from '../events';
import type { Response } from '../protocol/wire';
import type { DemoSnapshot, SnapshotDocument, SnapshotPack } from './snapshot';
import { scanReferences } from './references';
import { detached, fullRef, startingFields, templateCatalog, templateDefinition } from './templates';
import type { TemplateDocument } from './templates';

const ACTOR = 'demo';
const UNKNOWN_HEALTH = 'pipeline validation and apply freshness are unknown';
const ID_PATTERN = /^[a-z0-9_-]{2,64}$/;

class DemoError extends Error {
  constructor(readonly code: string, message: string, readonly hint?: string) {
    super(message);
  }
}

interface Revision { rev: number; ts: number; actor: string; action: string; sha256: string }

export class DemoServer implements RpcChannel {
  readonly #snapshot: DemoSnapshot;
  readonly #history = new Map<string, Revision[]>();
  readonly #audit: { ts: number; actor: string; op: string; target?: string }[] = [];
  #push: ((frame: PushFrame) => void) | null = null;
  #nextId = 0;
  #candidate = 0;

  constructor(snapshot: DemoSnapshot) {
    this.#snapshot = snapshot;
  }

  setPushHandler(handler: (frame: PushFrame) => void): void {
    this.#push = handler;
  }

  async call(op: string, payload: Record<string, unknown> = {}): Promise<Response> {
    const id = `demo-${++this.#nextId}`;
    try {
      const data = await this.#dispatch(op, payload);
      return { op, id, ok: true, data };
    } catch (error) {
      if (error instanceof DemoError) {
        return { op, id, ok: false, error: { code: error.code, message: error.message, hint: error.hint ?? null } };
      }
      return { op, id, ok: false, error: { code: 'MTR-0011', message: String((error as Error)?.message ?? error) } };
    }
  }

  async #dispatch(op: string, p: Record<string, unknown>): Promise<unknown> {
    const s = this.#snapshot;
    switch (op) {
      case 'ping': return {};
      case 'session.info': return { ...s.session, editorRunning: false, editorPort: -1 };
      case 'browse.packs': return { packs: s.packs.map((pack) => pack.name) };
      case 'browse.types': return { types: structuredClone(s.types) };
      case 'authoring.describe': return structuredClone(s.describe);
      case 'browse.resources': return { resources: this.#resources(p) };
      case 'authoring.summaries': return this.#summaries(p);
      case 'browse.folders': return { folders: [...this.#pack(p.pack).folders] };
      case 'browse.get': return this.#get(p);
      case 'browse.history': return { revisions: [...(this.#history.get(key(text(p.pack), text(p.path))) ?? [])].reverse() };
      case 'edit.put':
      case 'authoring.commit': return this.#put(op, p);
      case 'authoring.setFields': return this.#setFields(p);
      case 'authoring.references': return this.#references(p);
      case 'authoring.bulk': return this.#bulk(p);
      case 'edit.setScalar': return this.#setFields({ ...p, edits: [{ field: p.field, value: p.value }] });
      case 'edit.create': return this.#create(op, text(p.pack), text(p.path), optional(p.id));
      case 'authoring.createResource': return this.#createResource(p);
      case 'authoring.duplicateResource': return this.#duplicate(p);
      case 'authoring.templates': return { templates: templateCatalog(this.#documentMap(), this.#snapshot.types) };
      case 'authoring.detachTemplate': return this.#detach(p);
      case 'authoring.saveAsTemplate': return this.#saveAsTemplate(p);
      case 'edit.delete': return this.#delete(p);
      case 'edit.move': return this.#move(p);
      case 'edit.createFolder': return this.#folder(p, 'create');
      case 'edit.deleteFolder': return this.#folder(p, 'delete');
      case 'edit.moveFolder': return this.#moveFolder(p);
      case 'edit.createPack': return this.#createPack(text(p.pack));
      case 'assets.list': return { assets: this.#pack(p.pack).assets.map(({ path, kind, bytes }) => ({ path, kind, bytes })) };
      case 'assets.get': return this.#assetGet(p);
      case 'assets.put': return this.#assetPut(text(p.pack), text(p.path), text(p.data));
      case 'assets.delete': return this.#assetDelete(p);
      case 'assets.move': return this.#assetMove(p);
      case 'authoring.saveModel': return this.#saveModel(p);
      case 'validate.dryRun': return this.#dryRun(p);
      case 'pipeline.reload': this.#audited('pipeline.reload', text(p.pack) || 'everything'); this.#reloaded(); return { queued: true };
      case 'delivery.status': return { ...s.delivery };
      case 'delivery.publish': return this.#publish(p);
      case 'admin.diagnostics.recent': return { diagnostics: structuredClone(s.diagnostics) };
      case 'admin.audit.recent': return { entries: [...this.#audit].reverse().slice(0, numberOr(p.limit, 100)) };
      case 'admin.settings.get': return structuredClone(s.settings);
      case 'admin.settings.put': return this.#settingsPut(p);
      default:
        throw new DemoError('MTR-0033', `unknown op '${op}'`, 'the editor or server may be outdated (protocol 1)');
    }
  }

  // ---- reads -------------------------------------------------------------

  #pack(name: unknown): SnapshotPack {
    const pack = this.#snapshot.packs.find((candidate) => candidate.name === name);
    if (!pack) throw new DemoError('MTR-0009', `no such pack '${String(name)}'`);
    return pack;
  }

  #resources(p: Record<string, unknown>): ResourceRef[] {
    const type = optional(p.type);
    const query = optional(p.query)?.toLowerCase();
    return this.#snapshot.packs
      .filter((pack) => !p.pack || pack.name === p.pack)
      .flatMap((pack) => pack.resources)
      .filter((ref) => (!type || ref.type === type) && (!query || `${ref.id} ${ref.path}`.toLowerCase().includes(query)))
      .map((ref) => ({ ...ref }));
  }

  #summaries(p: Record<string, unknown>): unknown {
    const pack = this.#pack(p.pack);
    const offset = numberOr(p.offset, 0);
    const limit = numberOr(p.limit, 100);
    if (!Number.isInteger(offset) || offset < 0 || !Number.isInteger(limit) || limit < 1 || limit > 200) {
      throw new DemoError('MTR-0008', 'summary page has invalid offset or limit');
    }
    const type = optional(p.type);
    const query = optional(p.query)?.replaceAll('_', ' ').trim().toLowerCase();
    const all = pack.summaries.filter((summary) => {
      if (type && summary.type !== type) return false;
      if (!query) return true;
      return [summary.id, summary.path, summary.name, summary.material ?? '']
        .join(' ').replaceAll('_', ' ').toLowerCase().includes(query);
    });
    const end = Math.min(offset + limit, all.length);
    return {
      resources: structuredClone(all.slice(Math.min(offset, all.length), end)),
      total: all.length,
      ...(end < all.length ? { nextOffset: end } : {}),
    };
  }

  #document(pack: string, path: string): SnapshotDocument {
    const document = this.#pack(pack).documents[path];
    if (!document) throw new DemoError('MTR-0009', 'no such document');
    return document;
  }

  #get(p: Record<string, unknown>): unknown {
    const document = this.#document(text(p.pack), text(p.path));
    return {
      raw: document.raw,
      rev: document.rev,
      sha256: document.sha256,
      parsed: structuredClone(document.parsed),
      ...(document.computed ? { computed: structuredClone(document.computed) } : {}),
    };
  }

  #assetGet(p: Record<string, unknown>): unknown {
    const asset = this.#pack(p.pack).assets.find((entry) => entry.path === p.path);
    if (!asset) throw new DemoError('MTR-0009', 'no such asset');
    return { data: asset.data };
  }

  // ---- document writes ---------------------------------------------------

  #checkRev(document: SnapshotDocument, rev: unknown): void {
    if (rev !== document.rev) {
      throw new DemoError('MTR-0034', 'document changed since it was read (revision mismatch)',
        'reload the document and re-apply your change');
    }
  }

  async #store(pack: string, path: string, raw: string, action: string): Promise<SnapshotDocument> {
    const parsed = parseContent(raw);
    const packState = this.#pack(pack);
    const previous = packState.documents[path];
    const document: SnapshotDocument = {
      raw,
      rev: (previous?.rev ?? 0) + 1,
      sha256: await sha256(raw),
      parsed,
      ...(previous?.computed ? { computed: previous.computed } : {}),
    };
    packState.documents[path] = document;
    this.#index(packState, path, parsed);
    const history = this.#history.get(key(pack, path)) ?? [];
    history.push({ rev: document.rev, ts: Date.now(), actor: ACTOR, action, sha256: document.sha256 });
    this.#history.set(key(pack, path), history);
    this.#audited(action, `${pack}/${path}`);
    this.#reloaded();
    return document;
  }

  async #put(op: string, p: Record<string, unknown>): Promise<unknown> {
    const pack = text(p.pack);
    const path = text(p.path);
    this.#checkRev(this.#document(pack, path), p.rev);
    const document = await this.#store(pack, path, text(p.content), op);
    return { rev: document.rev, ...(op === 'authoring.commit' ? { raw: document.raw } : {}), parsed: structuredClone(document.parsed) };
  }

  async #setFields(p: Record<string, unknown>): Promise<unknown> {
    const pack = text(p.pack);
    const path = text(p.path);
    const current = this.#document(pack, path);
    this.#checkRev(current, p.rev);
    if (!Array.isArray(p.edits)) throw new DemoError('MTR-0008', 'edits must be a list');
    const yaml = parseDocument(current.raw);
    for (const edit of p.edits as Record<string, unknown>[]) {
      const field = edit.field;
      if (!Array.isArray(field) || field.length < 2 || !field.every((part) => typeof part === 'string')) {
        throw new DemoError('MTR-0008', 'field path must name a resource root and a field');
      }
      if (!yaml.hasIn([field[0]])) throw new DemoError('MTR-0009', `no resource root '${String(field[0])}'`);
      if (edit.remove === true) yaml.deleteIn(field);
      else yaml.setIn(field, yaml.createNode(edit.value));
    }
    const document = await this.#store(pack, path, yaml.toString(), 'authoring.setFields');
    return { rev: document.rev, raw: document.raw, parsed: structuredClone(document.parsed) };
  }

  #references(p: Record<string, unknown>): unknown {
    const kind = text(p.kind);
    if (kind !== 'asset' && kind !== 'resource') throw new DemoError('MTR-0008', "kind must be 'asset' or 'resource'");
    const documents = this.#snapshot.packs.flatMap((pack) => Object.entries(pack.documents)
      .map(([path, document]) => ({ pack: pack.name, path, parsed: document.parsed })));
    return scanReferences(documents, { kind, pack: text(p.pack), target: text(p.target) });
  }

  /** All-or-none: every operation is planned (and may throw) before the first document is written. */
  async #bulk(p: Record<string, unknown>): Promise<unknown> {
    const pack = text(p.pack);
    const packState = this.#pack(pack);
    const operations = p.operations;
    if (!Array.isArray(operations) || operations.length === 0) throw new DemoError('MTR-0008', "'operations' must be a non-empty array");
    if (operations.length > 50) throw new DemoError('MTR-0008', 'at most 50 documents per bulk change');
    const types = Object.keys(this.#snapshot.scaffolds);
    const planned: { path: string; kind: string; before?: string; raw: string; creates: boolean; edits: Record<string, unknown>[] }[] = [];
    for (const entry of operations as Record<string, unknown>[]) {
      const kind = text(entry.kind);
      const edits = Array.isArray(entry.edits) ? (entry.edits as Record<string, unknown>[]) : [];
      let path: string;
      let raw: string;
      let before: string | undefined;
      if (kind === 'set') {
        path = text(entry.path);
        const current = this.#document(pack, path);
        this.#checkRev(current, entry.rev);
        raw = current.raw;
        before = current.raw;
      } else if (kind === 'create') {
        const id = text(entry.id);
        path = documentPath(optional(entry.type) ?? 'items', optional(entry.folder), id, types);
        const template = this.#snapshot.scaffolds[path.split('/')[0] ?? ''];
        if (template === undefined) throw new DemoError('MTR-0008', `no scaffold for '${path}'`);
        raw = template.replaceAll(this.#snapshot.scaffoldId, id).replaceAll(display(this.#snapshot.scaffoldId), display(id));
      } else if (kind === 'duplicate') {
        const from = text(entry.fromPath);
        const id = text(entry.id);
        const source = this.#document(pack, from);
        path = documentPath(from.slice(0, from.indexOf('/')), optional(entry.folder), id, types);
        const sourceId = Object.keys(source.parsed)[0];
        if (sourceId === undefined) throw new DemoError('MTR-0008', 'cannot copy a file without a resource');
        raw = source.raw.replace(new RegExp(`^${escapeRegExp(sourceId)}:`, 'm'), `${id}:`);
      } else {
        throw new DemoError('MTR-0008', 'operation kind must be set, create or duplicate');
      }
      if (planned.some((other) => other.path === path)) throw new DemoError('MTR-0008', `the same document appears twice: ${path}`);
      if (kind !== 'set' && packState.documents[path]) throw new DemoError('MTR-0008', `document already exists: ${path}`, 'choose a different id');
      const yaml = parseDocument(raw);
      for (const edit of edits) {
        const field = edit.field;
        if (!Array.isArray(field) || field.length < 2 || !field.every((part) => typeof part === 'string')) {
          throw new DemoError('MTR-0008', 'field path must name a resource root and a field');
        }
        if (!yaml.hasIn([field[0]])) throw new DemoError('MTR-0009', `no resource root '${String(field[0])}' in ${path}`);
        if (edit.remove === true) yaml.deleteIn(field);
        else yaml.setIn(field, yaml.createNode(edit.value));
      }
      planned.push({ path, kind, ...(before === undefined ? {} : { before }), raw: yaml.toString(), creates: kind !== 'set', edits });
    }
    const apply = p.dryRun !== true && planned.some((entry) => entry.creates || entry.raw !== entry.before);
    const documents: unknown[] = [];
    for (const entry of planned) {
      const unchanged = !entry.creates && entry.raw === entry.before;
      const before = entry.before === undefined ? undefined : parseContent(entry.before);
      const after = parseContent(entry.raw);
      const changes = unchanged ? [] : entry.edits.map((edit) => {
        const field = edit.field as string[];
        return { field, before: valueAt(before, field) ?? null, after: valueAt(after, field) ?? null };
      }).filter((change) => JSON.stringify(change.before) !== JSON.stringify(change.after));
      let rev = entry.creates ? 1 : this.#document(pack, entry.path).rev + 1;
      if (apply && !unchanged) rev = (await this.#store(pack, entry.path, entry.raw, 'authoring.bulk')).rev;
      else if (unchanged) rev = this.#document(pack, entry.path).rev;
      documents.push({ path: entry.path, kind: entry.kind, rev, unchanged, changes });
    }
    return { applied: apply, documents };
  }

  async #create(op: string, pack: string, path: string, id: string | undefined, name?: string): Promise<SnapshotDocument> {
    if (this.#pack(pack).documents[path]) throw new DemoError('MTR-0008', `document already exists: ${path}`, 'choose a different id');
    const dir = path.split('/')[0] ?? '';
    const template = this.#snapshot.scaffolds[dir];
    if (template === undefined) {
      throw new DemoError('MTR-0008', `no scaffold for '${dir}' — create documents under ${Object.keys(this.#snapshot.scaffolds).join(', ')}`);
    }
    const resourceId = (id ?? path.slice(path.lastIndexOf('/') + 1).replace(/\.ya?ml$/, '')).trim().toLowerCase().replaceAll(' ', '_');
    if (!ID_PATTERN.test(resourceId)) throw new DemoError('MTR-0008', `resource id must be 2..64 chars of a-z 0-9 _ -: ${resourceId}`);
    const placeholder = this.#snapshot.scaffoldId;
    let raw = template.replaceAll(placeholder, resourceId).replaceAll(display(placeholder), display(resourceId));
    if (name !== undefined && raw.includes('display_name:')) {
      const yaml = parseDocument(raw);
      yaml.setIn([resourceId, 'display_name'], name);
      raw = yaml.toString();
    }
    return this.#store(pack, path, raw, op);
  }

  async #createResource(p: Record<string, unknown>): Promise<unknown> {
    const name = text(p.name).trim();
    if (!name || name.length > 128) throw new DemoError('MTR-0008', 'display name must be 1..128 characters');
    const id = text(p.id);
    const pack = text(p.pack);
    if (p.createPack === true && !this.#snapshot.packs.some((entry) => entry.name === pack)) this.#createPack(pack);
    const path = documentPath(optional(p.type) ?? 'items', optional(p.folder), id, Object.keys(this.#snapshot.scaffolds));
    const template = optional(p.template);
    if (template) {
      // A template-born resource is the link plus its name; the template supplies the rest.
      if (this.#pack(pack).documents[path]) throw new DemoError('MTR-0008', `document already exists: ${path}`, 'choose a different id');
      const start = startingFields(this.#documentMap(), pack, optional(p.type) ?? 'items', id, name, template, '');
      if ('error' in start) throw new DemoError('MTR-0008', start.error);
      const yaml = parseDocument(`# ${id} — created by the Materione editor
${id}: {}
`);
      for (const [field, value] of Object.entries(start.fields)) yaml.setIn([id, field], yaml.createNode(value));
      const created = await this.#store(pack, path, yaml.toString(), 'authoring.createResource');
      return { pack, path, id, rev: created.rev, raw: created.raw, parsed: structuredClone(created.parsed) };
    }
    const document = await this.#create('authoring.createResource', pack, path, id, name);
    return { pack, path, id, rev: document.rev, raw: document.raw, parsed: structuredClone(document.parsed) };
  }

  /** Every document of every pack, as the template helpers read them. */
  #documentMap(): Map<string, TemplateDocument> {
    return new Map(this.#snapshot.packs.flatMap((pack) => Object.entries(pack.documents).map(([path, document]) =>
      [`${pack.name}/${path}`, { pack: pack.name, path, rev: document.rev, raw: document.raw, parsed: document.parsed }] as const)));
  }

  /** Copies the inherited values into the resource and drops its `template:` link. */
  async #detach(p: Record<string, unknown>): Promise<unknown> {
    const pack = text(p.pack);
    const path = text(p.path);
    const current = this.#document(pack, path);
    this.#checkRev(current, p.rev);
    const root = optional(p.resource) ?? Object.keys(current.parsed)[0] ?? '';
    const fields = current.parsed[root] as Record<string, unknown> | undefined;
    if (!fields) throw new DemoError('MTR-0008', `no resource '${root}' in ${path}`);
    const result = detached(this.#documentMap(), pack, fields);
    if ('error' in result) throw new DemoError('MTR-0008', `${root}: ${result.error}`);
    const yaml = parseDocument(current.raw);
    for (const [field, value] of Object.entries(result.fields)) {
      if (!(field in fields)) yaml.setIn([root, field], yaml.createNode(value));
    }
    yaml.deleteIn([root, 'template']);
    const document = await this.#store(pack, path, yaml.toString(), 'authoring.detachTemplate');
    return { rev: document.rev, raw: document.raw, parsed: structuredClone(document.parsed) };
  }

  /** A new `templates/<id>.yml` holding the chosen authored fields of a resource. */
  async #saveAsTemplate(p: Record<string, unknown>): Promise<unknown> {
    const pack = text(p.pack);
    const id = text(p.id);
    const source = this.#document(pack, text(p.path));
    const root = optional(p.resource) ?? Object.keys(source.parsed)[0] ?? '';
    const fields = source.parsed[root] as Record<string, unknown> | undefined;
    if (!fields) throw new DemoError('MTR-0008', `no resource '${root}' in ${text(p.path)}`);
    if (!ID_PATTERN.test(id)) throw new DemoError('MTR-0008', 'id must be 2..64 chars of a-z 0-9 _ -');
    const chosen = Array.isArray(p.fields) ? p.fields.filter((field): field is string => typeof field === 'string') : [];
    if (chosen.length === 0) throw new DemoError('MTR-0008', 'choose at least one field to put in the template');
    const path = `templates/${id}.yml`;
    if (this.#pack(pack).documents[path] || templateDefinition(this.#documentMap(), `${pack}:${id}`)) {
      throw new DemoError('MTR-0008', `template already exists: ${id}`, 'choose a different template id');
    }
    const yaml = parseDocument(`# ${id} — saved from ${text(p.path)} by the Materione editor
${id}: {}
`);
    for (const field of chosen) {
      if (!(field in fields)) throw new DemoError('MTR-0008', `${root} does not set '${field}'`);
      yaml.setIn([id, field], yaml.createNode(fields[field]));
    }
    const created = await this.#store(pack, path, yaml.toString(), 'authoring.saveAsTemplate');
    return { pack, path, id, rev: created.rev, raw: created.raw, parsed: structuredClone(created.parsed) };
  }

  async #duplicate(p: Record<string, unknown>): Promise<unknown> {
    const pack = text(p.pack);
    const from = text(p.path);
    const id = text(p.id);
    const source = this.#document(pack, from);
    const type = from.includes('/') ? from.slice(0, from.indexOf('/')) : '';
    const path = documentPath(type, optional(p.folder), id, Object.keys(this.#snapshot.scaffolds));
    if (this.#pack(pack).documents[path]) throw new DemoError('MTR-0008', `document already exists: ${path}`, 'choose a different id');
    const sourceId = Object.keys(source.parsed)[0];
    if (sourceId === undefined) throw new DemoError('MTR-0008', 'cannot copy a file without a resource');
    // The source file's bytes, re-keyed: comments and unknown fields survive (§5.6).
    const raw = source.raw.replace(new RegExp(`^${escapeRegExp(sourceId)}:`, 'm'), `${id}:`);
    const document = await this.#store(pack, path, raw, 'authoring.duplicateResource');
    return { pack, path, id, rev: document.rev, raw: document.raw, parsed: structuredClone(document.parsed) };
  }

  #delete(p: Record<string, unknown>): unknown {
    const pack = this.#pack(p.pack);
    const path = text(p.path);
    this.#checkRev(this.#document(pack.name, path), p.rev);
    delete pack.documents[path];
    this.#index(pack, path, {});
    this.#audited('edit.delete', `${pack.name}/${path}`);
    this.#reloaded();
    return {};
  }

  async #move(p: Record<string, unknown>): Promise<unknown> {
    const pack = this.#pack(p.pack);
    const from = text(p.from);
    const to = text(p.to);
    const source = this.#document(pack.name, from);
    this.#checkRev(source, p.rev);
    if (pack.documents[to]) throw new DemoError('MTR-0008', `document already exists: ${to}`);
    delete pack.documents[from];
    this.#index(pack, from, {});
    const moved = await this.#store(pack.name, to, source.raw, 'edit.move');
    return { rev: moved.rev, parsed: structuredClone(moved.parsed) };
  }

  #folder(p: Record<string, unknown>, mode: 'create' | 'delete'): unknown {
    const pack = this.#pack(p.pack);
    const path = text(p.path).replace(/^\/+|\/+$/g, '');
    if (mode === 'create') {
      if (!pack.folders.includes(path)) pack.folders = [...pack.folders, path].sort();
    } else {
      if (Object.keys(pack.documents).some((doc) => doc.startsWith(`${path}/`))) {
        throw new DemoError('MTR-0008', `folder is not empty: ${path}`);
      }
      pack.folders = pack.folders.filter((folder) => folder !== path && !folder.startsWith(`${path}/`));
    }
    this.#audited(`edit.${mode}Folder`, `${pack.name}/${path}`);
    return {};
  }

  async #moveFolder(p: Record<string, unknown>): Promise<unknown> {
    const pack = this.#pack(p.pack);
    const from = text(p.from).replace(/\/+$/, '');
    const to = text(p.to).replace(/\/+$/, '');
    for (const path of Object.keys(pack.documents).filter((doc) => doc.startsWith(`${from}/`))) {
      const document = pack.documents[path]!;
      delete pack.documents[path];
      this.#index(pack, path, {});
      await this.#store(pack.name, `${to}${path.slice(from.length)}`, document.raw, 'edit.moveFolder');
    }
    pack.folders = pack.folders.map((folder) =>
      folder === from || folder.startsWith(`${from}/`) ? `${to}${folder.slice(from.length)}` : folder).sort();
    return {};
  }

  #createPack(name: string): unknown {
    if (!ID_PATTERN.test(name)) throw new DemoError('MTR-0008', 'pack id must be 2..64 chars of a-z 0-9 _ -');
    if (this.#snapshot.packs.some((pack) => pack.name === name)) throw new DemoError('MTR-0008', `pack already exists: ${name}`);
    this.#snapshot.packs.push({ name, folders: [], resources: [], summaries: [], documents: {}, assets: [] });
    this.#audited('edit.createPack', name);
    return { pack: name };
  }

  /** Keeps browse.resources and the catalog in step with a document's roots. */
  #index(pack: SnapshotPack, path: string, parsed: Record<string, unknown>): void {
    const type = path.includes('/') ? path.slice(0, path.indexOf('/')) : '';
    const roots = Object.keys(parsed).filter((root) => isRecord(parsed[root]));
    const previous = new Map(pack.summaries.filter((summary) => summary.path === path).map((summary) => [summary.id, summary]));
    pack.resources = pack.resources.filter((ref) => ref.path !== path);
    pack.summaries = pack.summaries.filter((summary) => summary.path !== path);
    for (const id of roots) {
      const fields = parsed[id] as Record<string, unknown>;
      pack.resources.push({ pack: pack.name, type, path, id });
      const before = previous.get(id);
      const material = typeof fields.material === 'string' ? fields.material : undefined;
      pack.summaries.push({
        ...(before ?? { health: 'unknown', healthMessage: UNKNOWN_HEALTH }),
        pack: pack.name, type, path, id,
        name: plainName(fields.display_name) ?? humanize(id),
        ...(material ? { material } : {}),
      } as ResourceSummary);
      if (!material && before?.material) delete pack.summaries[pack.summaries.length - 1]!.material;
    }
    pack.resources.sort(byPathThenId);
    pack.summaries.sort(byPathThenId);
  }

  // ---- assets & models -----------------------------------------------------

  #assetPut(packName: string, path: string, data: string): unknown {
    const pack = this.#pack(packName);
    const bytes = base64Length(data);
    const kind = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
    pack.assets = [...pack.assets.filter((asset) => asset.path !== path), { path, kind, bytes, data }]
      .sort((a, b) => a.path.localeCompare(b.path));
    this.#audited('assets.put', `${packName}/${path}`);
    this.#reloaded();
    return { bytes };
  }

  #assetDelete(p: Record<string, unknown>): unknown {
    const pack = this.#pack(p.pack);
    if (!pack.assets.some((asset) => asset.path === p.path)) throw new DemoError('MTR-0009', 'no such asset');
    pack.assets = pack.assets.filter((asset) => asset.path !== p.path);
    this.#audited('assets.delete', `${pack.name}/${text(p.path)}`);
    this.#reloaded();
    return {};
  }

  #assetMove(p: Record<string, unknown>): unknown {
    const pack = this.#pack(p.pack);
    const asset = pack.assets.find((entry) => entry.path === p.from);
    if (!asset) throw new DemoError('MTR-0009', 'no such asset');
    asset.path = text(p.to);
    this.#audited('assets.move', `${pack.name}/${text(p.from)}`);
    this.#reloaded();
    return {};
  }

  async #saveModel(p: Record<string, unknown>): Promise<unknown> {
    const pack = text(p.pack);
    for (const asset of Array.isArray(p.assets) ? p.assets as Record<string, unknown>[] : []) {
      this.#assetPut(pack, text(asset.path).replaceAll('\\', '/'), text(asset.data));
    }
    const ownerPath = text(p.ownerPath);
    if (p.createOwner === true) {
      const type = optional(p.ownerType) ?? 'items';
      const id = text(p.ownerId);
      const document = await this.#create('authoring.saveModel', pack, `${type}/${id}.yml`, id, optional(p.ownerName));
      return this.#attachModel(pack, `${type}/${id}.yml`, document.rev, [id, 'model'], text(p.modelRef));
    }
    if (!ownerPath) return {};
    return this.#attachModel(pack, ownerPath, p.rev, Array.isArray(p.field) ? p.field as string[] : [], text(p.modelRef));
  }

  async #attachModel(pack: string, path: string, rev: unknown, field: string[], modelRef: string): Promise<unknown> {
    const updated = await this.#setFields({ pack, path, rev, edits: [{ field, value: modelRef }] }) as { rev: number; raw: string };
    return { rev: updated.rev, raw: updated.raw, ownerPath: path };
  }

  // ---- pipeline, delivery, settings -----------------------------------------

  #dryRun(p: Record<string, unknown>): unknown {
    try {
      parseContent(text(p.content));
      return { ok: true };
    } catch (error) {
      return { ok: false, error: (error as Error).message };
    }
  }

  #publish(p: Record<string, unknown>): unknown {
    const delivery = this.#snapshot.delivery;
    if (!delivery.candidateSha256 || p.expectedCandidateSha256 !== delivery.candidateSha256) {
      throw new DemoError('MTR-0042', 'candidate hash changed; refresh status before publishing');
    }
    delivery.publishedSha256 = delivery.candidateSha256;
    delivery.candidateSha256 = '';
    this.#audited('delivery.publish', delivery.publishedSha256.slice(0, 12));
    return { publishedSha256: delivery.publishedSha256 };
  }

  async #settingsPut(p: Record<string, unknown>): Promise<unknown> {
    const settings = this.#snapshot.settings;
    if (p.sha256 !== settings.sha256) throw new DemoError('MTR-0034', 'settings changed since they were read');
    const content = text(p.content);
    let parsed: unknown;
    try {
      parsed = parse(content) ?? {};
    } catch (error) {
      throw new DemoError('MTR-0003', `settings.yml does not parse: ${(error as Error).message}`);
    }
    settings.raw = content;
    settings.sha256 = await sha256(content);
    settings.parsed = isRecord(parsed) ? parsed : {};
    this.#audited('admin.settings.put', 'settings.yml');
    return { sha256: settings.sha256, bytes: new TextEncoder().encode(content).length };
  }

  // ---- side effects a server has after a change ----------------------------

  #audited(op: string, target?: string): void {
    this.#audit.push({ ts: Date.now(), actor: ACTOR, op, ...(target ? { target } : {}) });
  }

  /** A save reloads its file and stages a new candidate pack (live preview), then pushes the outcome. */
  #reloaded(): void {
    this.#candidate += 1;
    this.#snapshot.delivery.candidateSha256 = `de${this.#candidate.toString(16).padStart(62, '0')}`;
    const { itemsLive, quarantined } = this.#snapshot.reload;
    const push = this.#push;
    if (push) {
      queueMicrotask(() => push({ op: 'push.reload', payload: { files: 1, accepted: true, noop: false, itemsLive, quarantined } }));
    }
  }
}

// ---- helpers -----------------------------------------------------------------

function parseContent(raw: string): Record<string, unknown> {
  const document = parseDocument(raw);
  if (document.errors.length > 0) {
    throw new DemoError('MTR-0007', `YAML parse failed: ${document.errors[0]!.message}`, 'fix the syntax, then save again');
  }
  const value: unknown = document.toJS();
  if (value === null || value === undefined) return {};
  if (!isMap(document.contents) || !isRecord(value)) {
    throw new DemoError('MTR-0008', 'a content file must be a mapping of resource ids');
  }
  return value;
}

function documentPath(type: string, folder: string | undefined, id: string, types: string[]): string {
  if (!types.includes(type)) throw new DemoError('MTR-0008', `unknown content type '${type}' — expected one of [${types.join(', ')}]`);
  if (!ID_PATTERN.test(id)) throw new DemoError('MTR-0008', 'id must be 2..64 chars of a-z 0-9 _ -');
  let cleaned = (folder ?? '').replace(/^\/+|\/+$/g, '');
  if (cleaned === type) cleaned = '';
  else if (cleaned.startsWith(`${type}/`)) cleaned = cleaned.slice(type.length + 1);
  return [type, cleaned, `${id}.yml`].filter(Boolean).join('/');
}

/** The scaffold's display form of an id (Scaffolds#display). */
function display(id: string): string {
  return id.split('_').filter(Boolean).map((word) => word[0]!.toUpperCase() + word.slice(1)).join(' ');
}

/** The summary computer's fallback name (ResourceSummaryComputer#humanize). */
function humanize(id: string): string {
  const words = id.slice(id.lastIndexOf('/') + 1).replaceAll('_', ' ').replaceAll('-', ' ').split(' ').filter(Boolean);
  return words.length ? words.map((word) => word[0]!.toUpperCase() + word.slice(1)).join(' ') : 'Resource';
}

/** A display name as plain text: legacy & / § codes and MiniMessage tags removed. */
function plainName(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const plain = value.replace(/[&§][0-9a-fk-orx#]/gi, '').replace(/<[^>]+>/g, '').trim();
  return plain || undefined;
}

async function sha256(textValue: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(textValue));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

function base64Length(data: string): number {
  const padding = data.endsWith('==') ? 2 : data.endsWith('=') ? 1 : 0;
  return Math.floor((data.length * 3) / 4) - padding;
}

function byPathThenId(a: ResourceRef, b: ResourceRef): number {
  return a.path === b.path ? a.id.localeCompare(b.id) : a.path.localeCompare(b.path);
}

function valueAt(tree: Record<string, unknown> | undefined, field: string[]): unknown {
  let node: unknown = tree;
  for (const part of field) {
    if (!isRecord(node)) return undefined;
    node = node[part];
  }
  return node;
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function key(pack: string, path: string): string {
  return `${pack}/${path}`;
}

function text(value: unknown): string {
  if (typeof value !== 'string') throw new DemoError('MTR-0008', 'missing required string field');
  return value;
}

function optional(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === 'number' ? value : fallback;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

