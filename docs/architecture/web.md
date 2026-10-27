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
| Workers (static assets) | `editor.materione.com` | Serve the editor SPA build (the *same* artifact embedded in the jar; `_headers` adds the cloud host marker, §6) — free, unlimited requests |
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
   editor capability token, plugin signing public key, protocol versions supported, the
   **capabilities** naming the editor features this build implements — plus,
   when the embedded listener is reachable from the admin's machine, a **direct-mode hint**
   (LAN auto-preference, §4.4). The self-description travels inside the encryption so the
   panel can negotiate and gate before any RPC: `protocolVersions` is the jar's accepted
   hello versions (§4.2); `capabilities` lists named feature areas (`items`, `blocks`,
   `furniture`, `paintings`, `glyphs`, `text-effects`, `sounds`, `recipes`, `models`,
   `assets`, `delivery` for the v1 surface) that a newer panel hides or annotates UI for
   when the connected jar does not advertise them — an absent list means a
   pre-capabilities jar, and the panel assumes full support (never worse than today).
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
| **authoring** | describe capabilities, bulk resource summaries, create resource/project, commit a field batch, save a model transaction |
| **validate** | dry-run validation of an unsaved resource → diagnostics |
| **pipeline** | trigger targeted/full reload, pre-apply report, apply confirm |
| **assets** | upload (chunked via payload store), delete, list |
| **preview** | subscribe to live preview events, request preview renders |
| **delivery** | candidate/published status, publish an exact candidate hash |
| **admin** | diagnostics feed, doctor summary, audit query, settings read (security keys never leave the server) |

