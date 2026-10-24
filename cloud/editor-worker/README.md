# Materione editor relay worker

The relay behind `editor.materione.com` (web.md §2–§3): one stateless Cloudflare
Worker that serves the editor SPA as static assets and speaks the `/v1` API —
a **frame mailbox** (long-poll channels for the plugin↔browser sealed-frame
protocol) and a **payload store** (TTL'd, envelope-only, content-addressed
blob store). Storage is one D1 database; an hourly cron sweeps TTLs. No
WebSocket, no Durable Objects, no R2, no secrets in the Worker — the whole
service fits in the Cloudflare **free tier, which hard-stops rather than
bills** (cost model: `docs/architecture/web.md` §3.3).

## Layout

```
src/index.ts     router + hourly TTL sweep (scheduled)
src/channels.ts  mailbox endpoints (create/post/poll/close)
src/store.ts     payload store endpoints
src/quotas.ts    daily usage counters + caps
src/config.ts    quota/config defaults (override via dashboard vars)
src/codes.ts     capability tokens/codes + sha256 hashing
src/http.ts      env types, response helpers, rate-limit binding use
migrations/      D1 schema
test/            vitest on the real Workers runtime (@cloudflare/vitest-pool-workers)
```

The wire contract is pinned by the shared golden fixture
[`fixtures/protocol/relay-mailbox-v1.json`](../../../fixtures/protocol/relay-mailbox-v1.json)
(the plugin's `RelayClient` tests and the SPA's `LongPollTransport` tests read
the same file).

## Developing

```
npm install
npm test          # vitest against a real Workers runtime + D1 (local)
npm run check     # tsc --noEmit
npm run deploy:dry
```

Quotas are code defaults in `src/config.ts`; override any of them with Worker
vars (dashboard or wrangler `vars`) without redeploying logic.

## Deploying (owner-run — agents never deploy; CLAUDE.md cost guardrails)

One-time setup on your Cloudflare account (free plan, no card-charged
products activated):

1. **API token** (dash.cloudflare.com → My Profile → API Tokens → Create
   Token → Custom token), all on the account that owns `materione.com`:
   Account · Workers Scripts · Edit; Account · D1 · Edit; Zone · Zone · Read;
   Zone · DNS · Edit (zone resources: materione.com).
2. **Database**: `npx wrangler d1 create editor-relay` (after
   `npx wrangler login`; the dashboard works too) and put the printed
   `database_id` into `wrangler.jsonc` (`d1_databases[0].database_id`).
3. **Repo secrets** (Settings → Secrets and variables → Actions, or
   `gh secret set` from the repo root so the token never leaves your
   machine): `CLOUDFLARE_API_TOKEN` (the token) and `CLOUDFLARE_ACCOUNT_ID`
   (dashboard right sidebar, or `npx wrangler whoami`).
4. **Dispatch** the `deploy editor relay` workflow. It builds the SPA, runs
   the worker tests, applies the D1 migrations, deploys, and attaches the
   custom domain — the `routes` block in `wrangler.jsonc` creates the
   `editor.materione.com` record in the `materione.com` zone automatically
   (the zone must be on the same account).

Manual equivalent from `cloud/editor-worker/` on your machine
(after `npx wrangler login`): `npx wrangler d1 migrations apply editor-relay
--remote` then `npx wrangler deploy`.

## Self-hosting (constraint #3)

Everything here is open-source and self-hostable: run the same commands
against your own account/zone and point servers at your instance via the
plugin's `relay_base_url` setting (`settings.yml`). The only Cloudflare
coupling is D1; the schema is tiny SQL and portable to any SQL store if you
fork the storage seam. Your instance, your quotas — the defaults are tuned
for the *shared* free instance and can be raised freely on your own.

## What the relay can and cannot see

The relay stores and forwards **ciphertext only**: sealed frames
(`{seq, nonce, ct, sig}`) and encrypted envelopes. It cannot read, forge,
replay, or reorder session content — the plugin and browser verify every
signature and sequence number end-to-end (web.md §5). What it does see:
traffic analysis (sizes, timing, endpoints) and the useless-without-keys
capability codes.
