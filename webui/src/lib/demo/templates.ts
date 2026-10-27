/**
 * The template operations of the in-tab servers, the demo and the deterministic
 * browser fixture (issue 154):
 * create from a template, copy, the derived catalog, detach, and save as a
 * template. They mirror what the server's `AuthoringApiImpl` does closely
 * enough for the editor's behaviour to be asserted without a server; the
 * server's own semantics are pinned by `TemplateAuthoringTest`.
 */
import type { ResourceRef, TypeInfo } from '../api';

export interface TemplateDocument {
  pack: string;
  path: string;
  rev: number;
  raw: string;
  parsed: Record<string, unknown>;
}

type Docs = Map<string, TemplateDocument>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

/** `pack:id` of a reference authored in `pack`. */
export function fullRef(authored: string, pack: string): string {
  return authored.includes(':') ? authored : `${pack}:${authored}`;
}

/** A template's authored definition by full `pack:id`. */
export function templateDefinition(documents: Docs, full: string): Record<string, unknown> | undefined {
  const [pack, id] = full.split(':');
  const definition = documents.get(`${pack}/templates/${id}.yml`)?.parsed[id ?? ''];
  return isRecord(definition) ? definition : undefined;
}

/** The fields a new resource starts with: the link and the name, or a scaffold. */
export function startingFields(
  documents: Docs,
  pack: string,
  type: string,
  id: string,
  name: string,
  template: string,
  serves: string,
): { fields: Record<string, unknown> } | { error: string } {
  if (template) {
    const full = fullRef(template, pack);
    if (!templateDefinition(documents, full)) return { error: `unknown template ${full}` };
    return { fields: { template, display_name: name } };
  }
  if (type === 'templates') {
    return { fields: serves === 'blocks' ? { technique: 'note_block' } : { material: 'PAPER' } };
  }
  if (type === 'blocks') return { fields: { technique: 'note_block', texture: `block/${id}`, display_name: name } };
  return { fields: { material: 'STICK', display_name: name } };
}

/** Mirrors the server's catalog: what each template sets, who it serves, who inherits it. */
export function templateCatalog(documents: Docs, types: TypeInfo[]): Record<string, unknown>[] {
  const capable = types.filter((type) => type.parameters.some((param) => param.referenceKind === 'template'));
  const out: Record<string, unknown>[] = [];
  for (const document of documents.values()) {
    if (!document.path.startsWith('templates/')) continue;
    for (const [id, definition] of Object.entries(document.parsed)) {
      if (!isRecord(definition)) continue;
      const usedBy: ResourceRef[] = [];
      for (const other of documents.values()) {
        for (const [otherId, fields] of Object.entries(other.parsed)) {
          if (!isRecord(fields)) continue;
          const authored = text(fields.template);
          if (authored && fullRef(authored, other.pack) === `${document.pack}:${id}`) {
            usedBy.push({ pack: other.pack, type: other.path.split('/')[0] ?? '', path: other.path, id: otherId });
          }
        }
      }
      let serves = capable.map((type) => type.directory ?? '')
        .filter((directory) => usedBy.some((user) => user.type === directory));
      if (serves.length === 0) {
        const scores = capable.map((type) => ({
          directory: type.directory ?? '',
          score: Object.keys(definition).filter((key) =>
            key !== 'template' && type.parameters.some((param) => param.id === key)).length,
        }));
        const best = Math.max(0, ...scores.map((entry) => entry.score));
        serves = (best === 0 ? scores : scores.filter((entry) => entry.score === best))
          .map((entry) => entry.directory);
      }
      const parent = text(definition.template);
      out.push({
        pack: document.pack,
        path: document.path,
        id,
        ...(parent ? { parent: fullRef(parent, document.pack) } : {}),
        definition: structuredClone(definition),
        serves,
        usedBy,
      });
    }
  }
  return out.sort((a, b) => String(a.id).localeCompare(String(b.id)));
}

/**
 * The resource's fields after detaching: its own, then the chain's values it
 * did not set (parent-first, like the server's resolver), without `template`.
 */
export function detached(
  documents: Docs,
  pack: string,
  fields: Record<string, unknown>,
): { fields: Record<string, unknown> } | { error: string } {
  const authored = text(fields.template);
  if (!authored) return { error: 'not based on a template' };
  const chain: Record<string, unknown>[] = [];
  const seen = new Set<string>();
  let next = fullRef(authored, pack);
  while (next) {
    const definition = templateDefinition(documents, next);
    if (!definition || seen.has(next)) return { error: `template does not resolve: unknown template ${next}` };
    seen.add(next);
    chain.unshift(definition);
    const parent = text(definition.template);
    next = parent ? fullRef(parent, next.split(':')[0] ?? pack) : '';
  }
  const inherited: Record<string, unknown> = {};
  for (const definition of chain) Object.assign(inherited, structuredClone(definition));
  delete inherited.template;
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(fields)) if (key !== 'template') result[key] = value;
  for (const [key, value] of Object.entries(inherited)) if (!(key in result)) result[key] = value;
  return { fields: result };
}
