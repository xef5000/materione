# Materione

One free, open-source Minecraft plugin + web editor replacing the content-creation stack
(ItemsAdder / Oraxen / Nexo / BetterHUD / DeluxeMenus-class tools) with a single unified
system: content files on disk are the source of truth, one pipeline takes them to runtime,
and a browser editor (with embedded Blockbench) edits the same files — no port forwarding
required.

**Status:** M0–M4 shipped. The plugin boots on Paper 1.21.4+ and carries real content:
items with custom models, recipes and mechanics; custom blocks of every state technique
plus shaped stairs/slabs; furniture clusters (seats, storage, lights, evolution); an ECA
rule engine; glyphs, emoji and GLSL text effects; custom sounds, music discs and
paintings — all hot-reloadable (media applies restart-required through the staging
ledger). The direct-mode web editor works today: `/materione editor` prints a capability
URL, the browser completes a signed, encrypted handshake after an in-game approval, and
lands in a VS Code-style workbench — file-tree explorer with scaffolded
create/rename/move, form-first type editors with texture pickers (YAML stays one click
away as a dev tool), an embedded pinned Blockbench build with save-back and `.bbmodel`
import, a pixel editor, live diagnostics, and a control center with manual reloads,
settings editing and the audit trail. Hand-edited files on disk stream into open editors
instead of colliding. Every save goes live via targeted reload (<2 s round trip enforced
in CI). Next: the relay/cloud path (M5), then menus/dialogs/HUDs (M6). See
[`docs/roadmap.md`](docs/roadmap.md), the [`editor quickstart`](docs/editor-quickstart.md),
and the [feature-parity matrix](docs/feature-parity.md) — ItemsAdder/Oraxen/Nexo side by
side with what Materione has added (and what's QA-tested) per feature.

## Build & test

```bash
./gradlew build                     # unit + ArchUnit ban tests
./gradlew :harness:integrationTest  # real headless Paper boot of the shaded jar
```

Java 21 required (auto-provisioned via toolchain if missing). First integration run
downloads a pinned, sha256-verified Paper jar (~50 MB).

- Architecture & decisions: [`docs/architecture.md`](docs/architecture.md)
- How the build enforces the architecture: [`docs/architecture/build.md`](docs/architecture/build.md)
- Feature-parity matrix vs ItemsAdder/Oraxen/Nexo: [`docs/feature-parity.md`](docs/feature-parity.md)
- In-game QA suite: [`qa/README.md`](qa/README.md)
- Contributing / working in this repo: [`CLAUDE.md`](CLAUDE.md)

License: TBD (tracked as an open question — see `docs/architecture.md` §14).
