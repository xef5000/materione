# AGENTS.md — Materione

[`CLAUDE.md`](CLAUDE.md) is the canonical instruction file for this repository and
binds ZCode sessions in full: required reading, hard rules (including § Cloudflare
cost guardrails), toolchain floor, work tracking, and documentation conventions.
It is not duplicated here — when the two files disagree, CLAUDE.md wins.

The agent guard ([`scripts/agent-guard/`](scripts/agent-guard/README.md)) enforces
the owner's boundaries (deploys, merges, pushes to `main`, releases, discarding
work) for every harness. ZCode loads it from `.zcode/config.json` (gitignored,
per machine). On a fresh clone, register it under `hooks.events.PreToolUse`:
matcher `Bash|Edit|Write|MultiEdit|mcp__.*`, a `process` hook running `node` with
args `["${ZCODE_PROJECT_DIR}/scripts/agent-guard/guard.mjs"]`. The same file holds
the Cloudflare MCP servers.
