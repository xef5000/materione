# Harness — headless Paper integration testing

Boots a real (pinned) Paper server in a temp workspace, installs the built
plugin jar, drives the console, and asserts **stable log markers**. The
harness is deliberately product-independent: it knows nothing about
Materione's internals, only these markers.

## Stable log markers (test API — treat as contract)

| Marker | Emitted when |
|---|---|
| `MATERIONE-BOOT-OK <version>` | bootstrap phases completed (even degraded) |
| `MATERIONE-CONTENT-OK items=<n> defs=<n> pack=<hash12\|noop>` | content pipeline ran to apply (M1) |
| `MATERIONE-DELIVERY-OK <url>` | the pack HTTP backend serves the live pack |
| `MATERIONE-PACKFORMAT detected=<n\|none> advertising=<min>..<max>` | server-reported resource-pack format read from the server jar's version.json |
| `MATERIONE-VERSION <version>` | `/materione version` executed |
| `MATERIONE-DOCTOR-OK` | `/materione doctor` completed |
| `MATERIONE-RELOAD-OK items=<n>` | `/materione reload` completed (live item count) |
| `MATERIONE-BYE` | clean disable (shutdown reached the end) |
| `MATERIONE-DEV-PLACE ok=<bool> <status>` | `/materione dev place` landed (M2 world runtime) |
| `MATERIONE-DEV-REMOVE ok=<bool> <status>` | `/materione dev remove` landed |
| `MATERIONE-DEV-INSPECT found=<bool> <block\|none …>` | `/materione dev inspect` answered |
| `MATERIONE-DEV-SWEEP indexed=<n> …` | `/materione dev sweep` validated the index |
| `MATERIONE-DEV-FURNITURE-PLACE ok=<bool> <status>` | furniture cluster placed via the `dev place` fallback |
| `MATERIONE-DEV-FURNITURE-REMOVE ok=<bool> <status>` | furniture cluster removed via the `dev remove` fallback |
| `MATERIONE-DEV-FURNITURE-INSPECT found=<bool> <status>` | furniture cluster anchored at the inspected cell |
| `MATERIONE-GIVE-OK id=<id> player=<name> amount=<n>` | `/materione give` delivered (console senders only) |
| `MATERIONE-GIVE-DENIED reason=player-required` | console give without a target player |
| `MATERIONE-GIVE-FAILED reason=no-such-player\|no-content` | give could not resolve its target |
| `MATERIONE-BROWSER-DENIED reason=player-only` | `/materione browser` from the console |
| `MATERIONE-DEV-SOUND key=<pack>:<sound>` | `/materione dev sound` dispatched an in-game play (M4) |
| `MATERIONE-DEV-SOUND-DENIED reason=player-required` | `/materione dev sound` from the console (the client resolves the key, so a player is required) |
| `MATERIONE-STACK id=<id> item_model=<key\|none>` | `dev stack` built a live item's stack; the component names the item definition (`<id>`), never the pack model — `none` for material-only items |

The standard scenario boots with the fixture packs under `fixtures/packs/`
filtered to sample + corpus + nested (4 + 50 items, plus the nested pack's
item under `items/weapons/`, #191), so the expected live count is **58**; the
hot-reload scenarios below move it to 59 and back. The world
smoke between doctor and reload force-loads chunk (0, 0) — a headless boot
has no players to keep chunks warm — then drives `dev place → inspect →
sweep → remove` against `sample:amethyst_lamp` at `0 200 0`, the shaped
stair/slab pair at `1/2 200 …`, and finally a `sample:chair` furniture
cluster at `3 200 3` (placed and removed through the same dev commands).

The **world-persistence scenario** (`WorldPersistenceRunner`,
`PaperWorldPersistenceIT`) is the M2 exit gate: it boots with all three
fixture packs (**57** items live) and, across five boots in four
workspaces, proves a placed world — one block per state technique, the
shaped pair, and a furniture cluster with seat, storage, light, label and
evolution — survives `reload`, a full restart, and a copy of the `world/`
folder into a fresh workspace. It also carries the M2 fault injections:
an item file with a broken rule condition and an empty rule chain
(MTR-0021/0022 land, everything else stays live), and the corruption
pair — a block is placed, the disposable allocation table deleted plus a
first-sorting definition added, and the reboot's rebuilt table hands the
state slot to another id; the chunk-PDC record wins (MTR-0024 mismatch on
inspect, `drifted=1` + targeted resync on sweep).

## Running

```bash
./gradlew :harness:integrationTest          # full boot + command + stop cycle
./gradlew :harness:downloadPaperServer      # pinned jar (sha256-verified) into Gradle cache
./gradlew :harness:printLatestPaperPin -PpaperVersion=1.21.4   # bump procedure
```

The server jar is pinned by absolute URL + sha256 in
`gradle/libs.versions.toml` (the old PaperMC v2 download API is dead; the
harness makes zero API calls at test time). CI caches the Gradle-cache
directory keyed on the catalog hash.

Manually debugging a boot (also from IntelliJ — `application` run config on
`dev.materione.harness.PaperHarnessRunner`): the runner preserves the whole
workspace under `harness/build/work/<scenario>/` including `stdout.log` and
`logs/latest.log`.

## Scenarios

- `PaperBootstrapIT` — the M1 exit gate, live on Paper: boot with the
  fixture corpus (58 items), then the hot-reload sequence —
  1. no-op reload (nothing changed since boot),
  2. add `gem_extra` to the corpus pack → reload → 59 items,
  3. drop a syntactically broken item file → reload → still 59 items and
     `MTR-0007` in the log (fault injection, fail-soft D6),
  4. remove both files → reload → back to 58,
  5. nested (#191): add `nested_gem` under `nested/items/weapons/` → reload →
     59 items; a broken file beside it → still 59 and `MTR-0007`; remove both →
     back to 58.
- `PaperFailSoftIT` — writes a deliberately broken `settings/settings.yml`
  before boot; the server must STILL boot and pass doctor (fail-soft proof,
  master D6), with `MTR-0004` reported in the log.
- `PaperEditorHandshakeIT` — drives the real local handshake and editor mutations,
  including rapid glyph texture/create/save requests. Before comparing allocated glyph
  metadata with the compiled font, a queued console reload acts as a completion barrier:
  older `push.reload` events and allocation visibility alone do not prove that save's
  archive is ready. Failures include the server log tail in the assertion summary.

## Related: the agent probe loop (`scripts/probe/`, `docs/probe.md`)

The tiers above are headless — no client ever joins, so visual failure modes
(tofu glyphs, checkerboarded models) stay invisible to them. The **probe
loop** complements them locally: a second Paper pin (`paper-probe-*`, the
26.x seam) plus a real windowed client joined via the DebugBridge bridge mod,
giving an agent session RCON scene control and framebuffer screenshots. Not
CI; prerequisites and the full recipe live in [`docs/probe.md`](../docs/probe.md).

## Related: the in-game QA suite (`qa/`, `qa/README.md`)

Where the probe loop is a *debugging* tool (reproduce one reported failure),
the **QA suite** is the systematic layer on top of it: a committed test
catalog (`qa/catalog/`), a ledger of what passed at which commit
(`qa/ledger/`), dependency-ordered execution that blocks dependents of a
failure, and failure dossiers that file as GitHub issues. It rides the same
26.x probe locally — never CI (which runs only the cheap catalog `check`).
Charter and commands: [`qa/README.md`](../qa/README.md).
