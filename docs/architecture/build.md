# Build, boundaries, and verification

How the Materione codebase is built — and how the build itself enforces the architecture.
Traces to master §10 (codebase division) and §11 (enforced, not aspirational); the module
boundaries here are the same ones described there, made mechanical.

Status: **as shipped for M0 + M1** (roadmap rule 1 — this doc reflects what exists, not what's
planned). Anything marked *M-later* is reserved, not built.

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
| SnakeYAML | 2.2 | `compileOnly` — the server provides it at runtime; never shaded. |
| creative | 1.7.3 | MIT, Maven Central (`team.unnamed`). The pack **output emitter** inside `pack/` + the self-host HTTP backend in `delivery/`. Shaded + relocated (§4). Determinism, allocation, validation, and the D7 hash guarantees remain ours — creative is the serializer of record. |
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
                             behavior, web, importers, integrations — skeletons until their
                             milestones; they may depend on api + core, never on each other's
                             internals, never on platform internals (master §10 rules)

  testing ──► testFixtures consumed by every module's tests (build infrastructure, not shipped)
  harness  ──► standalone: boots the real server, copies the shaded jar in (no product deps)
  fixtures ──► data only, not a Gradle module
  webui    ──► placeholder; its build lands M4/M5
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
| `materione.paper-conventions` | api, core, platform, plugin | `paper-api` and SnakeYAML as `compileOnly` — never `implementation`, never on a runtime classpath, never shaded. The server provides both. |
| `materione.plugin-conventions` | plugin only | Shadow wiring (M1: relocations for creative + Gson), `plugin.yml` `@materioneVersion@` token replacement, generated `MaterioneVersion` source, distributable naming (`Materione-<version>.jar`, plain jar classifier `plain`). |

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
| — | `MTR-####` codes: format + uniqueness + declared only in `core.diagnostics`. | Unit tests over `DiagnosticCodes`, not ArchUnit. |

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

Fixtures live in `fixtures/` (never a Gradle module; grow-only per roadmap rule 2), reached
via the `materione.fixtures.root` property. Golden files regenerate with
`-Dmaterione.golden.regen=true` — review the diff, then commit; CI never regenerates.

## 7. The harness (`harness/`)

`./gradlew :harness:integrationTest` downloads the pinned Paper jar once
(content-addressed cache at `~/.gradle/caches/materione-paper/<sha256>/paper.jar`),
builds the shaded jar, prepares a fresh workspace under `harness/build/work/` (eula,
offline flat world, plugin dropped into `plugins/`, fixture packs copied into
`plugins/Materione/content/packs/`), boots the server, drives the console,
and asserts the stable log markers:

```
MATERIONE-BOOT-OK <version>          MATERIONE-CONTENT-OK items=<n> defs=<n> pack=<hash12|noop>
MATERIONE-VERSION <version>          MATERIONE-DELIVERY-OK <url>
MATERIONE-DOCTOR-OK                  MATERIONE-RELOAD-OK items=<n>
MATERIONE-BYE
```

These markers are test API — treat changes to them like changes to `MTR-####` codes
(plugin.md §5). The standard scenario (`PaperBootstrapIT`) is the M1 exit gate: boot with
the fixture corpus (53 live items), then the hot-reload sequence — no-op reload, add an
item → 54, drop a syntactically broken file → still 54 with `MTR-0007` in the log,
remove both → 53. A fail-soft variant (`PaperFailSoftIT`) injects a broken `settings.yml`
and asserts the server still boots and the diagnostic is reported. Timeouts
(boot 300 s / command 60 s / stop 60 s) are env-overridable; on failure the assertion
includes missing markers, the log tail, and the workspace path. Marker waits are
cursor-based (`LogWatcher#mark`): a repeated command cannot match its own stale marker
from a previous occurrence.

**Bumping the Paper pin:** run `./gradlew :harness:printLatestPaperPin -PpaperVersion=1.21.x`
(it queries the live v3 fill API and prints the absolute URL + sha256), then update
`paper-harness-url` / `paper-harness-sha256` in `gradle/libs.versions.toml`. The CI cache
keys on the catalog hash, so a pin bump is a fresh download by construction. The pinned
v2 metadata API is gone (410); the pin is absolute-URL + checksum precisely so test runs
make **zero** metadata API calls.

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

No JDK matrix: the toolchain pins Java 21 everywhere (foojay provisions Temurin on CI;
locally-resolvable JDKs like `~/.jdks/...` win when present).

## 9. Local development notes

- Wrapper first: `./gradlew build`. The first run provisions a JDK via foojay if none
  matching 21 is found — point `JAVA_HOME` at your local JDK 21 to skip that.
- Windows shells (Git Bash) work for everything including the harness; the harness writes
  assertions against log files, never console stdout, and kills process trees
  descendants-first.
- Version bumps happen once, in `gradle.properties` (`version=`) — the jar name, the
  generated `MaterioneVersion`, `plugin.yml`, and the harness's expected version all
  derive from it.
