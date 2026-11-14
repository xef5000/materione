<script lang="ts">
  /**
   * One proposal, as the owner reviews it (codemode.md section 5.6, D13). The facts (what it writes, per document, as a
   * diff; the findings by operation; the program with the line that staged each operation) come from the dry run and the
   * operation list. The agent's summary is shown apart, labelled as the agent's words. The buttons call the owner's actions
   * with the click that caused them; a stale, refused or failed proposal offers none (the agent writes a new program).
   */
  import Button from '../../ui/Button.svelte';
  import Chip from '../../ui/Chip.svelte';
  import Icon from '../../components/Icon.svelte';
  import type { Plan, Proposal } from '../host';
  import DocumentRow from './DocumentRow.svelte';
  import ProblemList from './ProblemList.svelte';
  import ProgramView from './ProgramView.svelte';
  import { documentViews, factLine, hasBlockingError, problemGroups, stateInfo } from './model';
  import { reviewActions } from './state.svelte';

  interface Props {
    proposal: Proposal;
    plan: Plan | null;
    /** Start expanded: a proposal that waits for the owner is the one to read. */
    expanded?: boolean;
  }

  let { proposal, plan, expanded = false }: Props = $props();
  // svelte-ignore state_referenced_locally
  let open = $state(expanded);
  let busy = $state(false);
  let notice = $state('');
  let programOpen = $state(false);
  let highlight = $state<number | null>(null);

  const approval = $derived(
    proposal.classification !== null && (proposal.classification.existing.length > 0 || proposal.classification.blockers.length > 0)
      ? ('granted' as const)
      : ('not_needed' as const),
  );
  const info = $derived(stateInfo({ ...proposal, approval }));
  const views = $derived(documentViews(proposal));
  const newDocs = $derived(views.filter((view) => view.origin === 'new'));
  const existingDocs = $derived(views.filter((view) => view.origin === 'existing'));
  const groups = $derived(problemGroups(proposal.problems, proposal.operations));
  const blockers = $derived(proposal.classification?.blockers ?? []);
  const waiting = $derived(proposal.state === 'waiting');
  const needsOwner = $derived(waiting && (proposal.needs.length > 0 || proposal.needsProposalApproval));
  const planOpen = $derived(plan !== null && !plan.approved && plan.objects.length > 0);
  const bodyId = $derived(`proposal-${proposal.id}-body`);
  const noteId = $derived(`proposal-${proposal.id}-grant`);

  async function run(task: () => Promise<{ done: boolean; message?: string }> | { done: boolean; message?: string }): Promise<void> {
    busy = true;
    notice = '';
    try {
      const result = await task();
      if (!result.done) notice = result.message ?? 'That did not go through.';
    } finally {
      busy = false;
    }
  }

  const approve = (event: MouseEvent): Promise<void> => run(() => reviewActions()?.approveProposal(event, proposal.id) ?? Promise.resolve({ done: false }));
  const approvePlan = (event: MouseEvent): Promise<void> =>
    run(() => (plan === null ? Promise.resolve({ done: false }) : (reviewActions()?.approvePlan(event, plan.id) ?? Promise.resolve({ done: false }))));
  const apply = (event: MouseEvent): Promise<void> => run(() => reviewActions()?.applyReady(event, proposal.id) ?? Promise.resolve({ done: false }));
  const reject = (event: MouseEvent): Promise<void> => run(() => reviewActions()?.reject(event, proposal.id) ?? { done: false });

  function showLine(line: number): void {
    programOpen = true;
    highlight = line;
  }
</script>

