// The server's own gui-components description and New GUI starter, as recorded in the golden
// editor snapshot (what a live server answers to authoring.describe): the studio's tests read the
// real registry, not a copy of it.
import snapshot from '../../../fixtures/golden/editor-demo-snapshot.json';
import { readRegistry } from '../../src/lib/gui/registry';

export const registry = readRegistry(snapshot.describe as unknown as Record<string, unknown>)!;

/** The starter file for a new GUI named `id` / `title` (the same substitution the server makes). */
export function scaffoldGui(id: string, title: string): string {
  return snapshot.scaffolds.guis.replaceAll('demo_scaffold', id).replace('Demo Scaffold', title);
}
