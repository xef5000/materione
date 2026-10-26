/**
 * Which host served this SPA (web.md §6, cloud landing). The plugin jar and the cloud
 * Worker serve the *same* build, so the difference is a response header the
 * Worker's static-asset `_headers` file attaches to `/host.json`. The plugin
 * listener does not apply `_headers`, so a plugin-served editor never sees the
 * marker and keeps its paste-a-link card. The probe is a static asset on the
 * cloud: no Worker invocation, no billable request.
 */
export type EditorHost = 'cloud' | 'plugin';

export const HOST_MARKER_PATH = '/host.json';
export const HOST_HEADER = 'X-Materione-Host';

/** What the page should show before any session exists. */
export type StartIntent =
  | { kind: 'session'; code: string; key: string }
  | { kind: 'demo' }
  | { kind: 'idle' };

/** Reads the capability (`?s=<code>#<key>`) or the demo flag (`?demo`) from the address. */
export function startIntent(search: string, hash: string): StartIntent {
  const params = new URLSearchParams(search);
  const code = params.get('s');
  const key = hash.length > 1 ? hash.slice(1) : '';
  if (code && key) return { kind: 'session', code, key };
  if (params.has('demo')) return { kind: 'demo' };
  return { kind: 'idle' };
}

export async function detectHost(fetchImpl: typeof fetch = fetch): Promise<EditorHost> {
  try {
    const response = await fetchImpl(HOST_MARKER_PATH, { cache: 'no-store' });
    return response.headers.get(HOST_HEADER)?.trim().toLowerCase() === 'cloud' ? 'cloud' : 'plugin';
  } catch {
    return 'plugin';
  }
}
