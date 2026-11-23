---
name: verify
description: Prove a Materione change works against the real artifact (booted plugin, real editor, real client) instead of claiming "tests pass". Use after any change with observable behavior and before saying done or opening a PR. Triggers - "verify", "prove it works", "does it work in game".
---

# verify

"Compiles" and "unit tests pass" are not proof. Pick the cheapest lane that exercises the
real artifact, run it, paste the evidence. A check you never executed is a draft.
Run from the repo root (or your worktree root). Shell is Git Bash on Windows; CI runs Linux.

## 1. Pick the lane

| Kind of change | Cheapest lane that is real | Command |
|---|---|---|
| Pure content-pipeline / compiler logic | unit + golden tests of that module | `./gradlew :<module>:test` (then `./gradlew build` before a PR) |
| Plugin boot, registry, command wiring, runtime on Paper | headless Paper 1.21.4 boot | `./gradlew :harness:integrationTest` |
| Editor UI (Svelte SPA) | typecheck + vitest, then Playwright | `cd webui && npm run check && npm test`; browser: `./gradlew :webui:installPlaywrightChromiumCi` once, then `./gradlew :webui:browserTest` |
| Cloud relay Worker | vitest on the Workers runtime | `cd cloud/editor-worker && npm ci && npx tsc --noEmit && npx vitest run` |
| Model proxy Worker | same | `cd cloud/model-proxy && npm ci && npx tsc --noEmit && npx vitest run` |
| Cloud editor vs released plugins / SDK ops | compat gate | `node --test scripts/compat/editor-compat.test.mjs` |
| Agent guard hook | node tests | `node --test scripts/agent-guard/guard.test.mjs` |
| Anything only visible in a client (tofu glyphs, models, GUI, paintings) | probe loop | `node scripts/probe/probe.mjs boot` (docs/probe.md) |
| Player-visible behavior (required for such PRs) | QA catalog entry + run | `node qa/runner.mjs check`, then `run <ids>` |
| Code-mode (SDK/sandbox) quality | benchmark | `cd webui && npm run benchmark` (read scripts/benchmark/README.md first) |

Rules: a change spanning lanes needs every lane it touches. Player-visible changes always
end in the probe/QA lane; lower lanes alone do not count. Never run `wrangler deploy` or
any deploy-class command (CLAUDE.md cost guardrails); `npx wrangler deploy --dry-run` is CI's.

## 2. Doctor (read-only, run before driving)

```bash
java -version                      # gradle needs 21; probe needs a JDK >= 25 (see below)
ls ~/.jdks ~/.gradle/jdks          # probe scans these for JDK >= 25 (or --java / MTR_PROBE_JAVA)
node --version                     # >= 22
tasklist | grep -i -E "javaw?\.exe"        # is anyone's Minecraft client/server already running?
netstat -ano | grep -E ":(25599|25575) "   # probe server/RCON ports; empty output = free
git status --short; git rev-parse --short HEAD   # evidence is tied to a commit; note dirty state
node qa/runner.mjs check           # catalog integrity, no Minecraft needed
```

Decision rules:
- A `javaw.exe`/`java.exe` you did not start, or anything listening on 25599/25575, is
  probably the owner's client or server (gradle daemons also show as java.exe; tell them
  apart by command line). STOP the client/server lanes and ask the owner to close it.
  Never kill, reuse, or work around a process you did not start. Lanes that need no
  Minecraft (unit, vitest, browser, compat, `qa check|plan`) stay allowed.
- Probe prerequisites (docs/probe.md): JDK >= 25, Node >= 22, a vanilla launcher install of
  Minecraft 26.1.2 (`%APPDATA%/.minecraft`, or `--vanilla-dir`). Missing = report, do not fake.
- Browser QA entries (`editor.browser-*`) also need `npm ci` and `npm run browser:install` in
  `webui` (on Windows PowerShell use `npm.cmd`).

## 3. Launch / Drive

**Harness (headless Paper, CI-equivalent):** `./gradlew :harness:integrationTest`. Ready signal
is the server log line `MATERIONE-BOOT-OK`; the harness asserts it. Long: run it only when the
owner has no server up, and record the PID if you background it.

