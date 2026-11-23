---
name: blast-radius
description: Find what a change could break outside its diff, and prove the one fact its safety rests on by running code. Use before changing shared code, a wire format, a content schema, a default, or anything with many callers; or when asked "what could this break".
---

# Blast radius

Listing callers is a grep. The job is the breakage grep will not show you, and a writeup that
reads as convincing whether or not it is true is worth nothing. Find the fact everything hinges
on and prove it.

## Confidence ladder

Push each key fact as high as is cheap and say where it stopped:

1. Assertion. Worthless alone.
2. A cited `file:line` or library source.
3. A step-by-step walk showing the failure cannot happen.
4. A script or test that calls the real code and fails loudly if the fact is wrong.
5. A reproduction in the running product (see the `verify` skill).

Level 4 is usually a small test in the module that owns the code, or a Node script importing the
same module the editor ships.

## Steps

1. **Read the change**: the diff, the symbols added, changed and removed, and the implicit shifts
   (a default, an ordering, a timing, an error that used to be thrown).
2. **Find the safety fact**: most risky-looking changes are safe because of one fact. Name it.
3. **Look past grep**. In Materione, the usual hidden consumers are:
   - content on disk written by older versions (lossless round-trip; unknown fields preserved);
   - the plugin↔editor protocol and released plugins (`fixtures/protocol/released/`,
     `scripts/compat/editor-compat.mjs`);
   - generated surfaces: the code-mode SDK `.d.ts` goldens, registry self-description, editor
     snapshots;
   - diagnostic codes, which are API (plugin.md §5);
   - the pack compiler's byte-for-byte output (golden fixtures);
   - the Workers (`cloud/`), which ship separately from the plugin;
   - QA catalog assertions that pin wording or counts.
4. **Assess**: per risk give the mechanism, `file:line`, likelihood and cost. Separate confirmed
   from cleared. An empty search is an answer; never invent callers.
5. **Prove the safety fact**: write and run the code; paste the output.

## Deliverable

- **What it does**, including the non-obvious parts.
- **The safety fact**, with the ladder level reached and the proof, or "unproven".
- **Risks**: mechanism, `file:line`, likelihood × severity, how to check.
- **Cleared**: what you checked and why it is fine.
- **Before merge**: the cheapest test or repro that would catch a regression, ready to run.

Adapted from pstack's `blast-radius` (MIT, © 2026 Lauren Tan).
