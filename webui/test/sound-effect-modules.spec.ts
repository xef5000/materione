/**
 * Sound and text-effect modules (issue #153): the schemas are the registry's
 * own (recorded in the plugin's golden snapshot), and the preview helpers are
 * pinned against the formulas the generated text shader bakes (pack
 * TextEffectShaders).
 */
import { describe, expect, it } from 'vitest';
import golden from '../../fixtures/golden/editor-demo-snapshot.json';
import type { TypeInfo } from '../src/lib/api';
import { bytesFromBase64, formatDuration, peaksOf } from '../src/lib/audio';
import { effectSpecOf, frameOf, parseHex, toHex } from '../src/lib/effects';
import { categoriesOf, displayFieldsOf, moduleCatalog, usesModules } from '../src/lib/modules';
import { isRenderable } from '../src/lib/studio';

const types = golden.types as unknown as TypeInfo[];
const typeNamed = (name: string): TypeInfo => {
  const found = types.find((type) => type.name === name);
  if (!found) throw new Error(`no ${name} type in the golden snapshot`);
  return found;
};

describe('sound modules', () => {
  const sound = typeNamed('sound');
  const catalog = moduleCatalog(sound, null);

  it('puts the sources, subtitle, volume and pitch on the display card', () => {
    expect(usesModules(sound)).toBe(true);
    expect(displayFieldsOf(sound).others.map((field) => field.id))
      .toEqual(['sounds', 'subtitle', 'volume', 'pitch']);
    expect(displayFieldsOf(sound).others[0]!.uiHint).toBe('sound-sources');
  });

  it('declares the music disc as a module wrapping the jukebox fields, plus category, streaming and range', () => {
    expect(catalog.map((module) => module.id)).toEqual(['category', 'stream', 'attenuation_distance', 'jukebox']);
    const disc = catalog.find((module) => module.id === 'jukebox')!;
    expect(disc.label).toBe('Music disc');
    expect(disc.field?.uiHint).toBe('object-fields');
    expect(disc.field?.nested?.map((param) => param.id))
      .toEqual(['description', 'duration', 'comparator_output', 'range']);
    expect(categoriesOf(catalog)).toEqual(['audio', 'gameplay']);
  });

  it('labels every sound category without renaming it, and offers subtitle suggestions', () => {
    const category = sound.parameters.find((param) => param.id === 'category')!;
    expect(category.options?.map((option) => option.value).sort()).toEqual([...(category.allowedValues ?? [])].sort());
    expect(category.options?.find((option) => option.value === 'record')?.label).toBe('Jukebox / note blocks');
    const subtitle = sound.parameters.find((param) => param.id === 'subtitle')!;
    expect(subtitle.uiHint).toBe('presets');
    expect(subtitle.options?.length).toBeGreaterThan(2);
  });

  it('keeps the rarely-wanted fields advanced and every field renderable', () => {
    for (const id of ['weight', 'preload', 'replace', 'replace_vanilla']) {
      expect(sound.parameters.find((param) => param.id === id)?.advanced).toBe(true);
    }
    for (const param of sound.parameters) {
      expect(isRenderable(param), `${param.id} (${param.uiHint})`).toBe(true);
    }
  });
});

describe('effect modules', () => {
  const effect = typeNamed('effect');

  it('the kind and its strength are the display card; the palette is the one module', () => {
    expect(displayFieldsOf(effect).others.map((field) => field.id)).toEqual(['kind', 'speed', 'amplitude']);
    expect(moduleCatalog(effect, null).map((module) => [module.id, module.label])).toEqual([['colors', 'Palette']]);
    expect(effect.parameters.find((param) => param.id === 'kind')?.options?.map((option) => option.label))
      .toEqual(['Rainbow', 'Wave', 'Shake', 'Pulse']);
  });

  it('every effect field is renderable', () => {
    for (const param of effect.parameters) {
      expect(isRenderable(param), `${param.id} (${param.uiHint})`).toBe(true);
    }
  });
});

