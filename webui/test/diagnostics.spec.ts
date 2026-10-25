import { describe, expect, it } from 'vitest';
import { diagnosticSource } from '../src/lib/diagnostics';

const resource = { pack: 'sample', path: 'items/shared.yml', type: 'item', id: 'second' };
const entry = { code: 'MTR-0001', severity: 'error', message: 'Invalid content', resource: 'sample:second' };

describe('diagnostic source navigation', () => {
  it.each([
    'sample/items/shared.yml:8:3',
    'C:\\server\\plugins\\Materione\\content\\packs\\sample\\items\\shared.yml:8:3',
    '/srv/plugins/Materione/content/packs/sample/items/shared.yml:8:3',
  ])('resolves a known pack and exact resource from %s', (location) => {
    expect(diagnosticSource({ ...entry, location }, ['sample'], [resource])).toEqual({
      resource,
      source: { line: 8, column: 3 },
    });
  });

  it('opens quarantined files not present in the resource index', () => {
    expect(diagnosticSource(
      { ...entry, location: 'sample/items/broken.yml:2' }, ['sample'], [],
    )).toEqual({
      resource: { pack: 'sample', path: 'items/broken.yml', type: 'items', id: 'sample:second' },
      source: { line: 2, column: 1 },
    });
  });

  it.each([
    'other/items/shared.yml:2',
    'sample/../items/shared.yml:2',
    'sample/assets/model.json:2',
    '/unrelated/sample/items/shared.yml:2',
  ])('does not infer a pack from an unrelated or invalid location %s', (location) => {
    expect(diagnosticSource({ ...entry, location }, ['sample'], [resource])).toBeNull();
  });
});
