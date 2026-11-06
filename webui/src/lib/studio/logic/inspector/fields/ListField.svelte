<script lang="ts">
  /**
   * A parameter that holds several values (logic.md section 7.3): `layer: [buy, sell]`, a list of permissions, a list
   * of commands. One row per value, each edited as the element's own field (a template, or the item browser); rows are
   * added, removed and reordered, and the whole list is written back as one edit. An empty list removes the key.
   */
  import Icon from '../../../../components/Icon.svelte';
  import type { FieldKind } from '../../../../logic/params';
  import type { NodeFlag } from '../../../../logic/verdicts';
  import ItemField from './ItemField.svelte';
  import TemplateField from './TemplateField.svelte';

  interface Props {
    value: unknown;
    label: string;
    element: FieldKind;
    id?: string;
    disabled?: boolean;
    suggestions?: string[];
    flags?: NodeFlag[];
    onchange: (value: unknown) => void;
    ondraft?: (value: unknown) => void;
  }

  let { value, label, element, id, disabled = false, suggestions = [], flags = [], onchange, ondraft }: Props = $props();

  const rows = $derived<unknown[]>(Array.isArray(value) ? value : value === undefined ? [] : [value]);

  function commit(next: unknown[]): void {
    onchange(next.length === 0 ? undefined : next);
  }

  function replace(index: number, item: unknown): void {
    commit(rows.map((row, at) => (at === index ? item : row)));
  }

  function draft(index: number, item: unknown): void {
    ondraft?.(rows.map((row, at) => (at === index ? item : row)));
  }

  function remove(index: number): void {
    commit(rows.filter((_, at) => at !== index));
  }

  function move(index: number, delta: number): void {
    const to = index + delta;
    if (to < 0 || to >= rows.length) return;
    const next = [...rows];
    const [moving] = next.splice(index, 1);
    next.splice(to, 0, moving);
    commit(next);
  }
</script>

<div class="list" role="group" aria-label={label} {id}>
  {#each rows as row, index (index)}
    <div class="row" data-row={index}>
      <div class="cell">
        {#if element === 'item'}
          <ItemField value={row} label="{label} {index + 1}" {disabled} onchange={(next) => replace(index, next)} />
        {:else}
          <TemplateField
            label="{label} {index + 1}"
            value={typeof row === 'string' ? row : typeof row === 'number' || typeof row === 'boolean' ? String(row) : JSON.stringify(row)}
            {disabled}
            {suggestions}
            flags={index === 0 ? flags : []}
            onchange={(next) => replace(index, next)}
            ondraft={(next) => draft(index, next)}
          />
        {/if}
      </div>
      <div class="tools">
        <button type="button" aria-label="Move {label} {index + 1} up" title="Move up" disabled={disabled || index === 0} onclick={() => move(index, -1)}><Icon name="chevron-up" size={13} /></button>
        <button type="button" class="down" aria-label="Move {label} {index + 1} down" title="Move down" disabled={disabled || index === rows.length - 1} onclick={() => move(index, 1)}><Icon name="chevron-down" size={13} /></button>
        <button type="button" aria-label="Remove {label} {index + 1}" title="Remove" {disabled} onclick={() => remove(index)}><Icon name="x" size={13} /></button>
      </div>
    </div>
  {/each}
  <button type="button" class="add" {disabled} onclick={() => commit([...rows, ''])}>
    <Icon name="plus" size={13} /> Add {rows.length === 0 ? 'a value' : 'another'}
  </button>
</div>

<style>
  .list {
    display: grid;
    gap: var(--space-1);
    min-width: 0;
  }
  .row {
    display: flex;
    align-items: flex-start;
    gap: var(--space-1);
  }
  .cell {
    flex: 1;
    min-width: 0;
  }
  .tools {
    display: flex;
    flex: none;
    gap: 2px;
  }
  .tools button {
    display: inline-grid;
    place-items: center;
    width: 24px;
    height: 28px;
    padding: 0;
    background: none;
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    color: var(--muted);
    cursor: pointer;
  }
  .tools button:hover:not(:disabled) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .tools button:disabled {
    opacity: 0.3;
    cursor: default;
  }
  .add {
    justify-self: start;
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: var(--space-half) var(--space-3);
    background: none;
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--accent);
    font-size: var(--text-sm);
    cursor: pointer;
  }
</style>
