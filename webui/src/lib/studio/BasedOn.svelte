<script lang="ts">
  /**
   * The studio's inheritance header (issue 154). A template is a creation
   * choice, so the raw `template:` field never appears as a form control;
   * this bar shows the link instead — "Based on: Ruby Base ▾" — and offers
   * what you can do with it: change it, open it, or detach (copy the inherited
   * values into this resource and drop the link). Per-field provenance and
   * "Reset to inherited" stay with the fields themselves.
   */
  import Icon from '../components/Icon.svelte';
  import { searchTemplates, templateRef, templateSets, templateTitle, servedSchema } from '../templates';
  import type { InheritanceView, TemplateEntry } from '../api';
  import { workspace } from '../state.svelte';

  interface Props {
    /** The `template:` value in the draft ('' when none). */
    authored: string;
    /** The saved link's resolution, when the saved file names a template. */
    inheritance?: InheritanceView;
    /** The draft's link differs from the saved one. */
    edited: boolean;
    /** Templates this resource may switch to. */
    candidates: TemplateEntry[];
    /** Why detaching is unavailable right now ('' when it is available). */
    detachBlocked: string;
    editable: boolean;
    onChange: (ref: string) => void;
    onOpen: (id: string) => void;
    onDetach: () => void;
  }

  let { authored, inheritance, edited, candidates, detachBlocked, editable, onChange, onOpen, onDetach }: Props =
    $props();

  let open = $state(false);
  let query = $state('');
  let highlighted = $state(0);
  let root = $state<HTMLDivElement>();
  let search = $state<HTMLInputElement>();

  const shown = $derived(searchTemplates(candidates, query, workspace.types));
  const label = $derived(authored ? authored.slice(authored.lastIndexOf(':') + 1) : '');
  const current = $derived(
    authored
      ? candidates.find((entry) => templateRef(entry, workspace.activePack) === authored)
        ?? workspace.templates.find((entry) => entry.id === label)
      : undefined,
  );

  function title(id: string): string {
    const entry = workspace.templates.find((candidate) => candidate.id === id.slice(id.lastIndexOf(':') + 1));
    return entry ? templateTitle(entry) : id.slice(id.lastIndexOf(':') + 1);
  }

  function toggle(): void {
    open = !open;
    query = '';
    highlighted = 0;
    if (open) queueMicrotask(() => search?.focus());
  }

  function choose(entry: TemplateEntry): void {
    open = false;
    onChange(templateRef(entry, workspace.activePack));
  }

  function onKey(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.stopPropagation();
      open = false;
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      highlighted = shown.length ? (highlighted + 1) % shown.length : 0;
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      highlighted = shown.length ? (highlighted - 1 + shown.length) % shown.length : 0;
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const entry = shown[highlighted];
      if (entry) choose(entry);
    }
  }

  function onWindowPointer(event: PointerEvent): void {
    if (open && root && !root.contains(event.target as Node)) open = false;
  }
</script>

<svelte:window onpointerdown={onWindowPointer} />

