<script lang="ts">
  /**
   * The search palette (logic.md §7.3): opened with `/` or from a free port, it lists every trigger, action,
   * condition and flow step the server's vocabulary describes, grouped and searchable, and puts the chosen one
   * where the wire or the selection says. Nothing in it is an editor-side list: an add-on's actions appear under
   * their own category, and an unavailable provider's entries are greyed with the server's reason.
   */
  import Icon from '../../components/Icon.svelte';
  import type { LogicCanvasState } from '../../logic/canvas.svelte';
  import { categoryColor, categoryIcon, type PaletteEntry } from '../../logic/vocabulary';

  let { canvas, onclose }: { canvas: LogicCanvasState; onclose: () => void } = $props();

  let query = $state('');
  let active = $state(0);
  let input = $state<HTMLInputElement>();

  const target = $derived(canvas.palette);
  const groups = $derived(canvas.search(query));
  /** The entries in the order the arrow keys walk them (the available ones). */
  const flat = $derived(groups.flatMap((group) => group.entries));
  const usable = $derived(
    flat.filter((entry) => entry.available && (entry.kind === 'trigger' || target?.slot)),
  );

  $effect(() => {
    void query;
    active = 0;
  });

  $effect(() => {
    input?.focus();
  });

  function blocked(entry: PaletteEntry): string | null {
    if (!entry.available) return entry.reason ?? 'Not available on this server.';
    if (entry.kind !== 'trigger' && !target?.slot) return 'Add a trigger first: a step needs a rule to run in.';
    return null;
  }

  function choose(entry: PaletteEntry): void {
    const why = blocked(entry);
    if (why) {
      canvas.say(why);
      return;
    }
    canvas.pick(entry);
  }

  function key(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      onclose();
    } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (usable.length === 0) return;
      active = (active + (event.key === 'ArrowDown' ? 1 : -1) + usable.length) % usable.length;
      document.getElementById(`logic-palette-${usable[active]!.key}`)?.scrollIntoView({ block: 'nearest' });
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const entry = usable[active];
      if (entry) choose(entry);
    }
  }
</script>

<div
  class="palette"
  role="dialog"
  aria-label="Add a node"
  tabindex="-1"
  data-testid="logic-palette"
  onkeydown={key}
  onpointerdown={(event) => event.stopPropagation()}
>
  <label class="search">
    <Icon name="search" size={14} />
    <input
      bind:this={input}
      bind:value={query}
      type="text"
      placeholder="Search triggers, actions, flow…"
      aria-label="Search the palette"
      aria-controls="logic-palette-list"
      aria-activedescendant={usable[active] ? `logic-palette-${usable[active]!.key}` : undefined}
      spellcheck="false"
      autocomplete="off"
    />
  </label>
  <p class="where" data-testid="logic-palette-target">{target?.label ?? ''}</p>
  <div class="list" id="logic-palette-list" role="listbox" aria-label="Nodes">
    {#each groups as group (group.id)}
      <section aria-label={group.title}>
        <h4 style="--c:{categoryColor(group.category)}"><Icon name={categoryIcon(group.category)} size={12} /> {group.title}</h4>
        {#each group.entries as entry (entry.key)}
          {@const why = blocked(entry)}
          <button
            type="button"
            id="logic-palette-{entry.key}"
            class="entry"
            class:active={usable[active]?.key === entry.key}
            class:off={!!why}
            role="option"
            aria-selected={usable[active]?.key === entry.key}
            aria-disabled={why ? 'true' : undefined}
            data-entry={entry.key}
            style="--c:{categoryColor(entry.category)}"
            onclick={() => choose(entry)}
            onpointermove={() => {
              const at = usable.findIndex((candidate) => candidate.key === entry.key);
              if (at >= 0) active = at;
            }}
          >
            <span class="swatch" aria-hidden="true"></span>
            <span class="name">{entry.title}</span>
            <span class="id">{entry.id}</span>
            {#if why}<span class="why">{why}</span>{:else if entry.description}<span class="desc">{entry.description}</span>{/if}
          </button>
        {/each}
      </section>
    {:else}
      <p class="none">{canvas.vocabulary ? `Nothing matches "${query}".` : 'The server has not described its actions yet.'}</p>
    {/each}
  </div>
</div>

<style>
  .palette {
    position: absolute;
    top: var(--space-4);
    left: 50%;
    z-index: 5;
    display: flex;
    flex-direction: column;
    box-sizing: border-box;
    width: min(380px, calc(100% - var(--space-8)));
    max-height: min(520px, calc(100% - var(--space-8)));
    transform: translateX(-50%);
    background: var(--bg-panel);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-3);
  }
  .search {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
    color: var(--muted);
  }
  .search input {
    flex: 1;
    min-width: 0;
    background: none;
    border: 0;
    color: var(--fg);
    font: var(--text-base) var(--font-sans);
    outline: none;
  }
  .where {
    margin: 0;
    padding: var(--space-1) var(--space-3);
    color: var(--muted);
    font-size: var(--text-xs);
    border-bottom: 1px solid var(--border);
  }
  .list {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-1) 0 var(--space-2);
  }
  h4 {
    display: flex;
    align-items: center;
    gap: var(--space-1);
    margin: var(--space-2) 0 var(--space-half);
    padding: 0 var(--space-3);
    color: var(--c);
    font-size: var(--text-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .entry {
    display: grid;
    grid-template-columns: 4px minmax(0, 1fr) auto;
    grid-template-areas: 'swatch name id' 'swatch desc desc';
    align-items: baseline;
    column-gap: var(--space-2);
    width: 100%;
    padding: var(--space-1) var(--space-3);
    background: none;
    border: 0;
    border-left: 0;
    color: var(--fg);
    font: var(--text-sm) var(--font-sans);
    text-align: left;
    cursor: pointer;
  }
  .entry.active,
  .entry:hover {
    background: var(--bg-hover);
  }
  .entry.off {
    cursor: not-allowed;
  }
  .entry.off .name,
  .entry.off .id,
  .entry.off .swatch {
    opacity: 0.45;
  }
  .swatch {
    grid-area: swatch;
    align-self: stretch;
    background: var(--c);
    border-radius: var(--radius-sm);
  }
  .name {
    grid-area: name;
    font-weight: 600;
  }
  .id {
    grid-area: id;
    color: var(--faint);
    font: var(--text-xs) var(--mono);
  }
  .desc,
  .why {
    grid-area: desc;
    color: var(--muted);
    font-size: var(--text-xs);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .why {
    color: var(--warn);
  }
  .none {
    margin: var(--space-3);
    color: var(--muted);
    font-size: var(--text-sm);
  }
</style>
