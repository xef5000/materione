<script lang="ts">
  /**
   * The review panel (issue 347, codemode.md section 5.6, D13's human-review step): proposals the agent made and what each
   * would write, and the change log of what applied and how to revert it. A side panel of the workbench, present only when
   * the server advertises code mode. It holds no state of its own beyond what is open: the store is session memory, and
   * nothing here is saved anywhere (D1).
   */
  import { onMount, tick } from 'svelte';
  import Button from '../../ui/Button.svelte';
  import Chip from '../../ui/Chip.svelte';
  import Icon from '../../components/Icon.svelte';
  import AuditEntry from './AuditEntry.svelte';
  import LogEntry from './LogEntry.svelte';
  import ProposalCard from './ProposalCard.svelte';
  import { isOpen } from './model';
  import { loadAudit, review, reviewActions } from './state.svelte';

  interface Props {
    /** Closes the panel; the caller returns focus to the control that opened it. */
    onClose: () => void;
  }

  let { onClose }: Props = $props();

  let heading = $state<HTMLElement | null>(null);
  let planNotice = $state('');

  const proposals = $derived([...review.snapshot.proposals].reverse());
  const log = $derived([...review.snapshot.log].reverse());
  const plans = $derived(review.snapshot.plans);
  const loggedIds = $derived(new Set(review.snapshot.log.map((entry) => entry.transactionId)));
  const earlier = $derived(review.audit.filter((row) => !loggedIds.has(row.transaction)));
  const open = $derived(proposals.filter((proposal) => isOpen(proposal.state)).length);

  onMount(() => {
    // Focus moves in only when the owner asked for the panel; a panel that opens itself for a proposal moves nothing.
    if (review.focusOnOpen) {
      review.focusOnOpen = false;
      void tick().then(() => heading?.focus());
    }
  });

  $effect(() => {
    // The log tab shows the audit: read it when the tab is shown, and again when the session's own log changes.
    if (review.tab === 'log') {
      void review.snapshot.log.length;
      void loadAudit();
    }
  });

  // Escape closes the panel when focus is inside it, or nowhere (a button that was just used can vanish with its action).
  function onKey(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || event.defaultPrevented) return;
    const active = document.activeElement;
    const inside = active !== null && document.getElementById('review-panel')?.contains(active) === true;
    if (inside || active === null || active === document.body) {
      event.preventDefault();
      onClose();
    }
  }

  function onTabKey(event: KeyboardEvent): void {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight' && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();
    review.tab = event.key === 'ArrowLeft' || event.key === 'Home' ? 'proposals' : 'log';
    void tick().then(() => document.getElementById(`review-tab-${review.tab}`)?.focus());
  }

  async function approvePlan(event: MouseEvent, id: string): Promise<void> {
    planNotice = '';
    const result = await reviewActions()?.approvePlan(event, id);
    if (result !== undefined && !result.done) planNotice = result.message;
  }
</script>

<svelte:window onkeydown={onKey} />