<div class="based" class:broken={Boolean(inheritance?.failure)} bind:this={root} data-based-on>
  <Icon name="layout-template" size={14} />
  <span class="lead">Based on</span>
  <div class="control">
    <button
      type="button"
      class="btn btn-sm picker"
      aria-haspopup="listbox"
      aria-expanded={open}
      disabled={!editable}
      onclick={toggle}
      data-based-on-button
    >
      {authored ? (current ? templateTitle(current) : label) : 'Nothing'}
      <Icon name="chevron-down" size={12} />
    </button>
    {#if open}
      <div class="menu popover" role="presentation" onkeydown={onKey}>
        <input
          bind:this={search}
          bind:value={query}
          type="search"
          placeholder="Search templates…"
          aria-label="Search templates"
          spellcheck="false"
          autocomplete="off"
          onkeydown={onKey}
        />
        <ul role="listbox" aria-label="Templates">
          {#each shown as entry, index (entry.pack + ':' + entry.id)}
            {@const ref = templateRef(entry, workspace.activePack)}
            <li
              role="option"
              aria-selected={ref === authored}
              class="menu-item"
              class:highlight={index === highlighted}
              data-template={entry.id}
            >
              <button type="button" class="row" onclick={() => choose(entry)}>
                <span class="name">{templateTitle(entry)}</span>
                <code>{entry.id}</code>
                <span class="sets">sets {templateSets(entry, servedSchema(entry, workspace.types)).length}</span>
              </button>
            </li>
          {/each}
          {#if shown.length === 0}
            <li class="none">{candidates.length ? `Nothing matches “${query}”.` : 'No templates fit this yet.'}</li>
          {/if}
        </ul>
        {#if authored}
          <div class="actions">
            <button type="button" class="menu-item" onclick={() => { open = false; onOpen(inheritance?.chain[0] ?? authored); }}>
              <Icon name="file" size={13} /> Open template
            </button>
            <button
              type="button"
              class="menu-item"
              disabled={Boolean(detachBlocked)}
              title={detachBlocked || 'Copy the inherited values into this resource and drop the link'}
              onclick={() => { open = false; onDetach(); }}
              data-detach
            >
              <Icon name="layers" size={13} /> Detach — keep the values here
            </button>
          </div>
        {/if}
      </div>
    {/if}
  </div>

  {#if authored && inheritance?.failure}
    <span class="status">
      “{inheritance.template}” does not resolve ({inheritance.failure}); the server skips this resource until it does.
    </span>
  {:else if authored && inheritance}
    <span class="status chain">
      Inherits from
      {#each inheritance.chain as id, index (id)}
        {#if index > 0}<Icon name="chevron-right" size={12} />{/if}
        <button type="button" class="link" title={`Open ${id}`} onclick={() => onOpen(id)}>{title(id)}</button>
      {/each}
      {#if edited}· shown for the saved template until you apply{/if}
    </span>
  {:else if authored && edited}
    <span class="status">Will inherit from {title(authored)} once you apply.</span>
  {:else if !authored && edited}
    <span class="status">The link is removed once you apply; inherited values go with it.</span>
  {:else if !authored}
    <span class="status">Nothing is inherited; every value is set here.</span>
  {/if}
</div>

<style>
  .based {
    position: relative;
    display: flex;
    flex: none;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .based.broken {
    color: var(--warn);
  }
  .lead {
    color: var(--faint);
  }
  .control {
    position: relative;
  }
  .picker {
    gap: var(--space-1);
    color: var(--fg);
  }
  .popover {
    position: absolute;
    z-index: 20;
    top: calc(100% + var(--space-1));
    left: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    width: 340px;
    max-width: calc(100vw - 48px);
    padding: var(--space-2);
  }
  .popover ul {
    margin: 0;
    padding: 0;
    list-style: none;
    max-height: 220px;
    overflow-x: hidden;
    overflow-y: auto;
  }
  li.menu-item {
    padding: var(--space-1) var(--space-2);
  }
  .row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 0 var(--space-3);
    width: 100%;
    border: none;
    background: none;
    color: inherit;
    text-align: left;
    padding: 0;
    cursor: pointer;
  }
  .row .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .row code {
    grid-column: 1;
    color: var(--faint);
    font: var(--text-xs) var(--mono);
  }
  .row .sets {
    grid-column: 2;
    grid-row: 1 / span 2;
    align-self: center;
    color: var(--faint);
    font-size: var(--text-xs);
  }
  li.highlight {
    background: var(--bg-hover);
  }
  .none {
    padding: var(--space-3);
    color: var(--faint);
    text-align: center;
  }
  .actions {
    display: flex;
    flex-direction: column;
    border-top: 1px solid var(--border);
    padding-top: var(--space-1);
  }
  .actions .menu-item {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .actions .menu-item:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .status {
    display: inline-flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-1);
  }
  .link {
    border: none;
    background: none;
    color: var(--accent);
    padding: 0;
    font: inherit;
    cursor: pointer;
    text-decoration: underline;
  }
</style>
