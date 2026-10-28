<script lang="ts">
  /**
   * The template control in the studio header (issue 154, compact since issue
   * 172): a chip next to the id names the template this resource is based on.
   * Its menu changes the template, opens it, or detaches it (copy the inherited
   * values into this resource and drop the link). A template that does not
   * resolve turns the chip into a warning. Per-field provenance ("Inherited
   * from", "Overrides") stays with the fields themselves.
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
    /** Templates this resource may switch to. */
    candidates: TemplateEntry[];
    /** Why detaching is unavailable right now ('' when it is available). */
    detachBlocked: string;
    editable: boolean;
    onChange: (ref: string) => void;
    onOpen: (id: string) => void;
    onDetach: () => void;
  }

  let { authored, inheritance, candidates, detachBlocked, editable, onChange, onOpen, onDetach }: Props =
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
  /** The validation message for a template that does not resolve ('' when it does). */
  const failure = $derived(
    authored && inheritance?.failure
      ? `“${inheritance.template}” does not resolve (${inheritance.failure}); the server skips this resource until it does.`
      : '',
  );
  const named = $derived(authored ? (current ? templateTitle(current) : label) : '');
  const chipText = $derived(
    failure ? `${named} does not resolve` : named || 'No template',
  );
  /** Hover text: the failure in full, or the chain of templates this one inherits through. */
  const chipHint = $derived(
    failure || (inheritance && inheritance.chain.length > 1
      ? `Inherits from ${inheritance.chain.map((id) => title(id)).join(' › ')}`
      : undefined),
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

<div class="based" bind:this={root} data-based-on>
  <button
    type="button"
    class="chip template-chip"
    class:chip-warn={Boolean(failure)}
    aria-haspopup="listbox"
    aria-expanded={open}
    aria-label={`Template: ${chipText}`}
    title={chipHint}
    disabled={!editable}
    onclick={toggle}
    data-based-on-button
  >
    <Icon name={failure ? 'triangle-alert' : 'layout-template'} size={13} />
    <span>{chipText}</span>
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
        {#if failure}
          <p class="failure" role="note">{failure}</p>
        {:else if inheritance && inheritance.chain.length > 1}
          <p class="chain">
            Inherits from
            {#each inheritance.chain as id, index (id)}
              {#if index > 0}<Icon name="chevron-right" size={12} />{/if}
              <button type="button" class="link" title={`Open ${id}`} onclick={() => { open = false; onOpen(id); }}>{title(id)}</button>
            {/each}
          </p>
        {/if}
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
            <Icon name="layers" size={13} /> Detach template
          </button>
          {#if detachBlocked}<p class="blocked">{detachBlocked}</p>{/if}
        </div>
      {/if}
    </div>
  {/if}
</div>

<style>
  .based {
    position: relative;
    display: inline-flex;
    flex: none;
    min-width: 0;
  }
  .template-chip {
    max-width: 260px;
    padding: var(--space-half) var(--space-2);
    color: var(--fg);
    font-size: var(--text-sm);
  }
  .template-chip span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .template-chip:disabled {
    opacity: 0.6;
    cursor: default;
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
  .chain,
  .failure,
  .blocked {
    margin: 0;
    padding: 0 var(--space-2);
    font-size: var(--text-xs);
  }
  .chain {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-1);
    color: var(--muted);
  }
  .failure {
    color: var(--warn);
  }
  .blocked {
    padding-bottom: var(--space-1);
    color: var(--faint);
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