describe('audio helpers', () => {
  it('draws the peak of each slice, normalised to 0..1', () => {
    expect(peaksOf([0, 0.5, -1, 0.25], 2)).toEqual([0.5, 1]);
    expect(peaksOf([], 3)).toEqual([0, 0, 0]);
    expect(peaksOf([2, -3], 1)).toEqual([1]);
  });

  it('formats a duration under and over a minute', () => {
    expect(formatDuration(0.5)).toBe('0.5 s');
    expect(formatDuration(12.34)).toBe('12.3 s');
    expect(formatDuration(65)).toBe('1:05');
    expect(formatDuration(Number.NaN)).toBe('');
  });

  it('reads base64 bytes', () => {
    expect([...new Uint8Array(bytesFromBase64('AQID'))]).toEqual([1, 2, 3]);
  });
});

describe('effect preview formulas', () => {
  const base = { kind: 'wave' as const, speed: 2, amplitude: 1.5, colors: [] as string[] };

  it('reads an authored effect with the type\'s defaults', () => {
    expect(effectSpecOf({ kind: 'Pulse' })).toEqual({ kind: 'pulse', speed: 3, amplitude: 1, colors: [] });
    expect(effectSpecOf({ kind: 'nonsense', colors: ['#FF0000', 7] }).kind).toBe('rainbow');
    expect(effectSpecOf({ colors: ['#FF0000', 7] }).colors).toEqual(['#FF0000']);
  });

  it('wave bobs with the shader\'s sine, shake jitters in steps, pulse scales brightness', () => {
    // pos.y += sin(t * speed * TAU + x * 0.8) * amplitude
    expect(frameOf(base, 0.125, 0).dy).toBeCloseTo(Math.sin(0.125 * 2 * Math.PI * 2) * 1.5, 6);
    expect(frameOf(base, 0, 0).dy).toBeCloseTo(0, 6);
    // shake holds one phase for 1 / (speed * 20) seconds
    const shake = { ...base, kind: 'shake' as const };
    expect(frameOf(shake, 0.001, 0)).toEqual(frameOf(shake, 0.02, 0));
    expect(frameOf(shake, 0.001, 0)).not.toEqual(frameOf(shake, 0.06, 0));
    // brightness *= 1 + amplitude * 0.35 * sin(t * speed * TAU)
    const pulse = { ...base, kind: 'pulse' as const };
    expect(frameOf(pulse, 0.125, 0).brightness).toBeCloseTo(1 + 1.5 * 0.35, 6);
  });

  it('rainbow mixes its authored stops, wrapping the last to the first', () => {
    const rainbow = { kind: 'rainbow' as const, speed: 0, amplitude: 0, colors: ['#FF0000', '#0000FF'] };
    expect(frameOf(rainbow, 0, 0).rgb).toEqual([1, 0, 0]);
    // t = fract(0) = 0, so a stop is exact; at 0.5 s, t = 0.125: a quarter of the way through the first segment
    const halfway = { ...rainbow, speed: 1, amplitude: 0 }; // t = fract(seconds * 0.25)
    const [r, g, b] = frameOf(halfway, 0.5, 0).rgb!;
    expect(r).toBeCloseTo(0.75, 6);
    expect(g).toBeCloseTo(0, 6);
    expect(b).toBeCloseTo(0.25, 6);
  });

  it('with no stops, rainbow cycles the full spectrum on the cosine palette', () => {
    const [r, g, b] = frameOf({ ...base, kind: 'rainbow' }, 0, 0).rgb!;
    expect(r).toBeCloseTo(1, 6);
    expect(g).toBeCloseTo(0.5 + 0.5 * Math.cos(2.094), 6);
    expect(b).toBeCloseTo(0.5 + 0.5 * Math.cos(4.188), 6);
  });

  it('parses and writes #RRGGBB', () => {
    expect(parseHex('#FF8000')).toEqual([1, 128 / 255, 0]);
    expect(parseHex('FF8000')).toBeNull();
    expect(parseHex('#FF80')).toBeNull();
    expect(toHex(255, 128, 0)).toBe('#FF8000');
    expect(toHex(300, -4, 0.4)).toBe('#FF0000');
  });
});
