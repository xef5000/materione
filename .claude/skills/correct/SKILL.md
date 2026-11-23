---
name: correct
description: Find the agent mistakes that keep recurring in this repo and change the repo so they cannot recur, at the highest level that works. Use when the owner says "agents keep doing X", after a correction that has happened before, or periodically (e.g. each milestone).
disable-model-invocation: true
---

# Correct

Assume every contributor is an agent that sees only the files it opened, copies the nearest
example, and takes the quickest route that compiles. Build the repo so that a change that looks
right from one file is right for the whole repo.

## 1. Find the mistake classes

Sources: recent commits (`git log --format='%h %s' -400`: follow-ups, "correct the ... claims",
renumbering, regenerated goldens, reverts), PR conversations (`gh pr view N --comments`),
`qa-failure` issues, comments that justify workarounds, and the owner's corrections in the
current session. Group them into classes. A class qualifies after two occurrences.

## 2. Fix each class at the highest level that works

1. **Architecture**: one owner per piece of state; one supported way per task; hide internals so
   a wrong import fails (ArchUnit rules in each module's `ArchitectureTest`); replace hand-synced
   lists with one source; delete legacy paths agents might copy (no legacy before release).
2. **Types**: make the invalid state unwritable. If bad code still compiles, add a check whose
   error message names the right file, type or function.
3. **Checks and tests**: a rule in `scripts/checks/run.mjs`, a ban test, a golden, a guard rule in
   `scripts/agent-guard/` (owner-approved). If the pattern is already widespread, ratchet: fail
   only on growth, like the milestone-comment baseline. Tests assert behavior; repair or delete
   tests that pass when the behavior breaks.
4. **Docs and CLAUDE.md**: last, and only for judgment calls. Nothing fails when an agent skips
   prose.

## 3. Fix and prove

Most frequent class first, one commit per class. Show that each new check fails on a real past
instance: replay the commit, or put the bad line in a test, and name the instance in the test.
Run the same command locally and in CI. An exception sits on the offending line with a reason
and an issue link.

## 4. Keep the rule table true

CLAUDE.md § What enforces each rule pairs each rule with its enforcer. Add a row when you add a
check. If the mistake was already a **review**-only row, that is a repeat: move it up a level in
the same change. Remove rows whose mistake has become impossible.

## Reply

Per class: the evidence (hashes, PRs, `file:line`), the level chosen, why a higher level does not
fit, and the proof the check fails on the real instance.

Adapted from pstack's `correct` (MIT, © 2026 Lauren Tan).
