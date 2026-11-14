<script lang="ts">
  /**
   * Revert, in two steps the owner can see: a preview (the server's dry run of the revert: which documents it would
   * restore, as a diff against what is stored now, or why it is refused, per document) and then a confirmation. Only the
   * confirmation writes, and only from a real click. A transaction that cannot be reverted says why and offers nothing.
   */
  import Button from '../../ui/Button.svelte';
  import Chip from '../../ui/Chip.svelte';
  import DiffView from './DiffView.svelte';
  import ProblemList from './ProblemList.svelte';
  import { diffText, type Diff } from './diff';
  import { problemGroups } from './model';
  import type { Problem, RevertPreview } from '../host';
  import { readStored, reviewActions } from './state.svelte';

  interface Props {
    transactionId: string;
    /** The pack the transaction wrote to (the audit names it), for stale marking; null when unknown. */
    pack: string | null;
    /** True or false when the server said at apply time; null when only a dry run can tell (an audit row). */
    revertible: boolean | null;
    /** The server's reason it cannot be reverted. */
    note?: string | null;
    /** The refusal of the last confirmed attempt, kept by the store for this session's entries. */
    refusals?: readonly Problem[];
    /** The transaction's documents in report order: an operation index of a revert refusal names one of them. */
    paths?: readonly string[];
    /** The pack of the transaction, for reading what is stored now. */
    readPack?: string | null;
    /** Called once a revert went through, so lists refresh. */
    onReverted?: () => void;
  }

  let { transactionId, pack, revertible, note = null, refusals = [], paths = [], readPack = null, onReverted }: Props = $props();

  type Step = 'idle' | 'previewing' | 'preview' | 'confirming' | 'done';
  let step = $state<Step>('idle');
  let preview = $state.raw<RevertPreview | null>(null);
  let diffs = $state.raw<{ path: string; kind: string; diff: Diff | null }[]>([]);
  let notice = $state('');
  let refused = $state.raw<readonly Problem[]>([]);

  const shownRefusals = $derived(refused.length > 0 ? refused : refusals);

  async function showPreview(): Promise<void> {
    const actions = reviewActions();
    if (actions === null) return;
    step = 'previewing';
    notice = '';
    refused = [];
    const result = await actions.previewRevert(transactionId);
    preview = result;
    const rows: { path: string; kind: string; diff: Diff | null }[] = [];
    if (result.accepted) {
      for (const document of result.documents) {
        const from = readPack === null ? null : await readStored(readPack, document.path);
        const unknown = document.kind !== 'created' && readPack === null;
        rows.push({
          path: document.path,
          kind: document.kind,
          // current text against the text the revert restores; with no way to read the current one, no diff is drawn
          diff: unknown || (document.kind !== 'created' && from === null) ? null : diffText(from, document.text),
        });
      }
    }
    diffs = rows;
    step = 'preview';
  }

  async function confirm(event: MouseEvent): Promise<void> {
    const actions = reviewActions();
    if (actions === null) return;
    step = 'confirming';
    const result = await actions.confirmRevert(event, transactionId, pack);
    if (!result.done) {
      notice = result.message;
      step = 'preview';
      return;
    }
    if (result.value.reverted) {
      step = 'done';
      notice = '';
      onReverted?.();
    } else {
      refused = result.value.problems;
      step = 'preview';
      preview = { transactionId, accepted: false, documents: [], problems: result.value.problems };
    }
  }

  function cancel(): void {
    step = 'idle';
    preview = null;
    notice = '';
  }

  const groups = $derived(preview === null ? [] : problemGroups(preview.problems, []));
  const refusalGroups = $derived(problemGroups(shownRefusals, []));
  function pathOf(operation: number | null): string | null {
    return operation === null ? null : (paths[operation] ?? null);
  }
</script>

