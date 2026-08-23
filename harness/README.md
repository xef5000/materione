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
| `MATERIONE-VERSION <version>` | `/materione version` executed |
| `MATERIONE-DOCTOR-OK` | `/materione doctor` completed |
| `MATERIONE-RELOAD-OK items=<n>` | `/materione reload` completed (live item count) |
| `MATERIONE-BYE` | clean disable (shutdown reached the end) |
| `MATERIONE-DEV-PLACE ok=<bool> <status>` | `/materione dev place` landed (M2 world runtime) |
| `MATERIONE-DEV-REMOVE ok=<bool> <status>` | `/materione dev remove` landed |
| `MATERIONE-DEV-INSPECT found=<bool> <block\|none …>` | `/materione dev inspect` answered |
| `MATERIONE-DEV-SWEEP indexed=<n> …` | `/materione dev sweep` validated the index |

The standard scenario boots with the fixture packs under `fixtures/packs/`
(sample 3 items + the 50-item corpus pack), so the expected live count is
**53**; the hot-reload scenario below moves it to 54 and back. The world
smoke between doctor and reload force-loads chunk (0, 0) — a headless boot
has no players to keep chunks warm — then drives `dev place → inspect →
sweep → remove` against `sample:amethyst_lamp` at `0 200 0`.

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
  fixture corpus (53 items), then the hot-reload sequence —
  1. no-op reload (nothing changed since boot),
  2. add `gem_extra` to the corpus pack → reload → 54 items,
  3. drop a syntactically broken item file → reload → still 54 items and
     `MTR-0007` in the log (fault injection, fail-soft D6),
  4. remove both files → reload → back to 53.
- `PaperFailSoftIT` — writes a deliberately broken `settings/settings.yml`
  before boot; the server must STILL boot and pass doctor (fail-soft proof,
  master D6), with `MTR-0004` reported in the log.
