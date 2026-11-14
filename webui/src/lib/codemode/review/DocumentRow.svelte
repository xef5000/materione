<script lang="ts">
  /**
   * One document the dry run reports: its path, what happens to it (in words), whether it is new or an existing object,
   * whether it needs the owner's approval, and a disclosure for its diff. Facts only: nothing the agent said is read here.
   */
  import Chip from '../../ui/Chip.svelte';
  import Icon from '../../components/Icon.svelte';
  import DiffView from './DiffView.svelte';
  import type { DocumentView } from './model';

  interface Props {
    view: DocumentView;
    /** Start with the diff shown (a document that needs approval is the one to read). */
    expanded?: boolean;
  }

  let { view, expanded = false }: Props = $props();
  // svelte-ignore state_referenced_locally
  let open = $state(expanded);

  const KIND: Record<string, string> = {
    created: 'Creates',
    changed: 'Changes',
    deleted: 'Deletes',
    moved: 'Moves to',
    unchanged: 'Leaves as is',
  };
  const verb = $derived(KIND[view.kind] ?? view.kind);
  const panelId = $derived(`doc-${view.object.replace(/[^a-z0-9]+/gi, '-')}`);
</script>

<li class="doc">
  <div class="head">
    <button
      type="button"
      class="toggle"
      aria-expanded={open}
      aria-controls={panelId}
      aria-label={`${open ? 'Hide' : 'Show'} changes to ${view.path}`}
      onclick={() => (open = !open)}
    >
      <Icon name={open ? 'chevron-down' : 'chevron-right'} size={14} />
    </button>
    <span class="verb">{verb}</span>
    <code class="path">{view.path}</code>
    {#if view.diff && (view.diff.added > 0 || view.diff.removed > 0)}
      <span class="counts" aria-label={`${view.diff.added} lines added, ${view.diff.removed} lines removed`}>
        <span class="plus">+{view.diff.added}</span>
        <span class="minus">&minus;{view.diff.removed}</span>
      </span>
    {/if}
    {#if view.origin === 'existing'}
      {#if view.needsApproval}
        <Chip tone="warn">Needs approval</Chip>
      {:else}
        <Chip tone="ok">Approved</Chip>
      {/if}
    {:else}
      <Chip>New</Chip>
    {/if}
  </div>
  {#if view.movedFrom}
    <p class="note">Moved from <code>{view.movedFrom}</code></p>
  {/if}
  <div id={panelId} hidden={!open}>
    {#if open}
      {#if view.diff}
        <DiffView diff={view.diff} label={view.path} />
      {:else}
        {#if view.diffNote}<p class="note">{view.diffNote}</p>{/if}
        {#if view.text !== null}
          <!-- a scrollable region must be reachable by keyboard -->
          <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
          <pre class="plain" tabindex="0" aria-label={`Resulting text of ${view.path}`}>{view.text}</pre>
        {/if}
      {/if}
    {/if}
  </div>
</li>

<style>
  .doc {
    list-style: none;
    display: grid;
    gap: var(--space-1);
    padding: var(--space-2);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: var(--bg-raised);
  }
  .head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-1) var(--space-2);
    min-width: 0;
  }
  .toggle {
    flex: none;
    display: inline-flex;
    align-items: center;
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
  .verb {
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .path {
    min-width: 0;
    overflow-wrap: anywhere;
    font-family: var(--mono);
    font-size: var(--text-sm);
  }
  .counts {
    display: inline-flex;
    gap: var(--space-1);
    font-family: var(--mono);
    font-size: var(--text-xs);
  }
  .plus {
    color: var(--ok);
  }
  .minus {
    color: var(--error);
  }
  .note {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .plain {
    margin: 0;
    padding: var(--space-2);
    max-height: 240px;
    overflow: auto;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--bg-input);
    color: var(--fg-code);
    font-family: var(--mono);
    font-size: var(--text-xs);
  }
</style>
