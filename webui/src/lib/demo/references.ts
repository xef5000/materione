/**
 * The reference scan the in-tab demo server and the browser-test fixture
 * answer `authoring.references` with: the same exact-text matching and reply
 * shape as the Java ReferenceScanner (core/authoring), over already-parsed
 * documents. Neither stand-in reads asset files, and neither pretends to.
 */
import { logicalAssetName } from '../bulk';

export interface ScannableDocument {
  pack: string;
  path: string;
  parsed: Record<string, unknown>;
}

export interface ReferenceQuery {
  kind: 'asset' | 'resource';
  pack: string;
  target: string;
}

const MAX_HITS = 200;
const NOT_SCANNED = [
  'Asset files are not read: textures named inside a model or sound definition are not found.',
  'Matching is by authored text: a value built by a template is counted where the template writes it.',
  'Documents that do not parse are skipped.',
];

export function spellings(query: ReferenceQuery): { bare: Set<string>; qualified: Set<string> } {
  const bare = new Set<string>();
  const qualified = new Set<string>();
  const forms = query.kind === 'asset'
    ? [
        logicalAssetName(query.target, true),
        logicalAssetName(query.target, false),
        query.target,
        query.target.replace(/^assets\//, ''),
      ]
    : [query.target];
  for (const form of forms) {
    bare.add(form);
    qualified.add(`${query.pack}:${form}`);
  }
  return { bare, qualified };
}

export function scanReferences(documents: Iterable<ScannableDocument>, query: ReferenceQuery): unknown {
  const forms = spellings(query);
  const hits: Record<string, unknown>[] = [];
  let scanned = 0;
  let truncated = false;
  const walk = (node: unknown, path: string[], samePack: boolean, owner: ScannableDocument, id: string): void => {
    if (truncated || path.length > 24) return;
    if (Array.isArray(node)) {
      node.forEach((child, index) => walk(child, [...path, String(index)], samePack, owner, id));
    } else if (typeof node === 'object' && node !== null) {
      for (const [key, child] of Object.entries(node)) walk(child, [...path, key], samePack, owner, id);
    } else if (typeof node === 'string') {
      const value = node.trim();
      if (forms.qualified.has(value) || (samePack && forms.bare.has(value))) {
        if (hits.length >= MAX_HITS) {
          truncated = true;
          return;
        }
        const type = owner.path.split('/')[0] ?? '';
        hits.push({ pack: owner.pack, type, path: owner.path, id, field: path, value: node });
      }
    }
  };
  for (const document of documents) {
    scanned += 1;
    for (const [id, root] of Object.entries(document.parsed)) {
      walk(root, [], document.pack === query.pack, document, id);
    }
  }
  return {
    hits,
    coverage: {
      documentsScanned: scanned,
      unreadable: 0,
      truncated,
      complete: !truncated,
      notScanned: NOT_SCANNED,
    },
  };
}