<article class="card" aria-labelledby={`proposal-${proposal.id}-title`}>
  <header>
    <button type="button" class="toggle" aria-expanded={open} aria-controls={bodyId} aria-label={`${open ? 'Collapse' : 'Expand'} ${proposal.id}`} onclick={() => (open = !open)}>
      <Icon name={open ? 'chevron-down' : 'chevron-right'} size={14} />
    </button>
    <h4 id={`proposal-${proposal.id}-title`}>{proposal.id}</h4>
    <Chip tone={info.tone}>{info.label}</Chip>
    <span class="meta">pack <code>{proposal.pack}</code>{proposal.plan ? ` · ${proposal.plan}` : ''}</span>
  </header>
  <p class="facts">{factLine(views)}</p>

  <div id={bodyId} hidden={!open} class="body">
    {#if open}
      <p class="state">{info.explanation}</p>

      {#if proposal.summary}
        <aside class="agent" aria-label="The agent's summary">
          <p class="agent-label">The agent&rsquo;s words, not checked by the editor</p>
          <p class="agent-text">{proposal.summary}</p>
        </aside>
      {/if}

      {#if plan !== null}
        <section class="plan" aria-label={`Plan ${plan.id}`}>
          <h5>Plan {plan.id}{plan.title ? `: ${plan.title}` : ''}</h5>
          {#if plan.objects.length === 0}
            <p class="note">The plan declares no existing objects: every program of it only creates content.</p>
          {:else}
            <p class="note">
              {plan.approved ? 'You approved these existing objects once for the whole plan:' : 'The plan names these existing objects. Approving the plan approves them all, once, across its programs:'}
            </p>
            <ul class="objects">
              {#each plan.objects as object (object)}<li><code>{object}</code></li>{/each}
            </ul>
          {/if}
        </section>
      {/if}

      <section aria-label="What it will write">
        <h5>What it will write</h5>
        {#if newDocs.length > 0}
          <p class="group">New content ({newDocs.length})</p>
          <ul class="docs">{#each newDocs as view (view.path)}<DocumentRow {view} />{/each}</ul>
        {/if}
        {#if existingDocs.length > 0}
          <p class="group">Changes to existing objects ({existingDocs.length})</p>
          <ul class="docs">{#each existingDocs as view (view.path)}<DocumentRow {view} expanded={waiting && view.needsApproval} />{/each}</ul>
        {/if}
        {#if views.length === 0}<p class="note">The dry run reported no documents.</p>{/if}
        {#if blockers.length > 0}
          <p class="group">Not understood by the editor</p>
          <ul class="blockers">
            {#each blockers as blocker, index (index)}
              <li>{blocker.operation === null ? 'A document of the report' : `Operation ${blocker.operation + 1}`}: {blocker.reason}</li>
            {/each}
          </ul>
        {/if}
      </section>

      {#if groups.length > 0}
        <section aria-label="Findings">
          <h5>Findings from the dry run</h5>
          <ProblemList {groups} onShowLine={showLine} />
        </section>
      {/if}

      <ProgramView program={proposal.program} operations={proposal.operations} sources={proposal.sources} bind:open={programOpen} {highlight} />

      {#if waiting && !hasBlockingError(proposal.problems)}
        <div class="actions" role="group" aria-label={`Decide on ${proposal.id}`}>
          {#if needsOwner}
            <Button variant="primary" disabled={busy} aria-describedby={noteId} onclick={approve}>
              {proposal.needsProposalApproval && proposal.needs.length === 0 ? 'Approve this proposal' : 'Approve and apply'}
            </Button>
            {#if planOpen && plan !== null}
              <Button disabled={busy} onclick={approvePlan}>Approve plan: {plan.objects.length} {plan.objects.length === 1 ? 'object' : 'objects'}, once</Button>
            {/if}
          {:else}
            <Button variant="primary" disabled={busy} onclick={apply}>Apply</Button>
          {/if}
          <Button variant="ghost" disabled={busy} onclick={reject}>Reject</Button>
        </div>
        {#if needsOwner}
          <p id={noteId} class="note">
            Approving {proposal.needs.length > 0 ? `${proposal.needs.length} existing ${proposal.needs.length === 1 ? 'object' : 'objects'}` : 'this proposal'} applies it now{proposal.needs.length > 0 ? ' and covers later changes to the same objects for the rest of this session' : ''}. Nothing else is approved.
          </p>
        {/if}
      {/if}
      {#if notice}<p class="notice" role="status">{notice}</p>{/if}
    {/if}
  </div>
</article>

<style>
  .card {
    display: grid;
    gap: var(--space-2);
    padding: var(--space-3);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: var(--bg-raised);
    min-width: 0;
  }
  header {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-1) var(--space-2);
  }
  h4,
  h5 {
    margin: 0;
    font-size: var(--text-md);
    font-weight: 600;
  }
  h5 {
    font-size: var(--text-base);
    margin-bottom: var(--space-1);
  }
  .toggle {
    flex: none;
    display: inline-flex;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: none;
    padding: var(--space-half) var(--space-1);
    cursor: pointer;
    color: var(--muted);
  }
  .toggle:hover {
    color: var(--fg);
  }
  .meta {
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .facts {
    margin: 0;
    font-size: var(--text-base);
  }
  .body {
    display: grid;
    gap: var(--space-3);
  }
  .state {
    margin: 0;
    color: var(--muted);
  }
  .agent {
    padding: var(--space-2) var(--space-3);
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-panel);
  }
  .agent-label {
    margin: 0 0 var(--space-half);
    color: var(--faint);
    font-size: var(--text-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .agent-text {
    margin: 0;
    font-style: italic;
    overflow-wrap: anywhere;
  }
  .plan {
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-panel);
  }
  .note {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .objects,
  .docs,
  .blockers {
    list-style: none;
    margin: var(--space-1) 0 0;
    padding: 0;
    display: grid;
    gap: var(--space-1);
  }
  .objects code {
    font-family: var(--mono);
    font-size: var(--text-sm);
    overflow-wrap: anywhere;
  }
  .group {
    margin: var(--space-2) 0 var(--space-1);
    color: var(--muted);
    font-size: var(--text-sm);
    font-weight: 600;
  }
  .blockers li {
    padding: var(--space-1) var(--space-2);
    border-left: 3px solid var(--warn);
    background: var(--warn-bg);
    border-radius: var(--radius-sm);
    font-size: var(--text-base);
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .notice {
    margin: 0;
    color: var(--warn);
    font-size: var(--text-base);
  }
</style>
