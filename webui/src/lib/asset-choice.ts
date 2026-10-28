/**
 * What the item studio's asset picker offers (issue 173): a pack's textures or
 * models as stored references, with the same conventions the form pickers used
 * (a texture is its path under assets/textures/ without extension; a model is
 * `<pack>:<path>` without extension). Pure, so the picker and its tests agree.
 */
import type { AssetRef } from './api';

export type AssetKind = 'texture' | 'model';

export interface AssetChoice {
  /** The value the owning field stores. */
  stored: string;
  /** The asset's path inside the pack, for bytes and "Used by". */
  path: string;
  /** Folder under the kind's root (`item`, or '' at the root). */
  dir: string;
  /** File name without extension. */
  base: string;
}

const ROOT: Record<AssetKind, string> = { texture: 'assets/textures/', model: 'assets/models/' };

export function assetChoices(assets: readonly AssetRef[], pack: string, kind: AssetKind): AssetChoice[] {
  const root = ROOT[kind];
  const out: AssetChoice[] = [];
  for (const asset of assets) {
    if (!asset.path.startsWith(root)) continue;
    const image = asset.kind === 'png' || asset.kind === 'jpg' || asset.kind === 'jpeg';
    if (kind === 'texture' ? !image : asset.kind !== 'json') continue;
    const relative = asset.path.slice(root.length).replace(kind === 'texture' ? /\.(png|jpe?g)$/i : /\.json$/i, '');
    const slash = relative.lastIndexOf('/');
    out.push({
      stored: kind === 'texture' ? relative : `${pack}:${relative}`,
      path: asset.path,
      dir: slash < 0 ? '' : relative.slice(0, slash),
      base: relative.slice(slash + 1),
    });
  }
  return out.sort((a, b) => a.dir.localeCompare(b.dir) || a.base.localeCompare(b.base));
}

/** A stored reference without its own pack prefix, so `fixture_pack:item/x` and `item/x` compare equal. */
export function normalizedReference(value: string, pack: string, kind: AssetKind): string {
  const trimmed = value.trim();
  if (kind === 'model') return trimmed.includes(':') ? trimmed : trimmed ? `${pack}:${trimmed}` : '';
  return trimmed.startsWith(`${pack}:`) ? trimmed.slice(pack.length + 1) : trimmed;
}

export function matchesChoice(choice: AssetChoice, query: string): boolean {
  const needle = query.trim().toLowerCase();
  return !needle || `${choice.dir}/${choice.base}`.toLowerCase().includes(needle);
}

/** A texture path under `dir/name` that no asset uses yet: `item/ruby`, `item/ruby_2`, … */
export function freshTextureReference(assets: readonly AssetRef[], dir: string, name: string): string {
  const taken = new Set(assets.map((asset) => asset.path.toLowerCase()));
  const exists = (reference: string) =>
    ['png', 'jpg', 'jpeg'].some((ext) => taken.has(`assets/textures/${reference}.${ext}`.toLowerCase()));
  const base = `${dir}/${name}`;
  if (!exists(base)) return base;
  for (let n = 2; ; n++) if (!exists(`${base}_${n}`)) return `${base}_${n}`;
}
