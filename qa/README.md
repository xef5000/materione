# The in-game QA suite

Systematic, trackable, fixable in-game testing on top of the [agent probe
loop](probe.md) (issue #38). The probe made in-game verification *possible*;
this layer makes it *systematic*: a committed test catalog, a ledger that
answers "what has been tested, at which code state", dependency-ordered
execution that stops dependents of a failure, and failure dossiers that a
fixing agent can act on without re-testing.

It runs **locally only** — the probe boots a real Paper 26.x server and a real
windowed client, which is dev-machine work, not CI. CI runs the cheap
`check` step (catalog integrity) and pure runner/evidence unit tests, never Minecraft.

## The three properties (and how they are met)

**Covering/exhaustive.** Tests are not spot checks: each catalog entry names
its concrete subject (`corpus:gem_red renders its custom model in hand and
hotbar`) and declares `covers` — the source globs it exercises. Coverage
review is reading the catalog against the feature inventory, and every
milestone PR that ships a user-visible feature adds entries for it.

**Trackable.** Results live in the committed ledger (`qa/ledger/<suite>.json`):
test id → `{sha, dirty, result, run, at, env, def, issue?}`. A pass is *fresh*
at HEAD until one of these invalidates it:

| Invalidation | Meaning |
|---|---|
| `def` hash differs | the test itself was edited |
| `env` key differs | probe pins or optional runtime plugin jar names/content changed |
| `covers` globs intersect `git diff <recorded>..HEAD` | code the test exercises changed |
| `dirty` was set | it was recorded on an uncommitted tree — never counts |
| `sha` not an ancestor of HEAD | it was recorded off-main (see "bless on main") |
| result is `fail` / `visual` | failing or awaiting a visual verdict |

Unrelated commits do **not** invalidate a pass — so no, you do not re-test
everything on every commit. `node qa/runner.mjs plan` prints exactly what is
due and why.

**Fixable.** Tests declare `dependsOn` (a DAG). A failing test **blocks its
dependents** — at plan time and mid-run — because their evidence would be
uninterpretable. Every failure writes a **dossier**
(`qa/results/<run>/<test>/dossier.md`): expected vs actual, full call
transcript, chat dump, server/client log excerpts, screenshots, the test
source, the environment, and the exact repro command. `node qa/runner.mjs
file <runId> <testId>` turns the dossier into a labeled GitHub issue
(`qa-failure`, added to the board) with a machine-readable
`qa-test: <id> @ <sha>` footer that links it back to the ledger. Issues are
isolating by construction — "corpus:gem_red shows the missing-model cube in
hand", never "this block does not render".

## Assertion modes

- **`auto`** — the runner judges: console replies (RCON), server-log markers
  (the `MATERIONE-*` contract), client chat polling, client-side Groovy
  (`ctx.exec`), snapshots. Deterministic, no eyes needed.
- **`visual`** — the steps stage content and capture screenshots; the runner
  records `needs-eyes` and a human/vision pass closes it:
  `node qa/runner.mjs mark <id> pass|fail --note "..."`. Honest split:
  automate what is automatable, evidence-capture the rest.

Every test is **self-contained**: its steps stage their own scene
(`ctx.reset()` gives a clean inventory/effects/drops baseline and teleports to
the anchor). `dependsOn` is *epistemic* - "this result only means something
if that one passes" - never shared setup.

The editor catalog also has an optional **live browser** adapter for local
integration evidence. Browser entries call `ctx.browser.openEditor()` to launch
an isolated Playwright Chromium against the real local plugin App; the runner
closes it after each test. The runner opens `/materione editor local`, reads that
test's `MATERIONE-EDITOR` URL marker (including its actual listener port) only
in memory, and waits for the normal server approval handshake. It does not
bypass login, start a separate web server, capture browser chrome, or write
canonical fixtures; server content changes stay in the probe's disposable copy.
The adapter and `@playwright/test` are lazy, so `check` and non-browser suites do
not require Playwright. Install the `webui` dev dependencies and Chromium before
selecting `editor.browser-*` catalog tests. In Windows PowerShell, use
`npm.cmd ci` (if needed) and `npm.cmd run browser:install` from `webui`; use the
corresponding `npm` commands in shells without the restricted PowerShell shim.
For the focused local slice run:

```
node qa/runner.mjs run editor.browser-files-draft-save editor.browser-files-visual
```

Failure diagnostics retain only state flags/counts and a bounded, redacted App text excerpt after the address is scrubbed. No browser trace or network dump is recorded.

## Commands

```
node qa/runner.mjs check               # catalog integrity — CI-safe
node qa/runner.mjs plan [--json]       # what is due at HEAD and why
node qa/runner.mjs run [selectors]     # execute; selectors: ids, suite:<n>, all
node qa/runner.mjs report [runId]      # print a run summary
node qa/runner.mjs file <runId> <id>   # dossier → GitHub issue + board
node qa/runner.mjs mark <id> pass|fail # close a needs-eyes verdict
node --test qa/evidence-redaction.test.mjs qa/runner.test.mjs # pure runner tests
```

`logic.currency-xp-placeholders` requires the public PlaceholderAPI plugin in the disposable probe
server. Before starting the runner, place the official pinned PlaceholderAPI 2.11.7 jar (SHA-256
`f5a4ea718baaab6118cfe8f3983ea3a5660af1ee8572360182a3d1b6dbf2eb5d`) in
`harness/build/probe/server/plugins/`; the probe preserves optional plugin jars when it resets its
world. This is an opt-in local prerequisite, not a bundled dependency. Remove the jar when no longer
testing that integration. The catalog entry checks that PlaceholderAPI is loaded before asserting
the player-visible values. Its filename and hash participate in the boot environment key described
under the swarm policy; adding or removing it changes the environment of the next server boot.

### Local native currency callback gate

The Paper `logic.currency-custom-placeholders` test additionally uses the **local-only**
`MaterionePlaceholderProbe` addon. It registers a real `%mtr252_balance%` expansion and two
counted, deliberately unsafe Folia currency integrations. Neither addon nor PlaceholderAPI is
packaged with Materione. Only prepare the addon while another probe owns the shared ports/GPU;
install and run it only after the reservation transfers. On Windows, from this worktree:

```powershell
.\gradlew.bat :plugin:placeholderProbeJar
Get-FileHash harness\build\qa-optional\PlaceholderAPI-2.11.7.jar -Algorithm SHA256
# After reservation transfer, in the owned disposable server only:
New-Item -ItemType Directory -Force harness\build\probe\server\plugins
Copy-Item harness\build\qa-optional\PlaceholderAPI-2.11.7.jar harness\build\probe\server\plugins\
Copy-Item harness\build\qa-optional\MaterionePlaceholderProbe.jar harness\build\probe\server\plugins\
node qa\runner.mjs run logic.currency-xp-placeholders logic.currency-custom-placeholders --no-ledger
```

The hash must be `f5a4ea718baaab6118cfe8f3983ea3a5660af1ee8572360182a3d1b6dbf2eb5d`.
The runner boots its usual pinned Paper server and real client. The XP test uses the authored item
and checks player-visible XP 3 → 12 and unknown-token preservation. The custom test calls the real
PlaceholderAPI parser on the player owner and checks XP 3 → 12, custom raw/formatted 3 → 12 → 27,
and unregister/unavailable/re-register. The `MTR252-READY` boot marker independently records
enabled PAPI and registration of both public expansions.

To check the real PAPI disable and a subsequent clean enable, use a separate Paper boot after that
catalog run, while retaining the same reservation:

```powershell
node scripts\probe\probe.mjs boot
# The expected reply is MTR252-DISABLE disabled=true empty=true.
node scripts\probe\probe.mjs rcon "mtr252 papi-disable Probe"
node scripts\probe\probe.mjs quit
node scripts\probe\probe.mjs down
node scripts\probe\probe.mjs boot
node scripts\probe\probe.mjs rcon "mtr252 value 31"
node scripts\probe\probe.mjs rcon "mtr252 sample Probe"
Start-Sleep -Seconds 3
Select-String -Path harness\build\probe\server\logs\latest.log -Pattern "MTR252-READY|MTR252-SAMPLE"
node scripts\probe\probe.mjs quit
node scripts\probe\probe.mjs down
```

The fresh boot must report `papi=true probe=true materione=true`, and the sample must contain
`custom=31 customFormatted=C31.00`. Disabling invokes the captured, genuinely registered Materione
callback once while PAPI is disabled; the helper does not re-register it on production's behalf.
The pinned PAPI 2.11.7 clears its singleton in `onDisable` and sets it in `onLoad`; invoking Bukkit
`enablePlugin` on the same already-loaded instance is not a valid clean re-enable. Restarting the
disposable server exercises the public plugin lifecycle instead of faking PAPI initialization.

The ordinary QA runner always boots Paper, so a separate **fresh** Folia probe is required; do not
interpret Paper results as Folia evidence. After the final #250 merge and reservation transfer,
with an authorized local Folia 26.x server jar and its verified SHA-256:

```powershell
$foliaJar = (Resolve-Path 'harness\build\folia\server.jar').Path
$foliaSha = '<independently-verified-64-hex-sha256>'
node scripts\probe\probe.mjs up --server-jar $foliaJar --server-sha256 $foliaSha
node scripts\probe\probe.mjs client
node scripts\probe\probe.mjs join
node scripts\probe\probe.mjs rcon "experience set Probe 3 levels"
node scripts\probe\probe.mjs rcon "mtr252 sample Probe"
Start-Sleep -Seconds 3
Select-String -Path harness\build\probe\server\logs\latest.log -Pattern "MTR252-READY|MTR252-SAMPLE"
node scripts\probe\probe.mjs rcon "experience set Probe 12 levels"
node scripts\probe\probe.mjs rcon "mtr252 sample Probe"
Start-Sleep -Seconds 3
Select-String -Path harness\build\probe\server\logs\latest.log -Pattern "MTR252-SAMPLE"
node scripts\probe\probe.mjs quit
node scripts\probe\probe.mjs down
```

`MTR252-READY` must show `papi=true probe=true materione=true`. The first sample must contain
`xp=3 xpFormatted=XP3.00 custom= customFormatted= unsafeLookup= unsafeRead=` and the unchanged
unknown token, followed by `lookupBind=0 readBind=1 availability=0 balance=0 expansion=0`.
Thus an unsupported lookup is rejected before bind, a separately permitted lookup is rejected
before its unsafe availability/read, and custom PAPI is rejected before its expansion callback.
Missing or unresolved tokens, wrong counters, or a missing marker are failures, not zero balances.
Repeat with XP 12: native XP must change to `12`/`XP12.00`, while only `readBind` grows to 2.
Read counters from the fresh server; Paper samples intentionally invoke these providers and therefore
cannot be reused for this assertion. The addon and PAPI jars remain in the optional boot environment
fingerprint; remove them from the disposable plugins directory after this gate if no longer needed.

Run flags: `--no-build` (reuse the last `:plugin:shadowJar`), `--keep-server`
(leave the probe up for poking), `--no-ledger` (leave the ledger untouched and
keep results, transcripts, screenshots and failure dossiers local under
`qa/results/`), `--file` (auto-file issues for this run's failures).
`--no-ledger` is for pre-merge validation, not a blessing: visual results
cannot be marked in the ledger from that run, and it cannot be combined with
`--file`. Filing a dossier separately updates the ledger. Dependencies still
follow the normal ledger-based selection rules. Exit codes: `0` green, `1`
failures/blocked, `2` infra (the environment broke — findings from an infra
run are void).

## Writing tests

A catalog file is `qa/catalog/<suite>.mjs` default-exporting an array built
with `test()` from `./_lib.mjs` (which documents the full `ctx` API). The id
is namespaced by the suite and stable forever (renaming a test is deleting
one and adding another). Rules the `check` step enforces:

- unique, lowercase `<suite>.<slug>` ids; acyclic `dependsOn`
- `covers` lists at least one source glob (what this test exercises)
- titles name the concrete behavior *and content id*
- `visual` tests depend on the auto test that stages their content and must
  capture at least one screenshot + call `ctx.needsEyes(...)`

When authoring assertions, prefer the stable contracts: `MATERIONE-*` log
markers (documented in [`harness/README.md`](../harness/README.md)) over chat
prose, and player-perceivable state (inventory, screen, position) over
internals. A test that asserts internals breaks on every refactor; a test
that asserts what a player sees breaks only when the player-visible behavior
breaks.

`toEqual` compares structured values without depending on mapping key order;
array order, field presence and value types remain significant. Negative
identity/structured assertions use `.not.toBe` and `.not.toEqual`.

## The swarm policy

- **Run on main.** Squash merges change the sha by design, so passes recorded
  on a branch are `off-main` and never count. Full blessing happens on main;
  commit ledger updates there (`qa: ledger after <scope> swarm @ <sha>`).
- **Keep capture provenance.** A visual verdict retains the captured commit,
  environment, definition, timestamp and dirty flag; `markedAt` records the
  review time separately. Reviewing later cannot bless stale or off-main
  screenshots as though they were captured at the current HEAD.
- **Feature PRs add catalog entries** for every user-visible behavior they
  ship (marked `never` — the next main swarm picks them up). The PR's own
  run proves they can pass; the merge makes them count.
- **After a fix merges**, only the tests whose `covers` intersect the fix's
  diff (plus the failed one and its dependents) are due — run the default
  `plan`-driven swarm, not everything.
- **Full re-swarm** (`run all`) per milestone tag or probe-pin bump — the
  env key already forces the latter.
- One swarm at a time per machine (the probe owns fixed ports and the GPU).

The environment key includes sorted SHA-256 fingerprints of optional top-level
plugin jars in the disposable server. `Materione.jar` is excluded because its
changes are tracked by the tested commit and `covers`, not by invalidating every
test. A run verifies that its inputs stayed unchanged through server startup and
records that boot environment throughout; replacing a jar on disk afterwards
cannot relabel the already-loaded server. With no optional jars, the existing
pin-only environment key is unchanged. Ledger entries retain the optional jar
names and full hashes in `runtimePlugins`; failure dossiers include them too.

## Evidence lives in the repo (never on a machine)

Scratch and truth are different things:

- `qa/results/` is **scratch** (gitignored, machine-local, disposable): every
  run's transcripts, screenshots, and dossiers land there first.
- `qa/evidence/<runId>/<testId>/` is **tracked**. When a failure is filed
  (`runner file`), its dossier + transcript + screenshots are *promoted*
  there, committed, and pushed **before** the issue is created — so every
  link in an issue body is a permanent repo URL that resolves for anyone who
  can read the issue (repo readers; the repo is private, so that is exactly
  the audience), with screenshots inline or one click away. A failure of an
  already-filed test comments its new evidence on the existing issue instead
  of filing a duplicate (`--force` overrides for genuine regressions).
- The ledger stores repo-relative `qa/evidence/...` paths only — never
  machine-specific paths. `check` fails if a ledger-referenced evidence file
  is missing from the repo, and promoted evidence is never moved or renamed
  (issue links depend on it).
- Growth is bounded by construction: only failures are promoted (passes keep
  their run id), and evidence is JPEG/PNG + text — sized for targeted review.
- Text evidence redacts query-style and path-style editor capability URLs and
  session-creation identifiers before writing or promoting it. Minecraft client
  shots remain `ctx.shot()` JPEGs; `ctx.browser.browserShot()` writes a separate
  App-scoped PNG and records `browser-shot` in the transcript. Screenshots still
  require review before sharing; never capture an editor capability URL on screen.

The flow runs `file` from main (the swarm policy) so pushed evidence and the
main-branch raw URLs agree.

## Deliberate boundaries

- The probe is the **26.x registry seam** (`mode=registry`). Visual coverage
  of the 1.21.4 floor (datapack mode) stays with the headless
  [`:harness`](../harness/README.md) integration tests.
- CI never boots the windowed probe. Catalog integrity/ledger drift and pure
  runner/evidence tests are the cheap QA steps; the headless Paper harness is a
  separate CI gate.
- The ledger and the promoted failure evidence (`qa/evidence/`) are
  committed; `qa/results/` scratch is not. A pass whose scratch has been
  cleaned up is normal — the ledger's run id is its only pointer.
