#!/usr/bin/env node
// Agent guard: the PreToolUse hook every agent harness loads (Claude Code via
// .claude/settings.json, ZCode via .zcode/config.json). It enforces the
// boundaries CLAUDE.md assigns to the owner, so they hold even when an agent
// skips the prose. Rules and rationale: scripts/agent-guard/README.md.
//
// Protocol (shared by both harnesses): tool call as JSON on stdin; exit 0
// allows, exit 2 denies with the reason on stderr.

import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const MAIN_BRANCHES = new Set(['main', 'master']);

// Paths a PR may touch and still be merged by an agent (memory/policy:
// docs PRs merge themselves, spec and code PRs are the owner's).
const SPEC_FILES = new Set(['CLAUDE.md', 'AGENTS.md', 'CLA.md', 'docs/architecture.md', 'docs/roadmap.md']);
export function agentMergeable(file) {
  const f = file.replaceAll('\\', '/');
  if (SPEC_FILES.has(f) || f.startsWith('docs/architecture/')) return false;
  if (f.startsWith('docs/')) return true;
  return !f.includes('/') && f.endsWith('.md');
}

// Commits that may land on main without a PR: QA blessing output.
export function directToMainAllowed(file) {
  const f = file.replaceAll('\\', '/');
  return f.startsWith('qa/ledger/') || f.startsWith('qa/evidence/');
}

// Files that configure or implement this guard. An agent that can edit them
// can switch the guard off.
const GUARD_PATHS = ['scripts/agent-guard/', '.claude/settings.json', '.claude/settings.local.json', '.zcode/config.json', '.zcode/hooks/'];
export function isGuardPath(file) {
  const f = file.replaceAll('\\', '/');
  return GUARD_PATHS.some((p) => (p.endsWith('/') ? f.includes(`/${p}`) || f.startsWith(p) : f === p || f.endsWith(`/${p}`)));
}

const WRANGLER_DENY = new Set([
  'deploy', 'versions', 'delete', 'remove', 'secret', 'secrets', 'publish', 'pages', 'kv', 'r2', 'd1',
  'queues', 'workflows', 'ai', 'containers', 'pubsub', 'hyperdrive', 'mtls_certificates', 'dispatch',
  'dispatch-namespace', 'email', 'vectorize', 'rollback', 'triggers',
]);

// ---------------------------------------------------------------- shell lexing

// Splits a command line into simple commands (token arrays), honoring quotes,
// command separators and here-documents. Best effort: it is a guard against
// mistakes, and anything it cannot read stays allowed unless a rule below says
// otherwise.
export function splitCommands(command) {
  const commands = [];
  let tokens = [];
  let token = '';
  let hasToken = false;
  let quote = null;
  const heredocs = [];
  const flushToken = () => {
    if (hasToken) tokens.push(token);
    token = '';
    hasToken = false;
  };
  const flushCommand = () => {
    flushToken();
    if (tokens.length) commands.push(tokens);
    tokens = [];
  };
  for (let i = 0; i < command.length; i++) {
    const c = command[i];
    if (quote) {
      if (c === quote) quote = null;
      else if (c === '\\' && quote === '"' && i + 1 < command.length) token += command[++i];
      else token += c;
      continue;
    }
    if (c === '"' || c === "'") {
      quote = c;
      hasToken = true;
      continue;
    }
    if (c === '\\' && i + 1 < command.length) {
      if (command[i + 1] !== '\n') {
        token += command[i + 1];
        hasToken = true;
      }
      i++;
      continue;
    }
    if (c === '<' && command[i + 1] === '<' && command[i + 2] !== '<') {
      flushToken();
      let j = i + 2;
      if (command[j] === '-') j++;
      while (command[j] === ' ') j++;
      const m = /^(['"]?)([A-Za-z0-9_\-]+)\1/.exec(command.slice(j));
      if (m) {
        heredocs.push(m[2]);
        i = j + m[0].length - 1;
        continue;
      }
    }
    if (c === '\n') {
      flushCommand();
      while (heredocs.length) {
        const delimiter = heredocs.shift();
        let end = command.indexOf('\n', i + 1);
        while (true) {
          const line = command.slice(i + 1, end === -1 ? command.length : end);
          i = end === -1 ? command.length : end;
          if (line.trim() === delimiter || end === -1) break;
          end = command.indexOf('\n', i + 1);
        }
      }
      continue;
    }
    if (c === ';' || c === '|' || c === '&' || c === '(' || c === ')') {
      flushCommand();
      continue;
    }
    if (c === ' ' || c === '\t' || c === '\r') {
      flushToken();
      continue;
    }
    token += c;
    hasToken = true;
  }
  flushCommand();
  return commands;
}

const WRAPPERS = new Set(['sudo', 'env', 'command', 'exec', 'nohup', 'time', 'npx', 'pnpm', 'yarn', 'bunx', 'xargs']);

// Strips leading VAR=value assignments and wrappers (`npx`, `env`, ...).
function program(tokens) {
  let i = 0;
  while (i < tokens.length) {
    const t = tokens[i];
    if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(t)) i++;
    else if (WRAPPERS.has(t) || t === 'exec' || (t === 'run' && i > 0 && ['pnpm', 'yarn'].includes(tokens[i - 1]))) i++;
    else if (i > 0 && WRAPPERS.has(tokens[i - 1]) && t.startsWith('-')) i++;
    else break;
  }
  if (i >= tokens.length) return null;
  const name = path.basename(tokens[i].replaceAll('\\', '/')).replace(/\.(exe|cmd|bat)$/i, '').replace(/@.*$/, '');
  return { name, args: tokens.slice(i + 1) };
}

// First non-option argument; options in `withValue` consume the next token.
function subcommand(args, withValue = new Set()) {
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a.startsWith('-')) {
      if (withValue.has(a)) i++;
      continue;
    }
    return { name: a, rest: args.slice(i + 1), before: args.slice(0, i) };
  }
  return null;
}

