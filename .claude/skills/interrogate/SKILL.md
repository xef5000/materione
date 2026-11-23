---
name: interrogate
description: Adversarial review of a branch, PR or design where a finding survives only if a command proves it. Use before asking the owner to review a code PR, or when asked to "tear this apart", "find what breaks", or "review with proof".
---

# Interrogate

A review the owner can trust without re-reading the diff: every finding is a command that was run
against the real code, its actual output, and the output the change's intent requires. Opinions,
style notes and "might" risks are dropped. Report findings; do not fix them.

## 1. Frame

- **Scope**: what the user named. By default, the branch diff from its merge base with `main`,
  plus the working tree: `git diff $(git merge-base origin/main HEAD)`.
- **Intent**: restate in two or three sentences what the change must do, from the issue
  (`Closes #N`), the PR body and the commits. Include the rules it must keep (CLAUDE.md
  § What enforces each rule; rows marked **review** are the ones no check covers). If intent is
  unclear, ask before spawning anything.

## 2. Fan out

Spawn 3 adversaries in parallel (Agent tool, `isolation: "worktree"`, model `sonnet`), each with a
different angle. Give each the scope, the intent, its angle, and this brief:

> Break this change. Prove each finding with a run against the real code: a failing test you wrote,
> a repro script, or command output. Keep repros in your own worktree. For each finding give: a
> title, the exact command, its actual output (trimmed), and the output the intent requires.
> Omit anything you did not run: style, naming, structure, speculative future risk. No findings is
> a valid result.

Angles that fit Materione:

| Angle | Looks for |
|---|---|
| Content edge cases | YAML 1.1 coercions (`yes`, `on`, `1e3`), flat vs keyed files, unknown fields, lossless round-trip (load, save untouched, compare bytes), quarantine instead of crash |
| Boundaries and wire formats | plugin↔editor protocol fixtures (`fixtures/protocol/`), released-plugin compatibility (`node scripts/compat/editor-compat.mjs check`), SDK goldens, diagnostic codes |
| Runtime and concurrency | main-thread work, per-tick cost against the budgets in plugin.md §9, reload and teardown, two viewers or two documents at once |

Pick other angles when the change calls for it (e.g. rate limits and fail-closed paths for a
Worker).

## 3. Verify

Re-run every finding's command yourself in a clean worktree on the branch head. Keep it only if
the output shows a failure that breaks the intent. Merge findings with one root cause. Never keep
a finding you could not reproduce.

## 4. Report

- **Intent**: the restatement.
- **Findings**: each with the command, the output, how it breaks the intent, and which adversary
  found it. If none survive: "no verified findings".
- **Dropped**: per adversary, how many findings were dropped and why (not reproducible, not a
  violation, speculative).

Put the report in the PR as a comment when asked, or in the PR body's Evidence section.

Adapted from pstack's `interrogate` (MIT, © 2026 Lauren Tan).
