/**
 * Minecraft's text colors as game data. The editor computes gradients and
 * shadows from these values, so they are numbers here, not design tokens;
 * the matching swatches are drawn with the `--legacy-*` tokens in app.css.
 * Allowlisted in test/design-tokens.spec.ts for that reason.
 */

/** The 16 named colors in legacy-code order (`&0` … `&f`). */
export const NAMED_COLORS = [
  { name: 'black', code: '0', hex: '#000000', label: 'Black' },
  { name: 'dark_blue', code: '1', hex: '#0000aa', label: 'Dark blue' },
  { name: 'dark_green', code: '2', hex: '#00aa00', label: 'Dark green' },
  { name: 'dark_aqua', code: '3', hex: '#00aaaa', label: 'Dark aqua' },
  { name: 'dark_red', code: '4', hex: '#aa0000', label: 'Dark red' },
  { name: 'dark_purple', code: '5', hex: '#aa00aa', label: 'Dark purple' },
  { name: 'gold', code: '6', hex: '#ffaa00', label: 'Gold' },
  { name: 'gray', code: '7', hex: '#aaaaaa', label: 'Gray' },
  { name: 'dark_gray', code: '8', hex: '#555555', label: 'Dark gray' },
  { name: 'blue', code: '9', hex: '#5555ff', label: 'Blue' },
  { name: 'green', code: 'a', hex: '#55ff55', label: 'Green' },
  { name: 'aqua', code: 'b', hex: '#55ffff', label: 'Aqua' },
  { name: 'red', code: 'c', hex: '#ff5555', label: 'Red' },
  { name: 'light_purple', code: 'd', hex: '#ff55ff', label: 'Light purple' },
  { name: 'yellow', code: 'e', hex: '#ffff55', label: 'Yellow' },
  { name: 'white', code: 'f', hex: '#ffffff', label: 'White' },
] as const;

export type NamedColor = (typeof NAMED_COLORS)[number]['name'];

/** MiniMessage's spelling aliases for the named colors. */
export const COLOR_ALIASES: Record<string, NamedColor> = { grey: 'gray', dark_grey: 'dark_gray' };

/** The starting stops of a new gradient: Materione's own orange to gold. */
export const DEFAULT_GRADIENT = ['#f9731e', '#ffd27a'];

/** The color offered first in the custom color input. */
export const DEFAULT_CUSTOM_COLOR = '#f9731e';

const byName = new Map<string, (typeof NAMED_COLORS)[number]>(NAMED_COLORS.map((color) => [color.name, color]));

export function namedColor(name: string): (typeof NAMED_COLORS)[number] | undefined {
  return byName.get(COLOR_ALIASES[name] ?? name);
}

/** `#rrggbb` for a named or hex color; undefined for anything else. */
export function hexOf(color: string): string | undefined {
  if (/^#[0-9a-f]{6}$/i.test(color)) return color.toLowerCase();
  return namedColor(color)?.hex;
}

function channels(hex: string): [number, number, number] {
  const value = Number.parseInt(hex.slice(1), 16);
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

function toHex([r, g, b]: [number, number, number]): string {
  return `#${((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1)}`;
}

/**
 * The color of character `index` of `size` in a gradient, matching
 * Adventure's gradient tag: stops are spread evenly, the first character
 * takes the first stop and the last character the last.
 */
export function gradientColor(stops: readonly string[], index: number, size: number): string {
  const hexes = stops.map((stop) => hexOf(stop) ?? '#ffffff');
  if (hexes.length === 0) return '#ffffff';
  if (hexes.length === 1 || size <= 1) return hexes[0]!;
  const position = index * ((hexes.length - 1) / (size - 1));
  const low = Math.floor(position);
  const high = Math.min(Math.ceil(position), hexes.length - 1);
  const t = position - low;
  const a = channels(hexes[low]!);
  const b = channels(hexes[high]!);
  return toHex([0, 1, 2].map((c) => Math.round(a[c]! + t * (b[c]! - a[c]!))) as [number, number, number]);
}