// ---------------------------------------------------------------- rules

class Deny extends Error {}
const deny = (reason) => {
  throw new Deny(reason);
};

function checkWrangler(args) {
  const sub = subcommand(args, new Set(['--cwd', '--config', '-c', '--env', '-e']));
  if (!sub) return;
  const name = sub.name.split(':')[0];
  if (WRANGLER_DENY.has(name)) {
    deny(`'wrangler ${sub.name}' changes billable Cloudflare state. Deploy-class commands are the owner's (CLAUDE.md § Cloudflare cost guardrails): hand the owner the exact command.`);
  }
}

function gitTarget(args, cwd) {
  let dir = cwd;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '-C' && i + 1 < args.length) dir = path.resolve(dir, nativePath(args[i + 1]));
    if (!args[i].startsWith('-')) break;
  }
  return dir;
}

function checkPush(rest, dir, ctx) {
  const positional = [];
  const flags = new Set();
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i];
    if (a.startsWith('-')) {
      flags.add(a.split('=')[0]);
      if (['--repo', '-o', '--push-option', '--receive-pack', '--exec'].includes(a)) i++;
    } else positional.push(a);
  }
  if (flags.has('--all') || flags.has('--mirror')) deny('`git push --all/--mirror` would push main. Push one branch and open a PR.');
  if (flags.has('--tags') || flags.has('--follow-tags')) deny('Pushing tags publishes a release (release.yml runs on `v*` tags). Releases are the owner\'s.');
  const refspecs = positional.slice(1);
  if (refspecs.length === 0) {
    const branch = ctx.currentBranch(dir);
    if (branch && MAIN_BRANCHES.has(branch)) checkMainPush('HEAD', dir, ctx);
    return;
  }
  for (const spec of refspecs) {
    const clean = spec.replace(/^\+/, '');
    const [src, dst = src] = clean.includes(':') ? clean.split(':') : [clean, clean];
    const target = dst.replace(/^refs\/heads\//, '');
    if (dst.startsWith('refs/tags/') || /^v\d/.test(target)) deny(`Pushing '${spec}' publishes a release tag (release.yml runs on \`v*\` tags). Releases are the owner's.`);
    if (MAIN_BRANCHES.has(target)) {
      if (flags.has('--delete') || flags.has('-d') || src === '') deny('Deleting main is never an agent action.');
      checkMainPush(src || 'HEAD', dir, ctx);
    }
  }
}

function checkMainPush(src, dir, ctx) {
  let files;
  try {
    files = ctx.unpushedFiles(dir, src);
  } catch {
    deny('Could not tell what this push sends to main, so it is denied. Push a branch and open a PR.');
  }
  if (files.length && files.every(directToMainAllowed)) return;
  const sample = files.filter((f) => !directToMainAllowed(f)).slice(0, 5).join(', ');
  deny(`Direct pushes to main may only carry QA blessing output (qa/ledger/, qa/evidence/). This push also changes: ${sample || '(nothing detectable)'}. Push a branch and open a PR.`);
}