Requests carry `{op, id, payload}`; responses `{op, id, ok, data|error}`. Errors reuse the
plugin's structured diagnostic codes. **Protocol version is negotiated at hello**: the blob
advertises the jar's accepted versions, the panel picks the highest one its own build also
speaks, and the server accepts any version it advertised (membership, not exact-match) — a
cached cloud panel one release behind or ahead of the jar still connects. Disjoint lists
fail before any socket opens, with a message naming both sides. Unknown ops
return a clean error, never a disconnect; capabilities (§4.1) let the panel avoid calling
ops the connected jar predates.

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
and the studio preview renders it). The `parsed` tree in `browse.get` (and in
every write's returned view) is string-keyed at every depth: YAML-1.1 boolean
keys come back as the text the runtime reads (`on`/`off`), so a bare ECA `on:`
reaches the rule editor as `on`, not `"true"`. Asset uploads are capped per extension:
2 MiB for textures/models, 8 MiB for audio (`ogg`/`oga`) — bigger sources wait
for M5's chunk flow. Still illustrative-only:
import pack, pre-apply report / apply confirm, preview renders,
`close`. Concurrency notes learned the hard way: pushes and RPC replies seal from
different threads — `EditorSession.sendFrame` synchronizes seal+write so sequence
assignment matches wire order, and the browser transports buffer frames arriving
during the handshake→channel handover instead of dropping them (a dropped frame
desyncs the strict receive sequence).

*As built (M5 scalable authoring):* `authoring.references` (`{pack, kind: asset|resource, target}` →
`{hits: [{pack, type, path, id, field, value}], coverage}`) and `authoring.bulk`
(`{pack, operations, dryRun}`, operations `set` / `create` / `duplicate`, ≤ 50) answer from
`AuthoringApi.findReferences` / `bulk`. The editor never loops independent writes to
look atomic: a multi-document change is one request that the server validates as a whole,
writes under one store lock and rolls back on failure. Coverage is part of every reference
answer; an empty list is "not found", and only a `complete` scan with no unreadable
documents supports "no known users" — and even then asset files (a model's texture list) are
not read.

*As built:* `delivery.status` returns the current candidate and published hashes;
`delivery.publish` promotes only the expected candidate hash and returns a visible stale error
when that hash is no longer current. Candidate availability is not a receipt that a player
received or accepted the pack. `push.reload` reports a server-wide pipeline outcome and carries
no per-save correlation id, so the UI does not present it as proof that a specific save applied.
The event is emitted after the global-thread apply and delivery staging complete, not merely
after scheduling the swap. Glyph `computed` metadata is allocation information, not a reload
receipt: allocation is persisted before the corresponding archive is staged.

*As built (M5 editor-workbench follow-up):* `authoring.summaries` is a read-only, pack-scoped
catalog projection, optionally filtered by type/query and paged by offset. Each response page
is `{ resources, total, nextOffset? }`; the server defaults to 100 rows and caps a page at 200,
and the browser client follows `nextOffset` to assemble the catalog list. Each `ResourceSummary`
extends `ResourceRef` with `name`, optional `material`, optional `visual` (`{ pack, path,
kind: 'texture' | 'model-thumbnail' }`), optional `render` (`{ pack, path }` for a readable
model asset, or `{ pack, model }` carrying the model the type compiles inline), a `health` value (`ok`, `warning`, `error`, or
`unknown`), and an optional `healthMessage`. The current server reports `unknown` unless a direct
read of the canonical document proves an error, in which case it reports `error`; it does not
claim pipeline freshness or that a resource was applied. The catalog uses these pages rather than
issuing `browse.get` for every row; opening a resource is when the editor fetches its document.
Summary failures keep the catalog usable with an ID-derived name and unknown health, while an
absent visual uses an honest placeholder. Thumbnail bytes are read separately through the existing
asset operation, never embedded in the summary response. The browser keeps a 64-entry thumbnail
cache and limits asset reads to four concurrent requests. Visible previews beyond the pending-read
budget wait for capacity and resume without polling; hidden previews do not enqueue new reads.
Cached model thumbnails and textures
are static previews, not a live Minecraft render or interactive model.
Glyphs use this same summary and asset-read path: basic glyphs expose their bitmap,
sprite sheets their source image, animations a static frame, and aliases the target's
source preview. Thumbnails are labelled as source-texture previews rather than
claiming final font metrics, selected-cell layout, or animation playback.

*As built (vanilla item icons):* a resource with a `material` but no `visual` shows the
vanilla inventory icon for that material instead of the placeholder. Icons come from the
third-party `minecraft-textures` package (GPL-3.0 manifest; the images are Mojang's),
pinned to one package version and loaded by the browser from jsDelivr at runtime — they
are never bundled into the editor, committed, or served by the plugin or Materione's
Cloudflare account, so neither the project license (§14) nor Mojang asset redistribution
is decided by this feature. `session.info` carries `minecraftVersion` (the probed platform
version); the editor picks the newest manifest not newer than it, and the newest one when
the field is absent or the server is newer than the pinned package. Manifest entries are
accepted only as hash file names under the pinned package path. Offline, a CDN failure, or
an item the manifest lacks keeps the honest placeholder; vanilla icons are labelled as
such, never as the resource's own visual.

*As built (live model previews):* blocks and furniture now expose a catalog presentation, and
a summary's `render` lets the editor draw the resource's model in the browser, preferred over
the static visual. Types that compile a generated model (blocks per technique — cube, slab,
stairs, or the configured `model_parent` — and texture-only furniture) put that exact model
JSON in `ResourcePresentation.generatedModel`, produced by the same helper as the compile
output (parity is unit-tested), so the preview cannot drift from what the pack ships;
hand-authored models are referenced by path after the summary confirms the file is readable.
The editor (`model-render.ts`, `model-preview.ts`) resolves the parent chain (pack parents
over `assets.get`; vanilla parents from Materione's own minimal shape set — `block/block`,
`cube`, `cube_all`, `cube_column`, `cube_bottom_top`, `orientable`, `cross`, `slab`,
`slab_top`, `stairs`, and the flat `item/generated` family — never copies of Mojang files),
then projects element faces with the model's `display.gui` transform and inventory shading
(top 1.0, left 0.8, right 0.6). The view is orthographic, so each face is one affine
canvas draw — no 3D engine. Pack textures are read through the existing asset op; vanilla
textures (we hold none) and unreadable ones draw as a neutral grey checker. A model whose
geometry lives in an unknown vanilla parent cannot be drawn and falls back to the static
visual. Known approximations: painter's-order depth sorting (intersecting elements may
overlap wrongly), no tints, first animation frame only. The Studio uses the saved render only
while `texture`, `model`, `technique` and `model_parent` are unedited.

*As built (M5 guided authoring):* `authoring.describe` is registry-driven capability discovery:
`{ types, behavior }`, where `types` is the `browse.types` shape and `behavior` carries `events`
(id, displayName, description, the `details` the runtime fires such as `right`/`left`, and
`firedBy` — the content types whose runtime fires the event; reserved events have an empty list),
`actions` (every registered executor with its self-description, the `shorthand` parameter a scalar
value stands for, and whether it is `deferred` through the scheduling gateway; an executor without a
description is still listed), `ruleOptions`/`actionOptions` (the rule and action keys the parser
reads), and `mechanics` (every registered mechanic, built-in or add-on, with `repeatable` and
`registeredBy`). Each piece is read from where it is registered — the event taxonomy, the executors,
the mechanics registry — never from an editor copy. Self-descriptions may carry an `activation`
sentence (when the entry acts in game); absent means the entry never declared it and the editor says
so. A jar that predates the op answers with an unknown-op error and the editor falls back to
`browse.types`, leaving behaviour authoring to Files. `browse.types` now serialises declared
`nested` parameter shapes (they were previously dropped, which hid every structured-list field).
`authoring.setFields` accepts `{ field, remove: true }` (no `value`): the lossless layer deletes the
key and the lines its value owns, keeps surrounding comments, turns an emptied parent into `{}`,
treats an absent key as a no-op, and refuses keys in flow style or behind a sequence — all inside the
same revision-checked batch. `browse.get` adds a read-only `computed.inheritance` per root that names
a `template:`: `template` as authored, `chain` (full ids, nearest first), `inherited` (the value the
chain supplies for each key), `sources` (which template supplies it), `templates` (`{pack, path}` per
chain id) and `failure` when the chain does not resolve. It comes from the pipeline's own resolver,
reflects the saved file, and is computed only for documents that use inheritance. Diagnostics may
carry `target: { field: [...] }` — the definition field the problem is about (unresolved
`texture`/`model`/`template` references today). The bus publishes one quarantine notice per resource
(`MTR-0010`); it now names the first fatal reason and carries that reason's target.

*Planned:* resource and asset listings gain pagination/filtering before relay mode makes eager
full-pack traversal a scale boundary.

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
- **Icons** (issue #149): one icon set for the whole editor, **Lucide** (ISC licence;
  `@lucide/svelte`, the official Svelte 5 package). Lucide matches the stroke grammar of the rail's
  hand-drawn icons (24-unit grid, rounded strokes) and is permissively licensed, so bundling it
  is compatible with the project licence question (master §14) either way. Icons are per-file
  imports (`@lucide/svelte/icons/<name>`), tree-shaken because the package is `sideEffects: false`;
  there is no icon font and no runtime CDN. `webui/src/lib/components/icons.ts` is the single
  registry: it names every icon the editor draws and maps each content type to its icon
  (`CONTENT_TYPE_ICONS`, used in the Content tabs, Start tiles and empty states). `Icon.svelte`
  renders a registry name at a given size, decorative (`aria-hidden`) unless it is given a
  `label`, and inherits `currentColor`. Unicode glyphs are not icons: a control whose only content
  is an icon carries an accessible name (`aria-label`). **Bundle delta** (`vite build`, this
  change against `main`): the entry chunk grows from 325.0 kB to 351.0 kB (111.5 kB to 119.3 kB
  gzip), about 26 kB raw / 7.8 kB gzip for the 46 icons the editor names. Enforcement:
  `webui/test/icons.spec.ts` (vitest) fails on a glyph used as an icon-only control's content or as
  an icon-map value; `webui/browser/specs/icons.pw.ts` and the `editor.browser-icon-set*` QA
  entries check the rendered DOM. Blockbench's own icons inside its iframe are out of scope.
- **Stateless by design**: durable content state lives in server files; a refresh re-fetches the
  document snapshot from the plugin. Unsaved edits are bounded browser-session state, not a
  second content store. Undo/redo is client-side per editor session and bounded.
- **Layered like the plugin**: an API client (transport-agnostic: relay | direct), a normalized
  resource/asset/schema cache, bounded per-resource drafts, editors rendered **from registry
  self-description** (plugin.md §3 -- forms are generated from parameter schemas, never
  hand-written per mechanic), a diagnostics center, and the preview surface. The default
  information architecture is resource-first: onboarding → content catalog → type studio.
  Filesystem Explorer and YAML live under Advanced for T4 rather than defining the T2/T3 journey.
  *As built (M3):* the shell is an app header (pack switcher, new project, release state,
  publish), a rail, one full-area view per rail entry, and an optional activity dock.
  The redundant status footer is removed; Server owns the guarded Disconnect action.
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
- *As built (M5 editor-trust follow-up):* the initial Content surface is items-first. The authoring
  group is Content and Files; Problems, Activity and Server are support destinations. Rail labels
  start expanded and may be collapsed without shifting the navigation rows. Activity is absent
  until its sidebar button opens it; that button or the panel's close control hides it completely.
  New activity does not reopen a closed panel. Visited workspaces stay mounted
  and hidden so navigation does not destroy their editor state. Assets are opened from studio slots, the Assets
  workspace (below) or Files (Start is described below). Files↔Studio
  navigation carries pack, path, and selected resource identity, including the selected root in a
  multi-resource document. The header keeps the actions distinct: **Save & apply** changes gameplay
  server-wide; **Publish pack** releases the resource pack. Candidate status reports hash
  availability only, not player delivery or acceptance.
- *As built (M5 editor-workbench follow-up):* Content presents one row per resource root, with
  server-projected name/material/health and an optional static visual; multiple roots in one YAML
  file remain distinct and opening a row carries that exact root into Studio. Search includes
  human names and materials. Grid/list preference is per content type and client-side; resource
  IDs and paths are optional technical columns. These choices only affect presentation—files stay
  canonical (D1). The per-pack summary RPC avoids one document read per row; missing summaries
  degrade to humanised IDs and unknown health instead of hiding resources.
  Problems and audit rows retain separate event occurrences even when their visible fields repeat;
  diagnostic code/message pairs are not unique UI keys.
- **Draft lifecycle (as built, M5):** one in-memory store holds at most 32 document drafts keyed by
  pack/path and shared between Content and Files. Studio and Files form edits use one
  revision-checked `authoring.setFields` batch; raw YAML uses a revision-checked full-document
  write. Unchanged documents are not written, and switching between field and YAML representations
  requires an explicit apply or discard. The store is not persisted to browser storage: drafts
  survive workspace navigation and unexpected disconnection in the same tab, but not a confirmed
  page reload or tab close. The browser-unload guard also checks active editor guards for texture,
  asset, and model work; it is intentionally conservative and may warn while one of these editors
  is open even when unchanged. Reconnect checks the plugin signing identity and reconciles against
  server files without automatically replaying a save. Conflicting or overlapping changes block
  further apply until the author resolves them; unrelated pending fields remain pending.
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
- *As built (M5 item workbench follow-up):* the item registry marks display name and lore as
  formatted text and material as a server-provided material picker. *(The `&`-code text controls
  described below were replaced by the rich text editor, §6.2; the material picker, preview and
  draft rules stand.)* The material picker
  searches human labels and IDs over the registry's authoritative `allowedValues`, supports
  keyboard selection, and preserves a current ID the server no longer advertises until the author
  chooses a replacement. The name/lore controls edit only the bounded tab-local draft; `&` color
  and decoration codes remain in the authored value, decoration formatting respects existing
  color/reset codes, multiline lore formatting is applied line by line, blank lore lines and
  whitespace survive, and no blur-triggered save is performed. Lore containing non-string
  entries or embedded line breaks is refused by the plain-lines editor rather than flattened;
  the original value remains available in Files. The browser tooltip preview parses supported
  legacy codes as text (not authored HTML); unsupported tokens remain literal. A sticky item preview
  shows a 32 px inventory slot, an enlarged static visual, and the draft name/lore; clicking
  either text focuses its editor control. This is a browser approximation, not proof of the
  Minecraft font, glyphs, effects, obfuscation, or gameplay result. The game-check link opens the
  Server support view and keeps candidate/acceptance status explicit; it does not claim runtime
  verification.
- *As built (M5 guided authoring):* **Start** heads the authoring group; Content remains the landing
  view. Its creation starters are the registered content types (`authoring.describe`) and open
  Content's existing create dialog for that type. It lists warning/error diagnostics that name a
  resource in the open pack with a **Fix**/**Open** action, the tab's unsaved drafts, and the
  behaviour this server registers (built-in vs add-on mechanics with their activation sentences, the
  events rules can react to). Problems rows that name a resource gain the same action. A diagnostic's
  `target` field opens the studio with that field scrolled into view and focused; resource matching
  is exact (`<pack>:<id>`), not a substring.
  Studios render two behaviour widgets from the type registry: `mechanics` (an item's abilities) and
  `behavior-rules` (`behavior:` on items, blocks and furniture). Ability cards are generated from each
  mechanic's self-description: name, built-in or "added by <plugin>", description, **Activates:**
  (the declared sentence, or an explicit "does not say"), and its parameters through the shared field
  control. Non-repeatable abilities cannot be added twice; unqualified names are written for
  `materione:` mechanics and qualified names for add-ons. Entries the server does not register stay
  visible as "not registered on this server" with their settings untouched, and parameters the
  description does not declare are kept as written. Rules read as a sentence ("On right click, if …:
  send message “…” (at most every 3 s)") and expand into **When** (only events the host's runtime
  fires, plus the current one marked if it never fires), side, **Only if** expressions, **Then**
  actions with their described parameters and timing, and **Limits** (rule options). Each rule
  states when it activates from the event table — or that it never runs on this content type.
  Starting points are a small client list of on/action pairs written in the existing format and
  offered only when this server fires the event for the host and registers the action; they are
  starting values, not a rule language. A scalar shorthand (`message: "Hi"`) round-trips as a scalar
  while only its shorthand parameter is set; unknown rule keys and action keys are preserved.
  When a root inherits, the studio header shows the template chain (each link opens the template in
  Files) or the resolution failure. Each field the chain supplies shows **Inherited from …** with the
  effective value, or **Overrides …** with the template's value and **Reset to inherited**, which
  stages a removal edit (undoable) applied by the next revision-checked **Save & apply**. The view
  reflects the saved `template:`; an edited template field is labelled as such until applied.
  A texture slot whose reference names a file missing from this pack shows a repair panel —
  **Draw it now** opens the pixel editor at exactly that path (a new canvas; saving writes the PNG
  and no YAML), **Choose another texture** focuses the picker, whose upload control remains — and
  any targeted diagnostic for the field is shown inline. None of this verifies runtime behaviour:
  activation text and sentences are explanations from self-description, not a simulation.
- *As built (M5 scalable authoring):* **Assets** joins the authoring group. It lists the active pack's
  textures, models and sounds (search, kind filter, arrow-key navigation); selecting one asks
  `authoring.references` and shows every user with its field and authored value, the coverage
  sentence (documents read; partial/unknown scans are worded as such) and what it cannot see. A user
  opens in its studio with the field focused and a **Back to <asset>** bar returns. Two ways to change
  a shared asset are explicit and both go through one previewed `authoring.bulk` call:
  **Replace everywhere…** points every user at another asset of the same kind (the old file is left
  alone), and **Give some users a copy…** checks users, uploads a copy under a new name, then
  retargets only those. The copy is an ordinary `assets.put` before the bulk call — a separate write,
  not part of the atomic change — and is removed again if the bulk call is refused (and named in the
  error if even that fails). **Edit pixels** states how many uses the change will reach. A texture or model slot's **Used by**
  button opens this view on its asset.
  In Content, **dragging a rectangle** over rows marks them (Ctrl/Cmd/Shift adds to the marks; a click on empty space clears them),
  **Space** marks rows (**Ctrl/Cmd+A** marks everything shown, so a search narrows the
  set), and **Edit N marked…** opens the bulk dialog: pick a registry field, give a value (or remove the
  field), **Preview changes**, then **Apply** — one revision per document, written together or not at
  all, with the revisions read when the dialog was previewed (a document that changed in between
  refuses the whole change). **Make variants…** (context menu, single-root files) turns a list of
  names (`Name` or `Name = value`) into lossless duplicates with their own ids and display names, and
  optionally one new recipe per item in the same change; **Create recipe** makes a recipe whose `result`
  is the item. Marked rows with pending drafts are refused rather than written under. **Ctrl/Cmd+K**
  opens a command palette over views, create starters, every resource, every asset, and — with a
  resource open — that type's fields (the property search). It runs actions the UI already has.
  Menu integration stays M6.
- *As built (M5 item-studio modules follow-up, issue #152):* the item studio is **a display card
  plus modules**. A type joins this layout as soon as its self-description marks one parameter
  `module` (`EditorMetadata.module/category/icon/summary`, plugin.md §3) — the framework is
  type-agnostic, and types without module fields keep the classic sections. The **display card**
  holds the formatted name, the lore, and the texture/model slots (with the existing
  Draw/Edit-in-Blockbench actions and the missing-texture repair panel). Each **module** is a
  card: schema field modules (base material — required, so its card has no Remove — model style,
  jukebox song), one card per authored mechanic instance (labelled and explained by the mechanic
  registry; unregistered entries stay visible with their settings untouched), and one
  "When… do…" rules module wrapping the rules editor. The **picker** searches labels, summaries
  and categories with chip filters and keyboard navigation, marks already-added modules
  (repeatable mechanics stay addable), and offers only mechanics this server registers. Adding
  and removing stages the same lossless edits as the classic sections — one
  `authoring.setFields` batch on **Save & apply**; removing the last ability removes the
  `mechanics:` key rather than writing an empty list; a module with no honest default (a
  reference the author has not chosen) opens an empty card and writes its key only when a value
  is set. Advanced fields (the item `template`, `components`) stay reachable through an Advanced
  disclosure and the footer's Advanced-only report; every card carries **Show in file**.
  Coverage is honest: only what the plugin applies today is a module (display name/lore, the
  mechanics, jukebox song) — the `components:` block still warns instead of applying (#103), so
  component modules (attack damage/speed, glint, tint, can-break…) wait for the M7 pass-through.
  Enums with 2–4 values render as segmented controls. The derivation is a pure module pinned by
  vitest; the deterministic `?modules` browser fixture pins add / configure / remove and the
  resulting field edits.
- *As built (M5 per-type modules, issue #153 — blocks and furniture):* blocks and furniture use
  the same display + modules framework, with every module declared in the Java self-description
  (plugin.md §3) and the browser fixture serving those schemas straight from the plugin's golden
  snapshot, so a spec cannot pass against a schema the server does not have. **Blocks**: the
  display card (name, texture/model slots, preview) plus **Shape** (the `technique` key as labelled
  choices — Full block / Slab / Stairs / Thin plant / Directional plant / Custom host), **Drops**,
  **Explosion resistant**, **When pushed by pistons**, **Placed by item**, and **When… do…**; the
  `host` key stays under Advanced. **Furniture**: Floating label, **Rotation snapping** (Free / 45°
  / 90° / 180°, stored as the number), **Hitbox**, **Seats**, **Light source**, **Storage** (a
  number with its 9–54 slots range), **Grows into…**, Placed by item, When… do…. Smart inputs:
  the **item picker** (this pack's items, blocks and furniture, then the server's vanilla items,
  each with its icon — pack visuals from the summaries, vanilla icons through the pinned
  `vanilla-icons.ts` package; placement links offer pack items only), **item chips** with a count
  stepper (drops; the file stores a repeated reference, entries the chips cannot show are kept as
  written), segmented controls for up to eight *labelled* choices, number controls that clamp to
  their declared range and show the unit, and the **spatial editor**: seats, hitboxes and lights
  are drawn on a front (x, y) and a top (x, z) view at block scale — handles drag or take arrow
  keys (5 cm steps, Shift for 25 cm), only the row's `offset` is rewritten — with the exact
  numeric rows beneath as the fallback. It is an approximation of where things sit, not a client
  render; the preview model is not drawn behind it. Honest gaps: block drops have no count key in
  the format (the repeat is documented above); furniture has no model overlay yet.
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
  Its **Save & apply** commits the edited asset and its owning reference when needed; other
  pending document fields remain pending. Reconciliation preserves unrelated fields, while an
  overlapping or revision-conflicting change blocks further apply until the author resolves it.
  Failed reads do not become empty canvases to overwrite existing files. Before each upload,
  the editor compares current source bytes with its opening snapshot; a mismatch preserves
  the canvas and refuses apply. This read-before-write check is not an atomic asset
  compare-and-write, so it does not guarantee isolation from concurrent asset writers.
  Model workbenches retain their opening reference and owner revision rather than silently
  retargeting a loaded project when another view changes the shared document.
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
- **Versioning**: implemented for the handshake (§4.2) — the SPA intersects the blob's
  advertised versions with its own and the hello speaks the common one, so jar and panel
  releases land independently; capabilities (§4.1) let a newer panel hide features the
  connected jar predates instead of surfacing per-call errors. Not yet built: the plugin
  serving "a newer editor is available — refresh" hints when the cached SPA is stale.
- **Cloud landing & demo (as built, M5):** with no session in the address, the cloud editor shows a
  landing card (Materione · Web content editor · **View Demo** · "To start a new session, run
  `/materione editor` in-game") instead of the paste-a-link card; the plugin-served editor keeps
  the paste-a-link card. Both hosts serve the *same* build (§2, §9), so the switch is a runtime
  host marker, not a build flag: the SPA fetches the static `/host.json` and reads the
  `X-Materione-Host: cloud` response header, which only the Workers static-asset `_headers` file
  attaches. The plugin listener serves the same file without that header, and a failed probe
  counts as the plugin, so a locally hosted editor can never show the landing card or the demo,
  even under `?demo`. A capability URL (`?s=…#…`) connects without waiting for the probe.
  Self-hosted relays (§8) deploy the same assets and get the landing card too. The probe is a
  static asset: no Worker invocation, nothing billable (§3.3).

  **View Demo** (`/?demo`) is the editor itself, not a mock-up. The App, Workbench, Studio, forms
  and previews are the same components a session mounts; the demo swaps exactly one thing, the
  channel (`webui/src/lib/demo/`):

  | Layer | Live session | Demo |
  |---|---|---|
  | UI (App → Workbench → studios, forms, previews) | same code | same code |
  | Channel (`RpcChannel`) | sealed session to the plugin (§4) | `DemoServer`, in the tab |
  | Schemas, behaviour vocabulary, catalog, documents, assets, diagnostics | the server's registries and packs, live | a recording of those same ops |
  | Writes | the server's document and asset stores | the recording, in memory: same revision checks and conflict codes, comment-keeping YAML edits, a staged candidate and a `push.reload` per save |
  | Content pipeline (validation, compile, summaries) | runs on every save | not available in a browser: recorded results stay, and a saved resource's name and material are re-derived from its fields |

  The recording is `fixtures/golden/editor-demo-snapshot.json`, produced by the JVM test
  `EditorDemoSnapshotTest`. It boots the plugin's own wiring headless (real content types and
  material palette, native mechanics, the behaviour engine's vocabulary), loads the `sample` and
  `world` fixture packs and records `EditorTransportService#opsDispatcher`, the dispatcher a live
  server serves. It is a golden file: an op, registry or fixture-pack change shows up as a diff and
  is regenerated with `-Dmaterione.golden.regen=true`. So the demo cannot drift from the editor: new
  fields, types and mechanics arrive with the next regeneration, and a vitest test fails if the
  editor calls an op the `DemoServer` does not answer. There is no game server, no session and no
  endpoint. Nothing is persisted, a reload starts over, and Exit demo returns to the landing card.
  The demo code and the recording are a lazily loaded chunk (~170 kB gzipped), fetched only when
  the demo starts. Being client-side only, the demo adds no public endpoint and nothing to the
  cost model.

### 6.1 Design tokens and primitives

The editor carries the Materione identity: navy surfaces, the orange accent, Inter. The palette
is the marketing site's (`materione-website`, `:root` in `src/pages/index.astro`), so the two
surfaces read as one product. Dark only for now; a light theme is not designed yet.

**One source.** Every color, font size and corner radius the editor draws is a custom property in
`webui/src/app.css`. Components reference `var(--token)`, and canvas code reads the same properties
through `token()` in `src/lib/tokens.ts` (canvas cannot take `var()`). `test/design-tokens.spec.ts`
fails on any hex, `rgb()` or `hsl()` literal in `src/**/*.{svelte,ts}`, on a `font-size` or
`border-radius` in pixels in a component, and on a `var()` that app.css does not define. The
allowlist is empty and must stay justified beside any entry. `index.html` is the one place with a
literal: the `theme-color` meta, which HTML cannot read from CSS. A new color is added to app.css
first, then used.

| Group | Tokens | Notes |
|---|---|---|
| Brand | `--brand-bg` `#04101d`, `--brand-navy` `#00274a`, `--brand-accent` `#f9731e`, `--brand-accent-hover` `#fb9450`, `--brand-accent-low` `#3d1f08` | Raw brand values; the names below alias them |
| Surfaces | `--bg-app` (level 0), `--bg-panel`, `--bg-raised`, `--bg-hover`, `--bg-input` (recessed), `--navy` | Four elevation steps lifted from the background toward navy |
| Lines | `--border` `#132539` (dividers), `--border-strong` `#2a3d55` (card outlines), `--control-line` `#5a7798` (inputs, buttons) | Controls meet WCAG 1.4.11 (3:1) on every surface; decorative dividers need not |
| Text | `--fg` `#f5f7fa`, `--fg-code`, `--muted` `#9fb0c3`, `--faint` `#8193ab` | AA on every surface, including tinted status rows (4.7:1 on hover) |
| Accent | `--accent`, `--accent-hover`, `--accent-low`, `--accent-ink` (`#04101d` on orange, 6.8:1) | The accent is for the one primary action, focus and selection |
| Status | `--ok`/`--ok-bg`, `--warn`/`--warn-bg`, `--error`/`--error-bg` | Text on its tinted surface stays above 6:1 |
| Focus and effects | `--focus-ring`, `--selection`, `--shadow-1..3`, `--scrim` | Focus is a 2px outline, offset 2px |
| Type | `--text-xs` 11, `--text-sm` 12, `--text-base` 13, `--text-md` 14, `--text-lg` 16, `--text-xl` 22, `--text-display` 30 px; `--font-sans` (Inter), `--mono` | Seven steps; a size between steps takes the step below (12.5 px becomes 12, 15 px becomes 14) |
| Spacing | `--space-half` 2, `--space-1` 4, `--space-2` 8, `--space-3` 12, `--space-4` 16, `--space-5` 20, `--space-6` 24, `--space-8` 32, `--space-10` 40 px | 4 px base; 2 px for chips and icon gaps. Optical negative margins stay literal |
| Radius | `--radius-sm` 4, `--radius` 8, `--radius-lg` 12, `--radius-pill` | Controls use `--radius`; panels and cards `--radius-lg` |
| Canvas | `--checker-a/b`, `--paint-default`, `--canvas-grid`, `--canvas-hover`, `--canvas-mark`, `--canvas-dim`, `--preview-*` | Read by `token()`; never a literal in a `.ts` file |
| Legacy | `--legacy-0` … `--legacy-f` | Minecraft's 16 text colors, for the game surface and swatches |
| Game surface | `--font-minecraft`, `--text-minecraft` (16 px), `--mc-shadow-offset`, `--mc-tooltip-bg`, `--mc-tooltip-border(-low)` | Player-visible text drawn the way the client draws it (§6.2) |

**Primitives.** The shared look is CSS classes in `app.css`, so a screen gets it without new
markup: `.btn` (secondary by default) with `.btn-primary` (the accent, one per view), `.btn-ghost`,
`.btn-danger`, `.btn-sm`; `.chip` with `.chip-ok|warn|error|accent`; `.card`; `.field` with
`.field-label`, `.field-hint` and `.field-error`; `.menu` with `.menu-item`; `.dialog-actions`.
Form controls (`input`, `select`, `textarea`) get the field look globally. The Svelte wrappers in
`src/lib/ui/` (`Button`, `Chip`, `Field`, `Dialog`) apply the same classes; a screen that needs only the
look uses the class. `Dialog` owns the
scrim, the labelled panel, Escape, and a scrim click; `dismissOnScrim={false}` keeps a dialog with
unsaved work open on a stray click. Escape always cancels.

Svelte scopes a component's rules, so a local `button { … }` rule outranks the global `.btn`. A
component that still has unclassed buttons writes its generic button rule as
`button:not(.btn)`; a button that carries `.btn` takes its chrome from the primitive alone.

**Type and brand assets.** Inter Variable (latin subset, `src/assets/fonts/InterVariable-latin.woff2`)
is bundled and served from the build, with no font CDN. It is from `@fontsource-variable/inter`
5.3.0, licensed under the SIL Open Font License 1.1 (`src/assets/fonts/LICENSE-Inter.txt`). The
logo is `public/materione-logo.svg`, identical to the website's `favicon.svg`; the header and the
landing card use it, and `index.html` links it as the favicon. Contrast checks are run on the
palette pairs (WCAG AA: 4.5:1 for text, 3:1 for control outlines and focus).

### 6.2 Rich text (issue #151)

Player-visible text (item names and lore, block and furniture names, the furniture label,
painting titles and authors) is edited in place, in the game's look: there is no code field and no separate preview to read.
The stored form is decided in master §5.7 (MiniMessage, legacy codes as input); the plugin side
is plugin §6.1.

**One control.** `RichTextEditor.svelte` renders every field the registry marks `formatted-text`
(one line) or `formatted-lines` (lore: Enter starts a line, the value is a list). The studio and
Files use the same control, so a block name is edited exactly like an item name. A disabled
`<fieldset>` around it disables it, like a native input. A value whose shape is not text (a map,
a list with a non-string entry, a string with a line break) is refused with a pointer to Files,
and the original stays untouched.

**The model owns the text.** `src/lib/rich-text/` holds pure functions with no DOM:

| Module | Role |
|---|---|
| `model.ts` | A line is a list of cells: one per character, glyph chip or kept tag, each with a full style (color or gradient, five decorations, effect). `parseLine` reads the stored form; `serializeLine` writes the canonical one (tags open in a fixed order, close only when the style changes, stay open at the end; `<` and `\` escaped). `storedValue` keeps bytes: an unchanged line returns the original string, and an edit that ends where it started returns the original value. |
| `edit.ts` | Typing, deleting by character, word or line, formatting a selection or the caret's pending style, glyph insertion, copy fragments, and `History` (typing within 800 ms is one undo step). |
| `dom.ts` | Draws the cells (a block per line, a span per run, a span per character inside a gradient, `contenteditable=false` chips) and maps DOM positions to cell offsets. Text goes in as text nodes only. |
| `colors.ts` | The 16 named colors as data, and the gradient math (Adventure's: stops spread evenly, first and last character on the end stops). The one allowlisted color file in `design-tokens.spec.ts`. |

The component intercepts `beforeinput`, `paste`, `copy`, `cut` and the shortcuts, applies the
edit to the model, and redraws; the browser never edits the DOM itself. IME composition is the
exception: it runs natively and its result is applied as one insert at `compositionend`.
Unknown input types are blocked rather than let the DOM drift from the model.

**Authoring rules.** A floating toolbar appears while the field has focus: bold, italic,
underline, strikethrough, obfuscated, color (the 16 named colors as swatches, plus a picker and a
hex field), gradient (2 to 8 stops, previewed), insert glyph (the open pack's glyph resources,
with their thumbnails), and clear formatting (which keeps an effect span written in Files).
Shortcuts: Ctrl+B / I / U, Ctrl+Shift+X (strikethrough), Ctrl+Shift+K (obfuscated),
`Ctrl+\` (clear), Ctrl+Z / Ctrl+Y or Ctrl+Shift+Z. With a collapsed caret, a format becomes the style of
what is typed next. Typed text takes the style of the text before the caret. A legacy code typed
or pasted (`&l`, `&6`, `&#rrggbb`) is removed and becomes formatting, as the user decided on the
issue. Pasted HTML is ignored: only `text/plain` (control characters dropped, line breaks become
spaces in a one-line field) or the editor's own fragment format (`application/x-materione-rich-text`,
written on copy) is read. Tags the model does not edit (`<shift:N>`, `<rainbow>`, a click tag in a
hand-written file) are kept as chips and written back as they were. There is deliberately no
"raw" toggle: the stored string is visible in Files' raw view (master §5.7).

**Game look.** The surface and the read-only previews (`RichText.svelte`, used by the item
tooltip preview) share `dom.ts` and the global `.mc-*` rules in `app.css`: the tooltip panel,
the client's drop shadow (the text color at 25% brightness), a name's default white and lore's
default dark purple, non-italic like the plugin's item text. Obfuscated text scrambles in
previews and flickers while editing (scrambling would fight the caret); both respect
`prefers-reduced-motion`. Shader effects are marked with a dotted underline, because they only
animate in game.

**Font.** Minecraftia 1.0 by Andrew Tyler, licensed CC BY-SA 3.0 (the license is recorded in the
font file's own name table and in `src/assets/fonts/LICENSE-Minecraftia.txt`). It comes from Font
Library and is bundled unmodified as `Minecraftia.ttf` (18 KB, sha256 `6124fe5b…788e12`); there is
no font CDN. It is a look-alike drawn by its author, not Mojang's font, which is not ours to
bundle. Its pixel grid is 1/8 em, so 16 px (`--text-minecraft`) is the game's 2x GUI scale with
every pixel crisp. ShareAlike binds adaptations of the font itself (a subset or a converted
file must stay CC BY-SA 3.0). It does not reach the editor's code, so it does not touch the
project licence question (master §14).

**Tests.** `test/rich-text.spec.ts` runs every case of `fixtures/text/rich-text.json` (the same
file the plugin's `MaterioneTextTest` reads). It also has a golden check that every display name,
label and lore list in `fixtures/packs` comes back byte-identical when untouched, plus the
editing and history rules. `browser/specs/rich-text.pw.ts` drives the real control: legacy codes
shown as formatting and left alone, toolbar formats, a gradient, typed codes and shortcuts with
undo and redo, lore lines, paste sanitising, glyph chips, block names, and a screenshot. QA
entries `editor.browser-rich-text-gradient` (+ `-visual`) check the authored gradient on the held
item in a real client.

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
