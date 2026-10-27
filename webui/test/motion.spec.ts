/**
 * The motion policy in src/lib/motion.ts (docs/architecture/web.md, "Motion"). The
 * durations are CSS tokens and are read in the browser; these checks cover the pure
 * rules: when rows animate, and how a first reveal staggers.
 */
import { describe, expect, it } from 'vitest';
import { ROW_MOTION_LIMIT, motionMs, prefersReducedMotion, rowsAnimate, scrollBehavior, staggerDelay } from '../src/lib/motion';

describe('motion', () => {
  it('animates rows up to the limit and not beyond it, so a 1,000-row pack never pays for flip', () => {
    expect(rowsAnimate(0)).toBe(true);
    expect(rowsAnimate(ROW_MOTION_LIMIT)).toBe(true);
    expect(rowsAnimate(ROW_MOTION_LIMIT + 1)).toBe(false);
    expect(rowsAnimate(1000)).toBe(false);
  });

  it('staggers a first reveal 18ms apart, capped so the tail does not lag', () => {
    expect(staggerDelay(0)).toBe(0);
    expect(staggerDelay(3)).toBe(54);
    expect(staggerDelay(10)).toBe(180);
    expect(staggerDelay(40)).toBe(180);
  });

  it('reads no motion outside a browser, so server-side or test code never waits on a transition', () => {
    expect(motionMs('base')).toBe(0);
    expect(prefersReducedMotion()).toBe(false);
    expect(scrollBehavior()).toBe('smooth');
  });
});
