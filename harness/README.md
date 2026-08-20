# Harness — headless Paper integration testing

Boots a real (pinned) Paper server in a temp workspace, installs the built
plugin jar, drives the console, and asserts **stable log markers**. The
harness is deliberately product-independent: it knows nothing about
Materione's internals, only these markers.

## Stable log markers (test API — treat as contract)

| Marker | Emitted when |
|---|---|
| `MATERIONE-BOOT-OK <version>` | bootstrap phases completed (even degraded) |
| `MATERIONE-VERSION <version>` | `/materione version` executed |
| `MATERIONE-DOCTOR-OK` | `/materione doctor` completed |
| `MATERIONE-BYE` | clean disable (shutdown reached the end) |

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

- `PaperBootstrapIT` — happy path: boot, version, doctor, stop.
- `PaperFailSoftIT` — writes a deliberately broken `settings/settings.yml`
  before boot; the server must STILL boot and pass doctor (fail-soft proof,
  master D6), with `MTR-0004` reported in the log.
