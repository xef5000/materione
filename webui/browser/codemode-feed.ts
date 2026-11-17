/**
 * The browser harness's way to put a proposal in front of the review panel (issue 347): a recorded corpus program, as the
 * sandbox sealed it (`fixtures/golden/codemode/ops/<name>.json`) with its program text (`fixtures/codemode/corpus/<name>.ts`),
 * handed to the agent port of the review store. Nothing produces proposals in the product yet (the chat is issue 348), so
 * this is a harness seam: it lives under `browser/`, is not part of the editor build, and reaches only `propose` and
 * `declarePlan`, the same two calls the chat tool will get. It cannot approve anything.
 */
import type { ProposalInput } from '../src/lib/codemode/host';
import { ops, unwrap } from '../src/lib/api';
import { session } from '../src/lib/state.svelte';
import { createScriptedProvider } from '../src/lib/codemode/chat/scripted';
import { bloodfangScript } from '../src/lib/codemode/chat/scripts/bloodfang';
import { installChatProvider } from '../src/lib/codemode/chat/state.svelte';
import { reviewAgentPort } from '../src/lib/codemode/review/state.svelte';
import type { CodemodeHarness } from './contract';

const sealedFiles = import.meta.glob('../../fixtures/golden/codemode/ops/*.json', { eager: true, import: 'default' }) as Record<string, unknown>;
const programFiles = import.meta.glob('../../fixtures/codemode/corpus/*.ts', { eager: true, query: '?raw', import: 'default' }) as Record<string, string>;

const nameOf = (path: string): string => /([^/]+)\.(json|ts)$/.exec(path)?.[1] ?? path;
const sealed = new Map(Object.entries(sealedFiles).map(([path, value]) => [nameOf(path), value as ProposalInput]));
const programs = new Map(Object.entries(programFiles).map(([path, text]) => [nameOf(path), text]));

export function createCodemodeHarness(pack: string, options: { currentRevisions?: boolean } = {}): CodemodeHarness {
  return {
    programs: () => [...sealed.keys()].sort(),
    declarePlan(objects, title) {
      const agent = reviewAgentPort();
      if (agent === null) throw new Error('the review session is not connected');
      return agent.declarePlan({ title, objects }).id;
    },
    async propose(name, proposalOptions = {}) {
      const agent = reviewAgentPort();
      if (agent === null) throw new Error('the review session is not connected');
      const recorded = sealed.get(name);
      const program = programs.get(name);
      if (recorded === undefined || program === undefined) throw new Error(`no recorded corpus program '${name}'`);
      const [from, to] = proposalOptions.slice ?? [0, recorded.operations.length];
      const result: ProposalInput = {
        ...structuredClone(recorded),
        pack,
        operations: structuredClone(recorded.operations.slice(from, to)),
        sources: structuredClone(recorded.sources.slice(from, to)),
      };
      if (options.currentRevisions) {
        const channel = session.channel;
        if (channel === null) throw new Error('the editor session is not connected');
        for (const operation of result.operations) {
          if (typeof operation.path !== 'string' || typeof operation.rev !== 'number') continue;
          const document = unwrap(await ops.get(channel, pack, operation.path));
          if (typeof document.rev !== 'number') throw new Error(`no revision for ${operation.path}`);
          operation.rev = document.rev;
        }
      }
      const proposal = await agent.propose(result, { program, plan: proposalOptions.plan, autoApply: proposalOptions.autoApply });
      return proposal.id;
    },
    connectBloodfangChat(paceMs = 0) {
      const script = bloodfangScript(pack);
      installChatProvider(createScriptedProvider(script, { paceMs }), script.prompts);
      return script.prompts;
    },
    disconnectChatProvider() {
      installChatProvider(null);
    },
  };
}
