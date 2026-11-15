/**
 * Origin and base URL resolution for the Materione AI model proxy (ai-credits.md section 8.2).
 *
 * Live model proxy calls are strictly gated to `https://editor.materione.com` when `EditorHost === 'cloud'`.
 * Local development (`localhost` / `127.0.0.1`) and plugin-hosted editors never contact `ai.materione.com`
 * automatically; only the Playwright browser harness (`?codemode=cloud-proxy`) activates the test override.
 */
import type { EditorHost } from '../../host';

export const CLOUD_EDITOR_ORIGIN = 'https://editor.materione.com';
export const DEFAULT_MODEL_PROXY_BASE_URL = 'https://ai.materione.com';

let testProxyBaseOverride: string | null = null;

/** Test-only seam used by the Playwright browser harness (`?codemode=cloud-proxy` + `page.route`). */
export function setTestProxyBaseOverride(baseUrl: string | null): void {
  testProxyBaseOverride = baseUrl;
}

export function resolveCloudProxyBaseUrl(
  host: EditorHost | null | undefined,
  origin: string = typeof location !== 'undefined' ? location.origin : '',
): string | null {
  if (host !== 'cloud') return null;
  if (testProxyBaseOverride !== null) return testProxyBaseOverride;
  if (origin === CLOUD_EDITOR_ORIGIN) return DEFAULT_MODEL_PROXY_BASE_URL;
  return null;
}
