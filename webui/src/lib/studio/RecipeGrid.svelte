<script lang="ts">
  /**
   * A real 3×3 crafting table. Click a slot, pick an ingredient; the studio
   * writes back `shape` + `ingredients` so the YAML stays the canonical form
   * (D1) and the grid is only a view of it.
   */
  import { gridFrom, gridTo } from '../studio';

  interface Props {
    shape: unknown;
    ingredients: unknown;
    /** Ids offered by the picker: pack items first, then vanilla materials. */
    choices: { value: string; label: string; group: string }[];
    onChange: (next: { shape: string[]; ingredients: Record<string, string> }) => void;
  }

  let { shape, ingredients, choices, onChange }: Props = $props();

  let active = $state(-1);
  let search = $state('');

  const cells = $derived(gridFrom(shape, ingredients).cells);
  const matches = $derived(
    choices
      .filter((choice) => choice.label.toLowerCase().includes(search.trim().toLowerCase()))
      .slice(0, 60),
  );

  function set(index: number, value: string) {
    const next = [...cells];
    next[index] = value;
    onChange(gridTo(next));
    active = -1;
    search = '';
  }

  function short(value: string): string {
    const bare = value.includes(':') ? value.slice(value.indexOf(':') + 1) : value;
    return bare.replaceAll('_', ' ');
  }
</script>

<div class="grid-editor">
  <div class="grid">
    {#each cells as cell, index (index)}
      <button
        type="button"
        class="slot"
        class:filled={!!cell}
        class:active={active === index}
        title={cell || 'empty'}
        onclick={() => (active = active === index ? -1 : index)}
      >
        {#if cell}<span>{short(cell)}</span>{/if}
      </button>
    {/each}
  </div>

  {#if active >= 0}
    <div class="picker">
      <input
        type="search"
        placeholder="search items and materials"
        bind:value={search}
        onkeydown={(event) => {
          if (event.key === 'Escape') active = -1;
          if (event.key === 'Enter' && matches[0]) set(active, matches[0].value);
        }}
      />
      <ul>
        {#if cells[active]}
          <li><button type="button" class="clear" onclick={() => set(active, '')}>clear slot</button></li>
        {/if}
        {#each matches as choice (choice.value)}
          <li>
            <button type="button" onclick={() => set(active, choice.value)}>
              <span class="name">{choice.label}</span>
              <span class="group">{choice.group}</span>
            </button>
          </li>
        {/each}
        {#if matches.length === 0}
          <li class="none">no match</li>
        {/if}
      </ul>
    </div>
  {/if}
</div>

<style>
  .grid-editor {
    display: flex;
    gap: var(--space-4);
    align-items: flex-start;
    min-width: 0;
    margin-bottom: var(--space-4);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(3, 56px);
    gap: var(--space-1);
    flex: none;
  }
  .slot {
    height: 56px;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--muted);
    font-size: var(--text-xs);
    line-height: 1.2;
    padding: var(--space-half);
    cursor: pointer;
    overflow: hidden;
  }
  .slot.filled {
    color: var(--fg);
    background: var(--bg-raised);
  }
  .slot.active {
    border-color: var(--accent);
  }
  .slot span {
    display: -webkit-box;
    -webkit-line-clamp: 3;
    line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
    word-break: break-word;
  }
  .picker {
    flex: 1;
    min-width: 0;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: var(--bg-panel);
    display: flex;
    flex-direction: column;
    max-height: 180px;
  }
  input {
    background: var(--bg-input);
    color: inherit;
    border: none;
    border-bottom: 1px solid var(--border);
    padding: var(--space-2) var(--space-2);
    font-size: var(--text-sm);
    min-width: 0;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    overflow-x: hidden;
  }
  li button:not(.btn) {
    display: flex;
    justify-content: space-between;
    gap: var(--space-2);
    width: 100%;
    background: none;
    border: none;
    color: inherit;
    text-align: left;
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-sm);
    cursor: pointer;
    min-width: 0;
  }
  li button:not(.btn):hover {
    background: var(--bg-hover);
  }
  .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .group,
  .none {
    color: var(--faint);
    font-size: var(--text-xs);
    flex: none;
  }
  .none {
    padding: var(--space-2) var(--space-2);
  }
  .clear {
    color: var(--muted);
  }
</style>
