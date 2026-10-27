/**
 * Motion for Svelte transitions and list animation (docs/architecture/web.md,
 * "Motion"). The durations are the `--motion-*` tokens in src/app.css: this
 * reads them, so CSS and Svelte share one source. Under prefers-reduced-motion
 * the tokens are 0ms, so every transition here completes at once.
 */

export type MotionToken = 'fast' | 'base' | 'slow';

/** A duration in milliseconds from the `--motion-<token>` custom property (0 when unset). */
export function motionMs(token: MotionToken): number {
  if (typeof document === 'undefined') return 0;
  const raw = getComputedStyle(document.documentElement).getPropertyValue(`--motion-${token}`);
  const ms = Number.parseFloat(raw);
  return Number.isFinite(ms) ? ms : 0;
}

/**
 * Lists longer than this do not animate their rows. Svelte's `animate:flip` reads
 * every animated row's box before and after each update, so a 1,000-row pack would
 * pay thousands of layout reads per keystroke. Above the limit the rows update
 * without motion; the list stays at 60 fps.
 */
export const ROW_MOTION_LIMIT = 200;

export function rowsAnimate(count: number): boolean {
  return count <= ROW_MOTION_LIMIT;
}

/** Delay for item `index` of a first reveal: 18ms apart, capped so the tail does not lag. */
export function staggerDelay(index: number, step = 18, cap = 10): number {
  return Math.min(index, cap) * step;
}

/** Whether the user asked for less motion (the OS setting, or Playwright's `reducedMotion`). */
export function prefersReducedMotion(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** `scrollIntoView` behavior: smooth unless reduced motion is requested (the CSS rule cannot reach JS scrolls). */
export function scrollBehavior(): ScrollBehavior {
  return prefersReducedMotion() ? 'auto' : 'smooth';
}
