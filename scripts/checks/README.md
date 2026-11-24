# Repo rules

`node scripts/checks/run.mjs` runs the CLAUDE.md rules a script can decide. It is pure Node with
no install. CI runs the same command in the `rules` job, on every PR including docs-only ones. Each
check exists because the mistake it catches happened. `run.test.mjs` replays those instances.

| Check | Catches | Real instance |
|---|---|---|
| UTF-8 encoding | Tracked text files that are not valid UTF-8 | V2 work (#491) wrote one Latin-1 `§` (byte `0xA7`) into `docs/architecture/views.md`; docs-only PRs skipped the Gradle encoding test |
| Docs links and anchors | A relative Markdown link to a missing file, or a `#anchor` with no matching heading (GitHub slug rules) | `qa/README.md` linked `probe.md` (it lives in `docs/`); `cloud/editor-worker/README.md` climbed one directory past the root. Both were on `main` when this check landed. |
| Naming | "MaterYard" in any spelling outside CLAUDE.md | CLAUDE.md hard rule |
| Diagnostic codes in docs | An `MTR-####` in Markdown that `DiagnosticCodes.java` doesn't define and [`mtr-reserved.json`](mtr-reserved.json) doesn't reserve | Docs named `MTR-0099` and `MTR-0106..0117` as reserved only in prose; allocation was tracked as prose in `logic.md` ("the next free code is…"), and codes collided across branches (`d6dd520a`, `29b39373`) |
| Vendored Blockbench unmodified | Any change to `webui/public/blockbench/` except `PIN.md`, compared by git blob id against [`baselines/blockbench.blobs.json`](baselines/blockbench.blobs.json) | CLAUDE.md hard rule (D9) |
| Milestone references in code comments | A production source file whose comments name more milestones (`M2`, `M6.5`) than its baseline in [`baselines/milestone-comments.json`](baselines/milestone-comments.json) | `FurnitureDefinition.java`: "cycling is parsed but inert until M7"; `Cancellation.java`: "how the M2 engine always behaved" |

## Commands

| Command | Use |
|---|---|
| `node scripts/checks/run.mjs` | Run every check (exit 1 on any failure) |
| `node scripts/checks/run.mjs --next-mtr` | Print the next free diagnostic code. Use it instead of reading prose; add the code to `DiagnosticCodes.java` in the same PR. Reserve a block by adding it to `mtr-reserved.json` with a reason. |
| `node scripts/checks/run.mjs --update-baselines` | Rewrite the milestone-comment baseline. Run it after **removing** references; a baseline that grows is reported on the PR. |
| `node scripts/checks/run.mjs --pin-blockbench` | Re-pin the vendored Blockbench build, only with an upstream bump recorded in `PIN.md` |
| `node scripts/checks/run.mjs --spec-report <base>` | CI only: lists the spec files a branch changes, plus a growing ratchet or Blockbench re-pin, in the job summary and as warnings |

## Why milestone references are ratcheted

A comment that names a milestone is true the day it's written and wrong once the roadmap moves.
Milestone state lives in `docs/roadmap.md`. A comment says what the code does now, and names a doc
section if the reader needs the design. The existing references are grandfathered per file. The
baseline only goes down, and removing them is ordinary cleanup.

## Adding a check

Add a check when a mistake has happened twice, and add it to the CLAUDE.md rule table (§ Hard rules).
The test replays the real instance; name the commit, PR or file in the test.
