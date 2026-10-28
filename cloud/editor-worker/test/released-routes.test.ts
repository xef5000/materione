import { describe, expect, it } from "vitest";
import { env as cloudflareEnv } from "cloudflare:test";
import worker from "../src/index";
import type { Env } from "../src/http";

/**
 * Published plugin jars keep calling the relay after the Worker redeploys
 * (build.md §8). fixtures/protocol/released/<version>.json records the relay
 * routes each release calls (scripts/compat/editor-compat.mjs); every one must
 * still be routed: neither the router's own 404 "not_found" nor a 405. Auth,
 * validation and missing-resource errors are fine: the route exists.
 */
const env = cloudflareEnv as unknown as Env & { RELEASED_MANIFESTS_JSON: string };
const manifests = JSON.parse(env.RELEASED_MANIFESTS_JSON) as {
  version: string;
  relayRoutes: string[];
}[];

const ID = "compatProbeId0000000000";

async function routed(method: string, path: string): Promise<{ status: number; error?: string }> {
  const response = await worker.fetch(
    new Request(`https://relay.test${path.replaceAll("{id}", ID)}`, {
      method,
      ...(method === "POST" ? { body: "{}", headers: { "content-type": "application/json" } } : {}),
    }),
    env,
  );
  const text = await response.text();
  let error: string | undefined;
  try {
    error = (JSON.parse(text) as { error?: string }).error;
  } catch {
    error = undefined;
  }
  return { status: response.status, error };
}

const missing = (result: { status: number; error?: string }) =>
  result.status === 405 || (result.status === 404 && result.error === "not_found");

describe("relay routes of released plugins", () => {
  it("has at least one released manifest", () => {
    expect(manifests.length).toBeGreaterThan(0);
  });

  it("detects a route the Worker does not serve (oracle check)", async () => {
    expect(missing(await routed("GET", "/v1/nowhere"))).toBe(true);
    expect(missing(await routed("DELETE", "/v1/channels"))).toBe(true);
  });

  for (const manifest of manifests) {
    for (const route of manifest.relayRoutes) {
      it(`${manifest.version}: ${route}`, async () => {
        const [method, path] = route.split(" ");
        const result = await routed(method!, path!);
        expect(missing(result), `${route} -> ${result.status} ${result.error ?? ""}`).toBe(false);
      });
    }
  }
});
