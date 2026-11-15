# Materione AI credits — accounts, payments, ledger & free allowance (#386) — design

> **Scope.** How users authenticate to the model proxy (`https://ai.materione.com`), buy and spend **Materione AI credits**
> via Polar Merchant of Record, draw from the bounded daily free allowance, and never expose editor session keys (`?s=...#...`)
> to `ai.materione.com`, OAuth providers, or Polar. Companion to [`codemode.md`](codemode.md) §12.1 and
> [`web.md`](web.md).
>
> **Status.** **Design, decided by the owner (2026-11-15, #386).** Implementation follows in `cloud/model-proxy/` and `webui/`.
> Still open from §12.1: which model(s) and routing rule (#385).
>
> **Traceability.**
>
> | Decision / Section | How this doc applies it |
> |---|---|
> | **D13** (master §3.2) | The proxy only serves code-mode assistant turns (`run_program`); no server-side scripting or stored code. |
> | **D11 & §9.2** | The relay (`editor.materione.com`) stays accountless and E2E-encrypted; session wrapping keys stay in `#...` and never reach `ai.materione.com`, Google, Discord, or Polar (§2.4). |
> | **D1 & §5.1** | Files on disk are canonical content; the D1 ledger holds identity, balances, orders, and token accounting only — never content. |
> | **§14.8 (#351)** | `https://ai.materione.com` is the one new endpoint (the model proxy), forwarding to Cloudflare AI Gateway Unified Billing. |
> | **Cloudflare cost guardrails (`CLAUDE.md`)** | Workers Free + D1 Free (hard-stop, $0 infra), AI Gateway **Auto Top-Up OFF** + hard **Spend Limit**, and **Spend Never Exceeds Income + Bounded Free Pool** (§1). |

---

## 1. Purpose, Non-Goals & Core Invariant

### 1.1 Purpose & non-goals

The #402 vertical slice proved `model-proxy` with an anonymous IP-only `$0.05/day` allowance behind a hard cap. Production
needs real accounts, prepaid credit purchases, refunds/dispute handling, and multi-account Sybil resistance without breaking
D11's accountless E2E-encrypted editor relay.

**Explicit non-goals:**

- **No bring-your-own-key (BYOK)** — users pay Materione for AI credits; the proxy holds the single Cloudflare AI Gateway credential (#351).
- **No anonymous inference in production** — the anonymous IP-only mode from #402 is retired before live launch; every `POST /v1/chat`, `GET /v1/allowance`, `POST /v1/checkout`, and `POST /v1/portal` requires an authenticated Materione AI account session.
- **No team/org wallets in M6.5** — an account is one person; shared team billing is deferred.
- **No credit expiry or cash-out** — bought credits never expire and cannot be withdrawn or transferred between accounts (§4.5).
- **No content storage** — prompts, SDK indices, tool outputs, and model replies are never logged or stored in D1 (`cf-aig-collect-log-payload: false`).

### 1.2 Core invariant: Spend Never Exceeds Income + Bounded Free Pool

```
Total Cloudflare AI Gateway Wholesale Spend
  <= Sum(Net Customer Revenue Dedicated to Inference) + Bounded Daily Free Pool ($1.00/day max)
```

Four independent layers enforce this invariant simultaneously:

1. **Pre-call mathematical worst-case reservation in D1** — before any upstream fetch, a single atomic SQLite statement reserves the mathematical worst-case picodollar cost (bounded `80,000` input tokens × `max(input, cache_write)` + `4,000` output tokens × `output`).
2. **Dual-gated daily free allowance** — `$0.05/day` per verified `account_id` **and** `$0.05/day` per canonical IP/subnet, inside a `$1.00/day` global free pool (`<= $31.50/mo` worst-case including Cloudflare's 5% Unified Billing fee).
3. **Prepaid credit balance funded strictly from Polar `net_amount`** — `$1.00` net collected grants `100 credits = $0.75` wholesale AI budget (`750_000_000_000` picodollars), leaving positive safety margin even after worst-case Polar MoR international + 25% VAT fees, Stripe Connect CAD payout fees, and Cloudflare's 5% Unified Billing fee (§5).
4. **Cloudflare AI Gateway hard Spend Limit + Auto Top-Up OFF** — the gateway blocks inference below loaded prepaid Unified Billing credits; hitting it degrades `POST /v1/chat` to `503 unavailable` while leaving the editor, relay, and server plugin unaffected.

---

## 2. Account Identity, OAuth Sign-In & Session-Key Unlinkability

### 2.1 What an account is

An account represents a **person** (a Minecraft server admin or pack author), never a Minecraft server and never bound to the
relay (`cloud/editor-worker/`). Non-AI authoring over the relay or local listener remains 100% accountless (`D11` unchanged).

### 2.2 OAuth providers (decided)

Sign-in supports **Google OAuth 2.0 (OIDC)** and **Discord OAuth 2.0** only — **zero passwords**, zero email-magic-link SMTP
infrastructure, and zero password-reset attack surface.

| Provider | Protocol & flow | Scopes | Verified-email gate | Stable subject | Extra eligibility signal |
|---|---|---|---|---|---|
| **Google** | OAuth 2.0 Auth Code + PKCE (`S256`) + `nonce` | `openid email profile` | `email_verified === true` on verified ID token / userinfo | `("google", sub)` | — |
| **Discord** | OAuth 2.0 Auth Code + PKCE (`S256`) | `identify email` | `verified === true` on `/users/@me` | `("discord", id)` | **Snowflake creation age `>= 7 days`** |

**Discord Snowflake age check:** Discord's 64-bit Snowflake ID encodes exact UTC creation time:

```ts
const DISCORD_EPOCH_MS = 1_420_070_400_000n;
const createdAtMs = Number((BigInt(discordUserId) >> 22n) + DISCORD_EPOCH_MS);
```

Accounts whose Discord ID is `< 7 days` old at first sign-in are created with `free_eligible = 0` (refreshed on subsequent logins once `>= 7 days` old). They may still buy and spend paid credits immediately, stopping instant disposable-Discord farming of the free allowance.

**Account linking & email normalization:**

- Verified emails are normalized (`trim().toLowerCase()`) solely for initial lookup/linking; canonical identity is always `(provider, provider_subject)`.
- Signing in with provider B while **already holding a valid session cookie** for `account_id` A links `(B, sub_B) -> A` (refusing if `(B, sub_B)` already belongs to a different account with balances/orders).
- Signing in unauthenticated with `(provider, sub)`:
  1. Look up `(provider, sub)` in `account_identities`. If found, sign into that `account_id` (even if the user later changed their email at Google/Discord).
  2. Otherwise, look up an existing `accounts` row with matching verified normalized email. If found, link `(provider, sub)` to that `account_id` (preventing accidental split credit balances).
  3. Otherwise, insert a new `accounts` row (`id = crypto.randomUUID()`, `credit_balance_picos = 0`) and `account_identities` row.

### 2.3 Session cookie & zero-XSS-token architecture

All auth endpoints live on `https://ai.materione.com`:

| Endpoint | Behavior |
|---|---|
| `GET /v1/auth/{google\|discord}/start` | Generates cryptographic `state`, `codeVerifier` (PKCE `S256`), and `nonce`; sets short-lived (10 min) HMAC-SHA256-signed cookie `materione_ai_oauth` (`HttpOnly; Secure; Path=/v1/auth; SameSite=Lax; Max-Age=600`), and redirects `302` to Google/Discord. |
| `GET /v1/auth/{google\|discord}/callback` | Verifies `materione_ai_oauth` HMAC + `state`, exchanges `code` + `codeVerifier`, verifies `email_verified` / `verified`, upserts `accounts` & `account_identities`, generates a 256-bit `rawSessionToken`, inserts `token_hash = SHA-256(rawSessionToken)` into `sessions` (30-day TTL), clears `materione_ai_oauth`, sets `__Host-materione_ai_session=<rawSessionToken>; HttpOnly; Secure; Path=/; SameSite=Lax; Max-Age=2592000`, and serves a CSP-locked (`default-src 'none'; script-src 'sha256-...'`) HTML page that calls `window.opener?.postMessage({ type: "materione-ai-auth-complete" }, "https://editor.materione.com")` and `window.close()`. |
| `POST /v1/auth/logout` | Requires `Origin` + `Sec-Fetch-Site` check; deletes `sessions` row matching `SHA-256(cookie)` and clears `__Host-materione_ai_session` (`Max-Age=0`). |
| `GET /v1/me` | Returns `200 { authenticated: false }` when unsigned, or `200 { authenticated: true, account: { id, displayName, provider }, credits, freeAllowance }` when signed in. |
| `GET /v1/allowance` | Requires valid session (`401 unauthenticated` otherwise); returns `{ day, account, credits, freeAllowance }`. |

**Why `__Host-` + `SameSite=Lax` works across `editor.materione.com -> ai.materione.com`:**

- Both `editor.materione.com` and `ai.materione.com` share the registrable site `materione.com` (**same-site** in RFC 6265bis and the Fetch spec), so `fetch("https://ai.materione.com/v1/...", { credentials: "include" })` from `https://editor.materione.com` automatically sends `SameSite=Lax` cookies.
- Cross-site origins (e.g. `https://evil.example`) **cannot** send `SameSite=Lax` cookies on `fetch` or `POST`, and are additionally rejected by `Origin === "https://editor.materione.com"` + `Sec-Fetch-Site === "same-site"` plus `Access-Control-Allow-Origin: https://editor.materione.com` / `Access-Control-Allow-Credentials: true`.
- Because the session token is `HttpOnly`, **no bearer token ever enters `localStorage`, `sessionStorage`, JS memory, or URLs**.
- Because `__Host-` prohibits a `Domain` attribute, `__Host-materione_ai_session` is host-locked to `ai.materione.com` and **never sent to `editor.materione.com` (`cloud/editor-worker/`)**. (The temporary OAuth state cookie uses `Path=/v1/auth` so it is not even sent to `/v1/chat`, `/v1/checkout`, or `/v1/portal`, since RFC 6265bis restricts `__Host-` to `Path=/`.)

### 2.4 Proof of session-key unlinkability (`?s={code}#{wrapping-key}`)

```
  MC Server                    editor.materione.com              Browser (editor tab)
  ─────────                    ────────────────────              ────────────────────
  /materione editor ──blobs──► cloud/editor-worker/ ◄──E2E────── https://editor.materione.com/?s={code}#{wrapping-key}
  (in-game nonce approval      (ciphertext only;                 - URL fragment #... never leaves browser
   grants server edit rights)   never sees cookies or OAuth)     - Referrer-Policy: no-referrer
                                                                   │
                       ┌───────────────────────────────────────────┴──────────────────────────────────┐
                       │                                                                              │
                       ▼ window.open(popup)                                                           ▼ new tab (noopener,noreferrer)
            https://ai.materione.com/v1/auth/{google|discord}/start                        Polar Checkout (checkout.polar.sh)
                       │                                                                              │
                       ▼ OAuth 2.0 + PKCE                                                             ▼ success_url
            Google / Discord                                                               https://ai.materione.com/v1/checkout/complete
                       │                                                                              │
                       ▼ callback sets __Host-materione_ai_session                                    ▼ webhook (server-to-server)
            ai.materione.com (D1 ledger + Cloudflare AI Gateway) ◄────── POST /v1/webhooks/polar ─────┘
              - receives ONLY cookies + chat prompt/SDK index
              - NEVER sees channelId, ?s={code}, or #{wrapping-key}
```

Four structural guarantees keep editor session keys and payment/auth identities strictly disjoint:

1. `https://editor.materione.com/?s={code}#{wrapping-key}` keeps the wrapping key in the URL fragment (`#...`, never transmitted over HTTP) and serves `Referrer-Policy: no-referrer` (or `same-origin`).
2. OAuth sign-in opens in a popup (`window.open("https://ai.materione.com/v1/auth/...", ...)`) and Polar Checkout opens in a new tab (`noopener,noreferrer`) whose `success_url` is `https://ai.materione.com/v1/checkout/complete` — **never** `editor.materione.com/?s=...`.
3. The live E2E-encrypted editor tab **never reloads or navigates away** during sign-in or credit purchase; `ai.materione.com` never sees `channelId`, `code`, or `wrapping-key`, and `cloud/editor-worker/` never sees `account_id` or `__Host-materione_ai_session`.
4. The in-game handshake (`/materione editor` nonce approval) continues to authorize *session edit rights* on the Minecraft server (`codemode.md` §12.2), completely orthogonal to *who pays for AI tokens* on `ai.materione.com`.

---

## 3. Storage Choice & Two-Bucket Atomic D1 Ledger

### 3.1 Why D1 (`model-proxy-allowance`) over KV or Durable Objects

| Backend | Consistency & atomicity | Billing on Workers Free | Verdict |
|---|---|---|---|
| **Workers KV** | Eventually consistent across PoPs — concurrent requests can double-spend balances | Free tier available | **Rejected** — fails spend invariant under concurrency |
| **Durable Objects** | Strongly consistent single-threaded actors | **Requires Workers Paid plan ($5/mo min + per-GB-s billing)** | **Rejected** — violates `CLAUDE.md` free-plan guardrail |
| **Cloudflare D1** | Single-primary strongly consistent SQLite with atomic `BEFORE INSERT` / `AFTER UPDATE` triggers | **Included on Workers Free** (`5M` reads/day, `100k` writes/day, `5 GB` storage — hard-stops rather than billing) | **Chosen** |

**Why Polar's built-in Meter Credits are NOT the enforcement gate:** Polar's documentation explicitly warns that *"Polar doesn't block usage if the customer exceeds their balance."* Async HTTP meter ingestion cannot prevent a 10-way concurrent burst from overdrawing a balance in `< 2 ms`, whereas a D1 SQLite trigger enforces exact atomicity synchronously before any AI Gateway call leaves the Worker.

### 3.2 Units: internal picodollars vs. user-facing credits

- **Internal ledger unit:** exact 64-bit signed integer **picodollars** (`1 USD = 1_000_000_000_000` picos) of **wholesale AI Gateway token budget**.
  - SQLite 64-bit signed integers hold up to `9.22 × 10^18` (`~$9.22M`), overflowing never at project scale.
- **User-facing display unit:** **Materione AI credits**, defined as:
  - **`100 credits = $1.00 USD retail = $0.75 USD wholesale AI budget`**
  - **`1 credit = $0.0075 wholesale = 7_500_000_000 picodollars`**
- **Conversion:** display renders `credits = floor(credit_balance_picos / 7_500_000_000)` (or 2-decimal fractional credits when showing per-turn spend or `$0.05/day` ≈ `6.66 credits/day`), while all reservations and settlements run in exact picodollars at Cloudflare's published wholesale model rates (`models.ts`). Adding or swapping models in #385 requires zero ledger unit migration.

### 3.3 Complete D1 schema (`0002_accounts_and_credits.sql`)

Evolves #402's `0001_allowance.sql` (`daily_caller_usage` → dual-gated `daily_account_free_usage` + `daily_subnet_free_usage`, plus durable identity, session, order, and credit-ledger tables):

```sql
-- Accounts, OAuth identities, sessions, Polar orders, and two-bucket atomic reserve/settle (#386).
-- Monetary amounts are exact integer picodollars (1 USD = 1_000_000_000_000 picos) of wholesale AI budget.

CREATE TABLE IF NOT EXISTS accounts (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL,
  email_verified INTEGER NOT NULL DEFAULT 1,
  display_name TEXT,
  credit_balance_picos INTEGER NOT NULL DEFAULT 0,
  free_eligible INTEGER NOT NULL DEFAULT 1,
  suspended INTEGER NOT NULL DEFAULT 0,
  suspend_reason TEXT,
  polar_customer_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_accounts_email ON accounts(email);

CREATE TABLE IF NOT EXISTS account_identities (
  provider TEXT NOT NULL CHECK (provider IN ('google', 'discord')),
  provider_subject TEXT NOT NULL,
  account_id TEXT NOT NULL REFERENCES accounts(id),
  provider_email TEXT,
  provider_created_at INTEGER,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (provider, provider_subject)
);

CREATE INDEX IF NOT EXISTS idx_identities_account ON account_identities(account_id);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id),
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);
CREATE INDEX IF NOT EXISTS idx_sessions_account ON sessions(account_id);

CREATE TABLE IF NOT EXISTS credit_ledger_entries (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id),
  kind TEXT NOT NULL CHECK (kind IN ('purchase', 'refund', 'dispute_hold', 'dispute_release', 'manual_adjustment')),
  delta_picos INTEGER NOT NULL,
  balance_after_picos INTEGER NOT NULL,
  polar_order_id TEXT,
  webhook_id TEXT UNIQUE,
  note TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ledger_account ON credit_ledger_entries(account_id, created_at);

CREATE TABLE IF NOT EXISTS polar_orders (
  order_id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id),
  net_amount_cents INTEGER NOT NULL,
  granted_picos INTEGER NOT NULL,
  refunded_amount_cents INTEGER NOT NULL DEFAULT 0,
  refunded_picos INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_polar_orders_account ON polar_orders(account_id);

CREATE TABLE IF NOT EXISTS daily_account_free_usage (
  day TEXT NOT NULL,
  account_id TEXT NOT NULL,
  used_picos INTEGER NOT NULL DEFAULT 0,
  request_count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (day, account_id)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS daily_subnet_free_usage (
  day TEXT NOT NULL,
  subnet_hash TEXT NOT NULL,
  used_picos INTEGER NOT NULL DEFAULT 0,
  request_count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (day, subnet_hash)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS daily_pool_free_usage (
  day TEXT PRIMARY KEY,
  used_picos INTEGER NOT NULL DEFAULT 0,
  request_count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS reservations (
  id TEXT PRIMARY KEY,
  day TEXT NOT NULL,
  account_id TEXT NOT NULL,
  subnet_hash TEXT NOT NULL,
  model TEXT NOT NULL,
  reserved_picos INTEGER NOT NULL,
  free_reserved_picos INTEGER NOT NULL,
  paid_reserved_picos INTEGER NOT NULL,
  account_cap_picos INTEGER NOT NULL,
  subnet_cap_picos INTEGER NOT NULL,
  pool_cap_picos INTEGER NOT NULL,
  settled_picos INTEGER,
  free_settled_picos INTEGER,
  paid_settled_picos INTEGER,
  status TEXT NOT NULL DEFAULT 'reserved' CHECK (status IN ('reserved', 'settled', 'kept')),
  input_tokens INTEGER,
  output_tokens INTEGER,
  cache_read_tokens INTEGER,
  cache_write_tokens INTEGER,
  created_at INTEGER NOT NULL,
  settled_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_reservations_day ON reservations(day);
CREATE INDEX IF NOT EXISTS idx_reservations_account ON reservations(account_id, created_at);
```

### 3.4 Exact two-bucket atomic reserve & settle SQL algorithm

Because SQLite triggers cannot mutate `NEW.*` columns in-place, the single-statement `INSERT INTO reservations (...) SELECT ...`
computes the exact split from live state, and `BEFORE INSERT ON reservations` re-validates every invariant and performs all
four bucket mutations atomically inside the same statement:

**Reserve (1 SQL statement):**

```sql
CREATE TRIGGER IF NOT EXISTS trg_reservations_before_insert
BEFORE INSERT ON reservations
BEGIN
  -- 1. Reject suspended or negative-balance accounts immediately.
  SELECT RAISE(ABORT, 'ACCOUNT_SUSPENDED')
  FROM accounts
  WHERE id = NEW.account_id
    AND (suspended != 0 OR credit_balance_picos < 0);

  -- Ensure daily free usage rows exist.
  INSERT INTO daily_account_free_usage (day, account_id, used_picos, request_count, updated_at)
  VALUES (NEW.day, NEW.account_id, 0, 0, NEW.created_at)
  ON CONFLICT(day, account_id) DO NOTHING;

  INSERT INTO daily_subnet_free_usage (day, subnet_hash, used_picos, request_count, updated_at)
  VALUES (NEW.day, NEW.subnet_hash, 0, 0, NEW.created_at)
  ON CONFLICT(day, subnet_hash) DO NOTHING;

  INSERT INTO daily_pool_free_usage (day, used_picos, request_count, updated_at)
  VALUES (NEW.day, 0, 0, NEW.created_at)
  ON CONFLICT(day) DO NOTHING;

  -- Verify split integrity.
  SELECT RAISE(ABORT, 'INVALID_RESERVATION_SPLIT')
  WHERE NEW.free_reserved_picos < 0
     OR NEW.paid_reserved_picos < 0
     OR NEW.free_reserved_picos + NEW.paid_reserved_picos != NEW.reserved_picos;

  -- 2 & 3. Enforce free eligibility and all three daily free caps when drawing from the free bucket.
  SELECT RAISE(ABORT, 'FREE_NOT_ELIGIBLE')
  FROM accounts
  WHERE id = NEW.account_id
    AND NEW.free_reserved_picos > 0
    AND free_eligible != 1;

  SELECT RAISE(ABORT, 'ALLOWANCE_EXHAUSTED')
  FROM daily_account_free_usage a, daily_subnet_free_usage s
  WHERE a.day = NEW.day AND a.account_id = NEW.account_id
    AND s.day = NEW.day AND s.subnet_hash = NEW.subnet_hash
    AND NEW.free_reserved_picos > 0
    AND (a.used_picos + NEW.free_reserved_picos > NEW.account_cap_picos
      OR s.used_picos + NEW.free_reserved_picos > NEW.subnet_cap_picos);

  SELECT RAISE(ABORT, 'POOL_EXHAUSTED')
  FROM daily_pool_free_usage
  WHERE day = NEW.day
    AND NEW.free_reserved_picos > 0
    AND used_picos + NEW.free_reserved_picos > NEW.pool_cap_picos;

  -- 4. Require sufficient prepaid credits for the paid remainder.
  SELECT RAISE(ABORT, 'INSUFFICIENT_CREDITS')
  FROM accounts
  WHERE id = NEW.account_id
    AND credit_balance_picos < NEW.paid_reserved_picos;

  -- 5. Atomically debit free buckets and paid balance.
  UPDATE daily_account_free_usage
  SET used_picos = used_picos + NEW.free_reserved_picos,
      request_count = request_count + (CASE WHEN NEW.free_reserved_picos > 0 THEN 1 ELSE 0 END),
      updated_at = NEW.created_at
  WHERE day = NEW.day AND account_id = NEW.account_id AND NEW.free_reserved_picos > 0;

  UPDATE daily_subnet_free_usage
  SET used_picos = used_picos + NEW.free_reserved_picos,
      request_count = request_count + (CASE WHEN NEW.free_reserved_picos > 0 THEN 1 ELSE 0 END),
      updated_at = NEW.created_at
  WHERE day = NEW.day AND subnet_hash = NEW.subnet_hash AND NEW.free_reserved_picos > 0;

  UPDATE daily_pool_free_usage
  SET used_picos = used_picos + NEW.free_reserved_picos,
      request_count = request_count + (CASE WHEN NEW.free_reserved_picos > 0 THEN 1 ELSE 0 END),
      updated_at = NEW.created_at
  WHERE day = NEW.day AND NEW.free_reserved_picos > 0;

  UPDATE accounts
  SET credit_balance_picos = credit_balance_picos - NEW.paid_reserved_picos,
      updated_at = NEW.created_at
  WHERE id = NEW.account_id AND NEW.paid_reserved_picos > 0;
END;
```

And the single-statement `INSERT ... SELECT` driving it:

```sql
INSERT INTO reservations (
  id, day, account_id, subnet_hash, model, reserved_picos,
  free_reserved_picos, paid_reserved_picos,
  account_cap_picos, subnet_cap_picos, pool_cap_picos, created_at
)
SELECT
  ?1, ?2, a.id, ?3, ?4, ?5,
  MIN(?5, CASE WHEN a.free_eligible = 1 THEN MAX(0, MIN(
    ?6 - COALESCE((SELECT used_picos FROM daily_account_free_usage WHERE day = ?2 AND account_id = a.id), 0),
    ?7 - COALESCE((SELECT used_picos FROM daily_subnet_free_usage WHERE day = ?2 AND subnet_hash = ?3), 0),
    ?8 - COALESCE((SELECT used_picos FROM daily_pool_free_usage WHERE day = ?2), 0)
  )) ELSE 0 END) AS free_reserved_picos,
  ?5 - MIN(?5, CASE WHEN a.free_eligible = 1 THEN MAX(0, MIN(
    ?6 - COALESCE((SELECT used_picos FROM daily_account_free_usage WHERE day = ?2 AND account_id = a.id), 0),
    ?7 - COALESCE((SELECT used_picos FROM daily_subnet_free_usage WHERE day = ?2 AND subnet_hash = ?3), 0),
    ?8 - COALESCE((SELECT used_picos FROM daily_pool_free_usage WHERE day = ?2), 0)
  )) ELSE 0 END) AS paid_reserved_picos,
  ?6, ?7, ?8, ?9
FROM accounts a
WHERE a.id = ?10;
```

- When `INSUFFICIENT_CREDITS` fires: if `credit_balance_picos == 0`, return `429 allowance_exhausted` prompting the user to buy credits or wait for `00:00 UTC` reset; if `0 < credit_balance_picos < paid_reserved_picos`, return `429 allowance_exhausted` stating the remaining balance is below the request's worst-case reservation and prompting a top-up.
- When `ACCOUNT_SUSPENDED` fires: return `403 account_suspended` (with negative-balance / dispute guidance).

**Settle (1 SQL statement, idempotent on `OLD.status = 'reserved'`):**

Consumes **free reservation first, paid reservation second** (`free_settled = MIN(settled, OLD.free_reserved_picos)`, `paid_settled = MAX(0, settled - free_settled)`), refunding unused free allowance back to all three daily free tables and refunding unused paid credits back to `accounts`:

```sql
CREATE TRIGGER IF NOT EXISTS trg_reservations_after_settle
AFTER UPDATE OF status ON reservations
WHEN OLD.status = 'reserved' AND NEW.status IN ('settled', 'kept')
BEGIN
  UPDATE daily_account_free_usage
  SET used_picos = MAX(0, used_picos - (OLD.free_reserved_picos - MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos))),
      updated_at = COALESCE(NEW.settled_at, OLD.created_at)
  WHERE day = OLD.day
    AND account_id = OLD.account_id
    AND OLD.free_reserved_picos > MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos);

  UPDATE daily_subnet_free_usage
  SET used_picos = MAX(0, used_picos - (OLD.free_reserved_picos - MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos))),
      updated_at = COALESCE(NEW.settled_at, OLD.created_at)
  WHERE day = OLD.day
    AND subnet_hash = OLD.subnet_hash
    AND OLD.free_reserved_picos > MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos);

  UPDATE daily_pool_free_usage
  SET used_picos = MAX(0, used_picos - (OLD.free_reserved_picos - MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos))),
      updated_at = COALESCE(NEW.settled_at, OLD.created_at)
  WHERE day = OLD.day
    AND OLD.free_reserved_picos > MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos);

  UPDATE accounts
  SET credit_balance_picos = credit_balance_picos + (
        OLD.paid_reserved_picos - MAX(0, COALESCE(NEW.settled_picos, OLD.reserved_picos) - MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos))
      ),
      updated_at = COALESCE(NEW.settled_at, OLD.created_at)
  WHERE id = OLD.account_id
    AND OLD.paid_reserved_picos != MAX(0, COALESCE(NEW.settled_picos, OLD.reserved_picos) - MIN(COALESCE(NEW.settled_picos, OLD.reserved_picos), OLD.free_reserved_picos));
END;
```

The single `UPDATE` populates `free_settled_picos` and `paid_settled_picos` alongside `settled_picos` (`WHERE id = ?1 AND status = 'reserved'`).

**Mid-stream failure & client abort rules (carried over unchanged from #402, `ace3591e`):**

| Outcome | Status | Settled picodollars |
|---|---|---|
| Normal completion (`message_delta` received) | `settled` | Exact reported input + cache_read + cache_write + output |
| Pre-provider HTTP `4xx` reject | `settled` | `0` (full refund to free/paid buckets) |
| Upstream cutoff after `message_start`, before `message_delta` | `settled` | Exact input + bounded streamed output (`streamedBytes + 64`, capped at `MAX_OUTPUT_TOKENS`) |
| Client socket abort before `message_delta` | `settled` | Exact input + `MAX_OUTPUT_TOKENS` output (blocks TCP-abort output theft while refunding unused input reserve) |
| Network / timeout / `5xx` failure before `message_start` (or malformed/missing usage) | `kept` | Full `reserved_picos` kept |

### 3.5 Retention & audit

- **Transient tables (swept by daily cron):**
  - `sessions`: delete where `expires_at < now`.
  - `daily_account_free_usage`, `daily_subnet_free_usage`, `daily_pool_free_usage`: delete where `day < cutoffUtcDay(2)`.
  - `reservations`: delete where `status IN ('settled', 'kept')` and `day < cutoffUtcDay(30)` (stale `'reserved'` rows older than 1 day are transitioned to `'kept'` first).
- **Durable financial audit tables (never swept):** `accounts`, `account_identities`, `polar_orders`, and `credit_ledger_entries`. At a few hundred bytes per purchase, millions of orders fit comfortably inside D1 Free's `5 GB` cap.

---

## 4. Payments via Polar Merchant of Record

### 4.1 Why Polar MoR over raw Stripe at M6.5 scale

| Criterion | Polar (Merchant of Record) | Raw Stripe |
|---|---|---|
| **Global sales tax / VAT / GST** | **Polar registers, collects, and remits** EU/UK VAT (€0/£0 foreign threshold), Canadian GST/HST/QST, and US/global sales tax across 75+ countries | Developer must register and remit directly in every nexus |
| **Invoices & compliance** | Tax-compliant receipts, PDF invoices, and Customer Portal built in | Must build or pay for Stripe Tax + Invoicing |
| **Pre-dispute defense** | Automated pre-dispute refund handling before chargeback fees | Manual Radar / chargeback workflow |
| **Payouts** | CAD payouts via Stripe Connect Express to an individual/sole-proprietor Canadian bank account (no incorporation needed on day 1) | Same CAD bank payout |
| **Fees** | `$0/mo` fixed; Starter `5.0% + $0.50` (`+ 1.5%` international, `+ ~1.25%` CAD payout) | `~2.9% + $0.30` (`+ 1.5%` intl) — saves ~4–5% (`$10–$80/mo` under `$2k/mo` volume) |
| **Exit / migration cost** | **~150 lines** in `cloud/model-proxy/` (`checkout.ts` + `webhooks.ts`) because D1's `credit_ledger_entries` is processor-agnostic | — |

### 4.2 Custom user-selected top-up (`$10` min, `$200` max)

- **Product definition:** one Polar one-time product in organization `materione`: **"Materione AI Credits"**, tax-exclusive, USD, with a single `ProductPriceCustomCreate` price:
  - `amount_type: "custom"`
  - `price_currency: "usd"`
  - `minimum_amount: 1000` (`$10.00`)
  - `maximum_amount: 20000` (`$200.00`)
  - `preset_amount: 1000` (`$10.00`)
- **Assistant UI:** user enters any amount `$10`–`$200` (with quick-pick chips `$10`, `$25`, `$50` and live display `100 credits / $1`).
- **`POST /v1/checkout` (`ai.materione.com`):**
  1. Verifies `Origin === "https://editor.materione.com"`, `Sec-Fetch-Site`, rate limit, and authenticated `__Host-materione_ai_session`.
  2. Rejects `accounts.suspended != 0` (`403`), and validates integer `amountCents` in `[1000, 20000]` (`400`).
  3. Calls Polar `POST /v1/checkouts/` with:
     - `products: [POLAR_CREDITS_PRODUCT_ID]`
     - `amount: amountCents`
     - `external_customer_id: account.id`
     - `customer_email: account.email`
     - `customer_ip_address: rawCallerIp` (improves country tax accuracy; never persisted in D1)
     - `success_url: "https://ai.materione.com/v1/checkout/complete"`
  4. Returns `200 { checkoutUrl }`. The SPA opens `checkoutUrl` in a new tab with `noopener,noreferrer`.
- **`GET /v1/checkout/complete`:** serves a minimal CSP-locked page confirming purchase completion (or prompting return to the editor tab, which polls/refreshes `GET /v1/allowance`).

### 4.3 Standard Webhooks verification & idempotent credit grant (`POST /v1/webhooks/polar`)

- **Auth & security posture:** `POST /v1/webhooks/polar` is exempt from the browser `Origin` gate (Polar's servers call it) and authenticated strictly by **Standard Webhooks** HMAC-SHA256 verification before JSON parsing. Cloudflare **Bot Fight Mode** must remain **OFF** on `ai.materione.com` so Cloudflare does not challenge Polar's webhook deliveries.
- **Signature verification (Web Crypto):**
  - Read headers `webhook-id`, `webhook-timestamp`, `webhook-signature`.
  - Reject if `|nowSeconds - Number(webhookTimestamp)| > 300` (5-minute replay window).
  - Compute `HMAC-SHA256(base64Decode(POLAR_WEBHOOK_SECRET.replace(/^whsec_/, '')), "${webhookId}.${webhookTimestamp}.${rawBody}")` and constant-time compare against any `v1,<b64>` entry in `webhook-signature`.
- **`order.paid`:**
  - Extract `accountId = data.customer.external_id`, `orderId = data.id`, and `netCents = data.net_amount` (actual USD cents collected *after* discounts and *before* tax).
  - Verify `data.product_id === POLAR_CREDITS_PRODUCT_ID` (or order item product matches), `netCents > 0`, and `accountId` exists.
  - Compute `grantedPicos = netCents * 7_500_000_000` (`$1.00 net = 100 credits = $0.75 wholesale AI budget`). **Scaling strictly off `data.net_amount` guarantees promo/discount codes can never mint unfunded AI tokens.**
  - Run an atomic D1 `db.batch(...)` (or SQLite transaction):
    1. `INSERT INTO credit_ledger_entries (id, account_id, kind, delta_picos, balance_after_picos, polar_order_id, webhook_id, note, created_at) ...` — `webhook_id UNIQUE` guarantees idempotency; if `UNIQUE` constraint fails on a duplicate delivery, return `202` without double-crediting.
    2. `INSERT INTO polar_orders (order_id, account_id, net_amount_cents, granted_picos, status, created_at, updated_at) VALUES (...) ON CONFLICT(order_id) DO NOTHING`.
    3. `UPDATE accounts SET credit_balance_picos = credit_balance_picos + ?1, polar_customer_id = COALESCE(polar_customer_id, ?2), updated_at = ?3 WHERE id = ?4`.
- **`order.refunded` & disputes / chargebacks:**
  - Read cumulative `data.refunded_amount` (USD cents, tax-exclusive net refunded).
  - Compute `deltaRefundedCents = data.refunded_amount - polar_orders.refunded_amount_cents`. If `deltaRefundedCents > 0`, deduct `deltaRefundedPicos = deltaRefundedCents * 7_500_000_000` from `accounts.credit_balance_picos`, update `polar_orders.refunded_amount_cents` / `refunded_picos`, and record a `'refund'` `credit_ledger_entries` row (`webhook_id UNIQUE`).
  - **Friendly-fraud / spend-then-refund defense:** `accounts.credit_balance_picos` is explicitly allowed to become **negative** if the user already spent the credits before refunding or charging back. Any account with `credit_balance_picos < 0` sets `free_eligible = 0` and is blocked by `trg_reservations_before_insert` (`ACCOUNT_SUSPENDED`) until the negative balance is repaid; an unreversed chargeback/dispute sets `suspended = 1`.

### 4.4 Receipts, tax invoices & Customer Portal

- Polar automatically emails tax-compliant receipts and VAT/GST invoices upon payment.
- `POST /v1/portal` on `ai.materione.com` (authenticated session + `Origin` check) calls Polar `POST /v1/customer-sessions/` with `{ external_customer_id: account.id }` and returns `{ portalUrl }` so users can inspect order history and download invoices directly on Polar.

### 4.5 Terms, privacy, age & expiry

- **No expiry:** purchased credits never expire (avoids Canadian provincial and US/EU prepaid gift-card expiry prohibitions).
- **Non-transferable & non-withdrawable:** bound to one account; unused balances are refundable within 14 days via Polar's support/refund flow.
- **Age requirement:** minimum purchaser age 18 (or cardholder/parental consent per Polar MoR checkout terms).
- **Zero-content privacy:** `cf-aig-collect-log-payload: false` plus zero-data-retention (ZDR) posture; `ai.materione.com` stores account identity, order metadata, and picodollar accounting only.

---

## 5. Pricing & Unit-Economics Proof

**Retail price:** **`$1.00 USD = 100 Materione AI credits`** (`$0.01/credit`).
**Wholesale AI grant:** **`100 credits = $0.7500 USD`** (`750_000_000_000` picodollars) of Cloudflare AI Gateway Unified Billing inference.

Because Cloudflare charges a **5% fee** when loading Unified Billing credits, `$0.7500` of wholesale AI tokens costs **`$0.7875` USD cash** (`$0.7500 × 1.05`). Even on the single worst-case transaction — a minimum `$10.00` purchase on a non-US card in a 25% VAT country — the margin remains strictly positive:

| Purchase | Gross charged | Polar MoR fee (`5%+$0.50`, `+1.5%` intl) | Payout fee (`~1.25%` CAD) | Net USD received | Max wholesale AI fundable (`net / 1.05`) | Wholesale AI granted (`$0.75 / $1`) | Safety buffer (wholesale / % of order) |
|---|---|---|---|---|---|---|---|
| **$10.00 US** | `$10.00` | `$1.00` | `$0.11` | `$8.89` | `$8.47` | `$7.50` | **`+$0.97` (+9.7%)** (`+$1.02` cash) |
| **$10.00 Intl + 25% VAT** | `$12.50` | `$1.31` | `$0.11` | **`$8.58`** | **`$8.17`** | **`$7.50`** | **`+$0.67` (+6.7%)** (`+$0.70` cash) |
| **$25.00 US** | `$25.00` | `$1.75` | `$0.29` | `$22.96` | `$21.87` | `$18.75` | **`+$3.12` (+12.5%)** |
| **$25.00 Intl + 25% VAT** | `$31.25` | `$2.53` | `$0.28` | `$22.19` | `$21.13` | `$18.75` | **`+$2.38` (+9.5%)** |
| **$100.00 US** | `$100.00` | `$5.50` | `$1.18` | `$93.32` | `$88.88` | `$75.00` | **`+$13.88` (+13.9%)** |
| **$100.00 Intl + 25% VAT** | `$125.00` | `$8.63` | `$1.14` | `$90.24` | `$85.94` | `$75.00` | **`+$10.94` (+10.9%)** |

*(Note: on the `$10.00` Intl + 25% VAT row, Polar deducts `6.5% × $12.50 + $0.50 = $1.31` from the `$10.00` pre-VAT price while remitting the `$2.50` VAT directly to the buyer's jurisdiction, leaving `$8.69` before the `~1.25%` Stripe Connect CAD payout fee.)*

**How model price differences appear to the user:**

- D1 debits `credit_balance_picos` at the exact wholesale picodollar rate of whichever model served the turn (`models.ts`), and the UI displays `credits = picos / 7_500_000_000`.
- On a cheap model (`claude-haiku-5-5`, ~$0.001–$0.003 wholesale/turn), a turn naturally consumes **~0.15–0.40 credits** (~2,500–6,000 turns per `$10` purchase).
- If #385 later enables escalation to a mid-tier model, that turn automatically debits its exact wholesale picodollar price (`~4–10×` more credits per turn) with zero schema or ledger changes.

---

## 6. Free Daily Allowance & Fraud/Abuse Controls

### 6.1 Dual-gated `$0.05/day` free allowance under a `$1.00/day` global pool

Every eligible account receives **`$0.05/day` (`50_000_000_000` picos ≈ `6.66 credits/day`)**, resetting at **`00:00 UTC`**, gated by **four simultaneous checks** inside `trg_reservations_before_insert`:

1. **Verified eligible account:** signed-in via Google (`email_verified === true`) or Discord (`verified === true` **and** Snowflake age `>= 7 days`). Accounts failing eligibility (`free_eligible = 0`) get `$0.00/day` free allowance but can still buy and spend paid credits immediately.
2. **Per-account cap (`$0.05/day`):** `daily_account_free_usage(day, account_id)` caps each person at `$0.05/day`.
3. **Per-canonical-subnet cap (`$0.05/day`):** `daily_subnet_free_usage(day, subnet_hash)` uses a daily-salted SHA-256 hash of the canonical IPv4 `/32`, IPv6 `/64`, or 6to4 (`2002::/16`) `/48` network (`identity.ts`, #402). Multiple Google/Discord accounts sharing one home or VPS share **one** `$0.05/day` free bucket.
4. **Global daily free pool (`$1.00/day`):** `daily_pool_free_usage(day)` caps aggregate free inference across *all* users at `$1.00/day` (`1_000_000_000_000` picos), hard-bounding worst-case free-tier cost at **`<= $31.50/month` including Cloudflare's 5% fee** even against a distributed botnet of aged accounts.

### 6.2 Abuse & safety carry-overs from #402 (`ace3591e`)

- **Browser boundary:** `Origin === "https://editor.materione.com"` + `Sec-Fetch-Site` check on all SPA API routes (`POST /v1/chat`, `GET /v1/allowance`, `GET /v1/me`, `POST /v1/auth/logout`, `POST /v1/checkout`, `POST /v1/portal`).
- **Fail-closed rate limiting:** `10 req/min` Workers `RATE_LIMITER` binding (`503` if missing or throwing), applied per `account_id` and per `subnet_hash`.
- **Mathematical input upper bound:** exact serialized upstream payload checked before D1 reservation: `upstreamUtf8Bytes + 1_024 + framing * 16 <= 80_000` (`MAX_INPUT_TOKENS`), returning `413 too_large` and making 200k 5× long-context pricing unreachable even on 1-byte-per-token ASCII payloads.
- **Output ceiling:** `MAX_OUTPUT_TOKENS = 4_000`.
- **Anti-general-purpose LLM lock:** `buildIndex` line-grammar validation on the SDK index slice, client instruction/tool schema rejection, and server-injected `SERVER_SYSTEM_CHARTER` + `CANONICAL_RUN_PROGRAM_TOOL` (`run_program({ text })` only).
- **Zero-content logging:** no prompt text, SDK index, or model response ever written to D1 or Worker console logs; `cf-aig-collect-log-payload: false`.

---

## 7. Cloudflare Float Replenishment & Full Infrastructure Cost Model

### 7.1 Owner float replenishment modes

| Phase | How Unified Billing stays funded | Guardrail alignment |
|---|---|---|
| **Phase 1 — Launch (Manual Prepaid Float + Alert)** | Owner manually preloads e.g. `$25–$50` of Cloudflare AI Gateway Unified Billing credits with **Auto Top-Up OFF** and Gateway **Spend Limit** set below loaded credits. `model-proxy` optionally posts an alert to `ALERT_WEBHOOK_URL` when cumulative settled spend reaches `80%` of `LOADED_GATEWAY_CREDITS_USD`. | Compliant with `CLAUDE.md` as written |
| **Phase 2 — Optional Revenue-Isolated Virtual Card** | Route Polar payouts into a dedicated no-overdraft bank sub-account holding *only* customer AI revenue, and cap any card attached to Unified Billing to that balance. | Requires explicit owner amendment to `CLAUDE.md` before enabling |

### 7.2 Complete infrastructure & per-unit cost model

| Component | Free-plan / published limit | Row / request footprint per operation | Cost at M6.5 launch |
|---|---|---|---|
| **Cloudflare Workers (`ai.materione.com`)** | `100,000` req/day hard-stop (Error 1027) | `1` req per `/v1/chat`, `/v1/allowance`, `/v1/checkout`, `/v1/portal`, `/v1/webhooks/polar`; `2` req per OAuth login | **`$0/mo`** |
| **Workers Rate Limiting (`RATE_LIMITER`)** | Included on Workers Free | `1`–`2` checks per API request | **`$0/mo`** |
| **Cloudflare D1 (`model-proxy-allowance`)** | `5M` reads/day, `100k` writes/day, `5 GB` storage — **hard-enforced, never bills** | • **OAuth login:** ~`2–4` reads, ~`3–4` writes<br>• **`GET /v1/allowance` / `/v1/me`:** ~`2–3` reads, `0–1` write (`last_seen_at` throttled to >15 min)<br>• **`POST /v1/chat` (reserve + settle):** ~`5–6` reads, ~`6–12` writes<br>• **Polar webhook:** ~`2` reads, ~`3` writes | **`$0/mo`** (~8,000–14,000 chat turns/day headroom under 100k D1 writes/day) |
| **Cron Triggers** | Included on Workers Free | `1` daily sweep (expired sessions, `>2d` free usage, `>30d` reservations) | **`$0/mo`** |
| **Google & Discord OAuth 2.0** | Standard OAuth apps | `2` server-side token/userinfo HTTP fetches per login | **`$0/mo`** |
| **Polar (Merchant of Record)** | `$0/mo` fixed | `5% + $0.50` (`+1.5%` intl, `+~1.25%` payout) **only on paid sales** | **`$0/mo` fixed**; covered with `+6.7%`–`+13.9%` margin (§5) |
| **Cloudflare AI Gateway (Unified Billing)** | Prepaid credits + hard **Spend Limit** (**Auto Top-Up OFF**) | `5%` credit-purchase fee + wholesale token rates | **Capped at `$1.00/day` free pool (`<= $31.50/mo`) + customer-prepaid float** |

---

## 8. Implementation Plan (post-approval)

1. **`cloud/model-proxy/` (follow-up PR after #407):**
   - Apply D1 migration `0002_accounts_and_credits.sql` (`accounts`, `account_identities`, `sessions`, `credit_ledger_entries`, `polar_orders`, `daily_account_free_usage`, `daily_subnet_free_usage`, `daily_pool_free_usage`, `reservations`, and two-bucket triggers).
   - Add Google + Discord OAuth PKCE endpoints (`/v1/auth/{google|discord}/{start|callback}`, `POST /v1/auth/logout`, `GET /v1/me`) with `__Host-materione_ai_session` `HttpOnly` cookie auth.
   - Replace IP-only caller gating on `/v1/chat` and `/v1/allowance` with session auth + dual-gated free bucket + prepaid credit balance; retire anonymous mode.
   - Add Polar endpoints (`POST /v1/checkout`, `GET /v1/checkout/complete`, `POST /v1/webhooks/polar` with Standard Webhooks HMAC verification, `POST /v1/portal`) and optional `ALERT_WEBHOOK_URL` float watermark check.
2. **`webui/` (`webui/src/lib/codemode/chat/` + Assistant panel):**
   - Implement the remote `ModelProvider` SSE client calling `https://ai.materione.com/v1/chat` with `credentials: "include"` when `EditorHost === 'cloud'`.
   - Wire the Assistant header/account bar: Sign in with Google / Discord popup flow (`postMessage` listener), live display of remaining daily free allowance + paid Materione AI credits (`GET /v1/allowance`), custom `$10–$200` Buy Credits form (`$10` / `$25` / `$50` quick-pick chips + live `100 credits / $1` preview calling `POST /v1/checkout`), and a Billing/Invoices link (`POST /v1/portal`).
