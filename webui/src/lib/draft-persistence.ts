/**
 * Local drafts (web.md §4 "Local drafts"): unsaved document drafts are
 * kept in this browser's IndexedDB, one record per server, so a reload, a
 * closed tab or a dropped relay does not lose typing. The record is an unsaved
 * buffer, not a second source of truth (D1): files on disk win, and a kept
 * draft reaches them only through the editor's own revision-checked save after
 * it has been reconciled against the server.
 */
import type { PersistedDraft } from './drafts.svelte';

/** Kept drafts older than this are dropped unread: a month-old buffer is noise, not work. */
export const KEPT_DRAFT_TTL_MS = 30 * 24 * 60 * 60 * 1000;
/** Typing coalesces into one write per pause. */
export const PERSIST_DELAY_MS = 250;

export interface KeptDrafts {
  savedAt: number;
  drafts: PersistedDraft[];
}

/** Where kept drafts live; keyed by the server's signing identity. */
export interface DraftStorage {
  read(server: string): Promise<KeptDrafts | null>;
  /** Replaces the server's record; an empty list deletes it. */
  write(server: string, drafts: PersistedDraft[]): Promise<void>;
}

const DB_NAME = 'materione-editor';
const STORE = 'drafts';

function request<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

/** IndexedDB, or null where the browser has none (private modes, old engines): drafts then stay in-tab only. */
export function indexedDbDraftStorage(factory: IDBFactory | undefined = globalThis.indexedDB): DraftStorage | null {
  if (!factory) return null;
  let opened: Promise<IDBDatabase> | null = null;
  const db = () => opened ??= new Promise<IDBDatabase>((resolve, reject) => {
    const open = factory.open(DB_NAME, 1);
    open.onupgradeneeded = () => {
      if (!open.result.objectStoreNames.contains(STORE)) open.result.createObjectStore(STORE);
    };
    open.onsuccess = () => resolve(open.result);
    open.onerror = () => {
      opened = null;
      reject(open.error);
    };
  });
  return {
    async read(server) {
      const store = (await db()).transaction(STORE, 'readonly').objectStore(STORE);
      return (await request(store.get(server)) as KeptDrafts | undefined) ?? null;
    },
    async write(server, drafts) {
      const transaction = (await db()).transaction(STORE, 'readwrite');
      const store = transaction.objectStore(STORE);
      if (drafts.length) store.put({ savedAt: Date.now(), drafts } satisfies KeptDrafts, server);
      else store.delete(server);
      await new Promise<void>((resolve, reject) => {
        transaction.oncomplete = () => resolve();
        transaction.onerror = () => reject(transaction.error);
        transaction.onabort = () => reject(transaction.error);
      });
    },
  };
}

/** A storage that forgets on reload: the tests' stand-in, and the fallback shape. */
export function memoryDraftStorage(): DraftStorage & { records: Map<string, KeptDrafts> } {
  const records = new Map<string, KeptDrafts>();
  return {
    records,
    async read(server) {
      const kept = records.get(server);
      return kept ? structuredClone(kept) : null;
    },
    async write(server, drafts) {
      if (drafts.length) records.set(server, { savedAt: Date.now(), drafts: structuredClone(drafts) });
      else records.delete(server);
    },
  };
}

/** The server's kept drafts, or none when they expired or cannot be read. */
export async function readKeptDrafts(
  storage: DraftStorage | null,
  server: string,
  now = Date.now(),
): Promise<PersistedDraft[]> {
  if (!storage || !server) return [];
  try {
    const kept = await storage.read(server);
    if (!kept || !Array.isArray(kept.drafts)) return [];
    if (now - kept.savedAt > KEPT_DRAFT_TTL_MS) {
      await storage.write(server, []);
      return [];
    }
    return kept.drafts;
  } catch {
    return [];
  }
}

/**
 * Coalesces draft snapshots into one storage write per pause. Writes never
 * overlap: a snapshot taken while one is in flight is written after it.
 * Storage failures are swallowed: kept drafts are a convenience, and the
 * in-tab draft is still there.
 */
export function draftPersister(storage: DraftStorage, server: string, delayMs = PERSIST_DELAY_MS) {
  let pending: PersistedDraft[] | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let writing: Promise<void> = Promise.resolve();

  function flush(): Promise<void> {
    if (timer) clearTimeout(timer);
    timer = null;
    if (pending === null) return writing;
    const drafts = pending;
    pending = null;
    writing = writing.then(() => storage.write(server, drafts)).catch(() => undefined);
    return writing;
  }

  return {
    save(drafts: PersistedDraft[]): void {
      pending = drafts;
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => void flush(), delayMs);
    },
    flush,
    /** Forgets the server's kept drafts: the user discarded them. */
    clear(): Promise<void> {
      pending = [];
      return flush();
    },
  };
}

export type DraftPersister = ReturnType<typeof draftPersister>;
