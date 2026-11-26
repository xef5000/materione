/**
 * The recorded Bloodfang conversation, end to end without a browser (issue 348's exit criterion; agent-benchmark.md T01;
 * codemode.md section 9.1): the scripted provider replays the assistant's turns, and everything else is the real thing. Each
 * program goes through the real type checker (the Worker's `createChecker`) over the SDK generated from the recorded registry,
 * the real sandbox (QuickJS-WASM and the bundled core, in process), and the real proposal store over a model of the server's
 * transaction. The same conversation runs in a browser against the harness in `browser/specs/codemode-chat.pw.ts`.
 */
import { readFileSync } from 'node:fs';
import { beforeAll, describe, expect, it } from 'vitest';
import { agentContext } from '../src/lib/codemode/chat/context';
import { runAgent, type LoopResult } from '../src/lib/codemode/chat/loop';
import type { ChatMessage } from '../src/lib/codemode/chat/provider';
import { createScriptedProvider } from '../src/lib/codemode/chat/scripted';
import { BLOODFANG_PROMPTS, bloodfangScript } from '../src/lib/codemode/chat/scripts/bloodfang';
import { runProgram, TOOL_RESULT_MAX_CHARS, type ToolOutcome } from '../src/lib/codemode/chat/tool';
import { generateSdk } from '../src/lib/codemode/generate/generate';
import { createProposalStore, type ProposalStore } from '../src/lib/codemode/host';
import type { SandboxRunner } from '../src/lib/codemode/sandbox';
import { createChecker } from '../src/lib/codemode/typecheck/checker';
import { LIB_FILES } from '../src/lib/codemode/typecheck/libs';
import { CORE_DTS, surfaceNamed } from './helpers/codemode';
import { click, FakeServer } from './helpers/host';
import { hostOf, nodeRunner } from './helpers/sandbox-node';
import type { World } from './helpers/world';

const PACK = 'rpg';
const surface = surfaceNamed('plugin-current');
const sdk = generateSdk(surface);
const checker = createChecker({ libs: LIB_FILES, core: readFileSync(CORE_DTS, 'utf8') });

const world: World = {
  packs: [PACK],
  resources: { [PACK]: [{ type: 'items', path: 'items/ruby_sword.yml', id: 'ruby_sword' }, { type: 'items', path: 'items/ruby.yml', id: 'ruby' }] },
  documents: { [`${PACK}/items/ruby_sword.yml`]: { rev: 0, parsed: { ruby_sword: { material: 'DIAMOND_SWORD', lore: ['<gray>Sharp'] } } } },
};