const DESTRUCTIVE_HINT = 'It would discard uncommitted work in the owner\'s main checkout. Do this inside your own worktree (.claude/worktrees/...), or ask the owner.';
const WHOLE_TREE = new Set(['.', ':/', '*', ':/*', './']);

function checkGitWorkLoss(sub, rest, dir, ctx) {
  const has = (...f) => rest.some((a) => f.includes(a) || (/^-[a-zA-Z]+$/.test(a) && f.some((x) => /^-[a-zA-Z]$/.test(x) && a.includes(x[1]))));
  let destructive = false;
  if (sub === 'reset' && rest.includes('--hard')) destructive = true;
  if (sub === 'clean' && has('-f', '--force') && !has('-n', '--dry-run')) destructive = true;
  if ((sub === 'checkout' || sub === 'switch') && (rest.includes('-f') || rest.includes('--force') || rest.includes('--discard-changes'))) destructive = true;
  if (sub === 'checkout' && rest.some((a) => WHOLE_TREE.has(a))) destructive = true;
  if (sub === 'restore' && rest.some((a) => WHOLE_TREE.has(a)) && !(rest.includes('--staged') && !rest.includes('--worktree') && !rest.includes('-W'))) destructive = true;
  if (sub === 'stash' && ['drop', 'clear'].includes(rest[0])) destructive = true;
  if (!destructive) return;
  if (ctx.isPrimaryWorktree(dir)) deny(`\`git ${sub} ${rest.join(' ')}\`: ${DESTRUCTIVE_HINT}`);
}

function checkGit(args, ctx) {
  const dir = gitTarget(args, ctx.cwd);
  const sub = subcommand(args, new Set(['-C', '-c', '--git-dir', '--work-tree', '--namespace']));
  if (!sub) return;
  if (sub.name === 'push') checkPush(sub.rest, dir, ctx);
  else checkGitWorkLoss(sub.name, sub.rest, dir, ctx);
}

function checkMerge(prArg, repo, ctx, extra = '') {
  let pr;
  try {
    pr = ctx.prInfo(prArg, repo);
  } catch {
    deny(`Could not read the PR's files, so the merge is denied (merging code or spec is the owner's). ${extra}`.trim());
  }
  if (!MAIN_BRANCHES.has(pr.baseRefName)) return;
  const blocked = pr.files.filter((f) => !agentMergeable(f));
  if (blocked.length) {
    deny(`PR #${pr.number} changes code or spec (${blocked.slice(0, 5).join(', ')}${blocked.length > 5 ? ', ...' : ''}). Only docs-only PRs outside the spec may be merged by an agent; leave this one for the owner.`);
  }
}

const GH_API_PROTECTED = /(\/merge$|\/merges$|\/protection|\/rulesets|\/actions\/(secrets|variables|permissions|runners)|\/environments|\/hooks|\/collaborators|\/keys|\/invitations|\/git\/refs\/(heads\/(main|master)|tags)|\/releases|\/dispatches)/;
const GH_GRAPHQL_PROTECTED = /\b(mergePullRequest|enablePullRequestAutoMerge|createBranchProtectionRule|updateBranchProtectionRule|deleteBranchProtectionRule|deleteRepository|updateRepository|archiveRepository|createRepositoryRuleset|updateRepositoryRuleset|deleteRepositoryRuleset|createRef|deleteRef|updateRef)\b/;

