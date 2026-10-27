/**
 * The design-token invariant (docs/architecture/web.md, "Design tokens"): every
 * color, font size and corner radius the editor draws comes from the custom
 * properties in src/app.css. A literal here is a drift waiting to happen: it
 * will not follow a palette change, and it breaks the light/dark or contrast
 * work built on the tokens. The check runs on source, so it fails in CI before
 * a screenshot would show the drift.
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = fileURLToPath(new URL('../src', import.meta.url));
const ROOT = fileURLToPath(new URL('..', import.meta.url));

/**
 * Color literals that are data rather than styling, by file. Empty on purpose:
 * Minecraft's legacy palette and texture pixels live in app.css or in pixel
 * data, never in component code. Add an entry only with a reason beside it.
 */
const COLOR_ALLOWLIST: Record<string, string> = {};

const COLOR = /#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(/g;
/** Pixel sizes for type and corners in component styles; the scales are in app.css. */
const SCALED_PX = /\b(font-size|border-radius)\s*:[^;{}]*?\b\d+(?:\.\d+)?px\b/g;

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.(svelte|ts)$/.test(name) ? [path] : [];
  });
}

function lines(text: string, pattern: RegExp): { line: number; text: string }[] {
  const hits: { line: number; text: string }[] = [];
  text.split('\n').forEach((text, index) => {
    if (new RegExp(pattern.source, 'i').test(text)) hits.push({ line: index + 1, text: text.trim() });
  });
  return hits;
}

const files = sources(SRC).map((path) => ({
  name: relative(ROOT, path).replaceAll('\\', '/'),
  text: readFileSync(path, 'utf8'),
}));

describe('design tokens', () => {
  it('scans the editor sources, not an empty directory', () => {
    expect(files.length).toBeGreaterThan(50);
    expect(files.some((file) => file.name === 'src/main.ts')).toBe(true);
  });

  it('keeps raw color literals out of components and canvas code', () => {
    const offenders = files.flatMap((file) => {
      if (COLOR_ALLOWLIST[file.name]) return [];
      return lines(file.text, COLOR).map((hit) => `${file.name}:${hit.line}  ${hit.text}`);
    });
    expect(offenders, 'use a var(--token) in CSS, or token() from src/lib/tokens.ts in canvas code').toEqual([]);
  });

  it('keeps font sizes and corner radii on the type and radius scales', () => {
    const offenders = files
      .filter((file) => file.name.endsWith('.svelte'))
      .flatMap((file) => lines(file.text, SCALED_PX).map((hit) => `${file.name}:${hit.line}  ${hit.text}`));
    expect(offenders, 'use var(--text-*) or var(--radius*) from src/app.css').toEqual([]);
  });

  it('defines every token the editor reads', () => {
    const css = readFileSync(join(SRC, 'app.css'), 'utf8');
    const defined = new Set([...css.matchAll(/(--[\w-]+)\s*:/g)].map((match) => match[1] ?? ''));
    const used = new Set(
      files.flatMap((file) => [...file.text.matchAll(/var\((--[\w-]+)/g)].map((match) => match[1] ?? '')),
    );
    // --cols is set per element at runtime; --legacy-* is built from the legacy palette keys.
    const missing = [...used].filter((name) => !defined.has(name) && name !== '--cols' && !name.startsWith('--legacy-'));
    expect(missing).toEqual([]);
  });
});
