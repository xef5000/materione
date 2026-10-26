import type { DiagnosticEntry, ResourceRef } from './api';

export function diagnosticSource(
  entry: DiagnosticEntry,
  packs: readonly string[],
  resources: readonly ResourceRef[],
): { resource: ResourceRef; source?: { line: number; column: number } } | null {
  if (!entry.location) return null;
  const coordinate = /^(.*?):(\d+)(?::(\d+))?$/.exec(entry.location);
  const file = (coordinate?.[1] ?? entry.location).replace(/\\/g, '/').replace(/^\.\//, '');
  const target = [...packs].sort((left, right) => right.length - left.length).map((pack) => {
    const prefix = `/content/packs/${pack}/`;
    const offset = file.lastIndexOf(prefix);
    const path = file.startsWith(pack + '/') ? file.slice(pack.length + 1)
      : offset >= 0 ? file.slice(offset + prefix.length) : '';
    return { pack, path };
  }).find(({ path }) => /\.(yml|yaml)$/i.test(path) &&
    path.split('/').every((part) => part !== '' && part !== '.' && part !== '..'));
  if (!target) return null;
  const resource = resources.find((candidate) =>
    candidate.pack === target.pack && candidate.path === target.path &&
    (!entry.resource || candidate.id === entry.resource ||
      candidate.id === entry.resource.split(':').pop())) ?? {
    ...target,
    type: target.path.split('/')[0] ?? '',
    id: entry.resource?.trim() ?? '',
  };
  const line = Number(coordinate?.[2]);
  const column = Number(coordinate?.[3] ?? 1);
  const source = Number.isSafeInteger(line) && line > 0 &&
    Number.isSafeInteger(column) && column > 0 ? { line, column } : undefined;
  return { resource, source };
}

/** The id part of a resource id or a namespaced diagnostic resource (`pack:ruby` → `ruby`). */
function localId(id: string): string {
  return id.slice(id.lastIndexOf(':') + 1);
}

/** True when the diagnostic names exactly this resource (`<pack>:<id>`), not a substring match. */
export function diagnosticNames(entry: DiagnosticEntry, resource: ResourceRef): boolean {
  if (!entry.resource) return false;
  const colon = entry.resource.lastIndexOf(':');
  const namespace = colon < 0 ? '' : entry.resource.slice(0, colon);
  return localId(entry.resource) === localId(resource.id) &&
    (namespace === '' || namespace === resource.pack);
}

/**
 * The resource a structured diagnostic is about, and the field to open, so
 * Problems can send the author to the exact control (web.md §6). Null when
 * the resource is not in the open pack's catalog.
 */
export function diagnosticRepairTarget(
  entry: DiagnosticEntry,
  resources: readonly ResourceRef[],
): { resource: ResourceRef; field?: string } | null {
  if (!entry.resource) return null;
  const resource = resources.find((candidate) => diagnosticNames(entry, candidate));
  if (!resource) return null;
  const field = entry.target?.field?.[0];
  return field ? { resource, field } : { resource };
}
