/**
 * File-tree construction over the server's flat listings (browse.resources
 * returns one entry per RESOURCE — files may hold several — and assets.list
 * one per file). Pure functions, vitest-pinned: the explorer renders these
 * nodes directly.
 */
import type { AssetRef, ResourceRef } from './api';

export type NodeKind = 'dir' | 'doc' | 'asset' | 'packfile';

export interface TreeNode {
  /** Segment name shown in the tree. */
  name: string;
  /** Pack-relative path ('items', 'items/swords/gem.yml', 'assets/textures/a.png'). */
  path: string;
  kind: NodeKind;
  /** Top-level YAML keys inside a document file (one file, several resources). */
  resourceIds?: string[];
  /** Extension of an asset file ('png', 'json', …). */
  assetKind?: string;
  bytes?: number;
  children: TreeNode[];
}

/** Canonical root order: content types first, then assets, then manifest. */
const TYPE_ORDER = ['items', 'blocks', 'furnitures', 'recipes', 'templates'];

export function buildTree(resources: ResourceRef[], assets: AssetRef[]): TreeNode[] {
  const roots: TreeNode[] = [];

  const findOrCreateDir = (segments: string[], base: TreeNode[]): TreeNode[] => {
    let layer = base;
    let walked = '';
    for (const segment of segments) {
      walked = walked ? `${walked}/${segment}` : segment;
      let next = layer.find((node) => node.name === segment && node.kind === 'dir');
      if (!next) {
        next = { name: segment, path: walked, kind: 'dir', children: [] };
        layer.push(next);
      }
      layer = next.children;
    }
    return layer;
  };

  // Documents: group resource entries by FILE (several ids may share one).
  const files = new Map<string, string[]>();
  for (const resource of resources) {
    const ids = files.get(resource.path) ?? [];
    if (!ids.includes(resource.id)) {
      ids.push(resource.id);
    }
    files.set(resource.path, ids);
  }
  for (const [path, ids] of [...files.entries()].sort()) {
    const segments = path.split('/');
    const fileName = segments.pop()!;
    const siblings = findOrCreateDir(segments, roots);
    siblings.push({
      name: fileName,
      path,
      kind: 'doc',
      resourceIds: ids.sort(),
      children: [],
    });
  }

  // Assets: plain files under their directories.
  for (const asset of assets) {
    const segments = asset.path.split('/');
    const fileName = segments.pop()!;
    const siblings = findOrCreateDir(segments, roots);
    const dot = fileName.lastIndexOf('.');
    siblings.push({
      name: fileName,
      path: asset.path,
      kind: 'asset',
      assetKind: dot < 0 ? 'bin' : fileName.slice(dot + 1).toLowerCase(),
      bytes: asset.bytes,
      children: [],
    });
  }

  sortLayer(roots);
  return roots;
}

/** Keeps subtrees containing a match; parents of matches stay visible. */
export function filterTree(nodes: TreeNode[], rawQuery: string): TreeNode[] {
  const query = rawQuery.trim().toLowerCase();
  if (!query) {
    return nodes;
  }
  const walk = (layer: TreeNode[]): TreeNode[] =>
    layer.flatMap((node) => {
      const children = walk(node.children);
      const selfMatch =
        node.name.toLowerCase().includes(query) ||
        (node.resourceIds ?? []).some((id) => id.toLowerCase().includes(query));
      if (!selfMatch && children.length === 0) {
        return [];
      }
      return [{ ...node, children }];
    });
  return walk(nodes);
}

function sortLayer(layer: TreeNode[]): void {
  layer.sort((a, b) => {
    const rankA = rank(a);
    const rankB = rank(b);
    if (rankA !== rankB) {
      return rankA - rankB;
    }
    return a.name.localeCompare(b.name);
  });
  for (const node of layer) {
    sortLayer(node.children);
  }
}

function rank(node: TreeNode): number {
  if (node.kind === 'dir') {
    const index = TYPE_ORDER.indexOf(node.name);
    return index >= 0 ? index : TYPE_ORDER.length; // 'assets' and friends after types
  }
  return node.kind === 'packfile' ? 9 : 5;
}
