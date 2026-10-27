/**
 * Reads one design token from :root. Canvas drawing cannot use `var()`, so it
 * asks the document instead: app.css stays the only place colors are written
 * (see docs/architecture/web.md, "Design tokens"), and a token change reaches
 * the canvases too. Read once per paint, not per pixel.
 */
export function token(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}
