<script lang="ts">
  /**
   * The item picker (issue 153): search this pack's items (and blocks and
   * furniture, which can be dropped or crafted too) plus the server's vanilla
   * items, with icons. One control backs a single reference (`item` widget),
   * the "add" button of a chip list, and every recipe slot — it only ever
   * produces a reference string, so the file stays the one source of truth.
   */
  import Icon from '../components/Icon.svelte';
  import ItemIcon from './ItemIcon.svelte';
  import { itemChoices, itemLabel, matchItemChoices } from '../items';
  import { workspace } from '../state.svelte';

  interface Props {
    pack: string;
    /** The current reference; ignored in `add` mode. */
    value?: string;
    id?: string;
    label: string;
    /**
     * `single` shows the chosen item; `add` is a button that only emits picks;
     * `slot` is a square icon button (a crafting grid cell) that can clear itself.
     */
    mode?: 'single' | 'add' | 'slot';
    /** Only pack items (the ones that can own a placement link). */
    packOnly?: boolean;
    required?: boolean;
    placeholder?: string;
    onPick: (value: string) => void;
    onClear?: () => void;
  }

  let {
    pack, value = '', id, label, mode = 'single', packOnly = false, required = false,
    placeholder = 'Choose an item', onPick, onClear,
  }: Props = $props();

  let open = $state(false);
  let query = $state('');
  let selected = $state(0);
  let root: HTMLDivElement | undefined = $state();
  const uid = $props.id();
  const listId = `${uid}-items`;

  const choices = $derived(itemChoices({
    pack, resources: workspace.resources, types: workspace.types, packOnly,
  }));
  const matches = $derived(matchItemChoices(choices, query));
  const visible = $derived(matches.slice(0, 60));

  function show() {
    open = true;
    query = '';
    selected = 0;
    queueMicrotask(() => root?.querySelector<HTMLInputElement>('input')?.focus());
  }

  function close() {
    open = false;
    query = '';
  }

  function choose(next: string) {
    onPick(next);
    close();
  }

  function keyboard(event: KeyboardEvent) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      selected = Math.max(0, Math.min(visible.length - 1, selected + (event.key === 'ArrowDown' ? 1 : -1)));
      root?.querySelector(`[data-index="${selected}"]`)?.scrollIntoView({ block: 'nearest' });
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const next = visible[selected];
      if (next) choose(next.value);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      close();
    }
  }
</script>

