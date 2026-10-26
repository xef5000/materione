import { describe, expect, it } from 'vitest';
import { matchingMaterials, materialLabel } from '../src/lib/materials';

describe('server material choices', () => {
  const choices = ['GOLD_NUGGET', 'DIAMOND_SWORD', 'GOLDEN_SWORD', 'PAPER'];
  it('uses human labels while retaining the canonical technical ID', () => {
    expect(materialLabel('GOLD_NUGGET')).toBe('Gold Nugget');
    expect(choices[0]).toBe('GOLD_NUGGET');
  });
  it('searches words in either the label or the ID', () => {
    expect(matchingMaterials(choices, 'gold nug')).toEqual(['GOLD_NUGGET']);
    expect(matchingMaterials(choices, 'DIAMOND_SW')).toEqual(['DIAMOND_SWORD']);
    expect(matchingMaterials(choices, 'sword gold')).toEqual(['GOLDEN_SWORD']);
  });
  it('does not invent choices when the server list is empty or a new ID is unknown', () => {
    expect(matchingMaterials([], '')).toEqual([]);
    expect(matchingMaterials(choices, 'future material')).toEqual([]);
  });
});
