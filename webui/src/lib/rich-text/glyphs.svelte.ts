/**
 * Glyph pictures for rich text chips: the glyph resources of the open pack and
 * their texture thumbnails, loaded on demand through the same thumbnail queue
 * the content browser uses.
 */
import { SvelteMap } from 'svelte/reactivity';
import type { ResourceSummary } from '../api';
import { session, workspace } from '../state.svelte';
import { loadThumbnail } from '../thumbnails';

/** Loaded glyph textures by the id written in `<glyph:id>` (bare and pack-qualified). */
export const glyphImages = new SvelteMap<string, string>();
const requested = new Set<string>();

/** The glyph resources the open pack offers, for the insert-glyph picker. */
export function glyphResources(): ResourceSummary[] {
  return workspace.summaries.filter((summary) => summary.type === 'glyphs' || summary.type === 'glyph');
}

function glyphSummary(id: string): ResourceSummary | undefined {
  const [pack, bare] = id.includes(':') ? id.split(':', 2) : [undefined, id];
  return glyphResources().find((summary) => summary.id === bare && (!pack || summary.pack === pack));
}

/** Starts loading the textures for these glyph ids; chips redraw as they arrive. */
export function requestGlyphImages(ids: Iterable<string>): void {
  const channel = session.channel;
  if (!channel) return;
  for (const id of ids) {
    const visual = glyphSummary(id)?.visual;
    const key = `${id}\u0000${visual?.pack}/${visual?.path}`;
    if (!visual || visual.kind !== 'texture' || requested.has(key)) continue;
    requested.add(key);
    loadThumbnail(channel, visual.pack, visual.path, workspace.visualVersion).then(
      (url) => glyphImages.set(id, url),
      () => requested.delete(key),
    );
  }
}
