/**
 * Classification and grants (codemode.md section 5.6, C10, C11; issue 346): the pure client half of D13's approval rule,
 * tested exhaustively. The four rows of the 5.6 table, every operation kind, the fail-closed cases, and properties over
 * random operation lists.
 */
import { describe, expect, it } from 'vitest';
import { classify, type ReportDocument } from '../src/lib/codemode/host/classify';
import { decide, gestureFromEvent, GestureError, GrantSet } from '../src/lib/codemode/host/grants';
import { isObjectId, objectId, type ObjectId } from '../src/lib/codemode/host/objects';
import type { StagedOperation } from '../src/lib/codemode/core/types';
import { click, op } from './helpers/host';

const id = (path: string): ObjectId => objectId('demo', path) as ObjectId;
const none = new Set<string>();
const run = (operations: StagedOperation[], documents: ReportDocument[] = [], sessionCreated: ReadonlySet<string> = none) =>
  classify({ pack: 'demo', operations, documents, sessionCreated });
const doc = (path: string, kind: string, operations: number[]): ReportDocument => ({ path, kind, operations });

describe('object ids', () => {
  it('are exact, canonical pack:path strings', () => {
    expect(objectId('demo', 'items/sword.yml')).toBe('demo:items/sword.yml');
    for (const bad of ['', '/items/a.yml', 'items//a.yml', 'items/../a.yml', './a.yml', 'items\\a.yml', 'a\u0000.yml', 'a:b.yml']) {
      expect(objectId('demo', bad), bad).toBeNull();
    }
    expect(objectId('..', 'a.yml')).toBeNull();
    expect(objectId('de mo', 'a.yml')).toBeNull();
    expect(objectId('demo', 7)).toBeNull();
    expect(isObjectId('demo:items/sword.yml')).toBe(true);
    expect(isObjectId('demo:items/*.yml')).toBe(true); // a star is a legal file name; it is matched exactly, never expanded
    expect(isObjectId('demo')).toBe(false);
    expect(isObjectId('demo:../x')).toBe(false);
    expect(isObjectId(null)).toBe(false);
  });
});

describe('classification, by operation kind', () => {
  it('create touches nothing that existed', () => {
    const result = run([op('create', { type: 'items', id: 'ruby' })], [doc('items/ruby.yml', 'created', [0])]);
    expect(result.operations[0]).toMatchObject({ effect: 'new', objects: [] });
    expect(result.existing).toEqual([]);
    expect(result.created).toEqual([id('items/ruby.yml')]);
    expect(result.blockers).toEqual([]);
  });

  it.each(['setFields', 'logicEdit', 'guiEdit', 'detachTemplate', 'delete'])('%s touches the document it names', (kind) => {
    const result = run([op(kind, { path: 'items/sword.yml', rev: 1 })], [doc('items/sword.yml', kind === 'delete' ? 'deleted' : 'changed', [0])]);
    expect(result.operations[0]).toMatchObject({ effect: 'existing', objects: [id('items/sword.yml')] });
    expect(result.existing).toEqual([id('items/sword.yml')]);
  });

  it('duplicate and saveAsTemplate only read their source: they create, and touch nothing existing', () => {
    const result = run(
      [op('duplicate', { fromPath: 'items/sword.yml', id: 'copy' }), op('saveAsTemplate', { fromPath: 'items/sword.yml', templateId: 't' })],
      [doc('items/copy.yml', 'created', [0]), doc('templates/t.yml', 'created', [1])],
    );
    expect(result.operations.map((entry) => entry.effect)).toEqual(['new', 'new']);
    expect(result.existing).toEqual([]);
  });

  it('move removes its source (existing) and creates its target (new)', () => {
    const result = run(
      [op('move', { fromPath: 'items/a.yml', toPath: 'items/b.yml', rev: 2 })],
      [doc('items/a.yml', 'deleted', [0]), doc('items/b.yml', 'created', [0])],
    );
    expect(result.operations[0]).toMatchObject({ effect: 'existing', objects: [id('items/a.yml')] });
    expect(result.created).toEqual([id('items/b.yml')]);
  });

  it('the dry run is the authority for what changes: a changed source of a duplicate is touched', () => {
    const result = run(
      [op('duplicate', { fromPath: 'items/sword.yml', id: 'copy' })],
      [doc('items/copy.yml', 'created', [0]), doc('items/sword.yml', 'changed', [0])],
    );
    expect(result.operations[0]).toMatchObject({ effect: 'existing', objects: [id('items/sword.yml')] });
  });

  it('a document the report changes that no operation claims is still a touched object', () => {
    const result = run([op('create', { id: 'x' })], [doc('items/x.yml', 'created', [0]), doc('guis/shop.yml', 'changed', [])]);
    expect(result.existing).toEqual([id('guis/shop.yml')]);
  });
});

