# Web & Cloud Architecture — Technical

> **Traceability.** Implements D11 (outbound-only connectivity) and constraint #3 (free & open,
> self-hostable) from [`architecture.md`](../architecture.md). Companions:
> [`plugin.md`](plugin.md) · [`runtime-systems.md`](runtime-systems.md).
>
> **Scope:** the Cloudflare Workers serverless ecosystem (editor SPA + relay services + project
> website), the wire protocol & crypto between plugin and browser, self-hosting, and
> operations. The editor's *authoring UX* lives in the master doc §9; this doc is the
> infrastructure behind it.

---

## 1. Why Cloudflare Workers (and what that commits us to)

The user requirement: **serverless on Cloudflare Workers**. This fits D11 almost perfectly,
because the LuckPerms-style architecture needs the cloud side to be *dumb pipes* — and dumb
pipes are exactly what Workers are good at:

| Property | Fit |
|---|---|
| Outbound-only from the MC server | The plugin dials out to Workers over HTTPS; no inbound to the server, ever (D11) |
| Stateless request handling | The editor SPA is stateless; the relay is one stateless Worker plus D1 rows — opaque encrypted blobs (bytebin-style) and cursor-paged frames |
| Plain HTTPS everywhere | The interactive channel is a **long-poll frame mailbox** — no WebSocket, no Durable Objects, no long-lived cloud state (D11 amendment) |
| A free tier that cannot bill | Workers Free and D1 Free **hard-stop** at their daily limits (Error 1027 / query errors) and static assets are free and unlimited — the shared instance is $0 by construction (§3.3) |
| Global edge anycast | Admins get a nearby editor endpoint regardless of where the server lives |
| No servers to operate | Zero VMs for an OSS project to maintain |

