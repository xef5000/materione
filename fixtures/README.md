# Fixture corpus

Data-only content for tests — **never a Gradle module**. The corpus only
grows (roadmap rule 2): every bug found in the wild becomes a fixture.

Layout:

| Dir | Contents | First used |
|---|---|---|
| `golden/` | Golden files for deterministic output (D7): `settings-default.yml`, `m1-sample-pack.zip` | M0 |
| `packs/` | Valid content packs: `sample/` (3 items, template, recipe, shared-texture patterns — comments and blank lines are part of the lossless round-trip contract) and `corpus/` (the M1 exit-criteria pack: exactly 50 items — 30 auto-model sharing 8 textures, 10 template-inherited, 10 material-only — plus 4 recipes) | M1 |
| `faults/` | Fault-injection inputs: `broken-syntax.yml` (tab indent), `unresolved-ref.yml` (bad template ref), `missing-material.yml` (schema-invalid + healthy sibling in one file), `bad-recipe.yml` (unresolvable result + fine sibling), `template-cycle.yml` (self-looping inheritance), `hot-reload-broken.yml` (dropped into a live server by the harness) | M1 |
| `tools/` | `GenerateCorpus.java` — the deterministic generator behind `packs/corpus/` (run with `java fixtures/tools/GenerateCorpus.java`; regeneration is for review — the committed bytes are the fixture) | M1 |
| `importers/` | Real-world ItemsAdder/Oraxen/Nexo pack samples | M7 |
| `protocol/` | Editor wire-protocol contract fixtures (plugin + Workers tested against the same files) | M5 |

Tests reach this directory via the `materione.fixtures.root` system property
(injected by `materione.java-conventions`, which also declares `fixtures/`
as a test input so corpus changes invalidate test tasks). Regenerate goldens
with `-Dmaterione.golden.regen=true` — review the diff, then commit; CI
never regenerates.