describe('new content created earlier', () => {
  it('a later operation on a document created earlier in the same proposal is new', () => {
    const result = run(
      [op('create', { id: 'ruby' }), op('logicEdit', { path: 'items/ruby.yml' }), op('setFields', { path: 'items/ruby.yml' })],
      [doc('items/ruby.yml', 'created', [0, 1, 2])],
    );
    expect(result.operations.map((entry) => entry.effect)).toEqual(['new', 'new', 'new']);
    expect(result.existing).toEqual([]);
  });

  it('an operation before the one that creates the document is not new (fail closed)', () => {
    const reversed = run(
      [op('setFields', { path: 'items/ruby.yml' }), op('create', { id: 'ruby' })],
      [doc('items/ruby.yml', 'created', [1])],
    );
    expect(reversed.operations[0]).toMatchObject({ effect: 'existing' });
  });

  it('a move target is new for the operations after it', () => {
    const result = run(
      [op('move', { fromPath: 'items/a.yml', toPath: 'items/b.yml' }), op('setFields', { path: 'items/b.yml' })],
      [doc('items/a.yml', 'deleted', [0]), doc('items/b.yml', 'created', [0, 1])],
    );
    expect(result.operations.map((entry) => entry.effect)).toEqual(['existing', 'new']);
  });

  it('what this session created in an earlier applied proposal is new', () => {
    const created = new Set<string>([id('items/ruby.yml')]);
    const result = run([op('setFields', { path: 'items/ruby.yml' })], [doc('items/ruby.yml', 'changed', [0])], created);
    expect(result.operations[0]!.effect).toBe('new');
    expect(result.existing).toEqual([]);
    const deleted = run([op('delete', { path: 'items/ruby.yml' })], [doc('items/ruby.yml', 'deleted', [0])], created);
    expect(deleted.existing).toEqual([]);
  });

  it('a document that existed at the session start stays existing, however it is named later', () => {
    const created = new Set<string>([id('items/ruby.yml')]);
    const result = run([op('setFields', { path: 'items/sword.yml' })], [doc('items/sword.yml', 'changed', [0])], created);
    expect(result.existing).toEqual([id('items/sword.yml')]);
    // a different spelling is a different object: never matched to the created one
    const spelled = run([op('setFields', { path: 'Items/ruby.yml' })], [], created);
    expect(spelled.existing).toEqual([id('Items/ruby.yml')]);
  });
});

describe('fail closed', () => {
  it('an unknown operation kind is a blocker, whatever it carries', () => {
    for (const kind of ['frobnicate', '', '__proto__', 'constructor', 'toString']) {
      const result = run([op(kind, { path: 'items/sword.yml' })]);
      expect(result.operations[0]!.effect, kind).toBe('unknown');
      expect(result.blockers, kind).toHaveLength(1);
      expect(decide(result, new GrantSet(), false).allowed, kind).toBe(false);
    }
  });

  it('a known kind without a usable path is a blocker, not "new"', () => {
    for (const path of [undefined, null, 7, '', '../x.yml', 'a//b.yml']) {
      const result = run([op('setFields', { path })]);
      expect(result.operations[0]!.effect, String(path)).toBe('unknown');
      expect(result.blockers.length).toBeGreaterThan(0);
    }
  });

  it('a report document with a bad path is a blocker', () => {
    const result = run([op('setFields', { path: 'items/a.yml' })], [doc('../evil.yml', 'changed', [0])]);
    expect(result.blockers.length).toBeGreaterThan(0);
  });

  it('a report kind this code does not know counts as touched', () => {
    const result = run([op('create', { id: 'x' })], [doc('items/x.yml', 'created', [0]), doc('items/y.yml', 'frobnicated', [0])]);
    expect(result.existing).toEqual([id('items/y.yml')]);
  });
});

describe('the grant set', () => {
  it('only a live gesture adds, once; forged and spent gestures change nothing', () => {
    const grants = new GrantSet();
    const gesture = click();
    expect(grants.grant([id('items/a.yml')], gesture)).toEqual([id('items/a.yml')]);
    expect(() => grants.grant([id('items/b.yml')], gesture)).toThrow(GestureError); // spent
    expect(() => grants.grant([id('items/b.yml')], {} as never)).toThrow(GestureError); // forged
    expect(() => grants.grant([id('items/b.yml')], Object.freeze({}) as never)).toThrow(GestureError);
    expect(grants.ids()).toEqual([id('items/a.yml')]);
  });

  it('a script-made event is no gesture', () => {
    expect(gestureFromEvent({ isTrusted: false })).toBeNull();
    expect(gestureFromEvent(new Event('click'))).toBeNull(); // dispatched by script: isTrusted is false
    expect(gestureFromEvent({})).toBeNull();
    expect(gestureFromEvent(null)).toBeNull();
    expect(gestureFromEvent({ isTrusted: 'true' })).toBeNull();
  });

  it('ignores anything that is not a canonical id, and covers only the exact string', () => {
    const grants = new GrantSet();
    grants.grant(['demo:items/*', '../x', 'demo:items/../a.yml', 'demo:items/a.yml'], click());
    expect(grants.ids()).toEqual(['demo:items/*', 'demo:items/a.yml']);
    expect(grants.has('demo:items/b.yml')).toBe(false);
    expect(grants.has('demo:items/A.yml')).toBe(false);
    expect(grants.has('demo:items/')).toBe(false);
  });

  it('is monotonic: the class has no way to remove', () => {
    expect(Object.getOwnPropertyNames(GrantSet.prototype).sort()).toEqual(['constructor', 'grant', 'has', 'ids']);
  });
});

