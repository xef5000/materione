import { describe, expect, it } from 'vitest';
import { diagnosticNames, diagnosticRepairTarget, diagnosticSource } from '../src/lib/diagnostics';

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

describe('structured diagnostic targets', () => {
  const ruby = { pack: 'sample', type: 'items', path: 'items/gems.yml', id: 'ruby' };
  const rubyCase = { pack: 'sample', type: 'items', path: 'items/gems.yml', id: 'ruby_case' };
  const other = { pack: 'other', type: 'items', path: 'items/gems.yml', id: 'ruby' };

  it('names a resource exactly, never by substring or another pack', () => {
    const problem = { code: 'MTR-0010', severity: 'warn', message: 'quarantined', resource: 'sample:ruby' };
    expect(diagnosticNames(problem, ruby)).toBe(true);
    expect(diagnosticNames(problem, rubyCase)).toBe(false);
    expect(diagnosticNames(problem, other)).toBe(false);
  });

  it('opens the targeted field of the named resource', () => {
    expect(diagnosticRepairTarget({
      code: 'MTR-0009', severity: 'error', message: "unresolved texture 'items/ruby'",
      resource: 'sample:ruby', target: { field: ['texture'] },
    }, [rubyCase, ruby])).toEqual({ resource: ruby, field: 'texture' });
    expect(diagnosticRepairTarget({ code: 'MTR-0010', severity: 'warn', message: 'x', resource: 'sample:ruby' }, [ruby]))
      .toEqual({ resource: ruby });
    expect(diagnosticRepairTarget({ code: 'MTR-0001', severity: 'warn', message: 'x' }, [ruby])).toBeNull();
    expect(diagnosticRepairTarget({ code: 'MTR-0010', severity: 'warn', message: 'x', resource: 'sample:gone' }, [ruby])).toBeNull();
  });
});