<div class="revert">
  {#if revertible === false}
    <p class="cannot">
      <Chip tone="warn">Cannot be reverted</Chip>
      <span>{note ?? 'The server did not keep what a revert needs for this transaction.'}</span>
    </p>
  {:else if step === 'idle'}
    <Button size="sm" onclick={showPreview}>Revert&hellip;</Button>
    {#if shownRefusals.length > 0}
      <div class="why" role="group" aria-label="Why the last revert was refused">
        <p class="lead"><Chip tone="error">Revert refused</Chip> Nothing was written.</p>
        <ul>
          {#each shownRefusals as problem, index (index)}
            <li>
              {#if pathOf(problem.operation ?? null)}<code>{pathOf(problem.operation ?? null)}</code>{/if}
              {#if problem.code}<code class="code">{problem.code}</code>{/if}
              {problem.message}
            </li>
          {/each}
        </ul>
      </div>
    {/if}
  {:else if step === 'previewing'}
    <p class="status" role="status">Checking what a revert would do&hellip;</p>
  {:else if step === 'preview' || step === 'confirming'}
    <div class="preview" role="group" aria-label="Revert preview">
      {#if preview?.accepted}
        <p class="lead">
          A revert would restore {preview.documents.length} {preview.documents.length === 1 ? 'document' : 'documents'}. Nothing is written until you confirm.
        </p>
        <ul class="docs">
          {#each diffs as row (row.path)}
            <li>
              <p class="path"><code>{row.path}</code> <span class="kind">{row.kind === 'deleted' ? 'would be deleted' : row.kind === 'created' ? 'would be restored' : 'would go back'}</span></p>
              {#if row.diff}
                <DiffView diff={row.diff} label={row.path} />
              {:else}
                <p class="note">The text stored now could not be read, so the lines are not compared.</p>
              {/if}
            </li>
          {/each}
        </ul>
        <div class="actions">
          <Button variant="danger" size="sm" disabled={step === 'confirming'} onclick={confirm}>
            {step === 'confirming' ? 'Reverting…' : 'Confirm revert'}
          </Button>
          <Button variant="ghost" size="sm" disabled={step === 'confirming'} onclick={cancel}>Cancel</Button>
        </div>
      {:else}
        <p class="lead"><Chip tone="error">Revert refused</Chip> The server will not revert this, and nothing was written.</p>
        {#if (refused.length > 0 ? refusalGroups : groups).length > 0}
          <ul class="refusals">
            {#each (refused.length > 0 ? refused : (preview?.problems ?? [])) as problem, index (index)}
              <li>
                {#if pathOf(problem.operation ?? null)}<code>{pathOf(problem.operation ?? null)}</code>{/if}
                {#if problem.code}<code class="code">{problem.code}</code>{/if}
                <span>{problem.message}</span>
                {#if problem.hint}<span class="note">{problem.hint}</span>{/if}
              </li>
            {/each}
          </ul>
        {/if}
        <div class="actions"><Button variant="ghost" size="sm" onclick={cancel}>Close</Button></div>
      {/if}
      {#if notice}<p class="status" role="status">{notice}</p>{/if}
    </div>
  {:else}
    <p class="status" role="status"><Chip tone="ok">Reverted</Chip> Each document went back to the text it had before.</p>
  {/if}
</div>

<style>
  .revert {
    display: grid;
    gap: var(--space-2);
  }
  .cannot,
  .lead {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    margin: 0;
    font-size: var(--text-base);
  }
  .status {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-base);
  }
  .preview,
  .why {
    display: grid;
    gap: var(--space-2);
    padding: var(--space-2);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-panel);
  }
  .docs,
  .refusals,
  .why ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--space-2);
  }
  .refusals li,
  .why li {
    padding: var(--space-1) var(--space-2);
    border-left: 3px solid var(--error);
    background: var(--error-bg);
    border-radius: var(--radius-sm);
    font-size: var(--text-base);
    overflow-wrap: anywhere;
  }
  .path {
    margin: 0 0 var(--space-1);
    font-size: var(--text-sm);
  }
  .kind,
  .note {
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .note {
    margin: 0;
    display: block;
  }
  .code {
    font-family: var(--mono);
    font-size: var(--text-xs);
    color: var(--muted);
  }
  .actions {
    display: flex;
    gap: var(--space-2);
  }
</style>
