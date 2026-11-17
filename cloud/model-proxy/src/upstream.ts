import type { Env } from "./http";

const GATEWAY_ID_REGEX = /^[a-zA-Z0-9_-]{1,100}$/;

export function resolveUpstreamUrl(env: Env): string {
  if (
    env.ALLOW_TEST_UPSTREAM_OVERRIDE === "true" &&
    env.AIG_BASE_URL &&
    env.AIG_BASE_URL.trim().length > 0
  ) {
    return env.AIG_BASE_URL.trim();
  }
  const accountId = (env.CF_ACCOUNT_ID ?? "").trim();
  const gatewayId = (env.CF_AIG_GATEWAY_ID ?? "").trim();
  if (!GATEWAY_ID_REGEX.test(accountId) || !GATEWAY_ID_REGEX.test(gatewayId)) {
    throw new Error("Invalid gateway configuration.");
  }
  return `https://gateway.ai.cloudflare.com/v1/${accountId}/${gatewayId}/openai/responses`;
}

export async function callUpstreamModel(
  env: Env,
  bodyJson: string,
  signal?: AbortSignal,
): Promise<Response> {
  const url = resolveUpstreamUrl(env);
  const token = (env.CF_AIG_TOKEN ?? "").trim();

  return fetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "cf-aig-authorization": `Bearer ${token}`,
      "cf-aig-collect-log-payload": "false",
      "cf-aig-skip-cache": "true",
    },
    body: bodyJson,
    signal,
  });
}