**Probe loop (real server + real windowed client, local only):**
```bash
node scripts/probe/probe.mjs boot            # up + client + join + sanity shot
node scripts/probe/probe.mjs rcon "materione give corpus:gem_red Probe"
node scripts/probe/probe.mjs command "/materione browse"
node scripts/probe/probe.mjs shot --out shots/check.jpg   # then Read the image
node scripts/probe/probe.mjs state    # also: screen (open GUI), chat (client chat)
node scripts/probe/probe.mjs quit && node scripts/probe/probe.mjs down
```
`up` waits for `MATERIONE-BOOT-OK`; server is 127.0.0.1:25599. Server log:
`harness/build/probe/server/logs/latest.log`; shots land in `harness/build/probe/shots/`.
Reproduce the reported symptom first, apply the fix, then repeat the identical steps.
`up` rebuilds the plugin jar unless `--no-build`.

**QA catalog:**
```bash
node qa/runner.mjs plan            # what is due at HEAD and why
node qa/runner.mjs run <test-id> [<test-id>...]   # or suite:<name>; add --no-ledger on a branch
node qa/runner.mjs report <runId>
node qa/runner.mjs mark <id> pass|fail --note "..."   # only after viewing a `visual` test's screenshots
```
Exit codes: 0 green, 1 failures/blocked, 2 infra broke (not a product finding).
A new feature adds its tests to `qa/catalog/` (charter: qa/README.md). Pure runner tests:
`node --test qa/evidence-redaction.test.mjs qa/runner.test.mjs qa/guioverlay.test.mjs`.

## 4. Evidence

Paste into the PR, per lane:
- the exact command and the lines that show the result (`catalog ok: ...`, `ℹ pass N / ℹ fail 0`,
  `BUILD SUCCESSFUL`, `MATERIONE-BOOT-OK`), plus `git rev-parse --short HEAD` and dirty state;
- for probe/visual work: the screenshot path(s) you actually looked at and what they show;
- for QA: the run id and per-test results from `report`.

Where it must live: `qa/results/` is scratch (gitignored, machine-local). Truth is repo-tracked:
failures are promoted to `qa/evidence/<runId>/<testId>/` by `node qa/runner.mjs file <runId> <id>`
(commits and pushes, then files the issue); ledger entries reference repo-relative paths only.
Never cite a machine-local path as the only evidence.

Never weaken the journey to make it pass: do not loosen assertions, skip steps, shorten
waits, swap the real client for a mock, or edit the catalog entry to fit the bug. If the
journey is wrong, fix it in its own visible change. Report what was NOT verified, plainly.

## 5. Cleanup

- Kill only what you started, by the PID you recorded at launch. Prefer the tool's own stop:
  `node scripts/probe/probe.mjs quit && node scripts/probe/probe.mjs down`.
- Re-run the port check from Doctor; 25599/25575 must be free again (unless the owner's).
- Confirm evidence survived: screenshot files exist, `qa/evidence/...` is committed where
  required, `git status` shows only intended changes (no stray build artifacts staged).
- Remove optional jars you added to the probe server (e.g. PlaceholderAPI) when done.

## 6. Gotchas

- Blessing runs (ledger updates that count) happen on main only; a pass recorded on a branch is
  `off-main` and never counts. Branch runs: `--no-ledger`. Dirty-tree passes never count.
- Probe and QA are local only; CI never launches Minecraft. CI's boot lane is the 1.21.4 harness,
  the probe is the 26.x seam: green in one does not prove the other.
- Two probes cannot share the ports/GPU; check Doctor first and wait for ownership.
- Paper results are not Folia evidence; Folia needs a fresh probe with a verified server jar
  (docs/probe.md, qa/README.md).
- `check` passing says the catalog is well-formed, not that anything works. `plan` showing
  `blocked` means a dependency is not passing at HEAD, not that your change is broken.
- Windows: `./gradlew` in Git Bash or `.\gradlew.bat` in PowerShell; absolute paths; the
  probe kills with TerminateProcess (no save), so never `down` a server holding state you need.
- Unrelated commits do not invalidate a pass; `plan` shows what is stale via `covers` globs.
- Cloud work: deploy/secret/kv/r2/d1 commands are the owner's. Hand over the command; do not run it.

Adapted from pstack's create-verification-skill (MIT, © 2026 Lauren Tan).
