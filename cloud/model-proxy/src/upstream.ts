import type { Env } from "./http";

export function resolveUpstreamUrl(env: Env): string {
  if (env.AIG_BASE_URL && env.AIG_BASE_URL.trim().length > 0) {
    return env.AIG_BASE_URL.trim();
  }
  const accountId = encodeURIComponent((env.CF_ACCOUNT_ID ?? "").trim());
  const gatewayId = encodeURIComponent((env.CF_AIG_GATEWAY_ID ?? "").trim());
  return `https://gateway.ai.cloudflare.com/v1/${accountId}/${gatewayId}/anthropic/v1/messages`;
}

export async function callUpstreamModel(env: Env, payload: Record<string, unknown>): Promise<Response> {
  const url = resolveUpstreamUrl(env);
  const token = (env.CF_AIG_TOKEN ?? "").trim();

  return fetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "anthropic-version": "2023-06-01",
      "cf-aig-authorization": `Bearer ${token}`,
      "cf-aig-collect-log-payload": "false",
      "cf-aig-skip-cache": "true",
    },
    body: JSON.stringify(payload),
  });
}
