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
