import type { AssetRef, ResourceSummary } from './api';
import { thumbnailPath } from './blockbench/protocol';

/** Resolve only known local assets; namespace-to-pack resolution belongs to the server. */
export function localStudioVisual(
  pack: string,
  texture: string,
  model: string,
  assets: readonly AssetRef[],
): ResourceSummary['visual'] {
  if (model && (!model.includes(':') || model.startsWith(`${pack}:`))) {
    const path = thumbnailPath(model);
    if (assets.some((asset) => asset.path === path)) return { pack, path, kind: 'model-thumbnail' };
  }
  if (texture.includes(':')) {
    if (!texture.startsWith(`${pack}:`)) return;
    texture = texture.slice(pack.length + 1);
  }
  if (!texture) return;
  const path = `assets/textures/${texture.replace(/\.(png|jpe?g)$/i, '')}`;
  const asset = assets.find((entry) => ['png', 'jpg', 'jpeg'].some((ext) => entry.path === `${path}.${ext}`));
  return asset ? { pack, path: asset.path, kind: 'texture' } : undefined;
}
