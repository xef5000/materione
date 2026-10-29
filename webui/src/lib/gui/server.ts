/**
 * The GUI builder's server calls (gui.md §7, §10): the two places where the studio asks the server
 * rather than deciding for itself. `gui.validate` gives the compiler's verdict on the document as it
 * shows; `gui.customizeItem` gives a layer its own item. Both are capability-gated: a connection whose
 * session blob does not advertise `gui` (an older plugin, or the demo, which has no blob) gets no
 * answer, and the studio says so instead of guessing.
 *
 * Pure of the DOM. The wire shapes are the ones `GuiAuthoringOps` (web/rpc/ops) reads and writes.
 */
import { unwrap, type RpcChannel } from '../api';
import { hasCapability } from '../protocol/session';
import type { AssetUpload } from '../protocol/wire';
import type { GuiDocument, GuiLayer } from './model';

/** Whether the connected server answers the GUI API (the `gui` capability). */
export function guiApiAvailable(channel: RpcChannel | null | undefined): boolean {
  return !!channel && channel.blob !== undefined && hasCapability(channel.blob, 'gui');
}

/** One layer of a draft, in the typed shape the server reads (`GuiAuthoringOps.documentOf`). */
export interface DraftLayer {
  id: string;
  type: string;
  name?: string;
  visible: boolean;
  locked: boolean;
  opacity?: number;
  parameters: Record<string, unknown>;
}

/** The document the studio shows, as a draft. */
export interface DraftDocument {
  pack: string;
  path: string;
  id: string;
  title: string;
  rows: number;
  layers: DraftLayer[];
}

/** One diagnostic as the server reports it (`GuiAuthoringOps.diagnosticsJson`). */
export interface ServerDiagnostic {
  code: string;
  severity: 'info' | 'warn' | 'error';
  message: string;
  resource?: string | null;
  field?: string[];
  hint?: string | null;
}

/** A layer as the studio holds it, in the typed shape: its common fields apart from its own. */
export function draftLayer(layer: GuiLayer): DraftLayer {
  const { id, type, name, visible, locked, opacity, ...parameters } = layer;
  return {
    id,
    type,
    name: typeof name === 'string' ? name : undefined,
    visible: visible !== false,
    locked: locked === true,
    opacity: typeof opacity === 'number' ? opacity : undefined,
    parameters,
  };
}

/** The document as the draft the server validates: every layer in list order, so layer `i` is `layers.i`. */
export function draftOf(doc: GuiDocument, id: string, pack: string, path: string): DraftDocument {
  return { pack, path, id, title: doc.title, rows: doc.rows, layers: doc.layers.map(draftLayer) };
}

/** `gui.validate`: the diagnostics the compile would emit for the draft, and no writes. */
export async function validateDraft(
  channel: RpcChannel,
  document: DraftDocument,
  assets: readonly AssetUpload[] = [],
): Promise<ServerDiagnostic[]> {
  const response = await channel.call('gui.validate', { source: { kind: 'draft', document }, assets: [...assets] });
  const data = unwrap(response);
  return (data.diagnostics as ServerDiagnostic[] | undefined) ?? [];
}

/** What `gui.customizeItem` created: the item's id, its path, the reference the layer now holds, and the GUI's revision. */
export interface CustomizedItem {
  id: string;
  path: string;
  reference: string;
  rev: number;
}

/** `gui.customizeItem`: one transaction at `rev` that creates the layer's item and points the layer at it. */
export async function customizeItem(
  channel: RpcChannel,
  pack: string,
  path: string,
  rev: number,
  layer: string,
): Promise<CustomizedItem> {
  const response = await channel.call('gui.customizeItem', { pack, path, rev, layer });
  return unwrap(response) as unknown as CustomizedItem;
}
