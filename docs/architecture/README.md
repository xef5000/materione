# Architecture documents

| Doc | Level | Covers |
|---|---|---|
| [`../architecture.md`](../architecture.md) | **Master** — product & system architecture | Thesis, users & knowledge tiers, decisions D1–D11, five planes, content model (resources/packs/graph), pipeline overview, layouts, network mode, robustness principles, risks, open questions |
| [`../roadmap.md`](../roadmap.md) | **Route** — M0→M8 | Milestones from empty repo to full architecture; what's deliberately not in any milestone; cross-milestone rules |
| [`plugin.md`](plugin.md) | Technical | The Minecraft plugin: bootstrap & lifecycle, reload taxonomy, threading model, registry semantics, pipeline stages in detail, diagnostics, platform isolation, persistence layout (incl. durable allocation table), failure semantics, performance budgets, testing strategy, module boundaries |
| [`web.md`](web.md) | Technical | The Cloudflare serverless ecosystem: topology (one Worker + D1/Cron on `materione.com`), payload store & frame-mailbox specs + quotas & cost model, wire protocol & full handshake with key transport, cryptography (encrypted+signed+sequenced frames), editor SPA, self-hosting, CI/CD, threat model |
| [`runtime-systems.md`](runtime-systems.md) | Technical | Per content-type runtime: shared runtime contract (shadow-swap), ECA engine, items (incl. ItemModel tree builder), custom blocks, furniture, glyphs & GLSL, HUDs, menus & dialogs, recipes, sounds/paintings/discs, worldgen & fluids, armor, interop seams |
| [`build.md`](build.md) | Technical | The build as architecture enforcement: toolchain pins (incl. the M3 Node/SPA toolchain), module graph, convention plugins, shading policy (the FoliaLib drop; the M1 creative + M3 Undertow/SPA shading amendments), ban tests R1–R8 + how to prove them, test tiers (incl. fault injection + perf budgets), the headless-Paper harness (markers, hot-reload exit gate, the M3 editor handshake gate, pin bumping, IntelliJ run path), CI |
| [`parity-reconciliation.md`](parity-reconciliation.md) | **Contract** | Every target spec in `feature-parity.md` mapped to its owning section + milestone, or an explicit "dropped because"; resolved contradictions |
| [`../glossary.md`](../glossary.md) | **Reference** | One-line definitions of all project vocabulary, each linking to its full spec |

Reading order: **master → roadmap → parity-reconciliation** (the what, the when, the
contract with requirements), then whichever subsystem doc you're working in
(`plugin.md` for the server side, `web.md` for the cloud side, `runtime-systems.md` for
content behavior). Every subsystem section traces back to a master decision; subsystem docs
may not contradict the master — they amend it explicitly or not at all. The traceability
rule is enforced mechanically: every doc's claims must link to a D-number or a master
section, and `parity-reconciliation.md` is checked whenever `feature-parity.md` changes.

Still to be written (when their subsystem planning begins — tracked here so gaps stay
visible): the pack-compiler format doc (pack layout, model/ItemModel generation rules, font
allocation), the addon SDK guide, the importer specs (ItemsAdder/Oraxen/Nexo mappings), the
expression-language reference, and the schema/migration reference for content formats.
