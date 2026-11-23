---
name: reflect
description: At the end of a task, or right after the owner corrects you, turn what went wrong into a change to the repo (a check, a type, a test, a skill edit) instead of a memory note. Use when a task finishes, when the owner says "you did X wrong", or when you notice you hit the same snag twice.
---

# Reflect

A correction that only lives in a memory file or a chat log is relearned by the next agent. Put
it where the next agent cannot miss it.

## Steps

1. **Name what happened** in one sentence each: what was asked, what you did, what was wrong or
   slow, and what the owner had to catch.
2. **Classify it**:
   - *Mistake the repo allowed* (wrong import, stale comment, a code reused from prose, a spec
     edit nobody flagged): run the `correct` order (architecture → types → check → docs) for this
     one class, even if it has happened only once, when the fix is cheap.
   - *Missing context* (you could not find how something works): add it where the code lives: a
     doc section linked from the module, or a `verify`/`blast-radius` gotcha.
   - *Skill gap* (a skill's steps were wrong or missing): edit the skill under `.claude/skills/`.
   - *Owner preference* (judgment, taste, priorities): this alone belongs in memory or CLAUDE.md.
3. **Make the change** in the current branch if it is in scope, otherwise open an issue with the
   evidence and the proposed level.
4. **Report** in one short block: the lesson, where it now lives, and how it is enforced.

Do not write a lesson as prose when a check could carry it. If the fix is structural, use only the
structural fix: the instruction is the symptom.

Adapted from pstack's `reflect` and `principle-encode-lessons-in-structure` (MIT, © 2026 Lauren Tan).
