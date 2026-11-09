/**
 * The owner's vanilla-look sheets (`static/vanilla/*.png`, gui.md sections 11.2 and 12.7 option A) as decoded canvases,
 * keyed by file name without extension: the name a container profile's blit gives. They are studio reference art: the
 * plugin never ships them, because the client draws its own screens. Whatever sheet exists is found here, so adding a
 * container's sheet is dropping the PNG in the folder; a container whose sheet is missing gets the generated diagram
 * (`underlay.ts`). Loaded once per page.
 */
import { decodeImageUrl } from './browser-image';
import type { ArgbCanvas } from './raster/canvas';

const urls = import.meta.glob<string>('../../../static/vanilla/*.png', { eager: true, query: '?url', import: 'default' });

let loading: Promise<Map<string, ArgbCanvas>> | null = null;

function sheetName(path: string): string {
  return path.slice(path.lastIndexOf('/') + 1).replace(/\.png$/, '');
}

/** Every sheet that decodes; one that fails is left out, so its containers fall back to the diagram. */
export function loadUnderlaySheets(): Promise<Map<string, ArgbCanvas>> {
  loading ??= Promise.all(
    Object.entries(urls).map(async ([path, url]) => {
      try {
        return [sheetName(path), await decodeImageUrl(url)] as const;
      } catch {
        return null;
      }
    }),
  ).then((entries) => new Map(entries.filter((entry) => entry !== null)));
  return loading;
}
