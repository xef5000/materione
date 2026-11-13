# Fixture corpus

Data-only content for tests — **never a Gradle module**. The corpus only
grows (roadmap rule 2): every bug found in the wild becomes a fixture.

Layout:

| Dir | Contents | First used |
|---|---|---|
| `golden/` | Golden files for deterministic output (D7): `settings-default.yml`, `m1-sample-pack.zip`, `editor-demo-snapshot.json` (the editor ops recorded over `packs/sample` + `packs/world`; replayed by the cloud editor's demo, web.md §6) | M0 |
| `packs/` | Valid content packs: `sample/` (3 items, template, recipe, shared-texture patterns — comments and blank lines are part of the lossless round-trip contract) and `corpus/` (the M1 exit-criteria pack: exactly 50 items — 30 auto-model sharing 8 textures, 10 template-inherited, 10 material-only — plus 4 recipes) and `nested/` (one item under `items/weapons/`, the subfolder discovery fixture, #191) | M1 |
| `faults/` | Fault-injection inputs: `broken-syntax.yml` (tab indent), `unresolved-ref.yml` (bad template ref), `missing-material.yml` (schema-invalid + healthy sibling in one file), `bad-recipe.yml` (unresolvable result + fine sibling), `template-cycle.yml` (self-looping inheritance), `hot-reload-broken.yml` (dropped into a live server by the harness), `dangling-glyph.yml` (alias to an unknown glyph), `chained-glyph-alias.yml` (alias targeting another alias — mutual chain with its in-test sibling) | M1 |
| `tools/` | `GenerateCorpus.java` — the deterministic generator behind `packs/corpus/` (run with `java fixtures/tools/GenerateCorpus.java`; regeneration is for review — the committed bytes are the fixture) | M1 |
| `importers/` | Real-world ItemsAdder/Oraxen/Nexo pack samples | M7 |
| `protocol/` | Editor wire-protocol contract fixtures (plugin + Workers tested against the same files) | M5 |
| `gui/` | Golden GUI YAML: `shop.yml` (all seven built-in components; load → save is byte-identical, unknown fields survive) and `bad-layer.yml` (each broken layer skipped alone with `MTR-0052`). Read by `GuiTypeTest` (gui.md §10) | M6 |
| `protocol/editor-ops-v1.json` | Op-level frames: `authoring.setFields` with a paint asset, and the same save refused as stale. The JVM regenerates it (`EditorOpsGoldenTest`); the SPA wrapper must send it byte for byte (`ops-golden.spec.ts`) | M6 |
| `gui/raster/` | The GUI raster vectors (gui.md §4): `<name>.json` input + `<name>.png` expected for panel/button/slots bevels, slot grid, image/paint blits, opacity and compositing. The Java compiler and the editor's TS renderer (`webui/test/gui-raster.spec.ts`) both pass the same files; its README is the exact pixel-rule spec, `generate-vectors.mjs` an independent reference renderer | M6 |
| `escrow/` | Byte-exact goldens of the crash-safe input-slot escrow (gui-input-durability.md §5.3, row M): `account-v1-*.hex` (empty, one HELD, OWED + EXITING, the legacy `gui_inputs` journal adopted as OWED entries (row P), bad CRC, newer version, truncated, unknown state with a valid CRC) and `ledger-v1-*.hex` (empty, INTENT + DONE, torn tail, bad-CRC last record, corrupt mid-file, newer version, zero-filled tail, torn header). Hex files; `#` lines are comments. Read by `EscrowGoldenFixturesTest`; add a fixture with `-Dmaterione.golden.regen=true`, never change one | M6 |
| `codemode/` | Code mode (codemode.md §3.8, §11): `registry/plugin-current.json` (the real `authoring.describe` reply and capability list, written by the JVM test `EditorDemoSnapshotTest`) and `registry/with-addon.json` (the same plus a fake addon's action, trigger and content type, derived by a test helper); `corpus/` the benchmark programs `<task>.ts` with `<task>.expect.json` (registry, `pass`/`fail`, expected diagnostics), type-checked by `codemode-compile.spec.ts`. The generated `.d.ts` per registry is the golden in `golden/codemode/`; regenerate it with `MATERIONE_GOLDEN_REGEN=true npx vitest run test/codemode-generate.spec.ts` in `webui/` (the JVM's `-Dmaterione.golden.regen=true` regenerates the registry snapshot). The corpus only grows | M6.5 |
| `text/` | `rich-text.json`: what each stored rich text string means (master §5.7). The plugin's `MaterioneTextTest` and the editor's `rich-text.spec.ts` both read it, so the two parsers cannot drift | M5 |

Tests reach this directory via the `materione.fixtures.root` system property
(injected by `materione.java-conventions`, which also declares `fixtures/`
as a test input so corpus changes invalidate test tasks). Regenerate goldens
with `-Dmaterione.golden.regen=true` — review the diff, then commit; CI
never regenerates.
