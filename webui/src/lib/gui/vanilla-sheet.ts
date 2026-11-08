/**
 * The owner's vanilla chest sheet (`static/vanilla/generic_54.png`, gui.md section 11.2) as a decoded canvas. It is
 * studio reference art: the plugin never ships it, because the client draws its own chest. Loaded once per page.
 */
import { decodeImageUrl } from './browser-image';
import type { ArgbCanvas } from './raster/canvas';
import sheetUrl from '../../../static/vanilla/generic_54.png?url';

let loading: Promise<ArgbCanvas> | null = null;

export function loadVanillaSheet(): Promise<ArgbCanvas> {
  loading ??= decodeImageUrl(sheetUrl);
  // a failed load is retried by the next opened studio
  loading.catch(() => (loading = null));
  return loading;
}
