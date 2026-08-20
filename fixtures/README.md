# Fixture corpus

Data-only content for tests — **never a Gradle module**. The corpus only
grows (roadmap rule 2): every bug found in the wild becomes a fixture.

Layout:

| Dir | Contents | First used |
|---|---|---|
| `golden/` | Golden files for deterministic output (D7) | M0 — `settings-default.yml` |
| `packs/` | Sample content packs (valid + deliberately broken) | M1 |
| `faults/` | Fault-injection inputs (parse errors, corrupt tables) | M1 |
| `importers/` | Real-world ItemsAdder/Oraxen/Nexo pack samples | M7 |
| `protocol/` | Editor wire-protocol contract fixtures (plugin + Workers tested against the same files) | M5 |

Tests reach this directory via the `materione.fixtures.root` system property
(injected by `materione.java-conventions`). Regenerate goldens with
`-Dmaterione.golden.regen=true` — review the diff, then commit; CI never
regenerates.
