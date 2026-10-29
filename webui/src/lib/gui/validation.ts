/**
 * The compiler's verdict, as the studio shows it (gui.md §6, §10 #8). The studio carries no copy of
 * the compiler's rules: it sends the document it shows to `gui.validate` and draws the diagnostics
 * the server returns, each against the layer it names (`layers.<index>`, the position in the draft,
 * which is the position in the studio's list). A diagnostic about the GUI as a whole (a parse
 * failure) has no layer and is shown once, for the document.
 *
 * Pure: no DOM, no Svelte.
 */
import type { GuiDocument } from './model';
import type { ServerDiagnostic } from './server';

/**
 * One problem the compiler reports for a layer. `skip` is the only severity: every compile
 * diagnostic about a layer means the layer is not in the pack, and the server states why.
 */
export interface LayerFlag {
  severity: 'skip';
  code: string;
  message: string;
}

export interface FlagSet {
  /** Problems by layer id. */
  byLayer: Map<string, LayerFlag[]>;
  /** Problems about the GUI as a whole, in the order the server reported them. */
  document: string[];
}

/** The layer index a diagnostic names (`layers.<index>`), or null when it names none. */
export function layerIndexOf(diagnostic: ServerDiagnostic): number | null {
  const field = diagnostic.field;
  if (!field || field.length < 2 || field[0] !== 'layers') return null;
  const index = Number(field[1]);
  return Number.isInteger(index) && index >= 0 ? index : null;
}

/** The server's diagnostics, sorted onto the layers of the document they were computed for. */
export function flagsOf(diagnostics: readonly ServerDiagnostic[], doc: Pick<GuiDocument, 'layers'>): FlagSet {
  const byLayer = new Map<string, LayerFlag[]>();
  const document: string[] = [];
  for (const diagnostic of diagnostics) {
    const index = layerIndexOf(diagnostic);
    const layer = index === null ? undefined : doc.layers[index];
    if (!layer) {
      document.push(diagnostic.message);
      continue;
    }
    const flag: LayerFlag = { severity: 'skip', code: diagnostic.code, message: diagnostic.message };
    byLayer.set(layer.id, [...(byLayer.get(layer.id) ?? []), flag]);
  }
  return { byLayer, document };
}

/** Whether the compiler drops the layer (any flag on it means so). */
export function isSkipped(flags: readonly LayerFlag[] | undefined): boolean {
  return !!flags?.some((flag) => flag.severity === 'skip');
}
