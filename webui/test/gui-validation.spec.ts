/**
 * The compiler's verdict as the studio holds it (gui.md §6, §10 #8): the server's diagnostics sorted
 * onto the layers they name, the draft the studio asks about, the capability that decides whether the
 * server answers at all, and the GUI API's two calls. The rules themselves are the server's.
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { RpcError, type RpcChannel } from '../src/lib/api';
import type { SessionBlob } from '../src/lib/protocol/session';
import type { GuiDocument, GuiLayer } from '../src/lib/gui/model';
import { customizeItem, draftLayer, draftOf, guiApiAvailable, validateDraft, type ServerDiagnostic } from '../src/lib/gui/server';
import { flagsOf, isSkipped, layerIndexOf } from '../src/lib/gui/validation';
import type { Response } from '../src/lib/protocol/wire';

const layer = (id: string, extra: Record<string, unknown> = {}): GuiLayer => ({ id, type: 'text', ...extra }) as GuiLayer;
const doc = (...layers: GuiLayer[]): GuiDocument => ({ title: 'Shop', rows: 3, layers, rest: {}, order: [] });
const diagnostic = (message: string, field?: string[]): ServerDiagnostic => ({
  code: 'MTR-0052',
  severity: 'warn',
  message,
  resource: 'sample:main',
  field,
  hint: null,
});

/** A channel that answers each call from a script, and records what it was asked. */
function channel(answer: (op: string, payload: unknown) => Response): RpcChannel & { calls: Array<{ op: string; payload: unknown }> } {
  const calls: Array<{ op: string; payload: unknown }> = [];
  return {
    calls,
    call: (async (op: string, payload?: unknown) => {
      calls.push({ op, payload });
      return answer(op, payload);
    }) as RpcChannel['call'],
  };
}

const ok = (data: unknown): Response => ({ op: '', id: '', ok: true, data });

describe('the verdict is sorted onto the layers the server names', () => {
  it('a layer diagnostic lands on the layer at that position of the draft', () => {
    const d = doc(layer('heading'), layer('frame', { type: 'panel' }), layer('after'));
    const verdict = flagsOf([diagnostic('GUI layer \'after\' skipped: x', ['layers', '2'])], d);
    expect(verdict.byLayer.get('after')).toEqual([expect.objectContaining({ severity: 'skip', code: 'MTR-0052' })]);
    expect(verdict.byLayer.has('heading')).toBe(false);
    expect(verdict.document).toEqual([]);
    expect(isSkipped(verdict.byLayer.get('after'))).toBe(true);
    expect(isSkipped(undefined)).toBe(false);
  });

  it('a diagnostic about the whole GUI (no layer) is kept for the document', () => {
    const verdict = flagsOf([diagnostic('resource sample:main failed to parse as gui')], doc());
    expect(verdict.byLayer.size).toBe(0);
    expect(verdict.document).toEqual(['resource sample:main failed to parse as gui']);
  });

  it('a target past the end of the draft is not guessed at: it stays with the document', () => {
    const verdict = flagsOf([diagnostic('x', ['layers', '9'])], doc(layer('a')));
    expect(verdict.byLayer.size).toBe(0);
    expect(verdict.document).toEqual(['x']);
  });

  it('reads the index from layers.<n> only', () => {
    expect(layerIndexOf(diagnostic('x', ['layers', '3']))).toBe(3);
    expect(layerIndexOf(diagnostic('x', ['rows']))).toBeNull();
    expect(layerIndexOf(diagnostic('x', ['layers', 'two']))).toBeNull();
    expect(layerIndexOf(diagnostic('x'))).toBeNull();
  });
});

describe('the draft the studio asks about', () => {
  it('carries the document in list order with the common fields apart from the component\'s own', () => {
    const draft = draftOf(
      doc(layer('heading', { text: 'Shop', x: 8, name: 'Title' }), layer('gone', { visible: false, locked: true, opacity: 40 })),
      'main',
      'sample',
      'guis/main.yml',
    );
    expect(draft).toMatchObject({ pack: 'sample', path: 'guis/main.yml', id: 'main', title: 'Shop', rows: 3 });
    expect(draft.layers[0]).toEqual({
      id: 'heading',
      type: 'text',
      name: 'Title',
      visible: true,
      locked: false,
      opacity: undefined,
      parameters: { text: 'Shop', x: 8 },
    });
    expect(draft.layers[1]).toMatchObject({ visible: false, locked: true, opacity: 40, parameters: {} });
  });

  it('a layer without a name or opacity keeps them absent (the server\'s defaults apply)', () => {
    expect(draftLayer(layer('a'))).toEqual({ id: 'a', type: 'text', name: undefined, visible: true, locked: false, opacity: undefined, parameters: {} });
  });
});

