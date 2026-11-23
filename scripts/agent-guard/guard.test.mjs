import { test } from 'node:test';
import assert from 'node:assert/strict';
import { evaluate, splitCommands, agentMergeable } from './guard.mjs';

// A fake world: the main checkout at /repo, agent worktrees under
// /repo/.claude/worktrees/, PRs with fixed file lists.
const PRS = {
  '315': { number: 315, baseRefName: 'main', files: ['docs/architecture/gui-input-durability.md', 'plugin/src/main/java/X.java'] },
  '444': { number: 444, baseRefName: 'main', files: ['docs/guide/menus.md', 'README.md'] },
  '445': { number: 445, baseRefName: 'main', files: ['docs/architecture.md'] },
  '446': { number: 446, baseRefName: 'm6-stack-base', files: ['core/src/main/java/Y.java'] },
};
function ctx({ cwd = '/repo', branch = 'm6-feature', unpushed = ['core/A.java'], prFails = false } = {}) {
  return {
    cwd,
    currentBranch: () => branch,
    isPrimaryWorktree: (dir) => !dir.replaceAll('\\', '/').includes('/.claude/worktrees/'),
    unpushedFiles: () => unpushed,
    prInfo: (arg) => {
      if (prFails) throw new Error('gh down');
      return PRS[arg ?? '315'] ?? PRS['315'];
    },
  };
}
const bash = (command, c = ctx()) => evaluate({ tool_name: 'Bash', tool_input: { command } }, c);
const denied = (command, c) => assert.ok(bash(command, c), `expected deny: ${command}`);
const allowed = (command, c) => assert.equal(bash(command, c), null, `expected allow: ${command}`);

test('lexer honors quotes, separators and here-documents', () => {
  assert.deepEqual(splitCommands('git commit -m "push to main; later" && git push'), [['git', 'commit', '-m', 'push to main; later'], ['git', 'push']]);
  assert.deepEqual(splitCommands("cat > f <<'EOF'\ngit push origin main\nEOF\necho ok"), [['cat', '>', 'f'], ['echo', 'ok']]);
});

test('cloudflare: deploy-class wrangler and the API execute tool are denied (CLAUDE.md cost guardrails)', () => {
  denied('cd cloud/model-proxy && npx wrangler deploy');
  denied('npx wrangler@3 d1 execute model-proxy-allowance --remote --command "DELETE FROM x"');
  denied('wrangler --config wrangler.toml secret put KEY');
  denied('bash -c "npx wrangler kv:key put a b"');
  allowed('npx wrangler deploy --dry-run --outdir dist'.replace('deploy', 'dev'));
  allowed('npx wrangler tail');
  for (const tool of ['mcp__cloudflare__execute', 'mcp__cloudflare-api__execute']) {
    assert.ok(evaluate({ tool_name: tool, tool_input: { code: 'x' } }, ctx()));
  }
  assert.equal(evaluate({ tool_name: 'mcp__cloudflare__search', tool_input: {} }, ctx()), null);
});

test('merge: an agent merges docs-only PRs outside the spec, nothing else', () => {
  denied('gh pr merge 315 --squash'); // #315 touched Java alongside docs
  allowed('gh pr merge 444 --squash --delete-branch');
  denied('gh pr merge 445 --squash'); // architecture.md is spec
  allowed('gh pr merge 446 --squash'); // stacked PR into a non-main base
  denied('gh pr merge 444 --admin');
  denied('gh pr merge 444', ctx({ prFails: true })); // unknown => owner's
  denied("gh api graphql -f query='mutation { mergePullRequest(input:{pullRequestId:\"x\"}) { clientMutationId } }'");
  denied('gh api -X PUT repos/xef5000/materione/pulls/315/merge');
  assert.ok(evaluate({ tool_name: 'mcp__github__merge_pull_request', tool_input: { owner: 'xef5000', repo: 'materione', pullNumber: 315 } }, ctx()));
  assert.equal(evaluate({ tool_name: 'mcp__github__merge_pull_request', tool_input: { owner: 'xef5000', repo: 'materione', pullNumber: 444 } }, ctx()), null);
});

test('spec paths are never agent-mergeable; plain docs are', () => {
  for (const f of ['CLAUDE.md', 'AGENTS.md', 'docs/architecture.md', 'docs/architecture/gui.md', 'docs/roadmap.md', 'qa/catalog/x.mjs', '.github/workflows/ci.yml']) assert.equal(agentMergeable(f), false, f);
  for (const f of ['docs/guide/menus.md', 'docs/probe.md', 'README.md', 'CHANGELOG.md']) assert.equal(agentMergeable(f), true, f);
});

test('push: branches go up, main only takes QA blessing output, tags are releases', () => {
  allowed('git push -u origin m6-views-v2');
  allowed('git push --force-with-lease origin m6-views-v2');
  denied('git push origin main', ctx({ unpushed: ['core/A.java'] }));
  denied('git push origin HEAD:main');
  denied('git push origin +refs/heads/m6-x:refs/heads/main');
  denied('git push', ctx({ branch: 'main', unpushed: ['plugin/src/main/java/P.java', 'qa/ledger/x.json'] }));
  allowed('git push', ctx({ branch: 'main', unpushed: ['qa/ledger/components.json'] })); // b41313a2-style bless
  allowed('git push', ctx({ branch: 'main', unpushed: ['qa/evidence/run/sounds.jukebox/dossier.md'] })); // 6a5f51d0-style evidence
  denied('git push origin v0.6.0');
  denied('git push --tags');
  denied('git push origin --all');
  denied('git push origin --delete main');
});

