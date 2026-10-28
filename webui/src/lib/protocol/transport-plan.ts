/**
 * Transport planning (web.md §4.4): the pure, side-effect-free half of the
 * "which pipe does this browser use" decision. The cloud relay is the default
 * and the only pipe that needs no setup; the plan only ever says whether a
 * direct local connection is worth ATTEMPTING. Whether it may be USED is
 * decided by the authenticated probe (probe.ts), never by anything here.
 *
 * Rules, in order — the first that matches wins:
 *   1. `?mode=relay`            → skip (manual override: always cloud)
 *   2. no hint in the blob      → skip (the server never invited a probe)
 *   3. stored preference 'cloud' (and no `?mode=direct`) → skip
 *   4. malformed hint           → skip
 *   5. https page → plain-http non-loopback hint → skip: browsers block that
 *      mixed-content socket outright, so trying would only produce console
 *      noise (and, in Chrome, a pointless local-network permission prompt)
 *   6. otherwise                → probe
 */

export type TransportOverride = 'relay' | 'direct' | null;
export type TransportPreference = 'auto' | 'cloud';

export type SkipReason =
  | 'override-relay'
  | 'no-hint'
  | 'preference-cloud'
  | 'bad-hint'
  | 'mixed-content';

export type DirectPlan =
  | { kind: 'probe'; wsUrl: string }
  | { kind: 'skip'; reason: SkipReason };

export interface PlanInput {
  /** `location.protocol` of the page hosting the SPA ("https:" on the cloud). */
  pageProtocol: string;
  /** The `direct.url` carried (encrypted) in the session blob, if any. */
  hint?: string;
  override: TransportOverride;
  preference: TransportPreference;
}

/** True for hosts a browser treats as potentially trustworthy without TLS. */
export function isLoopbackHost(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '');
  return (
    host === 'localhost' ||
    host.endsWith('.localhost') ||
    host === '::1' ||
    /^127(\.\d{1,3}){3}$/.test(host)
  );
}

export function planDirect(input: PlanInput): DirectPlan {
  if (input.override === 'relay') return { kind: 'skip', reason: 'override-relay' };
  if (!input.hint) return { kind: 'skip', reason: 'no-hint' };
  if (input.preference === 'cloud' && input.override !== 'direct') {
    return { kind: 'skip', reason: 'preference-cloud' };
  }
  let url: URL;
  try {
    url = new URL(input.hint);
  } catch {
    return { kind: 'skip', reason: 'bad-hint' };
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return { kind: 'skip', reason: 'bad-hint' };
  }
  if (input.pageProtocol === 'https:' && url.protocol === 'http:' && !isLoopbackHost(url.hostname)) {
    return { kind: 'skip', reason: 'mixed-content' };
  }
  const wsProtocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return { kind: 'probe', wsUrl: `${wsProtocol}//${url.host}/editor/ws` };
}

/** `?mode=relay|cloud` → 'relay'; `?mode=direct|local` → 'direct'; anything else → null. */
export function parseOverride(search: string): TransportOverride {
  const value = new URLSearchParams(search).get('mode')?.toLowerCase();
  if (value === 'relay' || value === 'cloud') return 'relay';
  if (value === 'direct' || value === 'local') return 'direct';
  return null;
}

// ---------------------------------------------------------------- preference

export const PREFERENCE_KEY = 'materione.transport';

/** Minimal storage shape so tests (and a blocked-storage browser) need no DOM. */
export interface PreferenceStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

function defaultStorage(): PreferenceStorage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null; // storage can throw in private windows / blocked-cookie modes
  }
}

/** Per-browser "never probe a local listener" switch. Absent/unreadable → 'auto'. */
export function readPreference(storage: PreferenceStorage | null = defaultStorage()): TransportPreference {
  try {
    return storage?.getItem(PREFERENCE_KEY) === 'cloud' ? 'cloud' : 'auto';
  } catch {
    return 'auto';
  }
}

export function writePreference(
  preference: TransportPreference,
  storage: PreferenceStorage | null = defaultStorage(),
): void {
  try {
    storage?.setItem(PREFERENCE_KEY, preference);
  } catch {
    // a convenience only: failing to remember it must never break a session
  }
}