describe('the GUI API is there only when the connection advertises it', () => {
  const blob = (capabilities?: string[]): SessionBlob =>
    ({ sessionId: 's', protocolVersions: [1], capabilities, pluginSigningPubKey: new Uint8Array(), relay: undefined, direct: undefined }) as unknown as SessionBlob;

  it('a demo channel (no blob) has no GUI API', () => {
    expect(guiApiAvailable(channel(() => ok({})))).toBe(false);
    expect(guiApiAvailable(null)).toBe(false);
  });

  it('a jar that advertises gui has it; one that does not, lacks it', () => {
    expect(guiApiAvailable({ ...channel(() => ok({})), blob: blob(['items', 'gui']) })).toBe(true);
    expect(guiApiAvailable({ ...channel(() => ok({})), blob: blob(['items']) })).toBe(false);
  });

  it('a pre-capabilities jar is assumed to have the whole surface (web.md §4.1)', () => {
    expect(guiApiAvailable({ ...channel(() => ok({})), blob: blob(undefined) })).toBe(true);
  });
});

describe('the GUI API calls', () => {
  it('validateDraft sends the draft and its unsaved pixels, and returns the server\'s diagnostics', async () => {
    const sent = channel(() => ok({ diagnostics: [diagnostic('skipped', ['layers', '0'])] }));
    const draft = draftOf(doc(layer('heading', { text: 'a<b' })), 'main', 'sample', 'guis/main.yml');
    const result = await validateDraft(sent, draft, [{ path: 'assets/textures/gui/main/art.png', data: 'QUJD' }]);
    expect(result).toEqual([diagnostic('skipped', ['layers', '0'])]);
    expect(sent.calls).toEqual([{
      op: 'gui.validate',
      payload: { source: { kind: 'draft', document: draft }, assets: [{ path: 'assets/textures/gui/main/art.png', data: 'QUJD' }] },
    }]);
  });

  it('customizeItem asks for one item at the GUI\'s revision and reads the created item back', async () => {
    const sent = channel(() => ok({ id: 'main_sword', path: 'items/gui/main/main_sword.yml', reference: 'sample:main_sword', rev: 3 }));
    const created = await customizeItem(sent, 'sample', 'guis/main.yml', 2, 'sword');
    expect(created).toEqual({ id: 'main_sword', path: 'items/gui/main/main_sword.yml', reference: 'sample:main_sword', rev: 3 });
    expect(sent.calls).toEqual([{ op: 'gui.customizeItem', payload: { pack: 'sample', path: 'guis/main.yml', rev: 2, layer: 'sword' } }]);
  });

  it('a refused customize is an RpcError carrying the server\'s code', async () => {
    const sent = channel(() => ({ op: 'gui.customizeItem', id: '', ok: false, error: { code: 'MTR-0034', message: 'stale', resource: null, hint: null } }) as Response);
    await expect(customizeItem(sent, 'sample', 'guis/main.yml', 1, 'sword')).rejects.toBeInstanceOf(RpcError);
    await expect(customizeItem(sent, 'sample', 'guis/main.yml', 1, 'sword')).rejects.toMatchObject({ code: 'MTR-0034' });
  });
});

describe('the golden frame the JVM recorded for customize', () => {
  it('is the request the studio builds, byte for byte in its payload', () => {
    const golden = JSON.parse(readFileSync(new URL('../../fixtures/protocol/editor-gui-v1.json', import.meta.url), 'utf8'));
    const sent = channel(() => ok({}));
    void customizeItem(sent, 'demo', 'guis/main.yml', 2, 'sword');
    expect(sent.calls[0]!.op).toBe(golden.customizeItem.request.op);
    expect(sent.calls[0]!.payload).toEqual(golden.customizeItem.request.payload);
  });
});