function checkGh(args, raw, ctx) {
  const repoIdx = args.findIndex((a) => a === '-R' || a === '--repo');
  const repo = repoIdx >= 0 ? args[repoIdx + 1] : undefined;
  const sub = subcommand(args, new Set(['-R', '--repo']));
  if (!sub) return;
  const action = subcommand(sub.rest, new Set(['-R', '--repo']));
  const verb = action?.name;
  switch (sub.name) {
    case 'pr':
      if (verb === 'merge') {
        if (sub.rest.includes('--admin')) deny('`gh pr merge --admin` bypasses branch protection. Never an agent action.');
        const target = subcommand(action.rest, new Set(['-R', '--repo', '-t', '--subject', '-b', '--body', '-F', '--body-file', '-A', '--author-email', '--match-head-commit']));
        checkMerge(target?.name, repo, ctx);
      }
      return;
    case 'release':
      if (['create', 'delete', 'edit', 'upload', 'delete-asset'].includes(verb)) deny(`\`gh release ${verb}\` publishes or changes a release. Releases are the owner's.`);
      return;
    case 'workflow':
      if (verb === 'enable' || verb === 'disable') deny(`\`gh workflow ${verb}\` changes CI. Ask the owner.`);
      if (verb === 'run' && /deploy|release/i.test(action.rest.join(' '))) deny('Dispatching a deploy/release workflow is deploy-class (CLAUDE.md § Cloudflare cost guardrails). Hand the owner the command.');
      return;
    case 'secret':
    case 'variable':
      if (verb && verb !== 'list') deny(`\`gh ${sub.name} ${verb}\` changes repository secrets/variables. Ask the owner.`);
      return;
    case 'repo':
      if (['delete', 'edit', 'archive', 'rename', 'unarchive', 'transfer'].includes(verb)) deny(`\`gh repo ${verb}\` changes repository settings. Ask the owner.`);
      return;
    case 'ruleset':
      if (verb && !['list', 'view', 'check'].includes(verb)) deny('Rulesets are the owner\'s.');
      return;
    case 'api': {
      if (sub.rest[0] === 'graphql' || GH_GRAPHQL_PROTECTED.test(raw)) {
        if (GH_GRAPHQL_PROTECTED.test(raw)) deny('This GraphQL mutation merges, changes protection, or changes repository settings. Ask the owner.');
        return;
      }
      const methodIdx = sub.rest.findIndex((a) => a === '-X' || a === '--method');
      const inline = sub.rest.find((a) => a.startsWith('--method=') || /^-X[A-Za-z]+$/.test(a));
      const hasBody = sub.rest.some((a) => ['-f', '-F', '--field', '--raw-field', '--input'].includes(a) || /^--(raw-)?field=/.test(a));
      const method = (methodIdx >= 0 ? sub.rest[methodIdx + 1] : inline ? inline.replace(/^--method=|^-X/, '') : hasBody ? 'POST' : 'GET').toUpperCase();
      if (method === 'GET') return;
      const endpoint = sub.rest.find((a, i) => !a.startsWith('-') && !(i > 0 && ['-X', '--method', '-f', '-F', '--field', '--raw-field', '--input', '-H', '--header', '-q', '--jq', '-t', '--template', '--hostname', '-p', '--preview', '--cache'].includes(sub.rest[i - 1])));
      const ep = (endpoint ?? '').replace(/^\/+/, '').split('?')[0];
      if (GH_API_PROTECTED.test(`/${ep}`) || /^repos\/[^/]+\/[^/]+\/?$/.test(ep)) deny(`\`gh api -X ${method} ${ep}\` merges, publishes, or changes repository settings/protection. Ask the owner.`);
      return;
    }
    default:
      return;
  }
}

const WRITE_SHELL = /(^|[\s;|&])(rm|mv|cp|tee|truncate|chmod|chown|ln|install|dd)\s|>|\bsed\s+(-[a-zA-Z]*i|--in-place)|\bperl\s+-[a-zA-Z]*i|\bgit\s+(checkout|restore|rm|mv|update-index)\b|\bnode\s+-e|\bpython[0-9.]*\s+-c|Set-Content|Out-File|Remove-Item/;

function checkGuardTampering(raw) {
  const mentions = GUARD_PATHS.some((p) => raw.replaceAll('\\', '/').includes(p.replace(/\/$/, '')));
  if (!mentions) return;
  // Reading, testing and running the guard stay allowed.
  const stripped = raw.replace(/node\s+(--test\s+)?\S*scripts\/agent-guard\/\S+\.mjs/g, '');
  if (WRITE_SHELL.test(stripped) && GUARD_PATHS.some((p) => stripped.replaceAll('\\', '/').includes(p.replace(/\/$/, '')))) {
    deny('This command would change the agent guard or the hook configuration that loads it. Those are the owner\'s to change (scripts/agent-guard/README.md § Changing the guard).');
  }
}

function checkCommand(command, ctx, depth = 0) {
  checkGuardTampering(command);
  for (const tokens of splitCommands(command)) {
    const p = program(tokens);
    if (!p) continue;
    const raw = tokens.join(' ');
    if (p.name === 'wrangler') checkWrangler(p.args);
    else if (p.name === 'git') checkGit(p.args, ctx);
    else if (p.name === 'gh') checkGh(p.args, raw, ctx);
    else if (['bash', 'sh', 'zsh', 'dash'].includes(p.name) && depth < 3) {
      const c = p.args.indexOf('-c');
      if (c >= 0 && p.args[c + 1]) checkCommand(p.args[c + 1], ctx, depth + 1);
    }
  }
}