<div class="item-picker" class:slotted={mode === 'slot'} bind:this={root} onfocusout={(event) => {
  if (!(event.relatedTarget instanceof Node) || !root?.contains(event.relatedTarget)) close();
}}>
  {#if mode === 'slot'}
    <button type="button" class="trigger slot" class:filled={Boolean(value)} {id} data-field-control
      aria-haspopup="listbox" aria-expanded={open} title={value || 'empty'}
      aria-label={`${label}: ${value ? itemLabel(value, pack) : 'empty'}`} onclick={show}>
      {#if value}<ItemIcon {value} {pack} size={32} />{/if}
    </button>
  {:else if mode === 'add'}
    <button type="button" class="trigger add" {id} data-field-control aria-haspopup="listbox"
      aria-expanded={open} onclick={show}>
      <Icon name="plus" size={14} /> {label}
    </button>
  {:else}
    <div class="current">
      <button type="button" class="trigger" {id} data-field-control aria-haspopup="listbox"
        aria-expanded={open} aria-label={`${label}: ${value ? itemLabel(value, pack) : 'none'}`}
        onclick={show}>
        {#if value}
          <ItemIcon {value} {pack} size={24} />
          <span class="name">{itemLabel(value, pack)}</span>
          <code>{value}</code>
        {:else}
          <Icon name="search" size={14} />
          <span class="name placeholder">{placeholder}</span>
        {/if}
      </button>
      {#if value && !required}
        <button type="button" class="clear" aria-label={`Clear ${label}`}
          onclick={() => (onClear ? onClear() : onPick(''))}>
          <Icon name="x" size={14} />
        </button>
      {/if}
    </div>
  {/if}

  {#if open}
    <div class="popover">
      <input type="text" role="combobox" aria-label={`Search items for ${label}`}
        aria-expanded="true" aria-controls={listId} aria-autocomplete="list" autocomplete="off"
        spellcheck="false" placeholder="Search items…" value={query}
        aria-activedescendant={visible[selected] ? `${listId}-${selected}` : undefined}
        oninput={(event) => { query = event.currentTarget.value; selected = 0; }}
        onkeydown={keyboard} />
      <div class="results" role="listbox" id={listId} aria-label={`${label} choices`}>
        {#if mode === 'slot' && value}
          <button type="button" class="clear-row" tabindex="-1"
            onpointerdown={(event) => event.preventDefault()} onclick={() => choose('')}>
            <Icon name="x" size={14} /> Clear slot
          </button>
        {/if}
        {#each visible as choice, index (choice.value)}
          <button type="button" role="option" id={`${listId}-${index}`} data-index={index}
            data-choice={choice.value} tabindex="-1" aria-selected={index === selected}
            onpointerdown={(event) => event.preventDefault()} onclick={() => choose(choice.value)}>
            <ItemIcon value={choice.value} {pack} size={24} />
            <span class="name">{choice.label}</span>
            <span class="group">{choice.group === 'vanilla' ? 'vanilla' : choice.directory}</span>
          </button>
        {/each}
        {#if matches.length === 0}
          <p class="none">No matching item.</p>
        {:else if matches.length > visible.length}
          <p class="none">Showing {visible.length} of {matches.length}. Keep typing to narrow the list.</p>
        {/if}
      </div>
    </div>
  {/if}
</div>

<style>
  .item-picker {
    position: relative;
    min-width: 0;
  }
  .slotted .popover {
    min-width: 280px;
    right: auto;
  }
  .current {
    display: flex;
    gap: var(--space-1);
    align-items: center;
    min-width: 0;
  }
  .trigger {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    flex: 1;
    min-width: 0;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-base);
    cursor: pointer;
    text-align: left;
  }
  .trigger:hover {
    border-color: var(--control-line);
  }
  .trigger.slot {
    flex: none;
    justify-content: center;
    width: 56px;
    height: 56px;
    padding: var(--space-half);
  }
  .trigger.slot.filled {
    background: var(--bg-raised);
  }
  .trigger.add {
    flex: none;
    background: none;
    border-style: dashed;
    color: var(--muted);
  }
  .name {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .placeholder {
    color: var(--faint);
  }
  code {
    margin-left: auto;
    color: var(--faint);
    font: var(--text-xs) var(--mono);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .clear {
    flex: none;
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--muted);
    cursor: pointer;
  }
  .clear:hover {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .popover {
    position: absolute;
    z-index: 5;
    top: calc(100% + var(--space-1));
    left: 0;
    min-width: min(320px, 100%);
    right: 0;
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-2);
    display: grid;
  }
  .popover input {
    background: var(--bg-input);
    color: inherit;
    border: none;
    border-bottom: 1px solid var(--border);
    border-radius: var(--radius) var(--radius) 0 0;
    padding: var(--space-2);
    font-size: var(--text-base);
    min-width: 0;
  }
  .results {
    max-height: 260px;
    overflow-y: auto;
  }
  .results button {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    width: 100%;
    background: none;
    border: none;
    color: inherit;
    text-align: left;
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .results button:hover,
  .results button[aria-selected='true'] {
    background: var(--bg-hover);
  }
  .clear-row {
    color: var(--muted);
  }
  .group {
    margin-left: auto;
    color: var(--faint);
    font-size: var(--text-xs);
  }
  .none {
    margin: 0;
    padding: var(--space-2);
    color: var(--muted);
    font-size: var(--text-xs);
  }
</style>
