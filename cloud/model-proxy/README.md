# Materione model proxy worker

Stateless Cloudflare Worker behind `ai.materione.com` (`docs/architecture/codemode.md` §12.1,
`docs/architecture/web.md` §3.5): fronts **Cloudflare AI Gateway Unified Billing** for the
cloud editor's code-mode chatbot so gateway credentials never enter the browser and free
daily usage is hard-capped per caller and globally before any model call is reached.

The relay Worker (`cloud/editor-worker/`) stays separate and ciphertext-only.

## Layout

```
src/index.ts      router, CORS/origin gate, hourly cron sweep (scheduled)
src/http.ts       strict Origin gate, CORS/JSON response helpers, UTC day helpers
src/config.ts     request/token ceilings, picodollar constants, cap env resolution
src/models.ts     model allowlist (claude-haiku-5-5), exact integer picodollar math
src/identity.ts   proxy config check, CF-Connecting-IP extraction, daily-salted SHA-256
src/validate.ts   streamed 512 KiB body limit, strict Anthropic schema, token estimate
src/ledger.ts     single-statement atomic D1 reserve/settle, read-only allowance query
src/upstream.ts   AI Gateway fetch (cf-aig-authorization, ZDR/no-payload-logging)
src/sse.ts        Anthropic SSE parser + settlement event emitter
migrations/       D1 schema + atomic BEFORE INSERT / AFTER UPDATE triggers
test/             vitest on the real Workers runtime (@cloudflare/vitest-plugin)
```

## Developing

```
npm install
npm test          # vitest against a real Workers runtime + local D1
npm run check     # tsc --noEmit
npm run deploy:dry
```

Local `wrangler dev` against a stub upstream (`AIG_BASE_URL`) requires sending
`Origin: https://editor.materione.com` and `CF-Connecting-IP` explicitly — non-exact
origins (including `http://localhost:*`) are rejected with `403 bad_origin` by design.

## Owner setup & deployment (owner-run — agents never deploy; CLAUDE.md cost guardrails)

One-time setup on your Cloudflare account:

1. **AI Gateway**: create a gateway in the Cloudflare dashboard (e.g. `materione-ai`):
   - **Unified Billing**: purchase prepaid credits with **Auto top-up: OFF**.
   - **Require provider credentials: OFF** (Unified Billing + Zero Data Retention serves
     Anthropic without storing a provider key).
   - **Log payloads: OFF** in gateway settings (in addition to the Worker's
     `cf-aig-collect-log-payload: false` header).
   - **Gateway Spend Limit**: set a hard dollar budget below the prepaid credit balance.
   - **Gateway Rate Limit**: configure a gateway-level request ceiling as defense-in-depth.
2. **Gateway token**: create a scoped API token with `AI Gateway - Run` permission only
   (`CF_AIG_TOKEN`).
3. **D1 database**: `npx wrangler d1 create model-proxy-allowance` and put the printed
   `database_id` into `wrangler.jsonc` (`d1_databases[0].database_id`).
4. **Apply migrations**: `npx wrangler d1 migrations apply model-proxy-allowance --remote`.
5. **Secrets & vars**:
   - `npx wrangler secret put CF_AIG_TOKEN`
   - `npx wrangler secret put IP_HASH_SALT` (random >= 16-char secret)
   - Set `CF_ACCOUNT_ID` and `CF_AIG_GATEWAY_ID` in Worker vars (dashboard or `wrangler.jsonc`).
6. **Deploy**: `npx wrangler deploy`. The `routes` block in `wrangler.jsonc` attaches
   `ai.materione.com` (or `model-proxy.materione.com` if preferred) on the `materione.com` zone.

## Cost model

All monetary arithmetic uses exact integer **picodollars** (`1 USD = 1_000_000_000_000` picos).

| Component | Free / Prepaid Limit | Proxy Guard / Behavior |
|---|---|---|
| Workers Free | 100,000 req/day hard stop | `10 req/min` per caller on `/v1/chat` (`RATE_LIMITER`) |
| D1 Free | 5M reads, 100k writes/day, 5 GB hard stop | 2 writes/turn (reserve + settle) = max ~50k turns/day; `$1.00/day` pool caps much earlier (~125–500 turns/day); hourly cron retains 2 UTC days |
| Workers Rate Limiting | Free tier | Binding `RATE_LIMITER` (`10 req / 60s` per daily-salted caller hash) |
| AI Gateway Unified Billing | Prepaid credits, auto top-up OFF, 5% top-up fee | `claude-haiku-5-5` short-context only (`$0.10` in / `$0.01` cache read / `$0.125` cache write / `$0.50` out per 1M); `$0.05/day` caller cap; `$1.00/day` global D1 pool; gateway spend limit |

## What the proxy sees vs never stores

- **Stateless pass-through**: `system`, `messages`, `tools`, and SSE deltas are forwarded
  to AI Gateway with `cf-aig-collect-log-payload: false` and never written to D1 or
  `console.*`.
- **D1 stores only**: daily-salted `SHA-256(ip + ":" + utcDay + ":" + IP_HASH_SALT)`
  caller hashes and integer picodollar/token counters. Raw IP addresses are never stored
  or logged.
