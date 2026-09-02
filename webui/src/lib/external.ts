/**
 * The external-edit feed (web.md §4.2 "document.changed"): the plugin's file
 * watcher journals hand edits on disk and pushes them here. This reducer is
 * the pure decision core — given one pushed change it says what must happen:
 * the explorer tree re-fetches, open tabs are flagged (never auto-clobbered),
 * and the activity dock gets a human line.
 */

export interface DocumentChanged {
  pack: string;
  path: string;
  deleted: boolean;
}

export interface ChangeEffect {
  /** The explorer tree must re-fetch (the changed pack is the active one). */
  refreshPack: boolean;
  /** Open document tabs (key = `pack/path`) that must flag "changed on disk". */
  staleKeys: string[];
  /** Human line for the activity dock. */
  log: string;
}

export function documentChangedEffect(
  change: DocumentChanged,
  activePack: string | null,
  openDocKeys: readonly string[],
): ChangeEffect {
  const key = `${change.pack}/${change.path}`;
  return {
    refreshPack: change.pack === activePack,
    staleKeys: openDocKeys.includes(key) ? [key] : [],
    log: `${change.pack}/${change.path} ${change.deleted ? 'deleted' : 'changed'} on disk`,
  };
}
