# CLAUDE.md — Materione

One Minecraft plugin + web editor that replaces the content-creation plugin stack
(ItemsAdder / Oraxen / Nexo / BetterHUD / DeluxeMenus-class tools) with a single unified,
free, open-source system. **Current repo state: M0 shipped** (skeleton, spine, ban tests,
headless-Paper harness — see [`docs/roadmap.md`](docs/roadmap.md) and
[`docs/architecture/build.md`](docs/architecture/build.md)); content features start at M1.

## Required reading (in order)

1. [`docs/idea.txt`](docs/idea.txt) — the problem, one page
2. [`docs/architecture.md`](docs/architecture.md) — **master architecture: decisions D1–D11 are binding**
3. [`docs/roadmap.md`](docs/roadmap.md) — M0→M8; work belongs to a milestone
4. [`docs/architecture/README.md`](docs/architecture/README.md) — index, then the subsystem
   doc(s) relevant to your task:
   - [`docs/architecture/plugin.md`](docs/architecture/plugin.md) — plugin internals
   - [`docs/architecture/web.md`](docs/architecture/web.md) — Cloudflare/editor ecosystem
   - [`docs/architecture/runtime-systems.md`](docs/architecture/runtime-systems.md) — per-content-type runtime
5. [`docs/glossary.md`](docs/glossary.md) — project vocabulary (consult as needed)
6. [`docs/feature-parity.md`](docs/feature-parity.md) — requirements detail; its target specs
   map to owners in [`docs/architecture/parity-reconciliation.md`](docs/architecture/parity-reconciliation.md)

## Hard rules (violating any of these is a defect, not a style choice)

- **Decisions D1–D11 bind.** If work seems to require breaking one, stop and amend
  `docs/architecture.md` first (explicitly, with rationale) — never silently work around it.
- **Subsystem docs may not contradict the master doc.** New design detail goes in the
  subsystem doc and traces back to a D-number or master section.
- **Naming is "Materione"** everywhere (commands `/materione`, namespace `materione:`,
  folder `plugins/Materione/`). The old working name "MaterYard" is dead.
- **Oraxen's source is GPL** — read it as reference only; never copy code or config
  structure verbatim into this repo. **Blockbench stays unmodified** (GPL-3 with plugin
  exception); we integrate via a Blockbench plugin across a message boundary.
- **Files on disk are the source of truth** (D1). Nothing may create a second source of
  truth (no database of content, no editor-only state).
- **One pipeline** from files to runtime (D6) — no "UI applies changes directly" shortcut.
- **Fail-soft everywhere**: content errors quarantine; the server never crashes on content.
- **Lossless round-trip** (master §5.6): load → untouched save is byte-identical; unknown
  fields preserved; one schema source per content type (registry self-description).
- **Module boundaries are enforced by the build** (plugin.md §11), not by review goodwill.
- **Performance budgets (plugin.md §9) are enforced from M1**, not retrofitted.

## Toolchain floor (decided; details beyond this belong to M0)

- Java 21 (forced by the Paper 1.21.4+ floor, D10); Gradle multi-module per master §10.
- JUnit 5; golden-file fixtures for all compiled output; ArchUnit-style ban tests;
  headless-Paper integration harness. The fixture corpus only grows (roadmap rule 2).
- Cloud side: TypeScript on Cloudflare Workers (web.md); the same protocol fixtures test
  plugin and Workers against each other.

## Do not silently decide these (open questions — master §14)

Project license (GPL vs permissive — affects Oraxen-derived insight and publishing);
cross-server state adapters; Geyser timing; web UI bundling; relay quotas; community hub.
If your work forces a choice on one, surface it in the PR/doc discussion instead.

## Documentation conventions

- New architecture docs go in `docs/architecture/` and must be added to its
  [README](docs/architecture/README.md) index, including the "still to be written" tracking.
- Roadmap rule 1: a milestone isn't done until the docs reflect what shipped.
- Diagnostics codes are stable and documented (`MTR-####`, plugin.md §5) — treat them as API.

## Working on this repo

- Docs are the current product. Edit them with the same rigor as code: precise claims,
  traceability, no contradictions. Verify cross-references after restructures.
- English prose, tables for enumerations, ASCII diagrams where a picture prevents a
  paragraph. Match the existing docs' tone (declarative, rationale-bearing).
