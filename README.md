# Materione

One free, open-source Minecraft plugin + web editor replacing the content-creation stack
(ItemsAdder / Oraxen / Nexo / BetterHUD / DeluxeMenus-class tools) with a single unified
system: content files on disk are the source of truth, one pipeline takes them to runtime,
and a browser editor (with embedded Blockbench) edits the same files — no port forwarding
required.

**Status:** M0–M2 shipped. The plugin boots on Paper 1.21.4+ and carries real content:
items with custom models, recipes and mechanics; custom blocks of every state technique
plus shaped stairs/slabs; furniture clusters (seats, storage, lights, evolution); an ECA
rule engine — all hot-reloadable, with the world-facing systems proven against a live
Paper harness (reload, restart, world copy). Next: the web editor (M3), then
glyphs, sounds & paintings (M4). See [`docs/roadmap.md`](docs/roadmap.md).

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