describe('the policy: the four rows of 5.6', () => {
  const creating = run([op('create', { id: 'x' })], [doc('items/x.yml', 'created', [0])]);
  const changing = run([op('setFields', { path: 'items/sword.yml' })], [doc('items/sword.yml', 'changed', [0])]);

  it('row 1: only creates (or changes to session content) apply without approval', () => {
    expect(decide(creating, new GrantSet(), false)).toEqual({ allowed: true, missing: [], needsProposalApproval: false });
  });

  it('row 2: an existing object already approved applies without a new approval', () => {
    const grants = new GrantSet();
    grants.grant([id('items/sword.yml')], click());
    expect(decide(changing, grants, false).allowed).toBe(true);
  });

  it('row 3: an existing object not yet approved waits, and names that object only', () => {
    const two = run(
      [op('setFields', { path: 'items/sword.yml' }), op('delete', { path: 'items/axe.yml' })],
      [doc('items/sword.yml', 'changed', [0]), doc('items/axe.yml', 'deleted', [1])],
    );
    const grants = new GrantSet();
    grants.grant([id('items/sword.yml')], click());
    expect(decide(two, grants, false)).toEqual({ allowed: false, missing: [id('items/axe.yml')], needsProposalApproval: false });
  });

  it('the proposal approval alone clears a blocker, and never an ungranted object', () => {
    const blocked = run([op('mystery', {})]);
    expect(decide(blocked, new GrantSet(), true).allowed).toBe(true);
    expect(decide(changing, new GrantSet(), true).allowed).toBe(false);
  });
});

describe('properties over random operation lists', () => {
  // a seeded generator, so a failure names its seed
  const rng = (seed: number) => () => {
    seed = (seed * 1664525 + 1013904223) % 4294967296;
    return seed / 4294967296;
  };
  const KINDS = ['create', 'duplicate', 'saveAsTemplate', 'setFields', 'logicEdit', 'guiEdit', 'detachTemplate', 'delete', 'move', 'mystery'];
  const PATHS = ['items/a.yml', 'items/b.yml', 'guis/g.yml', 'templates/t.yml', 'items/c.yml'];

  it('an existing object is never classified new, a create never needs a grant, an unknown kind always blocks', () => {
    for (let seed = 1; seed <= 400; seed++) {
      const next = rng(seed);
      const pick = <T>(list: readonly T[]): T => list[Math.floor(next() * list.length)] as T;
      const created = new Set<string>();
      for (const path of PATHS) if (next() < 0.3) created.add(id(path));
      const operations: StagedOperation[] = [];
      const documents: ReportDocument[] = [];
      for (let index = 0; index < 1 + Math.floor(next() * 6); index++) {
        const kind = pick(KINDS);
        const path = pick(PATHS);
        operations.push(op(kind, { path, fromPath: path, toPath: pick(PATHS), id: `n${index}` }));
        if (kind === 'create') documents.push(doc(`items/n${index}.yml`, 'created', [index]));
      }
      const result = run(operations, documents, created);
      result.operations.forEach((entry, index) => {
        const operation = operations[index]!;
        if (entry.kind === 'mystery') {
          expect(entry.effect, `seed ${seed}`).toBe('unknown');
        } else if (entry.kind === 'create') {
          expect(entry.effect, `seed ${seed}`).toBe('new');
        }
        const modified = { setFields: 'path', logicEdit: 'path', guiEdit: 'path', detachTemplate: 'path', delete: 'path', move: 'fromPath' }[
          entry.kind
        ];
        if (modified !== undefined) {
          const target = id(operation[modified] as string);
          const movedHereBefore = operations.slice(0, index).some((earlier) => earlier.kind === 'move' && earlier.toPath === operation[modified]);
          const isNewHere = created.has(target) || documents.some((d) => d.path === operation[modified]) || movedHereBefore;
          if (!isNewHere) expect(entry.objects, `seed ${seed} op ${index}`).toContain(target);
        }
      });
      const creates = result.operations.every((entry) => entry.kind === 'create');
      if (creates) expect(decide(result, new GrantSet(), false).allowed, `seed ${seed}`).toBe(true);
      // nothing is allowed unless every existing object is granted
      const grants = new GrantSet();
      if (result.existing.length > 0) {
        expect(decide(result, grants, false).allowed, `seed ${seed}`).toBe(false);
        grants.grant(result.existing, click());
        expect(decide(result, grants, false).allowed, `seed ${seed}`).toBe(result.blockers.length === 0);
      }
    }
  });
});
