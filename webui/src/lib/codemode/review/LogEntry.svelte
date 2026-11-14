<script lang="ts">
  /**
   * One applied transaction of this session, in the change log: origin, whether it needed approval, when, the documents
   * it wrote as diffs, the program (collapsed) and Revert. The summary is the agent's, shown apart and labelled.
   */
  import Chip from '../../ui/Chip.svelte';
  import type { ChangeLogEntry, Proposal } from '../host';
  import DocumentRow from './DocumentRow.svelte';
  import ProgramView from './ProgramView.svelte';
  import RevertControl from './RevertControl.svelte';
  import { documentViews, logDocumentViews } from './model';

  interface Props {
    entry: ChangeLogEntry;
    /** The proposal it came from (its operations and source positions label the program). */
    proposal: Proposal | undefined;
    onReverted?: () => void;
  }

  let { entry, proposal, onReverted }: Props = $props();

  const when = $derived(new Date(entry.appliedAt).toLocaleTimeString());
  const rows = $derived(logDocumentViews(entry));
  const reverted = $derived(entry.revertedBy !== null);
  // Reuse the proposal's document view so a log entry reads like the proposal did (new vs existing, diff, status).
  const views = $derived(
    proposal === undefined
      ? []
      : documentViews({ ...proposal, documents: entry.documents.map((d) => ({ path: d.path, kind: d.kind, text: d.text })), before: entry.before, needs: [] }),
  );
</script>

<article class="entry" aria-label={`Applied ${entry.transactionId}`}>
  <header>
    <h4>{entry.transactionId}</h4>
    <Chip tone={reverted ? 'neutral' : 'ok'}>{reverted ? 'Reverted' : 'Applied'}</Chip>
    <Chip>code-mode</Chip>
    <Chip tone={entry.approval === 'granted' ? 'accent' : 'neutral'}>
      {entry.approval === 'granted' ? 'Approved by you' : 'No approval needed: new content only'}
    </Chip>
    <span class="when">{when}</span>
  </header>
  {#if entry.summary}
    <aside class="agent" aria-label="The agent's summary">
      <p class="label">The agent&rsquo;s words, not checked by the editor</p>
      <p class="text">{entry.summary}</p>
    </aside>
  {/if}
  <section aria-label="Documents written">
    <h5>Documents written ({entry.documents.length})</h5>
    {#if views.length > 0}
      <ul class="docs">{#each views as view (view.path)}<DocumentRow {view} />{/each}</ul>
    {:else}
      <ul class="plain">{#each rows as row (row.document.path)}<li><code>{row.document.path}</code> {row.document.kind}</li>{/each}</ul>
    {/if}
  </section>
  <ProgramView program={entry.program} operations={proposal?.operations ?? []} sources={proposal?.sources ?? []} />
  {#if reverted}
    <p class="done"><Chip tone="ok">Reverted</Chip> by <code>{entry.revertedBy}</code></p>
  {:else}
    <RevertControl
      transactionId={entry.transactionId}
      pack={proposal?.pack ?? null}
      readPack={proposal?.pack ?? null}
      revertible={entry.revertible}
      note={entry.revertNote}
      refusals={entry.revertProblems}
      paths={entry.documents.map((document) => document.path)}
      {onReverted}
    />
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
  h4,
  h5 {
    margin: 0;
    font-size: var(--text-md);
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  h5 {
    font-size: var(--text-base);
    margin-bottom: var(--space-1);
  }
  .when {
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .agent {
    padding: var(--space-2) var(--space-3);
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-panel);
  }
  .label {
    margin: 0 0 var(--space-half);
    color: var(--faint);
    font-size: var(--text-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .text {
    margin: 0;
    font-style: italic;
    overflow-wrap: anywhere;
  }
  .docs,
  .plain {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--space-1);
  }
  .done {
    margin: 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    align-items: center;
  }
</style>
