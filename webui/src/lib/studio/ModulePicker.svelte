<script lang="ts">
  /**
   * The searchable module picker (issue 152): category chips, a description
   * per module, and keyboard navigation. Modules already added are marked and
   * unselectable unless they are repeatable. The catalog is derived from the
   * type's self-description — this component only presents it. Focus stays in
   * the search field: arrow keys move the active row, Enter adds it.
   */
  import { fly } from 'svelte/transition';
  import Dialog from '../ui/Dialog.svelte';
  import Icon from '../components/Icon.svelte';
  import {
    categoriesOf,
    categoryLabel,
    type ModuleDescriptor,
  } from '../modules';
  import { motionMs, staggerDelay } from '../motion';

  interface Props {
    catalog: ModuleDescriptor[];
    /** Current values, so already-added modules can be marked. */
    isAdded: (module: ModuleDescriptor) => boolean;
    onClose: () => void;
    onPick: (module: ModuleDescriptor) => void;
  }

  let { catalog, isAdded, onClose, onPick }: Props = $props();

  let query = $state('');
  let activeCategory = $state('');
  let cursor = $state(0);
  let listElement: HTMLUListElement | undefined;
  // The rows stagger in while the picker first opens; later filtering enters them at once.
  let settling = $state(true);
  $effect(() => {
    const timer = setTimeout(() => (settling = false), 400);
    return () => clearTimeout(timer);
  });

  const categories = $derived(categoriesOf(catalog));

  const matches = $derived(catalog.filter((module) => {
    if (activeCategory && module.category !== activeCategory) return false;
    if (!query.trim()) return true;
    const haystack = [module.label, module.summary, categoryLabel(module.category)]
      .join(' ')
      .toLowerCase();
    return haystack.includes(query.trim().toLowerCase());
  }));

  $effect(() => {
    // Refiltering must never leave the cursor past the list's end, and the
    // active row must stay visible when navigation outgrows the viewport.
    if (cursor >= matches.length) cursor = Math.max(0, matches.length - 1);
    listElement
      ?.querySelector<HTMLElement>('[data-active="true"]')
      ?.scrollIntoView({ block: 'nearest' });
  });

  function choose(module: ModuleDescriptor): void {
    if (disabled(module)) return;
    onPick(module);
    query = '';
    activeCategory = '';
    cursor = 0;
  }

  /** Added modules cannot be added twice — except repeatable mechanics. */
  function disabled(module: ModuleDescriptor): boolean {
    if (!isAdded(module)) return false;
    return !(module.kind === 'mechanic' && module.mechanic?.repeatable === true);
  }

  function selectable(index: number): boolean {
    return index >= 0 && index < matches.length && !disabled(matches[index]!);
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.defaultPrevented || event.isComposing) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      let next = cursor;
      do {
        next = (next + step + matches.length) % Math.max(matches.length, 1);
      } while (matches.length > 1 && !selectable(next) && next !== cursor);
      cursor = next;
    } else if (event.key === 'Enter') {
      const module = matches[cursor];
      if (module) {
        event.preventDefault();
        choose(module);
      }
    }
  }
</script>

<Dialog title="Add a module" onClose={onClose} width="480px" dismissOnScrim={false}>
  <div class="picker" role="search">
    <input
      type="search"
      aria-label="Search modules"
      placeholder="Search modules…"
      bind:value={query}
      onkeydown={onKeydown}
      data-testid="module-search"
    />
    <div class="chips" role="group" aria-label="Module categories">
      <button
        type="button"
        class="chip"
        class:on={!activeCategory}
        aria-pressed={!activeCategory}
        onclick={() => (activeCategory = '')}
      >All</button>
      {#each categories as category (category)}
        <button
          type="button"
          class="chip"
          class:on={activeCategory === category}
          aria-pressed={activeCategory === category}
          onclick={() => (activeCategory = category)}
        >{categoryLabel(category)}</button>
      {/each}
    </div>
    <ul bind:this={listElement} role="listbox" aria-label="Modules">
      {#each matches as module, index (module.id)}
        {@const added = isAdded(module)}
        <li in:fly|global={{ y: 6, duration: motionMs('base'), delay: settling ? staggerDelay(index) : 0 }}>
          <button
            type="button"
            role="option"
            tabindex="-1"
            aria-selected={index === cursor}
            aria-disabled={disabled(module)}
            data-active={index === cursor}
            disabled={disabled(module)}
            onclick={() => choose(module)}
          >
            <Icon name={module.icon} size={16} />
            <span class="label">{module.label}</span>
            {#if module.mechanic && !module.mechanic.id.startsWith('materione:')}
              <span class="origin">{module.mechanic.registeredBy}</span>
            {/if}
            {#if added}
              <span class="state">Added</span>
            {/if}
            <span class="summary">{module.summary}</span>
          </button>
        </li>
      {:else}
        <li class="empty">No module matches “{query}”.</li>
      {/each}
    </ul>
  </div>
</Dialog>

<style>
  .picker {
    display: grid;
    gap: var(--space-3);
    min-width: 0;
  }
  input[type='search'] {
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-base);
    min-width: 0;
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .chip {
    border: 1px solid var(--border-strong);
    background: none;
    color: var(--muted);
    border-radius: var(--radius-pill);
    padding: var(--space-half) var(--space-3);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .chip.on {
    border-color: var(--accent);
    color: var(--fg);
    background: var(--accent-low);
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--space-1);
    max-height: min(340px, 45vh);
    overflow-y: auto;
  }
  li {
    min-width: 0;
  }
  li button {
    display: grid;
    grid-template-columns: auto auto 1fr auto;
    grid-template-areas: 'icon label origin state' 'icon summary summary summary';
    align-items: baseline;
    column-gap: var(--space-2);
    width: 100%;
    text-align: left;
    background: none;
    border: 1px solid transparent;
    border-radius: var(--radius);
    color: inherit;
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-base);
    cursor: pointer;
  }
  li button:hover:not(:disabled),
  li button[data-active='true'] {
    background: var(--bg-hover);
    border-color: var(--border-strong);
  }
  li button[data-active='true'] {
    outline: 2px solid var(--focus-ring);
    outline-offset: 1px;
  }
  li button:disabled {
    color: var(--faint);
    cursor: default;
  }
  li button :global(svg) {
    grid-area: icon;
    align-self: center;
  }
  .label {
    grid-area: label;
    font-weight: 500;
    min-width: 0;
  }
  .origin {
    grid-area: origin;
    color: var(--faint);
    font-size: var(--text-xs);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .state {
    grid-area: state;
    color: var(--muted);
    font-size: var(--text-xs);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-pill);
    padding: 0 var(--space-2);
    justify-self: end;
  }
  .summary {
    grid-area: summary;
    color: var(--muted);
    font-size: var(--text-sm);
    min-width: 0;
  }
  .empty {
    color: var(--muted);
    font-size: var(--text-sm);
    padding: var(--space-2);
  }
</style>
