# AGENTS.md — Materione

[`CLAUDE.md`](CLAUDE.md) is the canonical instruction file for this repository and
binds ZCode sessions in full: required reading, hard rules (including § Cloudflare
cost guardrails), toolchain floor, work tracking, and documentation conventions.
It is not duplicated here — when the two files disagree, CLAUDE.md wins.

ZCode-specific configuration — the Cloudflare MCP servers and the PreToolUse
cost-guard hook that enforces the guardrails — lives in `.zcode/` (gitignored,
per-machine). CLAUDE.md § Cloudflare cost guardrails describes what to re-create
on a fresh clone before any cloud work.
