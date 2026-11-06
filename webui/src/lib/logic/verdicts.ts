/**
 * The compiler's verdict on the draft, by node (logic.md section 26 decision 16, 7.4): a diagnostic carries a step
 * path, and it is drawn on the deepest node whose path is a prefix of it. The path is kept on the flag so the inspector
 * can go one step further and put the diagnostic on the field it names (`diagnose.ts`).
 *
 * Pure of the DOM and of Svelte.
 */
import type { Graph } from './project';
import type { LogicDiagnostic } from './server';

export interface NodeFlag {
  severity: 'error' | 'warn' | 'info';
  message: string;
  /** The step path the compiler reported, when the flag came from the compiler. */
  path?: string;
}

/** Maps the compiler's step-path diagnostics onto nodes: the deepest node whose path is a prefix of the diagnostic's. */
export function verdictsOf(graph: Graph, diagnostics: readonly LogicDiagnostic[]): Map<string, NodeFlag[]> {
  const out = new Map<string, NodeFlag[]>();
  for (const diagnostic of diagnostics) {
    const path = (diagnostic.field ?? []).join('.');
    let best: string | null = null;
    for (const node of graph.nodes) {
      if ((path === node.id || path.startsWith(`${node.id}.`)) && (best === null || node.id.length > best.length)) best = node.id;
    }
    if (!best) continue;
    const severity = diagnostic.severity === 'error' || diagnostic.severity === 'info' ? diagnostic.severity : 'warn';
    out.set(best, [...(out.get(best) ?? []), { severity, message: `${diagnostic.code}: ${diagnostic.message}`, path }]);
  }
  return out;
}
