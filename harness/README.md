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
filtered to sample + corpus, with **57** live items including auto placement
items; the hot-reload scenario below moves it to 58 and back. The world
smoke between doctor and reload force-loads chunk (0, 0) — a headless boot
has no players to keep chunks warm — then drives `dev place → inspect →
sweep → remove` against `sample:amethyst_lamp` at `0 200 0`, the shaped
stair/slab pair at `1/2 200 …`, and finally a `sample:chair` furniture
cluster at `3 200 3` (placed and removed through the same dev commands).

The **world-persistence scenario** (`WorldPersistenceRunner`,
`PaperWorldPersistenceIT`) is the M2 exit gate: it boots with all three
fixture packs (**76** items live) and, across five boots in three
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
./gradlew :harness:integrationTest          # all five scenarios, nine server launches
./gradlew :harness:integrationTest --rerun  # force execution, not a cached test result
./gradlew :harness:downloadPaperServer      # pinned jar (sha256-verified) into Gradle cache
./gradlew :harness:printLatestPaperPin -PpaperVersion=1.21.4   # bump procedure
```

The server jar is pinned by absolute URL + sha256 in
`gradle/libs.versions.toml` (the old PaperMC v2 download API is dead; the
harness makes zero API calls at test time). CI caches the Gradle-cache
directory keyed on the catalog hash, OS, Java major, and cache layout version.

### Runtime binary cache

Fresh workspaces receive independent copies of the pinned server's bootstrap
JARs from `cache/`, `libraries/`, and `versions/`. The first successful boot
populates a checksummed manifest atomically; subsequent workspaces reuse it.
Entries are addressed by the Paper JAR's SHA-256, OS/architecture, Java major, and layout
version under `~/.gradle/caches/materione-paper/runtime/`. Every restore verifies
the manifest before copying any files.

Worlds, settings, content, plugin JARs/remapping, allocation state, and generated
Materione output are **never** included. Restart, world-copy, and corruption tests
still operate on their actual scenario state. A corrupt cache fails explicitly;
remove the named entry or use `-Pharness.runtimeCache=false` to bypass it.
`-Pharness.runtimeCacheDir=<directory>` isolates a cold-cache experiment.
Quote these `-P...` arguments in PowerShell.

The binary cache and Gradle's **test-result cache** are different. Use `--rerun`
when measuring or investigating a flake; `integrationTest FROM-CACHE` does not
boot a server and is not evidence of improved execution speed.

### Failure evidence and timeouts

`MTR-HARNESS` records identify workspace, launch PID, readiness, marker waits,
cache hit/miss, editor save, and shutdown durations. Failed tests print complete
assertion messages, causes, actual process status, and bounded server/stdout tails.
Session capabilities are redacted. Marker cursors are taken **before** the action;
waits return only fresh lines and stop immediately if the child exits.
Child stdout/stderr are explicitly UTF-8 on Windows too. Incomplete trailing log
lines are not matched or decoded until the writer finishes them.

Boot, command, and clean-stop deadlines default to 300, 60, and 180 seconds.
Override them with `MTR_HARNESS_BOOT_SECS`, `MTR_HARNESS_COMMAND_SECS`, and
`MTR_HARNESS_STOP_SECS`. Changing these deadlines is not a fix for a missing
marker. The architecture's performance budgets are unchanged.

CI runs `node scripts/ci-report.mjs` even after a JVM failure. It prints JUnit
failures and phase measurements into the job console and summary **before**
attempting artifact upload. The optional `harness-debug` artifact contains
redacted XML/log/config/crash evidence, including `failsoft/bootstrap`, capped at
128 source files / 8 MiB total and 256 KiB per file (plus a small manifest).
Truncated XML is named `.truncated.txt`, not presented as a valid XML report.
Artifact quota exhaustion cannot replace or hide the test failure.

Manually debugging a boot (also from IntelliJ — `application` run config on
`dev.materione.harness.PaperHarnessRunner`): the runner preserves the whole
workspace under `harness/build/work/<scenario>/` including `stdout.log` and
`logs/latest.log`.

## Scenarios

- `PaperBootstrapIT` — the M1 exit gate, live on Paper: boot with the
  fixture corpus (57 items), then the hot-reload sequence —
  1. no-op reload (nothing changed since boot),
  2. add `gem_extra` to the corpus pack → reload → 58 items,
  3. drop a syntactically broken item file → reload → still 58 items and
     `MTR-0007` in the log (fault injection, fail-soft D6),
  4. remove both files → reload → back to 57.
- `PaperFailSoftIT` — writes a deliberately broken `settings/settings.yml`
  before boot; the server must STILL boot and pass doctor (fail-soft proof,
  master D6), with `MTR-0004` reported in the log.
- `PaperEditorHandshakeIT` — direct editor handshake, signed RPC, authoring,
  file-watcher and save-back flows, including the unchanged save latency budget.
- `PaperRelayEditorIT` — the default relay editor flow against a local fake
  mailbox; no production relay is contacted.
- `PaperWorldPersistenceIT` — the five-launch persistence and corruption sequence
  described above. Launches within this scenario remain ordered.

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