describe('the Bloodfang conversation with the scripted provider', () => {
  let runner: SandboxRunner;
  let server: FakeServer;
  let store: ProposalStore;
  const history: ChatMessage[] = [];
  const outcomes: ToolOutcome[] = [];
  const provider = createScriptedProvider(bloodfangScript(PACK));

  beforeAll(async () => {
    runner = await nodeRunner();
    server = new FakeServer({ 'items/ruby_sword.yml': 0, 'items/ruby.yml': 0 });
    store = createProposalStore({ transport: server });
  }, 120_000);

  async function say(text: string): Promise<LoopResult> {
    history.push({ role: 'user', text });
    const host = hostOf(world);
    const result = await runAgent(history, {
      provider,
      system: agentContext(surface),
      tool: async (input) => {
        const outcome = await runProgram(input, {
          surface,
          check: async (program) => checker.check({ program, sdk }),
          run: (program, document) => runner.run(program, document, host),
          // the agent port only: the loop is never given the store
          agent: store.agent,
        });
        outcomes.push(outcome);
        return outcome;
      },
    });
    history.push(...result.added);
    return result;
  }

  it('first request: looks, repairs one ordinary type error, and applies the item-scoped sword', async () => {
    const result = await say(BLOODFANG_PROMPTS[0]);
    expect(result.stop).toBe('done');
    expect(outcomes.map((outcome) => outcome.status)).toEqual(['read', 'type-error', 'applied']);
    // the read ran in the sandbox against the world and returned what is there
    expect((JSON.parse(outcomes[0]!.content) as { result: { value: unknown } }).result.value).toEqual(['ruby_sword']);
    expect(outcomes[1]!.missingPrimitives).toEqual([]);
    expect(outcomes[1]!.diagnostics.every((found) => found.phase === 'type-check' && found.line !== undefined)).toBe(true);
    const applied = outcomes[2]!;
    expect(applied.failed).toBe(false);
    const proposal = store.get(applied.proposalId!)!;
    expect(proposal.state).toBe('applied');
    expect(proposal.needs).toEqual([]);
    expect(proposal.documents.map((document) => (document as { path: string }).path).sort()).toEqual([
      'guis/bloodfang_menu.yml',
      'items/bloodfang.yml',
      'variables/bloodfang_level.yml',
      'variables/bloodfang_xp.yml',
    ]);
    expect(server.calls.map((entry) => entry.call.dryRun)).toEqual([true, false]);
    const apply = server.applies()[0]!.call;
    expect(apply.approval).toBe('not_needed');
    expect(apply.origin).toBe('code-mode');
    expect(apply.program).toContain('p.items.create("bloodfang"');
    expect(apply.program).toContain('scope: "item"');
    expect(apply.operations).toEqual(server.calls[0]!.call.operations);
    expect(apply.operations.map((operation) => operation.kind)).toEqual(['create', 'create', 'create', 'logicEdit', 'create', 'logicEdit', 'logicEdit', 'logicEdit']);
    expect(applied.diagnostics.some((found) => found.code === 'SDK-0016' && found.severity === 'warning')).toBe(true);
    expect(store.snapshot().log).toHaveLength(1);
    expect((result.added.at(-1) as { text: string }).text).toContain('item-scoped');
    expect(result.failedCalls).toBe(1);
  }, 120_000);

  it('follow-up: a change to an item that already exists waits for approval', async () => {
    const result = await say(BLOODFANG_PROMPTS[1]);
    expect(result.stop).toBe('done');
    const waiting = outcomes[3]!;
    expect(waiting.status).toBe('waiting');
    expect(waiting.failed).toBe(false);
    const sent = JSON.parse(waiting.content) as { note: string; proposal: { needsApprovalOf: string[]; state: string } };
    expect(sent.proposal.needsApprovalOf).toEqual([`${PACK}:items/ruby_sword.yml`]);
    expect(sent.note).toContain('You cannot approve it');
    const proposal = store.get(waiting.proposalId!)!;
    expect(proposal.state).toBe('waiting');
    // dry-run only: nothing was applied for it
    expect(server.applies()).toHaveLength(1);
    // the request is over and the model made no further call
    expect((result.added.at(-1) as { text: string }).text).toContain('I cannot approve it');

    // the owner reviews and approves: a gesture the loop has no way to make
    await store.approve({ proposal: proposal.id }, click());
    expect(store.get(proposal.id)!.state).toBe('applied');
    expect(server.applies()).toHaveLength(2);
    expect(server.applies()[1]!.call.approval).toBe('granted');
    expect(server.applies()[1]!.call.operations).toEqual([
      { kind: 'setFields', path: 'items/ruby_sword.yml', rev: 0, edits: [{ field: ['ruby_sword', 'lore'], value: ['<gray>An old blade.', '<dark_red>A smith can reforge it into Bloodfang.'] }] },
    ]);
    expect(store.snapshot().log).toHaveLength(2);
  }, 120_000);

  it('the recording is over: one more message is refused by the provider, plainly', async () => {
    const result = await say('And make it glow.');
    expect(result.stop).toBe('refused');
    expect(result.refusal?.code).toBe('script_ended');
    expect(outcomes).toHaveLength(4);
  });

  it('every tool result that went back to the model is bounded JSON', () => {
    const results = history.filter((message) => message.role === 'tool');
    expect(results).toHaveLength(4);
    for (const message of results) {
      expect(message.content.length).toBeLessThanOrEqual(TOOL_RESULT_MAX_CHARS);
      expect(() => JSON.parse(message.content) as unknown).not.toThrow();
    }
  });
});
