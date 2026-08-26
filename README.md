# Materione

One free, open-source Minecraft plugin + web editor replacing the content-creation stack
(ItemsAdder / Oraxen / Nexo / BetterHUD / DeluxeMenus-class tools) with a single unified
system: content files on disk are the source of truth, one pipeline takes them to runtime,
and a browser editor (with embedded Blockbench) edits the same files — no port forwarding
required.

**Status:** M0–M2 shipped; M3 (web editor v1) shipped with one deferred bullet
(Blockbench integration → early M4). The plugin boots on Paper 1.21.4+ and carries
real content: items with custom models, recipes and mechanics; custom blocks of every
state technique plus shaped stairs/slabs; furniture clusters (seats, storage, lights,
evolution); an ECA rule engine — all hot-reloadable. The direct-mode web editor works
today: `/materione editor` prints a capability URL, the browser completes a signed,
encrypted handshake after an in-game approval, edits items/blocks/furniture through
schema-driven forms over the lossless YAML layer, uploads textures, and watches every
save go live via targeted reload (<2 s round trip enforced in CI). Next: glyphs &
sounds plus the Blockbench integration (M4), then the relay/cloud path (M5). See
[`docs/roadmap.md`](docs/roadmap.md).

## Build & test

```bash
./gradlew build                     # unit + ArchUnit ban tests
./gradlew :harness:integrationTest  # real headless Paper boot of the shaded jar
```

Java 21 required (auto-provisioned via toolchain if missing). First integration run
downloads a pinned, sha256-verified Paper jar (~50 MB).

- Architecture & decisions: [`docs/architecture.md`](docs/architecture.md)
- How the build enforces the architecture: [`docs/architecture/build.md`](docs/architecture/build.md)
- Contributing / working in this repo: [`CLAUDE.md`](CLAUDE.md)

License: TBD (tracked as an open question — see `docs/architecture.md` §14).
