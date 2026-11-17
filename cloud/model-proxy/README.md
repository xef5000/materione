# Materione model proxy worker

Stateless Cloudflare Worker behind `ai.materione.com` (`docs/architecture/codemode.md` §12.1,
`docs/architecture/ai-credits.md`, `docs/architecture/web.md` §3.5): fronts **Cloudflare AI
Gateway Unified Billing** for the cloud editor's code-mode chatbot, handles Google & Discord
OAuth 2.0 PKCE sessions (`__Host-materione_ai_session`), and processes Polar Merchant of Record
credit purchases, refunds, and customer portal links against a two-bucket atomic D1 ledger.

The relay Worker (`cloud/editor-worker/`) stays separate and ciphertext-only.

## Layout

```
src/index.ts      router, CORS/origin gate, session auth, D1/rate-limit 503 boundary, cron sweep
src/auth.ts       Google + Discord OAuth 2.0 PKCE, __Host- session cookie, /v1/me, /v1/auth/logout
src/polar.ts      Polar checkout, Standard Webhooks HMAC-SHA256 (/v1/webhooks/polar), customer portal
src/http.ts       strict Origin + Sec-Fetch-Site gate, credentialed CORS/JSON/HTML helpers, UTC day
src/config.ts     request/token/checkout ceilings, picodollar & credit constants, clamped caps
src/models.ts     fixed gpt-6-luna-medium allowlist, exact integer picodollar math
src/identity.ts   fail-closed config & RATE_LIMITER, IPv6 /64 canonicalization, daily-salted SHA-256
src/prompt.ts     immutable server-owned system charter & canonical run_program tool definition
src/validate.ts   streamed 512 KiB body, strict prompt/tool lock, 1-byte=1-token upper bound
src/ledger.ts     single-statement two-bucket atomic D1 reserve/settle, allowance & cron sweep
src/upstream.ts   AI Gateway fetch (cf-aig-authorization, ZDR/no-payload-logging, abort signal)
src/responses-stream.ts   Responses-to-editor SSE adapter, bounded continuation and exact usage
src/sse.ts        per-stream UTF-8 + CRLF SSE parser, interrupted-stream settlement, event emitter
migrations/       0001_allowance.sql -> 0002_accounts_and_credits.sql (accounts, sessions, ledger, triggers)
test/             vitest on the real Workers runtime (@cloudflare/vitest-plugin)
```

## Endpoints

| Route | Auth / Gate | Purpose |
|---|---|---|
| `GET /v1/auth/{google\|discord}/start` | Popup navigation + `RATE_LIMITER` | Issues signed PKCE state cookie (`__Host-materione_ai_oauth`) and `302` redirect |
| `GET /v1/auth/{google\|discord}/callback` | Popup navigation + PKCE/HMAC verify | Links/creates account, sets `__Host-materione_ai_session`, posts to `editor.materione.com` |
| `POST /v1/auth/logout` | `Origin` + `RATE_LIMITER` | Revokes session row and clears `__Host-materione_ai_session` |
| `GET /v1/me` | `Origin` + `RATE_LIMITER` | Returns `{ authenticated: false }` or `{ authenticated: true, account, credits, freeAllowance }` |
| `GET /v1/allowance` | `Origin` + session cookie (`401`) | Returns live paid credit balance + dual-gated free daily allowance |
| `POST /v1/chat` | `Origin` + session cookie (`401`) | Two-bucket atomic reserve, streams AI Gateway SSE + `materione_settlement` |
| `POST /v1/checkout` | `Origin` + session cookie (`401`) | Creates Polar custom-amount checkout (`$10.00`–`$200.00`, `amountCents` in `[1000, 20000]`) |
| `GET /v1/checkout/complete` | Browser navigation | CSP-locked completion page posting `materione-ai-checkout-complete` |
| `POST /v1/webhooks/polar` | Standard Webhooks HMAC-SHA256 (`403`) | Idempotent `order.paid` grants, `order.refunded` / `order.updated` (`void`) reversals, `refund.created` / `refund.updated` dispute holds |
| `POST /v1/portal` | `Origin` + session cookie (`401`) | Creates Polar customer portal session (`customer_portal_url`) or falls back to org portal |

## Developing

```
npm install
npm test          # vitest against a real Workers runtime + local D1
npm run check     # tsc --noEmit
npm run deploy:dry
```

Local `wrangler dev` against a stub upstream (`AIG_BASE_URL`) requires sending
`Origin: https://editor.materione.com`, `CF-Connecting-IP`, and a valid session cookie for
authenticated routes — non-exact origins (including `http://localhost:*`) are rejected with
`403 bad_origin` by design.

## Owner setup & deployment (owner-run — agents never deploy; CLAUDE.md cost guardrails)

One-time setup on your Cloudflare, OAuth, and Polar accounts:

1. **AI Gateway**: create a gateway in the Cloudflare dashboard (e.g. `materione-ai`):
   - **Unified Billing**: purchase prepaid credits with **Auto top-up: OFF**.
   - **Require provider credentials: OFF** (Unified Billing serves the configured model
     without storing a provider key).
   - **Log payloads: OFF** in gateway settings (in addition to the Worker's
     `cf-aig-collect-log-payload: false` header).
   - **Gateway Spend Limit**: set a hard dollar budget below the prepaid credit balance.
   - **Gateway Rate Limit**: configure a gateway-level request ceiling as defense-in-depth.
   - Confirm that `gpt-6-luna` and the OpenAI Responses endpoint are available through this
     gateway's Unified Billing before the owner runs a live smoke test.
2. **Gateway token**: create a scoped API token with `AI Gateway - Run` permission only
   (`CF_AIG_TOKEN`).
