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
| `MATERIONE-GUI-OPENED id=<id> player=<name> rows=<n> slots=<n>` | a live GUI's window was opened for a player (`/materione gui open`, or the editor's preview) |
| `MATERIONE-GUI-REFRESH changed=<n> removed=<n> players=<n>` | a swap changed or removed live GUIs; the online players' windows are brought up to date (gui.md §5) |
| `MATERIONE-GUI-REFRESH-CLOSED id=<id> player=<name>` | a viewer's window was closed because its GUI is no longer live |
| `MATERIONE-GUI-DENIED reason=player-required` | `/materione gui open` from the console without naming a player |
| `MATERIONE-GUI-FAILED reason=not-live\|no-such-player\|refused` | `/materione gui open` could not resolve the GUI, the player, or the open was refused |
| `MATERIONE-GUI-FAILED reason=window gui=<id> player=<name> detail=<why>` | the server refused or could not open a GUI's window after its session was created; the session ended (#214) |
| `MATERIONE-OPEN-TREE declared=<n> registered=<n>` | the server's COMMANDS event registered the open commands from the built registries (#215); printed at start and on each server reload that fires the event |
| `MATERIONE-OPEN-TREE reload aliases=[<a>, …]` | a content swap asked for a server data reload, because an open command was missing from the tree or had a new argument shape (logic.md §23, decision 15). Never printed for an unchanged boot |
| `MATERIONE-OPEN-TREE reload-deferred aliases=[<a>, …] reason=rate-limit` | such a request fell inside the five-second window after the previous reload and is coalesced into the next swap after it |
| `MATERIONE-OPEN-COMMAND alias=<alias> outcome=opened\|refused\|usage\|console` | a GUI's open command (`open: commands:`, #215) ran: `opened` or `refused` for a player (the refusal's reason is in chat), `usage` for an argument that does not read as its type, `console` for a console sender, which has no window |

The `guilogic` fixture pack (#214) announces its own opens and closes on the console as
`say MATERIONE-GUILOGIC-OPEN <gui> ...`, `MATERIONE-GUILOGIC-PONG slot=<n>` and
`MATERIONE-GUILOGIC-CLOSE <gui> reason=<reason>`; those lines come from the pack's rules, not the plugin, and
only the QA catalog's `guilogic` suite reads them.

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

The boots use the CI ports (server 25599, editor 8089, delivery 8090). When another server on the
machine holds them, pass free ones: `-Pharness.serverPort=25611 -Pharness.editorPort=8111
-Pharness.deliveryPort=8112`. The editor and delivery ports are written into the workspace's
`settings.yml` only when overridden; the defaults are untouched.

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
- `PaperGuiRuntimeIT` — the M6 runtime gate (gui.md §5, §7, issue 185): a GUI authored into the sample
  pack goes live at boot; the console's `gui open` refuses correctly (no player to show it to, an
  unknown player, a GUI that is not live); the editor's `runtime.previewTarget` reports no player and
  `runtime.openGui` refuses with `MTR-0009` for a console session; then the editor's own saves drive the
  live refresh — a changed title is `changed=1` within the 2 s live-loop budget, a schema break is
  `removed=1` and the GUI stops resolving, and a slot naming an item that is not live is `MTR-0054`
  while the GUI stays live. Headless: no client joins, so the window itself is judged by the unit tiers
  and the QA catalog (`gui.runtime-*`).
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
