/**
 * The browser harness's way to put a proposal in front of the review panel (issue 347): a recorded corpus program, as the
 * sandbox sealed it (`fixtures/golden/codemode/ops/<name>.json`) with its program text (`fixtures/codemode/corpus/<name>.ts`),
 * handed to the agent port of the review store. Nothing produces proposals in the product yet (the chat is issue 348), so
 * this is a harness seam: it lives under `browser/`, is not part of the editor build, and reaches only `propose` and
 * `declarePlan`, the same two calls the chat tool will get. It cannot approve anything.
 */
import type { ProposalInput } from '../src/lib/codemode/host';
import { reviewAgentPort } from '../src/lib/codemode/review/state.svelte';
import type { CodemodeHarness } from './contract';

const sealedFiles = import.meta.glob('../../fixtures/golden/codemode/ops/*.json', { eager: true, import: 'default' }) as Record<string, unknown>;
const programFiles = import.meta.glob('../../fixtures/codemode/corpus/*.ts', { eager: true, query: '?raw', import: 'default' }) as Record<string, string>;

const nameOf = (path: string): string => /([^/]+)\.(json|ts)$/.exec(path)?.[1] ?? path;
const sealed = new Map(Object.entries(sealedFiles).map(([path, value]) => [nameOf(path), value as ProposalInput]));
const programs = new Map(Object.entries(programFiles).map(([path, text]) => [nameOf(path), text]));

export function createCodemodeHarness(pack: string): CodemodeHarness {
  return {
    programs: () => [...sealed.keys()].sort(),
    declarePlan(objects, title) {
      const agent = reviewAgentPort();
      if (agent === null) throw new Error('the review session is not connected');
      return agent.declarePlan({ title, objects }).id;
    },
    async propose(name, options = {}) {
      const agent = reviewAgentPort();
      if (agent === null) throw new Error('the review session is not connected');
      const recorded = sealed.get(name);
      const program = programs.get(name);
      if (recorded === undefined || program === undefined) throw new Error(`no recorded corpus program '${name}'`);
      const [from, to] = options.slice ?? [0, recorded.operations.length];
      const result: ProposalInput = {
        ...structuredClone(recorded),
        pack,
        operations: structuredClone(recorded.operations.slice(from, to)),
        sources: structuredClone(recorded.sources.slice(from, to)),
      };
      const proposal = await agent.propose(result, { program, plan: options.plan, autoApply: options.autoApply });
      return proposal.id;
    },
  };
}
