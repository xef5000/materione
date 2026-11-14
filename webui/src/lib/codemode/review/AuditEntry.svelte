<script lang="ts">
  /**
   * One recent transaction from the server's audit (this session's, an earlier session's or another editor's): who, when,
   * where it came from, what approval the editor asserted, how many documents, the program text the server kept. Revert is
   * offered through the same preview-then-confirm flow; only the server's dry run can say whether it is still possible.
   */
  import Chip from '../../ui/Chip.svelte';
  import ProgramView from './ProgramView.svelte';
  import RevertControl from './RevertControl.svelte';
  import { documentCountOfAuditTarget, packOfAuditTarget } from './model';
  import type { AuditRow } from './state.svelte';

  interface Props {
    row: AuditRow;
    onReverted?: () => void;
  }

  let { row, onReverted }: Props = $props();

  const when = $derived(row.at === null ? 'time unknown' : new Date(row.at).toLocaleString());
  const pack = $derived(packOfAuditTarget(row.target));
  const count = $derived(documentCountOfAuditTarget(row.target));
  const isRevert = $derived(row.op === 'authoring.revertTransaction');
  const truncated = $derived(row.program !== null && row.programLength !== null && row.programLength > row.program.length ? row.programLength : null);
</script>

<article class="entry" aria-label={`${isRevert ? 'Revert' : 'Transaction'} ${row.transaction}`}>
  <header>
    <h4>{row.transaction}</h4>
    <Chip tone={isRevert ? 'neutral' : 'ok'}>{isRevert ? 'Revert' : 'Applied'}</Chip>
    <Chip>{row.origin}</Chip>
    {#if row.approval}<Chip tone={row.approval === 'granted' ? 'accent' : 'neutral'}>{row.approval === 'granted' ? 'Approved' : 'No approval needed'}</Chip>{/if}
  </header>
  <p class="meta">
    by <strong>{row.actor}</strong> &middot; {when}{pack ? ` · pack ${pack}` : ''}{count !== null ? ` · ${count} ${count === 1 ? 'document' : 'documents'}` : ''}
  </p>
  {#if row.program !== null}
    <ProgramView program={row.program} digest={row.digest} truncatedFrom={truncated} />
  {:else if row.digest}
    <p class="meta">No program text kept. Digest <code>{row.digest}</code></p>
  {/if}
  {#if !isRevert}
    <RevertControl transactionId={row.transaction} {pack} readPack={pack} revertible={null} {onReverted} />
  {/if}
</article>

<style>
  .entry {
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
  h4 {
    margin: 0;
    font-size: var(--text-md);
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .meta {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
    overflow-wrap: anywhere;
  }
</style>
