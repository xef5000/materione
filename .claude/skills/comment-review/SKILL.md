---
name: comment-review
description: Review the comments in a diff and delete the ones that will go stale. Use before opening a PR, or when asked to clean up comments. Edits comments only, never code.
---

# Comment review

Comments in this repo go stale faster than code: they cite milestones, issue numbers and section
numbers that move, and they narrate what the next line does. The docs are the source of truth
for design (D1 applies to knowledge too); code says what it does through names and types.

## Scope

The files or diff the user named; by default `git diff $(git merge-base origin/main HEAD)`.
Only lines the diff adds or changes, unless asked for a whole file.

## What survives

Keep a comment only if it is one of these:

| Kind | Example |
|---|---|
| License or legal header | `// SPDX-License-Identifier: Apache-2.0` |
| A surprise imposed by something outside this repo: Paper, Minecraft, the client, a protocol, a library, the OS | `// The client drops a lore line longer than 255 chars without an error.` |
| The contract of a public API (`api/` module, the code-mode SDK, protocol messages) | Javadoc on an `api/` interface method |
| A link to the design a reader needs: a doc section or an issue that explains a constraint the code cannot express | `// Ordering: docs/architecture/logic.md §18 (rules fire before variables).` |
| A lint suppression whose rule is wrong here, with the reason | `@SuppressWarnings("unchecked") // Paper's PDC API is raw-typed` |

Delete everything else: narration ("loop over the slots"), history ("was X before #312",
"the M2 engine"), milestone or status claims ("inert until M7", "not yet wired"), banners,
commented-out code, and justifications for workarounds. When unsure, delete.

## Workarounds and warnings

"IMPORTANT", "do not remove", "for now", "temporary", "hack" are scent, not proof. Read the
surrounding code and the referenced symbols. Keep the comment only if it describes an external
gotcha that is true on a live path today. A long justification without such a gotcha is a
confession: delete it and flag the symbol.

## Flags

When a comment exists because the code cannot explain itself, delete the comment and flag the
symbol as **refactor**: a rename, an extracted function, a type, or a redesign that makes it
self-explanatory. Do not do the refactor; list it.

## Output

Files touched, comments deleted (count), **refactor** flags (one line each: `file:line symbol:
what would make it self-explanatory`), and comments kept with the kind that saved them.

After deleting milestone references, run `node scripts/checks/run.mjs --update-baselines` so the
ratchet records the drop.

Adapted from pstack's `no-comments` and the Comment Sicko agent (MIT, © 2026 Lauren Tan).