test('work loss: destructive git is denied in the main checkout, allowed in agent worktrees', () => {
  const wt = ctx({ cwd: '/repo/.claude/worktrees/agent-a1' });
  for (const cmd of ['git reset --hard origin/main', 'git clean -fdx', 'git checkout -- .', 'git restore .', 'git stash drop', 'git checkout -f main']) {
    denied(cmd);
    allowed(cmd, wt);
  }
  denied('git -C /repo reset --hard', wt); // -C retargets to the main checkout
  allowed('git -C .claude/worktrees/agent-a1 reset --hard');
  allowed('git clean -n');
  allowed('git restore --staged .');
  allowed('git checkout -- core/src/main/java/A.java');
  allowed('git reset --soft HEAD~1');
});

test('releases, workflows, secrets and settings are the owner\'s', () => {
  denied('gh release create v0.6.0 --generate-notes');
  denied('gh workflow run deploy.yml');
  denied('gh workflow run deploy-model-proxy.yml');
  allowed('gh workflow run ci.yml');
  denied('gh secret set CF_API_TOKEN');
  denied('gh repo edit --visibility private');
  denied('gh api -X PUT repos/xef5000/materione/branches/main/protection --input p.json');
  denied('gh api -X PATCH repos/xef5000/materione -f allow_auto_merge=true');
  allowed('gh api repos/xef5000/materione/branches/main/protection');
  allowed('gh api repos/xef5000/materione/issues/480/comments -f body=hi');
  allowed('gh issue create --title t --body b');
  allowed('gh pr create --fill');
});

test('the guard protects itself', () => {
  for (const file of ['C:\\repo\\scripts\\agent-guard\\guard.mjs', '/repo/.claude/settings.json', '/repo/.zcode/config.json', '/repo/.claude/worktrees/agent-a1/scripts/agent-guard/README.md']) {
    assert.ok(evaluate({ tool_name: 'Edit', tool_input: { file_path: file, old_string: 'a', new_string: 'b' } }, ctx()), file);
  }
  assert.equal(evaluate({ tool_name: 'Write', tool_input: { file_path: '/repo/scripts/checks/run.mjs' } }, ctx()), null);
  denied("sed -i 's/deny(/void(/' scripts/agent-guard/guard.mjs");
  denied('rm .claude/settings.json');
  denied('echo {} > .claude/settings.json');
  allowed('node --test scripts/agent-guard/guard.test.mjs');
  allowed('cat scripts/agent-guard/README.md');
  denied("cp /tmp/x.mjs scripts/agent-guard/guard.mjs");
  denied("git checkout origin/main -- .claude/settings.json");
  denied("node -e \"require('fs').writeFileSync('.claude/settings.json','{}')\"");
  denied('cat > .claude/settings.json <<EOF\n{}\nEOF');
  denied('echo x >> scripts/agent-guard/guard.mjs');
});

test('the guard does not mistake quoted text or here-documents for writes (false positives seen while building it)', () => {
  // git add of the hook file plus a commit message holding an e-mail address in angle brackets
  allowed('git add .claude/settings.json scripts/agent-guard && git commit -m "Guard\n\nCo-Authored-By: Claude <noreply@anthropic.com>"');
  // stderr redirection while running the guard's own tests
  allowed('node --test scripts/agent-guard/guard.test.mjs 2>&1 | grep -E "pass|fail"');
  // a here-document (an issue body) that mentions the guard
  allowed("cat > /tmp/issue.md <<'EOF'\nSee scripts/agent-guard/README.md => rules\nEOF\ngh issue create --body-file /tmp/issue.md");
  // an inline script that only reads
  allowed("node -e \"console.log(require('fs').readFileSync('.claude/settings.json','utf8'))\"");
});

test('malformed input fails open', () => {
  assert.equal(evaluate(null, ctx()), null);
  assert.equal(evaluate({ tool_name: 'Bash', tool_input: {} }, ctx()), null);
  assert.equal(evaluate({ tool_name: 'Read', tool_input: { file_path: '/repo/.claude/settings.json' } }, ctx()), null);
});

test('the committed Claude Code settings load the guard and carry no personal env', async () => {
  const { readFileSync } = await import('node:fs');
  const settings = JSON.parse(readFileSync(new URL('../../.claude/settings.json', import.meta.url), 'utf8'));
  assert.equal(settings.env, undefined, 'env (tokens, endpoints) belongs in .claude/settings.local.json, which is not committed');
  assert.equal(settings.apiKeyHelper, undefined);
  const hooks = settings.hooks?.PreToolUse ?? [];
  const entry = hooks.find((h) => h.hooks?.some((x) => x.command?.includes('scripts/agent-guard/guard.mjs')));
  assert.ok(entry, 'PreToolUse must run scripts/agent-guard/guard.mjs');
  for (const tool of ['Bash', 'Edit', 'Write', 'mcp__cloudflare__execute', 'mcp__github__merge_pull_request']) {
    assert.match(tool, new RegExp(`^(${entry.matcher})$`), `matcher must cover ${tool}`);
  }
});
