<script lang="ts">
  /**
   * A list of item references as chips with icons and a count stepper. The
   * count is the same reference repeated in the file (the drops format has no
   * count key), so every edit writes a plain list of strings. Hand-authored
   * entries that are not strings are not chips; the note says so.
   */
  import Icon from '../components/Icon.svelte';
  import ItemIcon from './ItemIcon.svelte';
  import ItemPicker from './ItemPicker.svelte';
  import { countedItems, expandCounted, itemLabel, opaqueEntries } from '../items';

  interface Props {
    value: unknown;
    pack: string;
    label: string;
    /** Show the count stepper (drops). */
    counts?: boolean;
    onChange: (value: unknown) => void;
  }

  let { value, pack, label, counts = true, onChange }: Props = $props();

  const items = $derived(countedItems(value));
  const opaque = $derived(opaqueEntries(value));

  /** Entries the chips cannot show ride along unchanged after the chips. */
  function commit(next: { value: string; count: number }[]) {
    onChange([...expandCounted(next), ...opaque]);
  }

  function add(reference: string) {
    if (!reference) return;
    const existing = items.find((item) => item.value === reference);
    commit(existing
      ? items.map((item) => (item === existing ? { ...item, count: item.count + 1 } : item))
      : [...items, { value: reference, count: 1 }]);
  }

  function step(index: number, delta: number) {
    commit(items
      .map((item, position) => (position === index ? { ...item, count: item.count + delta } : item))
      .filter((item) => item.count > 0));
  }

  function remove(index: number) {
    commit(items.filter((_, position) => position !== index));
  }
</script>

<div class="chips">
  {#if opaque.length > 0}
    <p class="note">This list has {opaque.length} entr{opaque.length === 1 ? 'y' : 'ies'} the chips can't show; edit {opaque.length === 1 ? 'it' : 'them'} in Files. {opaque.length === 1 ? 'It is' : 'They are'} kept as written.</p>
  {/if}
  <ul aria-label={label}>
    {#each items as item, index (item.value)}
      <li class="chip-item" data-item={item.value}>
        <ItemIcon value={item.value} {pack} size={24} />
        <span class="name" title={item.value}>{itemLabel(item.value, pack)}</span>
        {#if counts}
          <span class="count">
            <button type="button" aria-label={`Fewer ${itemLabel(item.value, pack)}`}
              onclick={() => step(index, -1)}><Icon name="minus" size={12} /></button>
            <output aria-label={`${itemLabel(item.value, pack)} count`}>{item.count}</output>
            <button type="button" aria-label={`More ${itemLabel(item.value, pack)}`}
              onclick={() => step(index, 1)}><Icon name="plus" size={12} /></button>
          </span>
        {/if}
        <button type="button" class="remove" aria-label={`Remove ${itemLabel(item.value, pack)}`}
          onclick={() => remove(index)}><Icon name="x" size={12} /></button>
      </li>
    {/each}
  </ul>
  <ItemPicker {pack} mode="add" label="Add item" onPick={add} />
</div>

<style>
  .chips {
    display: grid;
    gap: var(--space-2);
    justify-items: start;
    min-width: 0;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    min-width: 0;
  }
  .chip-item {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-2);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-pill);
    font-size: var(--text-sm);
    min-width: 0;
  }
  .name {
    max-width: 14ch;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .count {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    color: var(--muted);
  }
  output {
    min-width: 2ch;
    text-align: center;
    color: var(--fg);
    font-variant-numeric: tabular-nums;
  }
  .count button,
  .remove {
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
    padding: 0;
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-pill);
    color: var(--muted);
    cursor: pointer;
  }
  .count button:hover,
  .remove:hover {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .remove {
    border-color: transparent;
  }
  .note {
    margin: 0;
    font-size: var(--text-xs);
    color: var(--warn);
  }
</style>