Commitments we accept: TypeScript everywhere on the cloud side (Workers runtime, no JVM);
per-request CPU limits (fine — heavy work like pack compilation never runs in the cloud; it
runs on the MC server's pipeline); Cloudflare bindings kept to **D1 + Cron** only, contained
behind small adapter seams so self-hosters can swap them (§8) — and D1 is plain SQL over a
tiny schema, not a proprietary data model.

**Design rule carried throughout: the cloud never sees plaintext.** All relayed payloads are
E2E encrypted between plugin and browser (master §9.2); Workers store and forward ciphertext
only. The cloud is untrusted by construction.

---

## 2. Cloud topology

```
                        ┌────────────────────────────────────────────┐
                        │            cloudflare edge (workers)       │
                        │                                            │
  editor SPA ◄──────────┤  editor.materione.com   Worker + static    │
  (browser, stateless)  │                         assets (the SPA)  │
       │  same-origin   │                         + /v1/* API:      │
       │  HTTPS         │                         frame mailbox +   │
  plugin ──────────────►│  (outbound)             payload store     │
  (MC server,           │                                            │
   outbound HTTPS,      │  cron trigger          scheduled Worker   │
   E2E-encrypted        │                         (TTL sweeps)      │
   blobs + frames)      │                                            │
       │                │  D1 "editor-relay"     channels / frames / │
       │                │                         blobs rows        │
       │                │  materione.com         (reserved — the    │
       │                │                         website is        │
       │                │                         deferred, §7)     │
       └────────────────┴────────────────────────────────────────────┘

  NOT in the cloud: player-facing pack hosting (master §8.4) — that stays
  on the MC server's delivery plane (self-host/S3/CDN) because packs are
  public files the server already uploads.
```

One Worker, one D1 database, one cron trigger, one zone — each product has one job:

| Product | URL / binding | Job |
|---|---|---|
| Workers (static assets) | `editor.materione.com` | Serve the editor SPA build (the *same* artifact embedded in the jar) — free, unlimited requests |
| Workers (fetch handler) | `editor.materione.com/v1/*` | Relay API: frame mailbox (§3.2) + payload store (§3.1) + quotas (§3.3) |
| D1 | `editor-relay` | `channels`, `frames`, `blobs` rows — the relay's entire state |
| Cron Triggers | hourly | TTL sweep of expired channels/frames/blobs (§3.4) |

Deliberately **not** used: Durable Objects (no WebSocket, no long-lived state — D11
amendment), R2 (its beyond-free-allowance billing is the one product that could ever charge;
D1-only avoids that class entirely), KV, Queues, Workers AI, and Workers Sites (superseded
by static assets). The zone stays on the free plan; nothing here requires — or can spend
on — a paid plan.

---

## 3. The relay service — spec

> *Status: M5 build in progress. This section is the spec; per-section "as built" notes land
> with the implementing PRs.*

One small service, bytebin-inspired but written to our spec (master open question #5,
resolved: **write minimal equivalents** — the no-WebSocket amendment to D11 rules out a
bytesocks fork outright, and our crypto/session model differs enough that a bytebin fork
buys nothing). It is a single Worker behind two URL namespaces — a dumb blob store and a
frame mailbox. Both are dumb pipes: they store and forward ciphertext only, know no
accounts, and hold no state beyond D1 rows with TTLs.

### 3.1 Payload store (`editor.materione.com/v1/store`)

A dumb, opaque blob store with TTL. Endpoints:

| Method & path | Purpose |
|---|---|
| `POST /v1/store` | Upload encrypted payload → `{code, deleteToken, expires}` |
| `GET /v1/store/{code}` | Fetch encrypted payload by unguessable code |
| `DELETE /v1/store/{code}` | Delete (requires the per-blob delete token from upload) |
| `GET /v1/store/{code}/meta` | Size, expiry — no plaintext metadata leaks |

Semantics (storage is D1 rows, not R2 — chunk-sized envelopes fit a SQL row comfortably,
and D1's free tier hard-stops instead of billing):

- **Codes** are unguessable (§5.3), short base-N strings, capability-style.
- **Max size** 50 MB per payload (a full pack dump with assets), arriving as the
  **manifest + content-addressed chunks** flow (§4.3); each POST is one chunk-sized
  envelope (default 512 KiB — a constant, verified against D1 row limits).
- **TTL classes**: 1 h (default), 24 h, 7 d. Expiry via cron sweep (§3.4) + lazy check on
  read. Nothing persists forever; the relay deletes only by TTL or delete token.
- **Envelope content-type only** — the store refuses anything that is not a Materione payload
  envelope; it is not a general file host.
- **No accounts.** Auth is code secrecy + E2E encryption. Rate limits are per-IP (§3.3).
  There is no user database in the cloud at all (constraint #3; also nothing to leak).
- **Dedup for free**: chunks are content-addressed by their ciphertext hash
  (`INSERT OR IGNORE`) — an unchanged asset across uploads stores and transfers nothing.

### 3.2 Frame mailbox (`editor.materione.com/v1/channels`)

A signed-message channel service with **no long-lived server state**: each side speaks plain
HTTPS; a "channel" is two D1 frame queues. (Amends the original WebSocket-relay design per
D11: no WSS, no Durable Objects — a stateless Worker serves every request.)

Connection model:

1. Plugin runs `/materione editor` → generates session keys (§5), creates a channel
   (`POST /v1/channels` → `{channelId, pluginToken}`), joins it, uploads an E2E-encrypted
   session blob to the store, and prints the admin a URL:
   `https://editor.materione.com/?s={code}`.
2. The browser decrypts the session blob locally (§5.4); it contains the channel id, an
   editor capability token, and the key material needed to complete the handshake.
3. Both sides speak the **same sealed-frame protocol as direct mode**: frames are posted to
   the channel and fetched by long-poll. The mailbox sees only `{seq, nonce, ct, sig}` frames
   (ciphertext + detached signature, LuckPerms' frame format) and can neither read nor
   forge contents.

API:

| Method & path | Purpose |
|---|---|
| `POST /v1/channels` | Create channel → `{channelId, pluginToken}` (plugin-only; rate-limited) |
| `POST /v1/channels/{id}/frames` | Post a batch of sealed frames (≤ 1 MiB each, ≤ 32/batch); the caller's token selects the direction |
| `GET /v1/channels/{id}/frames?after={cursor}&wait={s}` | Long-poll the frames addressed to the caller; non-destructive cursor reads |
| `POST /v1/channels/{id}/close` | Close the channel (either capability) |

Channel rules:

- **Two capabilities per channel**: the `pluginToken` (from creation) and the editor token
  (inside the encrypted session blob — so only the URL holder can join). Both are ≥128-bit
  unguessable strings (§5.3); possession is the transport-level auth. The server stores
  only their hashes.
- **Max frame 1 MiB**; larger data goes through the payload store (§4.3).
- **Long-poll**: `wait` ≤ 25 s; the Worker internally re-checks D1 roughly every 1.5 s
  (`scheduler.wait()` — wall-clock time is free on Workers; only CPU counts, and awaits are
  not CPU). Interactive RPC round-trip latency is typically 0.3–2 s; direct mode remains
  the low-latency path on LAN (§4.4).
- **Ordered, non-destructive reads**: frames return in strict insertion order by cursor and
  are retained until the channel's cap/TTL — so the protocol's strict per-direction
  sequence numbers work unchanged, and the retained frames *are* the catch-up buffer: a
  briefly disconnected editor resumes `after={cursor}` and continues the same session. A
  reader that falls behind the retention horizon gets a clean error and re-handshakes —
  never silent corruption.
- **Keepalive**: presence is the long-poll itself (reads, not writes); the plugin posts a
  channel heartbeat every ~2 min, and the editor's application-level `ping` rides frames
  sparsely. Channels whose both sides stop polling are swept (§3.4).
- **Channel TTL 12 h**, renewable by plugin presence; channels also die on explicit close
  or when the server stops.
- **Frame retention cap** per channel (2 000 frames / 16 MiB) keeps the shared instance
  bounded; the sweep prunes oldest-first.

### 3.3 Cost model, quotas & abuse (free tier, verified 2026-10)

**Billing facts** (Cloudflare docs): Workers Free = 100 000 requests/day, then **Error 1027
hard stop**; D1 Free = 5 M rows read + 100 k rows written per day + 5 GB storage,
**hard-enforced since 2026-09-01** (queries fail — nothing bills); **static asset requests
are free and unlimited**; one cron trigger is free. The account stays on the free plan
(cost guardrails, CLAUDE.md) and R2 is never activated. The shared instance is therefore
**$0/month by construction**: the worst case is 429s and availability, never a charge.

Per-session economics (long-poll wait 20 s, internal poll ~1.5 s, sparse heartbeats):
≈ 430 Worker requests/h, ≈ 10 k D1 row reads/h, and ≤ ~1.5 k row writes/h while both sides
are connected and busy.

| Daily scenario (sessions × length) | Worker requests | D1 reads | D1 new-row writes |
|---|---|---|---|
| Light — 10 × 1 h | 4.3 k (4%) | 100 k (2%) | 3 k (3%) |
| Expected — 30 × 2 h | 26 k (26%) | 600 k (12%) | 30 k (30%) |
| Busy — 60 × 3 h | 77 k (77%) | 1.8 M (36%) | 40 k (40%) — quota-capped |

Row-*write* accounting is the binding constraint, and D1 also bills the sweep's **deletes**
as rows written: steady-state total ≈ 2 × new-row writes (insert now, delete at expiry), so
the quotas below cap *new* rows at 40 k/day and the true total stays under ~80 % of the
100 k ceiling even on a fully-quota'd day.

In-Worker quotas (config vars) keep the shared instance under the ceilings and degrade with
clear 429s pointing at the self-hosting docs (constraint #3): **20 concurrent channels**,
**12 h channel TTL**, **8 h idle sweep**, **40 k new-row writes/day self-cap**, **2 GB
stored**, **50 MB max payload**, blob TTL classes 1 h / 24 h / 7 d, **20 k store POSTs/day**.
Row reads carry no separate self-cap: every query is index-pointed (~1–3 rows), projections
sit far under the 5 M/day hard stop, and D1's own enforcement is the backstop.

Abuse posture: a Workers **Rate Limiting binding** (per-IP, per-endpoint) on every public
route, plus envelope-only content type, unguessable tokens, and the hard quotas above. The
sustainability posture is unchanged: the shared instance is a convenience, not a promise —
abuse or cost pressure shrinks quotas before it degrades the project, and self-hosting is
always the documented escape hatch.

### 3.4 TTL sweep

One hourly cron invocation deletes expired blobs, dead channels and their frames, and
prunes channels over the retention cap (oldest frames first). Deletes are idempotent; the
sweep is also the stored-below-2 GB enforcement point.

---

## 4. Wire protocol (plugin ↔ browser)

One versioned protocol across both transports (relay and direct). Same messages, same signing,
same E2E encryption — only the transport differs. Direct mode simply cuts out the relay.

### 4.1 Session establishment (key transport fully specified)

The session blob is the trust root of the whole exchange, so its key transport is exact:

1. Plugin runs `/materione editor` → generates a **session wrapping key** (random 256-bit) and
   an Ed25519 signing keypair.
2. Plugin creates the relay channel, joins it, and uploads the **session blob** to the store,
   encrypted with the wrapping key (AES-256-GCM). The blob contains: session id, channel id,
   editor capability token, plugin signing public key, protocol versions supported — plus,
   when the embedded listener is reachable from the admin's machine, a **direct-mode hint**
   (LAN auto-preference, §4.4).
3. Plugin prints the admin a URL of the form
   `https://editor.materione.com/?s={code}#{wrapping-key}` — **the store code identifies the
   blob, the URL fragment carries the wrapping key** (same grammar as direct mode: the SPA
   boots from `/`, reads `?s=`, and fetches the blob from `GET /s/{code}` on the same
   origin). Fragments are never sent to any server
   (not logged by the store, not in Referer, not in edge logs). The code alone is useless;
   the key alone is useless.
4. Browser fetches the blob by code, decrypts with the fragment key, and learns the channel
   id and the plugin's signing public key.
5. Browser generates its own Ed25519 keypair and an **ECDH ephemeral keypair**; sends `hello`
   over the channel: `{nonce, sessionId, editorSigningPubKey, editorEphemeralPubKey, browser}`.
6. Plugin confirms trust **in-game** (the nonce shown in-game disambiguates multiple editors).
   Plugin replies `hello-reply` with its own ECDH ephemeral public key and a **key-confirmation
   digest** — both sides derive the session symmetric key from the two ECDH ephemerals and
   verify the digest, catching any derivation mismatch immediately rather than at first use.
7. From then on: **every channel frame is encrypted *and* signed** — body encrypted with the
   session key (AES-256-GCM, per-frame nonce), then signed (Ed25519). Both directions carry
   **monotonic sequence numbers**; receivers reject gaps, replays, and reordering. The relay
   sees only ciphertext plus a sequence number.

```
plugin                                  browser
  │ /materione editor                      │
  │ wrapping key + signing keypair         │
  │ create channel, join it                │
  │ upload wrapped session blob            │
  │ print /?s={code}#{wrapping-key}        │
  │ ◄────── admin opens URL ──────────────►│
  │                                        │ fetch blob by code, decrypt with fragment key
  │ ◄──── hello {nonce, sessionId,         │
  │         signingPub, ecdhPub, browser} ─│
  │ admin confirms in-game (by nonce)      │
  │ ── hello-reply {ecdhPub, confirm} ────►│
  │      both derive session key (ECDH)    │
  │      + key-confirmation digest checked │
  │ ◄══ frames: encrypted + signed + ══════►│
  │     sequence-numbered, both ways       │
```

After the handshake, requests flow both ways: the plugin pushes live state; the browser issues
document-store reads/writes. Both sides verify every signature and sequence number; either
drops the session on a bad value. The wire format is identical in direct mode (M3, shipped);
the mailbox transport arrives with M5 and changes no bytes on the wire.

### 4.2 API surface (document-store operations)

A small, versioned RPC set over the established channel:

| Group | Operations (illustrative) |
|---|---|
| **session** | `hello`, `hello-reply`, `ping`/`pong`, `close`, `session.info` |
| **browse** | list packs, list resources (type-filtered), get resource, "where used" graph query, search |
| **edit** | put resource (with revision), delete, move/rename, create pack, import pack |
| **authoring** | describe capabilities, create resource/project, commit a field batch, save a model transaction |
| **validate** | dry-run validation of an unsaved resource → diagnostics |
| **pipeline** | trigger targeted/full reload, pre-apply report, apply confirm |
| **assets** | upload (chunked via payload store), delete, list |
| **preview** | subscribe to live preview events, request preview renders |
| **delivery** | candidate/published status, publish an exact previewed hash |
| **admin** | diagnostics feed, doctor summary, audit query, settings read (security keys never leave the server) |

Requests carry `{op, id, payload}`; responses `{op, id, ok, data|error}`. Errors reuse the
plugin's structured diagnostic codes. **Protocol version is negotiated at hello**; unknown ops
return a clean error, never a disconnect.

*As built (M3 + workbench update):* session `ping` / `session.info` (host status
extras); browse packs / resources (type + query filters) / **folders** / get / history / types;
edit put / delete / setScalar (scalars **and scalar lists** through the lossless
layer) / create (curated per-type scaffolds, duplicate-refusing) / createPack /
move (journal-truthful renames) / **createFolder, deleteFolder, moveFolder** (the
organisational folders the content browser exposes); authoring **createResource**
(any registered type, into any folder) / **duplicateResource** / **setFields** (a
batch of field edits — including structured values such as furniture hitboxes or a
recipe's ingredient map — as one revision, one journal entry and one reload) /
commit / saveModel; validate dryRun (syntax gate); assets
list / put / get / delete / **move**; pipeline reload (everything / one-pack —
fire-and-forget, outcome as `push.reload`, always announced for manual triggers);
admin diagnostics.recent (with source location) / audit.recent / settings.get /
settings.put (hash-guarded, validated before disk). Pushes: `push.reload`,
`push.diagnostic` (live, off the bus subscribe seam), and `push.document.changed`
(pack, path, deleted — the external-edit feed: a configurable WatchService pump
over `content/packs/` (~500 ms debounce, R5-clean via the scheduling gateway)
journals drift as an *external* revision through `DocumentStore.syncExternal` and
pushes to every editor; editor saves echo back unchanged because their write
already journaled). *M4 media addition:* `browse.get` responses for glyph
documents carry a read-only `computed` block — the server-allocated codepoint
(the durable allocation table is the truth; the file on disk never contains it,
and the studio preview renders it). Asset uploads are capped per extension:
2 MiB for textures/models, 8 MiB for audio (`ogg`/`oga`) — bigger sources wait
for M5's chunk flow. Still illustrative-only:
where-used graph, import pack, pre-apply report / apply confirm, preview renders,
`close`. Concurrency notes learned the hard way: pushes and RPC replies seal from
different threads — `EditorSession.sendFrame` synchronizes seal+write so sequence
assignment matches wire order, and the browser transports buffer frames arriving
during the handshake→channel handover instead of dropping them (a dropped frame
desyncs the strict receive sequence).

*Planned:* `authoring.describe` completes the capability handshake. `delivery.status`
exposes candidate/published hashes and `delivery.publish` promotes an expected candidate
hash or returns a visible stale error (as built). These are
capability-negotiated additions; the primitive M3 operations remain available to Advanced Files
and older clients. Resource and asset listings gain pagination/filtering before relay mode makes
eager full-pack traversal a scale boundary.

### 4.3 Large payloads — manifest + content-addressed chunks

Documents are small (KBs) and ride the frame channel. Assets (textures, models, sounds — tens
of MB) never ride the channel; they flow through the **payload store as a manifest + chunks**,
in both relay and direct mode (direct mode embeds a store-compatible endpoint):

```
upload:   asset → chunk (1 MiB) → encrypt each with session key →
          chunk hash (of ciphertext) = chunk id → upload chunks →
          upload manifest {asset name, size, chunk ids, total hash}
download: fetch manifest → fetch chunks (parallel) → decrypt → verify → assemble
```

- **Content addressing gives dedup for free**: an unchanged asset across pack dumps has
  identical chunk ids, so re-uploading a near-identical pack stores/transfers only what
  changed. Store-side quota pressure collapses (the §3.3 sketch depends on this).
- **Resumable** by construction: a failed transfer resumes at missing chunk ids.
- **The relay/store sees only encrypted chunks and hashes** — it cannot dedupe by content
  meaningfully, only by ciphertext identity (which is exactly what we want: dedup works,
  privacy holds).
- **Bandwidth honesty**: through the shared relay, every asset byte crosses the edge twice
  (upload + download). Large asset workflows (Blockbench sessions with many textures) should
  prefer direct mode on LAN — the editor detects a reachable direct endpoint and prefers it
  automatically.

### 4.4 Direct mode (embedded)

The plugin embeds a light HTTP + WS server (JVM) exposing exactly the same routes and message
set, binding `127.0.0.1` by default. The handshake is identical, but the session blob never
leaves the machine (localhost URL instead of a store code). Remote direct access for teams is
a documented reverse-proxy pattern. Direct mode exists so relay outage ≠ editor outage, and
for air-gapped setups.

Security notes: the embedded server binds loopback only unless explicitly configured
otherwise; binding `0.0.0.0` triggers a startup warning; and the **same signed-handshake auth
applies in direct mode** — loopback binding is a convenience, not the auth (anything that can
reach the port still must complete the handshake and in-game trust).

M5 additions: the embedded listener also serves the **store-compatible routes**
(`/v1/store*`) so the §4.3 chunk flow is one client implementation on both transports, and it
sends CORS headers on its read routes (`GET /s/{code}`, `GET /v1/store/*`) so the
centrally-hosted SPA can probe a reachable local server and prefer it — **LAN
auto-preference**: the session blob carries the direct hint when the listener is likely
reachable, the cloud-hosted SPA probes it with a short timeout before the first frame and
switches to the local WebSocket transport when it answers, falling back to the relay
otherwise. Manual override is a URL parameter. Direct mode exists so relay outage ≠ editor
outage, and for air-gapped setups.

---

## 5. Cryptography (the part that must be exactly right)

### 5.1 Principles

- The relay/store/edge are **untrusted**; secrecy and integrity are end-to-end between plugin
  and browser.
- Two independent concerns: **authenticity of channel messages** (signatures) and
  **confidentiality of payloads** (encryption). LuckPerms does the first; we add the second
  because our payloads are entire content packs.
- No custom primitives. Everything is WebCrypto-compatible (browser) and JVM-equivalent
  (plugin) — the same schemes available in both runtimes.

### 5.2 Key material per session

| Key | Generated by | Purpose |
|---|---|---|
| Session wrapping key (256-bit random) | plugin, per session | encrypts the session blob; transported **in the URL fragment** (§4.1) |
| Server signing keypair (Ed25519) | plugin, per session | signs plugin→editor frames; pubkey inside the session blob |
| Editor signing keypair (Ed25519) | browser, per session | signs editor→plugin frames; pubkey presented in `hello` |
| ECDH ephemerals (P-256) | both, per session | derive the session symmetric key (§4.1 step 6) |
| Session symmetric key (AES-256-GCM) | derived at handshake | encrypts **every** frame body and **every** store blob/chunk for this session |

Recognition across sessions is deliberately asymmetric, mirroring the trust asymmetry: the
plugin persists its long-term identity keypair in `storage/` so returning editors recognize
the *server* without a new prompt. The *editor's* keys are per-session by default (browser
storage is untrusted-by-default); the plugin prompts in-game for each new editor key. An
editor MAY persist its signing key in IndexedDB and the plugin MAY whitelist it — an opt-in
"trusted workstation" flag that skips repeated prompts for that browser only, revocable
in-game (`/materione editor revoke`).

### 5.3 Codes

Store codes and channel ids are drawn from a ≥ 100-bit CSPRNG space, base-N encoded —
capability URLs. Unguessability is the transport-level auth; short TTLs bound exposure.

### 5.4 Envelope & frame formats

```
envelope (store blobs & chunks) = {
  v: 1,                       // envelope version
  sess: <session-id>,         // binds blob to session
  nonce: <random>,            // unique per blob (GCM)
  ct: <encrypted payload>     // AES-256-GCM over {type, body}
}

channel frame (after handshake) = {
  seq: <monotonic int>,       // per-direction; receiver rejects gaps/replays/reorder
  nonce: <random>,            // unique per frame (GCM)
  ct: <encrypted body>,       // AES-256-GCM with the session key
  sig: <ed25519 signature>    // over {seq, nonce, ct} — sign-after-encrypt
}
```

The session blob additionally carries the server signing pubkey and channel id. Everything
else (pack dumps, edited documents, asset chunks) uses the same envelope, different `type`.
What the relay/cloud can still see despite all this: **traffic analysis** (sizes, timing,
which endpoints talk to which), session existence, and the (useless-without-key) codes. It
cannot read, forge, replay, or reorder anything.

---

## 6. Editor SPA architecture

- **Stack**: TypeScript, a lightweight reactive framework (framework choice is an
  implementation decision — the architecture requires: build to static files, no SSR need,
  embed-capable), Vite build, output deployed as static assets.
- **Stateless by design**: all state lives in the open session; a refresh re-fetches the
  document snapshot from the plugin. Undo/redo is client-side per editor session and bounded.
- **Layered like the plugin**: an API client (transport-agnostic: relay | direct), a normalized
  resource/asset/schema cache, bounded per-resource drafts, editors rendered **from registry
  self-description** (plugin.md §3 -- forms are generated from parameter schemas, never
  hand-written per mechanic), a diagnostics center, and the preview surface. The default
  information architecture is resource-first: onboarding → content catalog → type studio.
  Filesystem Explorer and YAML live under Advanced for T4 rather than defining the T2/T3 journey.
  *As built (M3):* the shell is an app header (pack switcher, new project, release state,
  publish), a rail, one full-area view per rail entry, the activity dock and the status bar.
  Tabs are **not** global — they belong to Advanced Files, which owns the pack tree, the generic
  schema form, YAML, assets, revisions and standalone Blockbench. Content is a file explorer per
  registered type: horizontal type tabs derived from `browse.types` (unimplemented types render
  as disabled tabs so the shape of the product stays visible), folders, breadcrumb navigation,
  details and icon layouts, search, keyboard navigation, and create/rename/duplicate/move/delete
  for files and folders. Layout invariants that keep a 50+ entry pack usable — every grid track
  shrinkable, every name ellipsised, no horizontal scrolling, no explanatory prose, and the child
  that fills the view growing to fill it whichever optional bars happen to be showing — are
  asserted by a test over the surfaces' own CSS, because each of them was regressed once. In
  particular a full-height surface stacks with flex rather than positional grid rows: an optional
  error line or toolbar that is absent must not shift every following child into the wrong track.
- **Schema-driven studios**: registry metadata declares labels, sections/facets, ordering,
  constraints, reference targets, structured children, conditional visibility, widgets, and
  creation capabilities. The UI provides reusable controls and domain previews; it does not
  maintain a second item schema. Unknown fields remain preserved and visible in Advanced.
  *As built (M3):* a shared studio shell (preview column, registry-driven sections, save, a
  footer reporting the fields only Advanced Files can edit) with per-type controls: material and
  reference pickers, texture and model slots, a row editor for structured lists whose shape the
  registry declares (furniture hitboxes, seats, lights, evolution), and a 3×3 crafting grid that
  reads and writes `shape` + `ingredients`. A save sends one `authoring.setFields` call carrying
  only the fields that actually changed, so an untouched resource is never rewritten and the
  round-trip stays lossless.
- **Texture editing** (master §9.3): a built-in pixel editor — pencil, eraser, flood fill,
  eyedropper, line, rectangle, crop, vertical mirror, zoom/pan, pixel grid, bounded undo/redo,
  PNG import/export, free canvas resize — opens in place from a studio's texture slot and from
  the asset preview in Advanced Files, and saves through `assets.put`. Creating a texture for a
  resource that has none also writes the owning field. The right panel is tool settings: colour
  controls appear exactly when the active tool consumes a colour, the crop tool offers marquee
  selection with aspect-ratio locks and manual x/y/w/h, and the canvas section resamples to any
  size (1–1024 px, non-square included — glyphs and paintings are not squares). All pixel
  arithmetic lives in a pure module (`pixels.ts`) pinned by vitest, per the repo's pure-seam
  convention; the server's 2 MiB asset cap is the honest upper bound on canvas size.
- **Sound editing**: a sound's studio is self-sufficient — the preview panel plays the first
  source and every source row carries an upload/replace control, so the .ogg never needs a
  detour through Advanced Files. "Add…" uploads a file and appends its ref to the sources
  list (an unsaved draft change like any other field); paths and name sanitisation live in
  `studio.ts` (`soundAssetPath`, `sanitizeSoundName`), pinned by vitest. The schema drives the
  rest: `category` declares `allowedValues`, so the studio renders the vanilla sound sources
  as a dropdown instead of a free-text field whose mistakes silently reject the definition.
- **Blockbench integration** (master §9.3): the pinned, unmodified Blockbench web build loads
  inside the editor (iframe with a scripting bridge) from **static assets served by the editor
  SPA's own host** — never an inbound connection to the game server (D11 intact); the Materione
  Blockbench plugin (our code, any license) runs inside it, talking "save to Materione" over
  the same session API. The editor treats Blockbench as an asset-producing subsystem, not a
  peer. The `blockbench/` folder on the server side (master §7.1) is a pinned copy for
  **offline/direct-mode installs** and tooling parity; the SPA ships its own pinned copy.
  *As built (M3):* the pinned build is **Blockbench v5.1.6**, vendored unmodified at
  `webui/public/blockbench/` (recipe + pin in its PIN.md — upgrades are pin bumps; the
  server-side `blockbench/` folder is not needed because the jar copy already serves offline
  installs). A resource's model control opens a **contextual workbench over its studio**, with
  our own header (model · owning resource · Save · Cancel), containing the same-origin iframe;
  `materione-bridge.js` is injected after load and communicates **only** via postMessage
  (`collect` → model JSON + textures + `.bbmodel` source; `importProject` → load a project;
  `thumbnail` → a render, feature-detected). There is no start screen and no format picker: the
  resource's `.bbmodel` source is loaded if it exists, and otherwise a minimal project is
  synthesised for the pinned build's single Java format (`java_block`, bbmodel 5.0) with the
  resource's texture already attached. `authoring.saveModel` receives
  owner/revision context and saves model JSON, textures, optional source, relationship manifest,
  and owner patch as one bounded transaction, with every target derived from the owning resource
  and an advanced disclosure for overrides. Existing-owner, new-owner, and assets-only saves all
  use that operation; conflicts and injected post-promotion failures restore the prior file set.
  Bridge messages target and accept only the exact iframe origin/window and use an allowlisted,
  payload-capped protocol.
  The versioned relationship manifest is `.materione/model-links.json` in the canonical pack. It
  maps `owner-path#field.path` to model and optional source paths, preserves other links on update,
  and is authoring metadata excluded from the compiled client pack. Model renders are cached
  beside it under `.materione/thumbnails/<model-path>.png` under the same exclusion, so a model
  can be shown without launching Blockbench. That one path is the only authoring location the
  asset API knows: `assets.get` may read it, `authoring.saveModel` may stage it, and
  `assets.put/move/delete` may not touch it — metadata is written by the transaction that owns
  it, never by a free-form upload.
- **Versioning**: the SPA declares the protocol versions it speaks; the plugin serves hints if
  the cached SPA is stale ("a newer editor is available — refresh").

---

## 7. Project website (deferred)

`materione.com` is purchased and reserved. The website originally planned here for M5
(landing page + docs rendered from the repo) is **deferred** (decision 2026-10-23):
documentation lives in the repository (`docs/`), the repo README is the landing page, and a
future marketing site — closed-source, in a private repo, links-first (download, Discord,
GitHub, Modrinth, editor) — will sit at the apex as its own milestone. The editor at
`editor.materione.com` is the only web surface M5 ships. `materione.dev` is abandoned (no
redirect planned). The community pack hub (master open question #6) remains undecided and,
if built, would be a separate Worker with its own read path — not part of the editor path.

---

## 8. Self-hosting (constraint #3 in practice)

Everything in §2 is self-hostable by a third party:

- **Editor SPA**: static files; host anywhere, point it at any relay (configured by URL at
  build or runtime).
- **Relay (mailbox + store)**: one `wrangler deploy` of `cloud/editor-worker/` with a D1
  binding and a cron trigger — open-source in this repo, no secrets required. The one hard
  Cloudflare coupling left is D1 itself; the storage seam is a tiny SQL schema, so a
  single-node variant on any SQL store is a documented weekend port (the original design's
  Durable-Objects coupling is gone with the WebSocket relay).
- **Federation defaults**: servers pin the relay base URL in `settings/` (`relay_base_url`,
  default `https://editor.materione.com`) — an organization can run fully private
  infrastructure with zero code changes. The plugin treats relay endpoints as configurable,
  never hardcoded.

---

## 9. CI/CD & environments

| Artifact | Pipeline |
|---|---|
| Editor SPA | one vite build → embedded in the jar (M3 path) **and** bundled as the Worker's static assets |
| Relay Worker | `cloud/` tests (vitest on the Workers runtime) on every PR; deploy via manual-dispatch `deploy.yml` using an owner-provided API token (cost guardrails: agents never deploy) |
| Harness relay gate | CI boots real Paper + `wrangler dev` locally and drives the full relay handshake + one save→apply round trip (the "local" environment below) |
| Protocol fixtures | golden handshake/frames/mailbox files checked in; plugin tests, SPA tests and Worker tests run against the same fixtures (drift fails all sides) |

Environments: **local** (`wrangler dev` + plugin dev server — the harness gate proves it),
**staging** (same Worker config, separate D1 and route; nightly conformance), **production**
(`editor.materione.com`). No secrets live in the Worker; the only secret is the deploy token
in CI.

---

## 10. Threat model summary

| Threat | Mitigation |
|---|---|
| Relay operator reads content | E2E encryption; relay stores/forwards ciphertext envelopes only (§5) |
| Relay drops/delays/replays frames | Per-direction sequence numbers; receivers reject gaps/replays/reorder (§5.4); ordered cursor reads make reordering impossible in practice (§3.2) |
| Forged messages via relay | Per-message signatures both directions (sign-after-encrypt); keys trusted only after in-game confirmation |
| Session blob interception (code or key alone) | Wrapping key travels in URL fragment, never to any server; code alone is useless (§4.1) |
| Capability URL guessing | ≥100-bit codes, short TTLs, rate limits (§5.3) |
| Malicious editor (second browser joins) | Explicit in-game approval with nonce disambiguation (§4.1) |
| Replay of old payloads | Envelope nonces + session binding; GCM rejects reuse |
| Direct-mode port exposed on LAN/hosting | Loopback-by-default, `0.0.0.0` warning, handshake auth applies regardless of binding (§4.4) |
| Relay/store outage | Direct mode is a complete fallback (master D11, §4.4) |
| Compromised `materione.com` hosting (worst case) | Authenticity still holds (signatures verified plugin-side regardless of transport); confidentiality holds (keys never transit the cloud); worst case = denial, not intrusion |
| Abuse of the shared free instance | Rate-limiter binding per IP, envelope-only store, hard quotas below free-tier ceilings; the free plan's hard stops mean abuse causes 429s and availability loss — never billing (§3.3) |
| Abuse of free store as file host | Envelope-only content type, size caps, TTLs, per-IP limits, content-addressed dedup keeps honest traffic cheap (§3, §4.3) |
| Traffic analysis by the relay | Accepted, documented residual: sizes/timing/metadata are visible; contents are not (§5.4) |

The property we actually promise: **compromising all cloud infrastructure yields an attacker a
denial-of-service at most — not read, not write, not forge, not replay.**