<section id="review-panel" class="panel" aria-labelledby="review-title">
  <div class="head">
    <h2 id="review-title" tabindex="-1" bind:this={heading}>Review</h2>
    {#if review.waiting > 0}<Chip tone="warn">{review.waiting} to approve</Chip>{/if}
    <span class="spacer"></span>
    <button type="button" class="close" aria-label="Close Review" title="Close Review" onclick={onClose}><Icon name="x" size={14} /></button>
  </div>

  <p class="sr" role="status" aria-live="polite">{review.announcement}</p>

  <div class="tabs" role="tablist" aria-label="Review sections" tabindex="-1" onkeydown={onTabKey}>
    <button
      type="button"
      role="tab"
      id="review-tab-proposals"
      aria-selected={review.tab === 'proposals'}
      aria-controls="review-tabpanel"
      tabindex={review.tab === 'proposals' ? 0 : -1}
      onclick={() => (review.tab = 'proposals')}
    >Proposals ({proposals.length})</button>
    <button
      type="button"
      role="tab"
      id="review-tab-log"
      aria-selected={review.tab === 'log'}
      aria-controls="review-tabpanel"
      tabindex={review.tab === 'log' ? 0 : -1}
      onclick={() => (review.tab = 'log')}
    >Change log ({log.length})</button>
  </div>

  <div id="review-tabpanel" class="content" role="tabpanel" aria-labelledby={`review-tab-${review.tab}`}>
    {#if review.tab === 'proposals'}
      {#if plans.some((plan) => plan.objects.length > 0)}
        <section class="plans" aria-label="Plans">
          <h3>Plans</h3>
          {#each plans.filter((plan) => plan.objects.length > 0) as plan (plan.id)}
            <div class="plan">
              <p class="plan-title">Plan {plan.id}{plan.title ? `: ${plan.title}` : ''} <Chip tone={plan.approved ? 'ok' : 'warn'}>{plan.approved ? 'Approved once' : 'Not approved'}</Chip></p>
              <ul>{#each plan.objects as object (object)}<li><code>{object}</code></li>{/each}</ul>
              {#if !plan.approved}
                <Button size="sm" onclick={(event) => approvePlan(event, plan.id)}>
                  Approve plan: {plan.objects.length} {plan.objects.length === 1 ? 'object' : 'objects'}, once
                </Button>
              {/if}
            </div>
          {/each}
          {#if planNotice}<p class="notice" role="status">{planNotice}</p>{/if}
        </section>
      {/if}
      {#if proposals.length === 0}
        <p class="empty">
          No proposals yet. What the agent proposes appears here with exactly what it would write. Content that only creates something new applies on its own and is listed; changes to anything that already exists wait for your approval.
        </p>
      {:else}
        <p class="count">{open} open, {proposals.length - open} settled.</p>
      {/if}
      <div class="list">
        {#each proposals as proposal (proposal.id)}
          <ProposalCard {proposal} plan={plans.find((plan) => plan.id === proposal.plan) ?? null} expanded={proposal.state === 'waiting'} />
        {/each}
      </div>
    {:else}
      <section aria-label="This session">
        <h3>This session</h3>
        {#if log.length === 0}
          <p class="empty">Nothing has been applied in this session yet.</p>
        {/if}
        <div class="list">
          {#each log as entry (entry.transactionId)}
            <LogEntry {entry} proposal={proposals.find((proposal) => proposal.id === entry.proposal)} onReverted={() => void loadAudit()} />
          {/each}
        </div>
      </section>
      <section aria-label="Earlier changes">
        <div class="section-head">
          <h3>Recent transactions on the server</h3>
          <Button size="sm" variant="ghost" onclick={() => void loadAudit()}>Refresh</Button>
        </div>
        {#if review.auditStatus === 'loading' && review.audit.length === 0}
          <p class="empty" role="status">Reading the audit&hellip;</p>
        {:else if review.auditStatus === 'error'}
          <p class="empty error" role="status">The audit could not be read: {review.auditError}</p>
        {:else if earlier.length === 0}
          <p class="empty">No other recent transactions in the audit.</p>
        {/if}
        <div class="list">
          {#each earlier as row (row.key)}
            <AuditEntry {row} onReverted={() => void loadAudit()} />
          {/each}
        </div>
      </section>
    {/if}
  </div>
</section>

<style>
  .panel {
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    border-left: 1px solid var(--border);
    background: var(--bg-panel);
  }
  .head {
    display: flex;
    flex: none;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  h2 {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: 600;
  }
  h2:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
  h3 {
    margin: 0;
    font-size: var(--text-base);
    font-weight: 600;
    color: var(--muted);
  }
  .spacer {
    margin-left: auto;
  }
  .close {
    flex: none;
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--muted);
    padding: var(--space-half) var(--space-2);
    cursor: pointer;
  }
  .close:hover {
    color: var(--fg);
  }
  .sr {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
    margin: 0;
  }
  .tabs {
    display: flex;
    flex: none;
    gap: var(--space-1);
    padding: var(--space-1) var(--space-3) 0;
    border-bottom: 1px solid var(--border);
  }
  [role='tab'] {
    border: 1px solid transparent;
    border-bottom: 2px solid transparent;
    border-radius: var(--radius-sm) var(--radius-sm) 0 0;
    background: none;
    color: var(--muted);
    padding: var(--space-1) var(--space-3);
    cursor: pointer;
    font-size: var(--text-base);
  }
  [role='tab']:hover {
    color: var(--fg);
  }
  [role='tab'][aria-selected='true'] {
    color: var(--accent);
    border-bottom-color: var(--accent);
    font-weight: 600;
  }
  .content {
    flex: 1 1 0;
    min-height: 0;
    overflow-y: auto;
    overflow-x: hidden;
    padding: var(--space-3);
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    align-content: start;
    gap: var(--space-3);
  }
  .list {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-3);
  }
  .empty,
  .count {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-base);
  }
  .empty.error {
    color: var(--error);
  }
  .plans {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-2);
  }
  .plan {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-1);
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-raised);
  }
  .plan-title {
    margin: 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    align-items: center;
  }
  .plan ul {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .plan code {
    font-family: var(--mono);
    font-size: var(--text-sm);
    overflow-wrap: anywhere;
  }
  .section-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
    margin-bottom: var(--space-2);
  }
  .notice {
    margin: 0;
    color: var(--warn);
  }
</style>
