/** The few bulk-authoring steps that read the server: kept apart from the pure seams in bulk.ts. */
import { ops, unwrap } from './api';
import { requireSavedDocument, session } from './state.svelte';

/**
 * The revision each document has right now. Refuses when the tab holds pending edits
 * for one of them, so a bulk write never lands under an unsaved draft.
 */
export async function readRevisions(pack: string, paths: string[]): Promise<Map<string, number>> {
  const channel = session.channel;
  if (!channel) throw new Error('Not connected.');
  const entries = await Promise.all(paths.map(async (path) => {
    requireSavedDocument(pack, path);
    return [path, Number(unwrap(await ops.get(channel, pack, path)).rev ?? 0)] as const;
  }));
  return new Map(entries);
}
