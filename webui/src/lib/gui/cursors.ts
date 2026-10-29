/**
 * Cursors for the studio's paint tools: small outlined glyphs (white fill, black edge, so they read
 * on any background) as inline SVG data URIs, each with the hotspot at the tool's working point and
 * a stock cursor as the fallback. Pure strings; the canvas applies them with `cursor:`.
 */
function svg(body: string): string {
  const encoded = encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke-linejoin="round" stroke-linecap="round">${body}</svg>`,
  );
  return `url("data:image/svg+xml,${encoded}")`;
}

const EDGE = 'stroke="black" stroke-width="3.2"';
const INK = 'stroke="white" stroke-width="1.4"';

function outlined(path: string, fill = 'none'): string {
  return `<path d="${path}" ${EDGE} fill="none"/><path d="${path}" ${INK} fill="${fill}"/>`;
}

/** An eyedropper: the tip (bottom-left) is the hotspot. */
export const DROPPER_CURSOR = `${svg(
  outlined('M4 20l1.2-4.2 8.6-8.6 3 3-8.6 8.6z', 'white') + outlined('M13.4 7.6l2.8-2.8a2.1 2.1 0 013 3l-2.8 2.8z', 'white'),
)} 4 20, crosshair`;

/** A paint bucket pouring: the drop (bottom-left) is the hotspot. */
export const BUCKET_CURSOR = `${svg(
  outlined('M7 12.5l5.5-5.5 7 7-5.5 5.5z', 'white') +
    outlined('M12.5 7C12.5 3.5 8 3.5 8 7.5') +
    outlined('M4.4 20a1.6 1.6 0 003.2 0c0-1.4-1.6-2.8-1.6-2.8S4.4 18.6 4.4 20z', 'white'),
)} 6 20, crosshair`;

/** A crosshair that stays visible on both light and dark art (the brush footprint is drawn on the canvas). */
export const BRUSH_CURSOR = `${svg(
  outlined('M12 3v6M12 15v6M3 12h6M15 12h6'),
)} 12 12, crosshair`;
