/**
 * The text-effect preview (issue 153). These are the same formulas the
 * generated `rendertype_text` include bakes per effect (pack
 * `TextEffectShaders`, runtime-systems §6): wave and shake displace a
 * character, rainbow and pulse recolor it, and time is real seconds. Positions
 * are in text pixels, one character about six of them, so the picture is a
 * faithful approximation of the motion, not a client render.
 */

export type EffectKind = 'rainbow' | 'wave' | 'shake' | 'pulse';

export interface EffectSpec {
  kind: EffectKind;
  speed: number;
  amplitude: number;
  /** `#RRGGBB` rainbow stops; fewer than two means the full-spectrum cosine palette. */
  colors: string[];
}

export interface EffectFrame {
  /** Displacement in text pixels; positive `dy` is up. */
  dx: number;
  dy: number;
  /** Replacement text color, or null to keep the base color. */
  rgb: [number, number, number] | null;
  /** Brightness multiplier on the base color. */
  brightness: number;
}

const TAU = Math.PI * 2;
export const KINDS: readonly EffectKind[] = ['rainbow', 'wave', 'shake', 'pulse'];

function fract(value: number): number {
  return value - Math.floor(value);
}

function hash(n: number): number {
  return fract(Math.sin(n) * 43758.5453123);
}

/** `#RRGGBB` as 0..1 channels; null when it is not exactly that. */
export function parseHex(color: string): [number, number, number] | null {
  const match = /^#([0-9a-fA-F]{6})$/.exec(color.trim());
  if (!match) return null;
  const value = parseInt(match[1]!, 16);
  return [((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255];
}

/** 0..255 channels as `#RRGGBB`. */
export function toHex(red: number, green: number, blue: number): string {
  const part = (channel: number) =>
    Math.max(0, Math.min(255, Math.round(channel))).toString(16).padStart(2, '0');
  return `#${part(red)}${part(green)}${part(blue)}`.toUpperCase();
}

function mix(
  a: [number, number, number],
  b: [number, number, number],
  amount: number,
): [number, number, number] {
  return [a[0] + (b[0] - a[0]) * amount, a[1] + (b[1] - a[1]) * amount, a[2] + (b[2] - a[2]) * amount];
}

/** The effect as authored (with the type's defaults); unreadable values fall back. */
export function effectSpecOf(values: Record<string, unknown>): EffectSpec {
  const kind = String(values.kind ?? 'rainbow').toLowerCase();
  const number = (value: unknown, fallback: number) =>
    typeof value === 'number' && Number.isFinite(value) ? value : fallback;
  return {
    kind: (KINDS as readonly string[]).includes(kind) ? kind as EffectKind : 'rainbow',
    speed: number(values.speed, 3),
    amplitude: number(values.amplitude, 1),
    colors: Array.isArray(values.colors)
      ? values.colors.filter((color): color is string => typeof color === 'string')
      : [],
  };
}

/** One character's frame at `seconds`, `x` text pixels from the start of the line. */
export function frameOf(spec: EffectSpec, seconds: number, x: number): EffectFrame {
  const none: EffectFrame = { dx: 0, dy: 0, rgb: null, brightness: 1 };
  switch (spec.kind) {
    case 'wave':
      return { ...none, dy: Math.sin(seconds * spec.speed * TAU + x * 0.8) * spec.amplitude };
    case 'shake': {
      const phase = Math.floor(seconds * spec.speed * 20);
      return {
        ...none,
        dx: (hash(phase) - 0.5) * 1.5 * spec.amplitude,
        dy: (hash(phase + 91.7) - 0.5) * 1.5 * spec.amplitude,
      };
    }
    case 'pulse':
      return { ...none, brightness: 1 + spec.amplitude * 0.35 * Math.sin(seconds * spec.speed * TAU) };
    default: {
      const stops = spec.colors.map(parseHex).filter((stop): stop is [number, number, number] => stop !== null);
      if (stops.length >= 2) {
        const t = fract(seconds * spec.speed * 0.25 + x * spec.amplitude * 0.1);
        const count = stops.length;
        const index = Math.min(count - 1, Math.floor(t * count));
        return { ...none, rgb: mix(stops[index]!, stops[(index + 1) % count]!, (t - index / count) * count) };
      }
      const phase = TAU * (seconds * spec.speed * 0.1 + x * spec.amplitude * 0.05);
      return {
        ...none,
        rgb: [0, 2.094, 4.188].map((shift) => 0.5 + 0.5 * Math.cos(phase + shift)) as [number, number, number],
      };
    }
  }
}
