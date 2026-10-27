<script lang="ts">
  /**
   * A list of maps edited as rows, driven by the field's declared nested
   * shape. Without a shape the studio refuses to render — the field is
   * reported as Advanced-Files-only instead of being mangled.
   */
  import Icon from '../components/Icon.svelte';
  import { blankRow } from '../studio';
  import type { TypeParam } from '../api';

  interface Props {
    field: TypeParam;
    value: unknown;
    onChange: (value: unknown) => void;
    /** Hide the heading when a module card already names the field. */
    compact?: boolean;
    /** The add button's text when the heading is hidden ("Add seat"). */
    addLabel?: string;
  }

  let { field, value, onChange, compact = false, addLabel }: Props = $props();

  const columns = $derived(field.nested ?? []);
  const rows = $derived(
    (Array.isArray(value) ? value : []).map((row) =>
      row && typeof row === 'object' ? (row as Record<string, unknown>) : {},
    ),
  );

  function cellText(row: Record<string, unknown>, column: TypeParam): string {
    const cell = row[column.id];
    if (Array.isArray(cell)) return cell.join(', ');
    return cell === undefined || cell === null ? '' : String(cell);
  }

  function parse(column: TypeParam, raw: string): unknown {
    if (column.type === 'list') {
      return raw
        .split(',')
        .map((part) => part.trim())
        .filter(Boolean)
        .map((part) => (Number.isNaN(Number(part)) ? part : Number(part)));
    }
    if (column.type === 'integer' || column.type === 'decimal') return Number(raw) || 0;
    if (column.type === 'boolean') return raw === 'true';
    return raw;
  }

  function setCell(index: number, column: TypeParam, raw: string) {
    const next = rows.map((row) => ({ ...row }));
    next[index]![column.id] = parse(column, raw);
    onChange(next);
  }

  function addRow() {
    onChange([...rows.map((row) => ({ ...row })), blankRow(field)]);
  }

  function removeRow(index: number) {
    onChange(rows.filter((_, position) => position !== index).map((row) => ({ ...row })));
  }
</script>

<div class="structured">
  {#if !compact}<span class="label">{field.label || field.id}</span>{/if}

  {#if rows.length > 0}
    <div class="table" role="table" style="--cols:{columns.length}">
      <div class="head" role="row">
        {#each columns as column (column.id)}
          <span role="columnheader" title={column.description ?? ''}>
            {column.label || column.id}
          </span>
        {/each}
        <span aria-hidden="true"></span>
      </div>
      {#each rows as row, index (index)}
        <div class="row" role="row">
          {#each columns as column (column.id)}
            {#if column.allowedValues && column.allowedValues.length > 0}
              <select
                value={cellText(row, column)}
                onchange={(event) => setCell(index, column, event.currentTarget.value)}
              >
                {#each column.allowedValues as option (option)}
                  <option value={option}>{option}</option>
                {/each}
              </select>
            {:else}
              <input
                type="text"
                spellcheck="false"
                value={cellText(row, column)}
                placeholder={column.default ?? ''}
                onblur={(event) => setCell(index, column, event.currentTarget.value)}
              />
            {/if}
          {/each}
          <button type="button" class="remove" title="remove" aria-label="Remove row" onclick={() => removeRow(index)}>
            <Icon name="x" size={14} />
          </button>
        </div>
      {/each}
    </div>
  {/if}

  <button type="button" class="add" onclick={addRow}>
    <Icon name="plus" size={14} />
    {addLabel ?? (compact ? 'Add row' : (field.label || field.id).replace(/e?s$/i, ''))}
  </button>

  {#if field.description && !compact}<small>{field.description}</small>{/if}
</div>

<style>
  .structured {
    display: grid;
    gap: var(--space-2);
    margin-bottom: var(--space-4);
    min-width: 0;
  }
  .label {
    font-size: var(--text-sm);
    color: var(--muted);
  }
  .table {
    display: grid;
    gap: var(--space-1);
    min-width: 0;
  }
  .head,
  .row {
    display: grid;
    grid-template-columns: repeat(var(--cols, 4), minmax(0, 1fr)) 24px;
    gap: var(--space-1);
    align-items: center;
    min-width: 0;
  }
  .head {
    font-size: var(--text-xs);
    color: var(--faint);
  }
  .head span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  input,
  select {
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font: var(--text-sm) var(--mono);
    min-width: 0;
    width: 100%;
    box-sizing: border-box;
  }
  .remove,
  .add {
    background: none;
    border: 1px solid var(--border-strong);
    color: var(--muted);
    border-radius: var(--radius);
    cursor: pointer;
  }
  .remove {
    height: 24px;
    line-height: 1;
    font-size: var(--text-md);
  }
  .add {
    justify-self: start;
    padding: var(--space-1) var(--space-3);
    font-size: var(--text-sm);
  }
  .remove:hover,
  .add:hover {
    background: var(--bg-hover);
    color: var(--fg);
  }
  small {
    color: var(--faint);
    font-size: var(--text-xs);
  }
</style>
