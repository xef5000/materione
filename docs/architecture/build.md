# Build, boundaries, and verification

How the Materione codebase is built — and how the build itself enforces the architecture.
Traces to master §10 (codebase division) and §11 (enforced, not aspirational); the module
boundaries here are the same ones described there, made mechanical.

Status: **M0–M4 shipped; the editor-trust follow-up and browser regression tier are M5 additions**
(roadmap rule 1 — this doc reflects what exists, not what's planned). The M5 relay/cloud
exit remains separate; anything else marked *M-later* is reserved, not built.

---

## 1. Toolchain floor

| Tool | Pin | Notes |
|---|---|---|
| Java | 21 | Toolchain-pinned; the Paper 1.21.4+ floor forces it (D10). No vendor constraint locally; CI provisions Temurin 21. |
| Gradle | 9.7.1 | Wrapper-committed, `distributionSha256Sum` pinned. |
| JUnit | 5.14.4 | Platform launcher 1.14.4 pinned too (Gradle no longer injects it). |
| AssertJ | 3.27.7 | |
| ArchUnit | 1.5.0 | Powers the ban tests (§5). |
| paper-api | 1.21.4-R0.1-SNAPSHOT | **Compile against the floor**, `compileOnly`. Post-floor APIs are structurally unreachable outside `platform/` — plugin.md §8.2 enforcement becomes a compile error. `platform/` may add a newer pin when a second implementation exists (M-later). |
| paper-api (modern) | 26.1.2.build.74-stable | The D10 exception, realized: `:modern` compiles against a 26.x paper-api (JVM 25 toolchain — Paper 26.x requires it — while `--release 21` keeps the bytecode floor-loadable; the compile classpath requests the JVM-25 dependency variant explicitly). Classes here run only after `RegistryCapabilities` confirms the 26.x registry APIs exist at runtime; a floor server never links them. `:modern` depends on api, core, content and platform; platform supplies only `MaterioneText`, so painting titles parse exactly like the datapack path (plugin §6.1). |
| SnakeYAML | 2.2 | `compileOnly` — the server provides it at runtime; never shaded. |
| Gson | 2.11.0 | `compileOnly` like SnakeYAML (server-provided); used for the server jar's `version.json` (pack-format detection) and by `pack/` via creative. |
| creative | 1.14.4 | MIT. The **Nexo-maintained fork** of `team.unnamed/creative` — same coordinates, resolved from `repo.nexomc.com/releases/` (filtered to `team.unnamed`). The pack **output emitter** inside `pack/` + the self-host HTTP backend in `delivery/`. Shaded + relocated (§4). Determinism, allocation, validation, and the D7 hash guarantees remain ours — creative is the serializer of record. The fork (not upstream 1.7.3) because it tracks new Minecraft versions in Nexo production: 1.14.x writes `pack.mcmeta` `min_format`/`max_format`, the only range schema 25w31a+ (1.21.9/26.x) clients honor. |
| undertow | 2.3.26.Final | Apache-2.0. **M3**: the embedded editor listener's HTTP+WS server (`web/` only — ban rule R8). Chosen over Jetty after Jetty 12's reworked websocket API proved unstable ground for a shaded long-term dependency; Undertow's embedded API has been stable across its whole 2.x line. Shaded + relocated with its XNIO/JBoss runtime (§4). |
| bStats | 3.2.1 | MIT. `org.bstats:bstats-bukkit` (+ its `bstats-base` runtime): anonymous usage metrics, plugin id 34981. Shaded + relocated under `materione.libs.org.bstats` (§4). Referenced only by `MaterionePlugin`, behind a fail-soft wrapper; the data and the opt-out are described in [plugin.md](plugin.md) §7.2. |
| Node / npm | node 22.17.0 | **M3**: provisions hermetically via `com.github.node-gradle.node` 7.1.0 for `:webui` (the SPA build). No local Node required; CI included. The Node distribution resolves through an Ivy repo declared in settings (`org.nodejs:node`). SPA deps pin via committed `package-lock.json`. |
| Playwright | `@playwright/test` 1.64.1 | Dev-only browser regression tier for `webui/`; Chromium is installed separately and is not shipped in the SPA or plugin jar. |
| Vault API | 1.7.1 (`com.github.MilkBowl:VaultAPI`) | **#213**: `compileOnly` in `integrations`, for the economy and permission adapters. Resolved from JitPack through a settings repository filtered to that group. Its pom declares no license; verify the upstream license before publishing a shaded adapter (none is shaded). Its Bukkit dependency is `provided`, so nothing transitive joins the build. |
| LuckPerms API | 5.4 (`net.luckperms:api`, MIT) | **#213**: `compileOnly` in `integrations`, for the LuckPerms permission adapter. Maven Central; its pom has no runtime dependencies. |
| Harness Paper | 1.21.4 build 232 | Absolute URL + sha256 pinned in the version catalog (§7). |

Kotlin DSL throughout, one version catalog (`gradle/libs.versions.toml`), convention plugins
under `build-logic/` (named `build-logic`, not `build` — `build/` is Gradle's output directory).

## 2. Module graph

```
                 ┌───────────────────────────── product (shaded) ─────────────────────────────┐
                 │                                                                            │
  api ◄──── core ◄── platform          plugin ──► (api, core, platform, content, pack, delivery
   ▲          ▲     ▲                             — the assembly leaf; nothing depends on it)
   │          │     │
   │          │     │        content ──► api, core          (item + recipe content types)
   └──────────┴─────┴─────► pack ──► api, core, (creative, SnakeYAML-testOnly)
                             delivery ──► api, core + creative-server
                             integrations ──► api, core + paper-api, Vault API, LuckPerms API
                                                    (compileOnly: the soft-depend adapters, #213)
                             behavior, web, importers — skeletons until their milestones; they may
                             depend on api + core, never on each other's internals, never on
                             platform internals (master §10 rules)

  testing ──► testFixtures consumed by every module's tests (build infrastructure, not shipped)
  harness  ──► standalone: boots the real server, copies the shaded jar in (no product deps)
  fixtures ──► data only, not a Gradle module
  webui    ──► M3: the editor SPA (Node/Svelte build; static output embedded
                             into the shaded jar under webui/ — not a Java module)
```

As-built amendment (M1, recorded): `platform` exposes `core` as an `api` dependency — the
platform layer builds runtime stacks (`MaterioneStacks`, `RecipeApplier`) from core's runtime
types (`RuntimeItem`, `RuntimeRegistries`). Master §10 forbids depending on *internals* of
siblings; core's `runtime` package is api-grade by design, so the composition is documented
here rather than routed around.

Enforcement is two-layered: Gradle dependency declarations (a module literally cannot see
another's classes) plus ArchUnit ban tests over the compiled result (§5). The shaded jar is
assembled by `plugin/` via Shadow; **nothing depends on `plugin`** (the leaf, rule R4).

## 3. Convention plugins (`build-logic/`)

| Plugin | Applied by | What it adds |
|---|---|---|
| `materione.java-conventions` | everything | Toolchain 21, `-parameters -Xlint -Werror` (escape: `-Pmaterione.noWerror`), UTF-8, reproducible archives (no timestamps, stable order — D7), JUnit + launcher, `materione.fixtures.root` system property, `fixtures/` declared as a test **input** (a corpus change invalidates test tasks — path references are invisible to Gradle otherwise), `materione.golden.regen` passthrough, integration-tag exclusion on unit `Test` tasks. |
| `materione.library-conventions` | library modules | Test bundle (JUnit, AssertJ, ArchUnit) + `testing` testFixtures. |
| `materione.paper-conventions` | api, core, platform, plugin, integrations | `paper-api` and SnakeYAML as `compileOnly` — never `implementation`, never on a runtime classpath, never shaded. The server provides both. (`integrations` joined in #213, for the Bukkit types its adapters name.) |
| `materione.plugin-conventions` | plugin only | Shadow wiring (relocations for creative, Gson, Undertow + its runtime, bStats), `paper-plugin.yml` `@materioneVersion@` token replacement, generated `MaterioneVersion` source, distributable naming (`Materione-<version>.jar`, plain jar classifier `plain`). |

Custom tasks in `build-logic/src/main/kotlin`: `GenerateVersionSourceTask` (version class
from the Gradle version — one source of truth), `DownloadPaperServerTask` (pinned-jar
fetch, sha256-verified, content-addressed cache), `PrintLatestPaperPinTask` (pin-bump
helper, §7).

## 4. Shading policy

**Zero shaded runtime dependencies at M0.** The Shadow wiring existed from day one so the
first shaded dependency would land correct-by-construction: relocate under `materione.libs.*`,
`mergeServiceFiles`, no minimize-by-hint (breaks reflection).

**M1 amendment (user-approved, recorded here): the first shaded dependency is [creative]
1.7.3** (MIT) plus its transitive Gson — both relocated under `materione.libs.team.unnamed` /
`materione.libs.com.google.gson` by `materione.plugin-conventions`. Relocation is verified in
the shaded jar (zero unrelocated classes of either root). Scope discipline: creative types
appear only in `pack/` (compiler) and `delivery/` (the self-host backend on creative's
`ResourcePackServer`); everything upstream of the compiler stays library-free — the graph,
allocation, validation, determinism (D7 sha-compare), and diagnostics are ours. SnakeYAML
stays `compileOnly` (the server provides it; the lossless YAML layer loads it reflectively
from the server at runtime).

**M3 amendment:** the second shaded dependency set is **Undertow 2.3.26.Final** (+ XNIO/JBoss transitive runtime) for the editor listener — relocated under `materione.libs.io.undertow` / `.org.xnio` / `.org.jboss` / `.org.wildfly` by `materione.plugin-conventions`. Referenced only from `dev.materione.web` (ban rule R8), so relocation is invisible to every other module. Gson stays server-provided for our own code; the SPA build toolchain (Node/npm) never ships — only its static output rides in the jar under `webui/`. The settings repository list gains one Ivy entry (nodejs.org dist) so the strict repositories policy survives the node plugin.

**#213 amendment (soft-depend adapters):** Vault and LuckPerms are **not shaded**. They are `compileOnly` in
`integrations` and supplied by the server; the adapters load only when their plugin is enabled
(`Integrations`, logic.md §16). `paper-plugin.yml` declares both as optional server dependencies with
`join-classpath: true`. The shaded jar gains no classes from either API.

**bStats amendment:** the third shaded set is **bStats 3.2.1** (`bstats-bukkit` + `bstats-base`, MIT), relocated under `materione.libs.org.bstats` by `materione.plugin-conventions`. Only `MaterionePlugin` references it, and it starts after the bootstrap phases in `onEnable`, behind a fail-soft wrapper: a failure is a warning and enable continues. The bStats jars carry no license file, so the shaded jar carries no MIT notice for it; creative and Undertow are in the same position. Bundling third-party notices is an open item, not decided here. No ban rule (§5) names bStats: the rules govern `dev.materione` code, and its only call site is `MaterionePlugin`.


Deviation from the original M0 plan, recorded here: **FoliaLib was dropped.** The scheduling
gateway (`core/…/scheduling/SchedulingGatewayImpl`) calls Paper's native `RegionScheduler`,
`EntityScheduler`, and `AsyncScheduler` directly — present since Paper 1.20.1+ Folia support
and safe on both flavors at our floor. This removed the only M0-planned shaded dependency and
the JitPack availability risk entirely. Folia *support* is still detection + gateway shape
only (plugin.md §1.3).

## 5. Ban tests — boundaries enforced by the build (plugin.md §11)

The rule set lives once, in `testing/src/testFixtures/…/arch/MaterioneArchRules.java`, and
is applied two ways: per-module `ArchitectureTest` classes, plus a whole-jar
`AllModulesArchitectureTest` in `plugin/` that imports the complete shaded classpath.

| Rule | Statement | Notes |
|---|---|---|
| R1 | Only the scheduling gateway (and `platform/`) may touch Bukkit schedulers. | Two layers: a dependency ban on `org.bukkit.scheduler..` **plus** a call-predicate ban on `Bukkit`/`Server#getScheduler()` — a bare `Bukkit.getScheduler();` statement (result discarded) leaves no scheduler-package dependency in bytecode, so the dependency rule alone has a blind spot. Found by planting a violation; the predicate closes it. |
| R2 | Only `platform/` may query the server version (`Bukkit#getBukkitVersion`). | Call-predicate based. |
| R3 | `api` depends on no internal module. | Master §10. |
| R4 | Nothing depends on `plugin`. | The assembly leaf. |
| R5 | No raw `Thread`/`Executors` outside the scheduling gateway. | Harness/testing exempt (build infrastructure). |
| R6 | Validators are pure: no file I/O in `..validate..` packages or `*Validator` classes. | M1. Validators receive their world as parsed data (`ValidationEnvironment`); the filesystem belongs to parse/compile. Has real subjects since M1 (`core.pipeline.validate`). |
| R7 | `behavior` stays Bukkit-free (embeddable engine). | M2. `org.bukkit..` is banned from `dev.materione.behavior..` — the ECA/expression engine is a pure library any host can embed; Bukkit bindings live in `plugin/`. Real subjects since M2 (`behavior..`). |
| R8 | The editor wire protocol lives only in `web/`. | M3. Everything under `dev.materione.web.protocol..` is reachable from nowhere else; hosts use facade types (`EditorListener`, `SessionPush`). Teeth note: constant reads (`Wire.VERSION`) fold away at compile time — the planted violation was a method call. Harness/testing exempt (build infrastructure: the harness plays the browser against the live listener). |
| R9 | The web editor consumes only the public API (`dev.materione.web..` may not depend on `core..`, `plugin..`, `content..`, `pack..`, `delivery..`, …). | M5 (API-first, [`api.md`](api.md) §4.2): every editor action must be a typed `MaterioneAPI` operation — the rule mechanically guarantees the editor cannot bypass the API. Two enforcement layers: `web/build.gradle.kts` depends only on `:api` (a core import does not compile), and ArchUnit over the shaded jar (proven by planted violation: a web-package class referencing `DocumentStore` is named in the failure). Fixture classes nested in `*Test` files are exempt like their enclosing tests. Registered in web's `ArchitectureTest` and the whole-jar `AllModulesArchitectureTest`. |
| R11 | One action, one class: classes in `dev.materione.plugin.logic.action..` do not depend on each other. | M6 ([`logic.md`](logic.md) §9 rule 2). A class's own nested types (its parameter record) are part of it; shared helpers go in the parent package. Proven by a planted violation (`LogicCodeOrganisationTest`) and applied whole-jar in `AllModulesArchitectureTest`. R10 (listeners are adapters) is reserved: it has no subjects until the GUI runtime creates the `plugin.gui..` packages, and lands with them. |
| — | Size guard: no main-source class over 400 lines in the logic packages (`api.logic`, `behavior.logic`, `plugin.logic`). | M6 (`logic.md` §9 rule 4). A source-line test, `LogicCodeOrganisationTest`, proven against a planted oversized file. New logic packages are added to its list as they appear. |
| — | `MTR-####` codes: format + uniqueness + declared only in `DiagnosticCodes` (now `api.diagnostics` — codes are API, CLAUDE.md). | Unit tests over `DiagnosticCodes`, not ArchUnit. |

Rules are predicate-based (no Bukkit class literals — pure modules have no paper-api on
their test classpath), exempt `*Test` classes by name, and use `allowEmptyShould(true)` so
skeleton modules stay green until they have code.

**Proving the teeth (do this when touching the rules):** plant a violation (e.g. a
`Bukkit.getScheduler();` line outside the gateway), run the module's `ArchitectureTest`,
watch it fail, revert, watch it pass. A ban test that has never failed is untested.

Two false-green traps already caught and closed, worth remembering:

- JUnit tag filters: a tag that is *both included and excluded* is silently **excluded**
  (Gradle warns, runs nothing, build goes green). `java-conventions` therefore skips the
  default `integration` exclusion for the task named `integrationTest`.
- Config cache: an execution-time action (e.g. `filesMatching { … }`) that reads a
  top-level `val` captures the *script object* and fails the store. Copy values into locals
  inside the config block.

## 6. Test tiers

| Tier | Task | What | Where |
|---|---|---|---|
| Unit | `test` (part of `build`) | Pure-JVM services: bootstrap runner, registries, diagnostics, settings round-trip, probe parsing, harness-internal logic; M1 adds lossless-YAML round-trip, pipeline stages, allocation-table invariants, compiler determinism + golden lock, runtime shadow-swap, delivery backend. | every module |
| Fault injection | `test` | Stage-pair matrix (`plugin` `StagePairFaultInjectionTest`): a fault at any stage boundary stays contained to one resource and never crosses into the previous stage's output. | `plugin/` |
| Perf budgets | `test` | plugin.md §9 budgets against the fixture corpus (`plugin` `ContentPerfBudgetTest`): regressions block merge. | `plugin/` |
| Architecture | `test` | R1–R6 per module + whole-jar in `plugin`. | every module |
| Integration | `:harness:integrationTest` | Real headless Paper 1.21.4 boot of the **shaded** jar; marker assertions, hot-reload sequence, live fault injection. | `harness/` |
| Browser UI | `npm run test:browser` (Chromium install: `npm run browser:install`; Gradle: `:webui:browserTest`) | Playwright mounts the real Workbench in Chromium using a typed deterministic RPC fixture. This is UI evidence, separate from Vitest logic and the real Paper wire harness. | `webui/` |

Fixtures live in `fixtures/` (never a Gradle module; grow-only per roadmap rule 2), reached
via the `materione.fixtures.root` property. Golden files regenerate with
`-Dmaterione.golden.regen=true` — review the diff, then commit; CI never regenerates.

The browser tier is also run in CI: it installs the Playwright-managed Chromium through
`:webui:installPlaywrightChromiumCi`, then runs `:webui:browserTest`. Its fake RPC fixture
exercises the rendered SPA, not the plugin protocol or in-game pack delivery; those remain the
responsibility of the Paper integration gate. Vitest (`npm run test`) continues to pin pure
reducers, editor rules, and protocol fixtures.

## 7. The harness (`harness/`)

`./gradlew :harness:integrationTest` downloads the pinned Paper jar once
(content-addressed cache at `~/.gradle/caches/materione-paper/<sha256>/paper.jar`),
builds the shaded jar, prepares a fresh workspace under `harness/build/work/` (eula,
offline flat world, plugin dropped into `plugins/`, bStats opted out with
`plugins/bStats/config.yml` `enabled: false`, fixture packs copied into
`plugins/Materione/content/packs/`), boots the server, drives the console,
and asserts the stable log markers:

```
MATERIONE-BOOT-OK <version>          MATERIONE-CONTENT-OK items=<n> defs=<n> pack=<hash12|noop>
MATERIONE-VERSION <version>          MATERIONE-DELIVERY-OK <url>
MATERIONE-DOCTOR-OK                  MATERIONE-RELOAD-OK items=<n>
MATERIONE-LOGIC-OK currency=<default id> available=<true|false> transfer=<yes|none> unavailable=<action ids|none>
MATERIONE-BYE
```

`MATERIONE-LOGIC-OK` (#213) is logged at boot and on every content reload. The harness asserts it on
a workspace with no Vault and no transfer host: `currency=materione:money available=false transfer=none
unavailable=give_money,take_money,connect`. The built-in money is unavailable without Vault, and the money
and connect actions report it.

These markers are test API — treat changes to them like changes to `MTR-####` codes
(plugin.md §5). The standard scenario (`PaperBootstrapIT`) is the M1 exit gate: boot with
the fixture corpus (53 live items), then the hot-reload sequence — no-op reload, add an
item → 54, drop a syntactically broken file → still 54 with `MTR-0007` in the log,
remove both → 53. A fail-soft variant (`PaperFailSoftIT`) injects a broken `settings.yml`
and asserts the server still boots and the diagnostic is reported. The M2 exit gate is
`PaperWorldPersistenceIT` (`WorldPersistenceRunner`): boots all three fixture packs
(57 live items), places one block per state technique plus a full furniture cluster, and
across five boots in four workspaces proves the world survives reload, restart and world
copy — with fault injections on both ends (a broken rule file mid-reload; the corruption
pair that deletes the allocation table and re-sorts definitions so the rebuilt accelerator
disagrees with chunk-PDC truth, expecting `MTR-0024` + a drifted sweep). Timeouts
(boot 300 s / command 60 s / stop 60 s) are env-overridable; on failure the assertion
includes missing markers, the log tail, and the workspace path. Marker waits are
cursor-based (`LogWatcher#mark`): a repeated command cannot match its own stale marker
from a previous occurrence.

**M3 — the editor handshake gate (`EditorHandshakeRunner` / `PaperEditorHandshakeIT`):**
boots with the fixture corpus, opens a session via console, and a JDK HTTP/WebSocket
client plays the browser end-to-end against the real listener: SPA served from the jar,
capability blob decrypt, hello, console-driven in-game approval, signed-reply +
key-confirmation verification, sealed RPC round trip, `edit.put` of a real item watched
going live (`dev stack sample:editor_gem`), asset upload/list, and the plugin.md §9
budget — send(save)→push.reload **under 2 s** — then cleanup back to 56 items.
*Workbench update extension:* the same run now also drives the authoring/ops surface —
`edit.create` (scaffold lands at rev 1, duplicate refused, item live via `dev stack`),
`edit.move` (old path unreadable after), `assets.move`, `pipeline.reload` (manual
outcome awaited as `push.reload`), `admin.settings.get`/`put` under the hash guard,
`admin.audit.recent` (every op accounted for), then cleanup of all artifacts. The
extension caught two real concurrency flaws live: the first `push.diagnostic` raced
the signed hello-reply to the socket (MTR-0032 now publishes only after the reply is
written — `EditorSession.announceActive`), and concurrent seal+send from WS and
scheduler threads could desync frame sequences (`sendFrame` is synchronized;
both browser transports buffer handover-gap frames instead of dropping them).
*M3 closeout extension:* the run also hand-edits a pack file on disk and asserts
the `push.document.changed` push plus a journaled *external* revision (the file
watcher, live), and drives the Blockbench save-back wire flow — `assets.put` of the
exported model + texture + `.bbmodel` source, `edit.setScalar` quick-attaching
`model:` onto the scaffold — watched going live in game.

**Bumping the Paper pin:** run `./gradlew :harness:printLatestPaperPin -PpaperVersion=1.21.x`
(it queries the live v3 fill API and prints the absolute URL + sha256), then update
`paper-harness-url` / `paper-harness-sha256` in `gradle/libs.versions.toml`. The CI cache
keys on the catalog hash, so a pin bump is a fresh download by construction. The pinned
v2 metadata API is gone (410); the pin is absolute-URL + checksum precisely so test runs
make **zero** metadata API calls. The task also handles the 26.x response shape
(`id`/`checksums.sha256`) and takes `-PpinPrefix=` — the agent probe loop's
`paper-probe-*` pins are maintained the same way (see `docs/probe.md`).

**Running it from IntelliJ:** the `harness` module is an `application`
(main class `dev.materione.harness.PaperHarnessRunner`). After one `:harness:integrationTest`
run (or a manual `:harness:downloadPaperServer` + `:plugin:shadowJar`), create an
Application run configuration with the same system properties the task injects
(`paper.jar`, `plugin.jar`, `materione.version`, `harness.workroot`) — see
`harness/build.gradle.kts` for the exact values. Useful when debugging bootstrap
interactively.

## 8. CI (`.github/workflows/ci.yml`)

- **build** (ubuntu): `./gradlew build`; uploads the shaded jar.
- **paper boot** (ubuntu, needs build): caches the pinned Paper jar keyed on
  `hashFiles('gradle/libs.versions.toml')`, runs `:harness:integrationTest`, uploads
  workspaces on failure.
- **windows-canary** (non-blocking): build + integration on Windows. Promote to required
  after it has been green for a few weeks — Windows is a first-class dev platform here
  (path handling, kill-tree semantics, console encoding all differ).
- **release** (`.github/workflows/release.yml`, `v*` tags only): checks the tag equals
  `v` + the `gradle.properties` version, runs `./gradlew build -Prelease`, and stages a
  **draft** GitHub release with `Materione-<version>.jar` and the tag's `CHANGELOG.md`
  section as notes. Publishing the draft is a manual maintainer step.

**Editor compatibility gate.** The cloud editor and relay redeploy independently of the jars
servers run, so each release commits `fixtures/protocol/released/<version>.json` (release
workflow step; generate it with `node scripts/compat/editor-compat.mjs snapshot <version>`
before tagging). The **build** job and `deploy.yml` run `editor-compat.mjs check`, and the
**cloud** job's Worker tests replay every released relay route (web.md §6, "Versioning").

**Snapshot vs release versions.** `gradle.properties` holds the bare version (`0.1.0`). The
root `build.gradle.kts` appends `-SNAPSHOT` to every project unless the build runs with
`-Prelease`, so local and CI jars are always `Materione-<version>-SNAPSHOT.jar` and only the
release workflow produces the unsuffixed production jar.

No JDK matrix: the toolchain pins Java 21 everywhere (foojay provisions Temurin on CI;
locally-resolvable JDKs like `~/.jdks/...` win when present).

## 9. Local development notes

- Wrapper first: `./gradlew build`. The first run provisions a JDK via foojay if none
  matching 21 is found — point `JAVA_HOME` at your local JDK 21 to skip that.
- Windows shells (Git Bash) work for everything including the harness; the harness writes
  assertions against log files, never console stdout, and kills process trees
  descendants-first.
- Version bumps happen once, in `gradle.properties` (`version=`, without `-SNAPSHOT`) —
  the jar name, the generated `MaterioneVersion`, `paper-plugin.yml`, and the harness's
  expected version all derive from it (plus the `-SNAPSHOT` suffix unless `-Prelease`, §8).
  Build a production jar locally with `./gradlew :plugin:shadowJar -Prelease`.
