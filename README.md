# Materione

One free, open-source Minecraft plugin + web editor replacing the content-creation stack
(ItemsAdder / Oraxen / Nexo / BetterHUD / DeluxeMenus-class tools) with a single unified
system: content files on disk are the source of truth, one pipeline takes them to runtime,
and a browser editor (with embedded Blockbench) edits the same files — no port forwarding
required.

**Status:** M0 shipped — skeleton & spine. The plugin boots on Paper 1.21.4+, enforces
nothing yet, but every architectural invariant (module boundaries, scheduler gateway,
diagnostics, lossless settings round-trip) is build-enforced and tested. Content features
begin at M1. See [`docs/roadmap.md`](docs/roadmap.md).

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
