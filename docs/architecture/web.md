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
| Outbound-only from the MC server | The plugin dials out to Workers over HTTPS/WSS; no inbound to the server, ever (D11) |
| Stateless request handling | The editor SPA is stateless; relay services store opaque encrypted blobs (bytebin-style) |
| WebSocket support | Durable Objects give us stateful channels for the signed WS relay (bytesocks-style) |
| Free tier generosity | Fits constraint #3 — community-project traffic sits comfortably in free tiers |
| Global edge anycast | Admins get a nearby editor endpoint regardless of where the server lives |
| No servers to operate | Zero VMs for an OSS project to maintain |

Commitments we accept: TypeScript everywhere on the cloud side (Workers runtime, no JVM);
per-request CPU limits (fine — heavy work like pack compilation never runs in the cloud; it
runs on the MC server's pipeline); Cloudflare-specific bindings (R2, DOs, Cron) contained
behind small adapter seams so self-hosters can swap them (§8).

**Design rule carried throughout: the cloud never sees plaintext.** All relayed payloads are
E2E encrypted between plugin and browser (master §9.2); Workers store and forward ciphertext
only. The cloud is untrusted by construction.

---

## 2. Cloud topology

```
                        ┌────────────────────────────────────────────┐
                        │            cloudflare edge (workers)       │
                        │                                            │
  editor SPA ◄──────────┤  editor.materione.dev   static assets      │
  (browser, stateless)  │                         (Workers Sites)    │
       │                │                                            │
       │ WSS (signed    │  relay.materione.dev   Worker + Durable    │
       │ frames)        │                         Objects (channels) │
  plugin ──────────────►│                                            │
  (MC server,           │  store.materione.dev   Worker + R2         │
   outbound HTTPS,      │                         (payload store)    │
   E2E-encrypted blobs) │                                            │
       │                │  materione.dev         Workers Sites       │
       │                │                         (project website)  │
       │                │                                            │
       │                │  cron trigger          scheduled Worker    │
       │                │                         (TTL sweeps)       │
       └────────────────┴────────────────────────────────────────────┘

  NOT in the cloud: player-facing pack hosting (master §8.4) — that stays
  on the MC server's delivery plane (self-host/S3/CDN) because packs are
  public files the server already uploads.
```

Five Cloudflare products, one job each:

| Product | URL | Job |
|---|---|---|
| Workers Sites (static) | `editor.materione.dev` | Serve the editor SPA build |
| Workers + Durable Objects | `relay.materione.dev` | Signed WS relay channels |
| Workers + R2 | `store.materione.dev` | Payload store (encrypted blobs) |
| Workers Sites | `materione.dev` | Project website (docs, downloads, hub later) |
| Cron Triggers | — | TTL sweep of expired payloads/channels |

---

## 3. The relay services — specs

Two small services, bytebin/bytesocks-inspired but written to our spec (master open question
#5: fork-and-rebrand vs. write-ourselves — this doc assumes **write minimal equivalents**,
because our requirements differ in crypto and session model).

### 3.1 Payload store (`store.materione.dev`)

A dumb, opaque blob store with TTL. Endpoints:

| Method & path | Purpose |
|---|---|
| `POST /` | Upload encrypted payload → `{code, deleteToken, expires}` |
| `GET /{code}` | Fetch encrypted payload by unguessable code |
| `DELETE /{code}` | Delete (requires the per-blob delete token from upload) |
| `GET /{code}/meta` | Size, expiry — no plaintext metadata leaks |

Semantics:

- **Codes** are unguessable (§5.3), short base-N strings, capability-style.
- **Max size** 50 MB per payload (a full pack dump with assets), streamed and enforced during
  upload. Payloads larger than ~1 MB are expected to use the **manifest + content-addressed
  chunks** flow (§4.3) rather than one monolithic blob.
- **TTL classes**: 1 h (default), 24 h, 7 d. Expiry via cron sweep + lazy check on read.
  Nothing persists forever; the relay deletes only by TTL or delete token.
- **Envelope content-type only** — the store refuses anything that is not a Materione payload
  envelope; it is not a general file host.
- **No accounts.** Auth is code secrecy + E2E encryption. Rate limits are per-IP edge rules.
  There is no user database in the cloud at all (constraint #3; also nothing to leak).

### 3.2 WebSocket relay (`relay.materione.dev`)

A signed-message channel service. Each **channel** is a Durable Object.

Connection model:

1. Plugin runs `/materione editor` → generates session keys (§5), creates a relay channel
   (DO), joins it, uploads an E2E-encrypted session blob to the store, and prints the admin a
   URL: `https://editor.materione.dev/s/{code}`.
2. The browser decrypts the session blob locally (§5.4); it contains the channel id and the
   key material needed to complete the handshake.
3. Browser opens WSS to the relay and joins the channel. The DO relays frames between the two
   parties — it sees only `{msg, sig}` frames (plaintext JSON packet + detached signature,
   LuckPerms' frame format) and cannot read or forge contents.

Channel rules:

- **Max 2 parties** (plugin + 1 editor). A second browser joining → plugin is prompted to
  approve/trust it explicitly (nonce disambiguation, LuckPerms-style).
- **Max frame 1 MiB**; larger data goes through the payload store (§4.3).
- **Keepalive**: the DO answers transport pings; the plugin answers editor `ping` packets with
  `pong` (also signaling intentional disconnect, as in LuckPerms).
- **Channel TTL 24 h**, renewable by plugin presence; channels die when the server stops.
- **Reconnect**: either party may drop and rejoin the same channel id within TTL; session keys
  are channel-independent, so rejoin is seamless.
- **Bounded catch-up buffer**: the DO retains the last ~64 frames per channel so a briefly
  disconnected editor can catch up after rejoining.

### 3.3 Abuse & economics (quota sketch — to be validated against live free-tier behavior)

- Per-IP rate limiting at the edge (WAF / Workers rate-limiter binding).
- Quotas sized for free tiers with headroom; hard caps return clear 429s pointing at
  self-hosting docs (constraint #3). Sketch (per server instance, per day, on the shared free
  instance): **8 GB** payload-store egress, **2 GB** stored at any moment, **20k** store
  requests, **10** concurrent relay channels, **12 h** max channel life. Content-addressed
  chunk dedup (§4.3) keeps repeated full-pack dumps off the quota — a re-upload of a
  near-identical pack transfers only changed chunks.
- Sustainability posture: the shared instance is a convenience, not a promise — abuse or cost
  pressure shrinks quotas before it degrades the project, and self-hosting is always the
  documented escape hatch.

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
   plugin signing public key, protocol versions supported.
3. Plugin prints the admin a URL of the form
   `https://editor.materione.dev/s/{code}#{wrapping-key}` — **the store code identifies the
   blob, the URL fragment carries the wrapping key**. Fragments are never sent to any server
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
  │ print /s/{code}#{wrapping-key}         │
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
drops the session on a bad value.

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
extras); browse packs / resources (type + query filters) / get / history / types;
edit put / delete / setScalar (scalars **and scalar lists** through the lossless
layer) / create (curated per-type scaffolds, duplicate-refusing) / createPack /
move (journal-truthful renames); validate dryRun (syntax gate); assets
list / put / get / delete / **move**; pipeline reload (everything / one-pack —
fire-and-forget, outcome as `push.reload`, always announced for manual triggers);
admin diagnostics.recent (with source location) / audit.recent / settings.get /
settings.put (hash-guarded, validated before disk). Pushes: `push.reload`,
`push.diagnostic` (live, off the bus subscribe seam), and `push.document.changed`
(pack, path, deleted — the external-edit feed: a configurable WatchService pump
over `content/packs/` (~500 ms debounce, R5-clean via the scheduling gateway)
journals drift as an *external* revision through `DocumentStore.syncExternal` and
pushes to every editor; editor saves echo back unchanged because their write
already journaled). Still illustrative-only:
where-used graph, import pack, pre-apply report / apply confirm, preview renders,
`close`. Concurrency notes learned the hard way: pushes and RPC replies seal from
different threads — `EditorSession.sendFrame` synchronizes seal+write so sequence
assignment matches wire order, and the browser transports buffer frames arriving
during the handshake→channel handover instead of dropping them (a dropped frame
desyncs the strict receive sequence).

*Planned M3 product redesign:* `authoring.describe`, `authoring.createResource`,
`authoring.commit`, and `authoring.saveModel` wrap the primitive operations in revision-checked,
multi-file transactions. `delivery.status` exposes candidate/published hashes and
`delivery.publish` promotes an expected candidate hash or returns a visible stale error. These are
capability-negotiated additions; the primitive M3 operations remain available to Advanced Files
and older clients. Resource and asset listings gain pagination/filtering before relay mode makes
eager full-pack traversal a scale boundary.

### 4.3 Large payloads — manifest + content-addressed chunks

Documents are small (KBs) and ride the WS channel. Assets (textures, models, sounds — tens of
MB) never ride the channel; they flow through the **payload store as a manifest + chunks**,
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
- **Schema-driven studios**: registry metadata declares labels, sections/facets, ordering,
  constraints, reference targets, structured children, conditional visibility, widgets, and
  creation capabilities. The UI provides reusable controls and domain previews; it does not
  maintain a second item schema. Unknown fields remain preserved and visible in Advanced.
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
  installs). The owning item's model control opens a contextual workbench tab containing the
  same-origin iframe; `materione-bridge.js` is injected
  after load and communicates **only** via postMessage (`collect` → model JSON + textures +
  `.bbmodel` source; `importProject` → load an external project). `authoring.saveModel` receives
  owner/revision context and saves model JSON, textures, optional source, relationship manifest,
  and owner patch as one bounded transaction. Existing-owner, new-owner, and assets-only saves all
  use that operation; conflicts and injected post-promotion failures restore the prior file set.
  Bridge messages target and accept only the exact iframe origin/window and use an allowlisted,
  payload-capped protocol.
  The versioned relationship manifest is `.materione/model-links.json` in the canonical pack. It
  maps `owner-path#field.path` to model and optional source paths, preserves other links on update,
  and is authoring metadata excluded from the compiled client pack.
- **Versioning**: the SPA declares the protocol versions it speaks; the plugin serves hints if
  the cached SPA is stale ("a newer editor is available — refresh").

---

## 7. Project website (`materione.dev`)

Static site on Workers Sites: landing page, documentation (the `docs/` tree rendered — the
architecture docs you are reading), download links (GitHub releases), and later the community
pack hub (master open question #6 — if built, it's a separate Worker + KV/R2 read path, not
part of the editor path). Docs deploy from the repo on merge: one pipeline, no manual sync
between repo docs and website docs.

---

## 8. Self-hosting (constraint #3 in practice)

Everything in §2 is self-hostable by a third party:

- **Editor SPA**: static files; host anywhere, point it at any relay/store (they're configured
  by URL at build or runtime).
- **Relay + store**: standard `wrangler deploy` projects (open-source repos); R2 → any S3-compatible
  bucket, DOs → Cloudflare only (documented as the one hard Cloudflare coupling; a
  single-node WebSocket fallback implementation is provided for non-CF self-hosters, with
  reduced scale).
- **Federation defaults**: servers can pin custom relay/store URLs in `settings/` — an
  organization can run fully private infrastructure with zero code changes. The plugin treats
  relay/store endpoints as configurable, never hardcoded.

---

## 9. CI/CD & environments

| Artifact | Pipeline |
|---|---|
| Editor SPA | build → deploy to `editor.materione.dev` on tag (preview URL per PR) |
| Relay/store Workers | `wrangler deploy` on tag; staging worker pair on every PR; protocol conformance tests run against staging |
| Website | build docs → deploy on merge to main |
| Protocol fixtures | golden handshake/frames checked in; plugin tests and Worker tests run against the same fixtures (drift fails both sides) |

Environments: **local** (miniflare + plugin dev server), **staging** (`*.staging.materione.dev`),
**production**. Secrets via Wrangler secrets; nothing secret in the repo.

---

## 10. Threat model summary

| Threat | Mitigation |
|---|---|
| Relay operator reads content | E2E encryption; relay stores/forwards ciphertext envelopes only (§5) |
| Relay drops/reorders/replays frames | Per-direction sequence numbers; receivers reject gaps/replays/reorder (§5.4) |
| Forged messages via relay | Per-message signatures both directions (sign-after-encrypt); keys trusted only after in-game confirmation |
| Session blob interception (code or key alone) | Wrapping key travels in URL fragment, never to any server; code alone is useless (§4.1) |
| Capability URL guessing | ≥100-bit codes, short TTLs, rate limits (§5.3) |
| Malicious editor (second browser joins) | Explicit in-game approval with nonce disambiguation (§4.1) |
| Replay of old payloads | Envelope nonces + session binding; GCM rejects reuse |
| Direct-mode port exposed on LAN/hosting | Loopback-by-default, `0.0.0.0` warning, handshake auth applies regardless of binding (§4.4) |
| Relay/store outage | Direct mode is a complete fallback (master D11, §4.4) |
| Compromised `materione.dev` hosting (worst case) | Authenticity still holds (signatures verified plugin-side regardless of transport); confidentiality holds (keys never transit the cloud); worst case = denial, not intrusion |
| Abuse of free store as file host | Envelope-only content type, size caps, TTLs, per-IP limits, content-addressed dedup keeps honest traffic cheap (§3, §4.3) |
| Traffic analysis by the relay | Accepted, documented residual: sizes/timing/metadata are visible; contents are not (§5.4) |

The property we actually promise: **compromising all cloud infrastructure yields an attacker a
denial-of-service at most — not read, not write, not forge, not replay.**