function checkMcp(toolName, input, ctx) {
  const [, server = '', tool = ''] = toolName.split('__');
  if (/^cloudflare/i.test(server) && tool === 'execute') {
    deny(`${toolName} can change billable Cloudflare state. Use the search/docs tools to read; hand the owner any change (CLAUDE.md § Cloudflare cost guardrails).`);
  }
  if (/^github/i.test(server)) {
    if (tool === 'merge_pull_request') {
      checkMerge(String(input.pullNumber ?? input.pull_number ?? ''), input.owner && input.repo ? `${input.owner}/${input.repo}` : undefined, ctx);
    }
    if (['push_files', 'create_or_update_file', 'delete_file'].includes(tool) && MAIN_BRANCHES.has(String(input.branch ?? ''))) {
      deny(`${toolName} writes straight to ${input.branch}. Write to a branch and open a PR.`);
    }
    if (/^(delete_repository|update_repository|create_release|update_release|delete_release|create_tag)/.test(tool)) {
      deny(`${toolName} changes the repository or publishes a release. Ask the owner.`);
    }
  }
}

// Returns null to allow, or the denial reason.
export function evaluate(input, ctx) {
  const toolName = input?.tool_name ?? input?.toolName;
  const toolInput = input?.tool_input ?? input?.toolInput ?? {};
  if (typeof toolName !== 'string') return null;
  try {
    if (toolName === 'Bash' && typeof toolInput.command === 'string') checkCommand(toolInput.command, ctx);
    else if (['Edit', 'Write', 'MultiEdit', 'NotebookEdit'].includes(toolName)) {
      const file = String(toolInput.file_path ?? toolInput.notebook_path ?? '');
      if (file && isGuardPath(file)) deny(`${file} is part of the agent guard or the hook configuration that loads it. It is the owner's to change (scripts/agent-guard/README.md § Changing the guard).`);
    } else if (toolName.startsWith('mcp__')) checkMcp(toolName, toolInput, ctx);
  } catch (e) {
    if (e instanceof Deny) return e.message;
    return null;
  }
  return null;
}

// ---------------------------------------------------------------- real context

function gitOut(dir, ...args) {
  return execFileSync('git', ['-C', dir, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 5000 }).trim();
}

export function realContext(cwd) {
  return {
    cwd,
    currentBranch(dir) {
      try {
        return gitOut(dir, 'rev-parse', '--abbrev-ref', 'HEAD');
      } catch {
        return null;
      }
    },
    isPrimaryWorktree(dir) {
      try {
        const [gitDir, common] = gitOut(dir, 'rev-parse', '--path-format=absolute', '--git-dir', '--git-common-dir').split(/\r?\n/);
        return path.resolve(gitDir) === path.resolve(common);
      } catch {
        return false;
      }
    },
    unpushedFiles(dir, src) {
      const out = gitOut(dir, 'log', '--name-only', '--format=', `origin/main..${src}`);
      return [...new Set(out.split(/\r?\n/).filter(Boolean))];
    },
    prInfo(prArg, repo) {
      const args = ['pr', 'view', ...(prArg ? [prArg] : []), '--json', 'number,files,baseRefName', ...(repo ? ['-R', repo] : [])];
      const json = JSON.parse(execFileSync('gh', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 10000 }));
      return { number: json.number, baseRefName: json.baseRefName, files: json.files.map((f) => f.path) };
    },
  };
}

// Git Bash reports /c/Users/... ; Node on Windows needs C:/Users/...
export function nativePath(p) {
  const m = process.platform === 'win32' && /^\/([a-zA-Z])(\/.*)?$/.exec(p);
  return m ? `${m[1].toUpperCase()}:${m[2] ?? '/'}` : p;
}

async function main() {
  if (process.env.MATERIONE_AGENT_GUARD === 'off') process.exit(0);
  let raw = '';
  try {
    for await (const chunk of process.stdin) raw += chunk;
  } catch {
    process.exit(0);
  }
  let input;
  try {
    input = JSON.parse(raw);
  } catch {
    process.exit(0);
  }
  const cwd = nativePath(typeof input.cwd === 'string' ? input.cwd : process.cwd());
  const reason = evaluate(input, realContext(cwd));
  if (reason) {
    process.stderr.write(`[agent-guard] BLOCKED: ${reason}\n`);
    process.exit(2);
  }
  process.exit(0);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) main();