3. **Google & Discord OAuth apps**:
   - **Google**: create an OAuth 2.0 Web Client with authorized redirect URI
     `https://ai.materione.com/v1/auth/google/callback` (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`).
   - **Discord**: create a Discord application with OAuth2 redirect URI
     `https://ai.materione.com/v1/auth/discord/callback` (`DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`).
4. **Polar (Merchant of Record)**:
   - Product UUID in `wrangler.jsonc` (`vars.POLAR_CREDITS_PRODUCT_ID`):
     `06c6939d-72a3-4bef-8ded-5c6543811a28`.
   - Create an API access token (`POLAR_ACCESS_TOKEN`).
   - Configure a Polar webhook pointing to `https://ai.materione.com/v1/webhooks/polar`
     subscribed to `order.paid`, `order.refunded`, `order.updated`, `refund.created`, and
     `refund.updated`, and store its `whsec_...` secret as `POLAR_WEBHOOK_SECRET`.
   - Keep Cloudflare **Bot Fight Mode OFF** on `ai.materione.com` so Polar webhook deliveries
     are never challenged.
5. **D1 database**: `npx wrangler d1 create model-proxy-allowance` and put the printed
   `database_id` into `wrangler.jsonc` (`d1_databases[0].database_id`).
6. **Apply migrations**: `npx wrangler d1 migrations apply model-proxy-allowance --remote`.
7. **Secrets & vars**:
   - `npx wrangler secret put CF_AIG_TOKEN`
   - `npx wrangler secret put IP_HASH_SALT` (random >= 16-char secret)
   - `npx wrangler secret put SESSION_HMAC_SECRET` (random >= 32-char secret for PKCE state cookies)
   - `npx wrangler secret put GOOGLE_CLIENT_ID`
   - `npx wrangler secret put GOOGLE_CLIENT_SECRET`
   - `npx wrangler secret put DISCORD_CLIENT_ID`
   - `npx wrangler secret put DISCORD_CLIENT_SECRET`
   - `npx wrangler secret put POLAR_ACCESS_TOKEN`
   - `npx wrangler secret put POLAR_WEBHOOK_SECRET`
   - Optional float alert: `ALERT_WEBHOOK_URL` and `LOADED_GATEWAY_CREDITS_USD`.
   - Set `CF_ACCOUNT_ID` and `CF_AIG_GATEWAY_ID` in Worker vars (dashboard or `wrangler.jsonc`).
8. **Deploy**: `npx wrangler deploy`. The `routes` block in `wrangler.jsonc` attaches
   `ai.materione.com` on the `materione.com` zone.

For the Luna rollout, deploy the compatible editor SPA **before** the model proxy; older
editor tabs should reload before starting a new conversation. Once these changes are
merged to `main`, the owner can dispatch the editor deployment with
`gh workflow run deploy.yml --repo xef5000/materione --ref main`, wait for it to finish,
then run `npx wrangler deploy` from `cloud/model-proxy/`. Verify a multi-turn tool call
and credit settlement with an owner-controlled account. The benchmark used a live
gateway, but does not prove that the deployed editor and proxy work together.

## D1 schema & cost model

All monetary arithmetic uses exact integer **picodollars** (`1 USD = 1_000_000_000_000` picos;
`1 credit = 7_500_000_000` picos = `$0.0075` wholesale; `100 credits = $1.00` retail).

- **Durable financial & identity tables (never swept):** `accounts`, `account_identities`,
  `polar_orders`, `credit_ledger_entries` (atomic `trg_credit_ledger_after_insert` +
  `webhook_id UNIQUE` + partial unique `idx_ledger_unique_purchase` prevent double-credits).
- **Transient tables (swept by hourly cron):** `sessions` (`expires_at < now`),
  `daily_account_free_usage` / `daily_subnet_free_usage` / `daily_pool_free_usage` (`> 2` UTC days),
  `reservations` (stale `'reserved'` `> 1` day transitioned to `'kept'`; settled/kept `> 30` UTC days deleted).
- **Two-bucket atomic triggers (`trg_reservations_before_insert`, `trg_reservations_after_settle`):**
  consume the dual-gated `$0.05/day` free allowance first (account + canonical IPv4 `/32` or
  IPv6 `/64` subnet inside `$1.00/day` global pool, gated on verified email and Discord
  Snowflake age `>= 7 days`) and draw any remainder from `accounts.credit_balance_picos`,
  settling free-first / paid-second. Negative balances or `suspended != 0` block inference
  with `403 account_suspended`.

The fixed Luna Medium model uses index context, no escalation and a 12,288-token output
ceiling. Configured per-million-token prices are `$0.10` input, `$0.01` cached input,
`$0.125` cache write and `$0.50` output ([OpenAI pricing](https://developers.openai.com/api/docs/pricing)).
The request fixes the cache lifetime at 30 minutes; the proxy checks reported read/write
counts and the write-lifetime breakdown before settling. The absolute per-turn reservation
bound is `$0.016144`; valid final usage settles the reservation, while missing or
inconsistent usage keeps it in full. AI Gateway Unified Billing fees and the owner's
configured gateway limits still apply; this change adds no Cloudflare service or paid plan.

## What the proxy sees vs never stores

- **Stateless pass-through**: `messages`, bounded `<untrusted_server_sdk_index>`, and SSE
  deltas are forwarded to AI Gateway with `cf-aig-collect-log-payload: false` and never
  written to D1 or `console.*`.
- **D1 stores only**: account identity/orders/ledger metadata, hashed session tokens
  (`SHA-256`), daily-salted `SHA-256(canonicalNetwork + ":" + utcDay + ":" + IP_HASH_SALT)`
  subnet hashes, and integer picodollar/token counters. Raw IP addresses, OAuth tokens,
  Polar secrets, and prompt/SSE contents are never persisted or logged.
