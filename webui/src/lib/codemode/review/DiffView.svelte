<script lang="ts">
  /**
   * One document's line diff: what the dry run would write against what is stored. Every row says what it is in text
   * (a sign and a word for assistive technology), not by colour alone, and long unchanged stretches fold.
   */
  import { blocks, type Diff, type DiffRow } from './diff';

  interface Props {
    diff: Diff;
    /** Names the region for assistive technology, e.g. the file path. */
    label: string;
  }

  let { diff, label }: Props = $props();

  /** Past this many rows a very long document is cut, with a button for the rest. */
  const ROW_LIMIT = 240;
  let showAll = $state(false);
  let unfolded = $state<Record<number, boolean>>({});

  type Item = { type: 'row'; row: DiffRow } | { type: 'fold'; id: number; hidden: number };

  const items = $derived.by(() => {
    const list: Item[] = [];
    blocks(diff).forEach((part, id) => {
      if (part.kind === 'rows' || unfolded[id]) for (const row of part.rows) list.push({ type: 'row', row });
      else list.push({ type: 'fold', id, hidden: part.hidden });
    });
    return list;
  });
  const cut = $derived(!showAll && items.length > ROW_LIMIT);
  const shown = $derived(cut ? items.slice(0, ROW_LIMIT) : items);
  const changed = $derived(diff.added + diff.removed);

  const SIGN = { add: '+', del: '−', same: ' ' } as const;
  const WORD = { add: 'added', del: 'removed', same: 'unchanged' } as const;
</script>

<!-- a scrollable region must be reachable by keyboard -->
<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<div class="diff" role="group" tabindex="0" aria-label={`Changes to ${label}: ${diff.added} added, ${diff.removed} removed`}>
  {#if changed === 0}
    <p class="none">No lines change.</p>
  {/if}
  {#each shown as item, index (index)}
    {#if item.type === 'fold'}
      <button type="button" class="fold" onclick={() => (unfolded[item.id] = true)}>
        Show {item.hidden} unchanged {item.hidden === 1 ? 'line' : 'lines'}
      </button>
    {:else}
      <div class={`row ${item.row.kind}`}>
        <span class="no" aria-hidden="true">{item.row.after ?? item.row.before}</span>
        <span class="sign" aria-hidden="true">{SIGN[item.row.kind]}</span>
        <span class="sr">{WORD[item.row.kind]}: </span>
        <code>{item.row.text === '' ? ' ' : item.row.text}</code>
      </div>
    {/if}
  {/each}
  {#if cut}
    <button type="button" class="fold" onclick={() => (showAll = true)}>Show all {items.length} lines</button>
  {/if}
</div>

<style>
  .diff {
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--bg-input);
    font-family: var(--mono);
    font-size: var(--text-xs);
    line-height: 1.5;
    overflow-x: auto;
  }
  .row {
    display: flex;
    gap: var(--space-2);
    min-width: max-content;
    padding: 0 var(--space-2);
  }
  .row.add {
    background: var(--ok-bg);
  }
  .row.del {
    background: var(--error-bg);
  }
  .no {
    flex: none;
    min-width: 3ch;
    text-align: right;
    color: var(--faint);
    user-select: none;
  }
  .sign {
    flex: none;
    width: 1ch;
    font-weight: 700;
    user-select: none;
  }
  .row.add .sign {
    color: var(--ok);
  }
  .row.del .sign {
    color: var(--error);
  }
  code {
    white-space: pre;
    color: var(--fg-code);
  }
  .sr {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .fold {
    display: block;
    width: 100%;
    border: none;
    border-top: 1px dashed var(--border-strong);
    border-bottom: 1px dashed var(--border-strong);
    background: var(--bg-panel);
    color: var(--muted);
    padding: var(--space-half) var(--space-2);
    font: inherit;
    text-align: left;
    cursor: pointer;
  }
  .fold:hover {
    color: var(--fg);
  }
  .none {
    margin: 0;
    padding: var(--space-1) var(--space-2);
    color: var(--muted);
    font-family: var(--font-sans);
  }
</style>
