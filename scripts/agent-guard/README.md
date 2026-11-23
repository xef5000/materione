# Agent guard

One PreToolUse hook that every agent harness on this repo loads. It enforces the boundaries
[CLAUDE.md](../../CLAUDE.md) gives to the owner, so they hold even when an agent skips the prose
(pstack's *encode lessons in structure*: a rule nothing enforces is a symptom, not a fix).

| Boundary | Denied | Allowed |
|---|---|---|
| Cloudflare cost (CLAUDE.md § Cloudflare cost guardrails) | deploy-class `wrangler` subcommands; `mcp__cloudflare*__execute` | `wrangler dev`, `tail`, `--dry-run` builds, docs/search MCP tools |
| Merging | `gh pr merge`, GitHub MCP merge, GraphQL/REST merge, when the PR into `main` touches anything but non-spec docs; `--admin` always | docs-only PRs outside the spec (anything under `docs/` except `docs/architecture.md`, `docs/architecture/`, `docs/roadmap.md`; top-level `*.md` except `CLAUDE.md`, `AGENTS.md`, `CLA.md`); merges into non-`main` bases (stacks) |
| Pushing | push to `main` unless every outgoing commit only touches `qa/ledger/` or `qa/evidence/` (blessing runs); `--all`, `--mirror`, `--tags`, `v*` refspecs (tags trigger `release.yml`) | any branch, including `--force-with-lease` |
| Releases, CI, repo settings | `gh release create/edit/delete/upload`; `gh workflow run` of deploy/release workflows; `gh workflow enable/disable`; `gh secret`/`variable` writes; `gh repo edit/delete/...`; non-GET `gh api` on protection, rulesets, secrets, hooks, collaborators, releases, dispatches, refs of `main` or tags, or the repo itself | reads; issues, PRs, comments, labels |
| Work loss | in the **main checkout** only: `git reset --hard`, `git clean -f`, `git checkout -f`, `git checkout/restore .`, `git stash drop/clear` | the same commands inside an agent worktree (`.claude/worktrees/...`); single-file restores |
| The guard itself | edits to `scripts/agent-guard/`, `.claude/settings.json`, `.claude/settings.local.json`, `.zcode/config.json`, `.zcode/hooks/` (Edit/Write tools, or shell writes naming them) | reading and running them |

## How it fails

- Malformed hook input, an unknown tool, or a bug in the guard: **allow**. A broken guard must not
  stop work; the harness's own permission prompt stays as the backstop. (A crash exits 1, which both
  harnesses treat as a non-blocking error.)
- A merge whose PR files cannot be read, or a push to `main` whose outgoing commits cannot be listed:
  **deny**. Those actions are hard to undo, and the owner can always do them.

## What it is not

The guard stops mistakes, not a determined adversary. An agent could write a script that calls the
GitHub API directly. The structural boundary for that is a separate GitHub identity for agents (a
fine-grained token or machine account without admin rights) together with `enforce_admins` and
required reviews on `main`. That is the owner's setup, tracked in #480.

## Wiring

- **Claude Code**: `.claude/settings.json` (committed, hooks only) runs
  `node "$CLAUDE_PROJECT_DIR/scripts/agent-guard/guard.mjs"` on `Bash|Edit|Write|MultiEdit|NotebookEdit|mcp__.*`.
  Personal settings (env, API keys, permissions) belong in `.claude/settings.local.json`, which stays
  ignored. `guard.test.mjs` fails if the committed file grows an `env` block or stops loading the guard.
- **ZCode**: `.zcode/config.json` (per machine, ignored) `hooks.events.PreToolUse` runs
  `node ${ZCODE_PROJECT_DIR}/scripts/agent-guard/guard.mjs` with matcher `Bash|Edit|Write|mcp__.*`.
  Recreate it on a fresh clone; see [AGENTS.md](../../AGENTS.md).

## Changing the guard

Agents cannot edit these files. The owner either edits them by hand, or starts a session with
`MATERIONE_AGENT_GUARD=off` in the environment (set outside the agent; the agent cannot set it for
the hook) to let an agent work on the guard, then removes it. Every rule change comes with a case in
`guard.test.mjs`, built from the real command it is meant to catch. CI runs
`node --test scripts/agent-guard/guard.test.mjs`.
