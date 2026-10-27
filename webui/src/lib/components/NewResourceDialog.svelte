<script lang="ts">
  /**
   * The New dialog (issue 154): a name, then *what to start from* — a blank
   * scaffold, a template (cards showing what each one sets), or a copy of an
   * existing resource. Templates are a creation choice, never a raw field:
   * the choice becomes `template:` plus the name, and nothing else is copied.
   *
   * Keyboard: Tab through name → start-from → search → cards; Up/Down move the
   * highlighted card from the search box or the list; Enter picks it (and, once
   * the name is valid, creates); Escape cancels.
   */
  import Button from '../ui/Button.svelte';
  import Chip from '../ui/Chip.svelte';
  import Dialog from '../ui/Dialog.svelte';
  import Field from '../ui/Field.svelte';
  import Icon from './Icon.svelte';
  import ResourceThumbnail from './ResourceThumbnail.svelte';
  import { humanise, idFromName } from '../browser';
  import type { ResourceRef, TemplateEntry } from '../api';
  import {
    acceptsTemplates,
    searchTemplates,
    servedSchema,
    templateMaterial,
    templateSets,
    templateTitle,
    templatesFor,
    usersLabel,
    type NewChoice,
  } from '../templates';
  import { workspace } from '../state.svelte';

  interface Props {
    /** Pack directory of the tab the author is creating in ("items"). */
    type: string;
    /** Singular noun for titles ("item"). */
    singular: string;
    onCreate: (choice: NewChoice) => void;
    onCancel: () => void;
  }

  let { type, singular, onCreate, onCancel }: Props = $props();

  type Mode = 'blank' | 'template' | 'duplicate';

  const isTemplateTab = $derived(type === 'templates');
  const available = $derived(
    isTemplateTab ? [...workspace.templates] : templatesFor(workspace.templates, type),
  );
  const copyable = $derived(
    workspace.resources.filter((entry) => entry.type === type)
      .map((entry) => ({
        ref: entry,
        name: workspace.summaries.find((summary) =>
          summary.pack === entry.pack && summary.path === entry.path && summary.id === entry.id)?.name
          ?? humanise(entry.id),
        summary: workspace.summaries.find((summary) =>
          summary.pack === entry.pack && summary.path === entry.path && summary.id === entry.id),
      })),
  );
  const servable = $derived(
    workspace.types.filter((candidate) => acceptsTemplates(candidate) && candidate.directory),
  );

  let name = $state('');
  let mode = $state<Mode>('blank');
  let query = $state('');
  let highlighted = $state(0);
  let pickedTemplate = $state<TemplateEntry | null>(null);
  let pickedSource = $state<ResourceRef | null>(null);
  let serves = $state('');
  let nameInput = $state<HTMLInputElement>();
  let list = $state<HTMLDivElement>();

  // A template is *for* one type; default to the first that takes templates.
  $effect(() => {
    if (isTemplateTab && !serves) serves = servable[0]?.directory ?? 'items';
  });

  const id = $derived(idFromName(name));
  const clash = $derived(
    id !== '' && workspace.resources.some((entry) =>
      entry.type === type && entry.pack === workspace.activePack && entry.id === id),
  );

  const templateRows = $derived(searchTemplates(available, query, workspace.types));
  const copyRows = $derived.by(() => {
    const needle = query.trim().toLowerCase().replaceAll('_', ' ');
    return needle
      ? copyable.filter((row) =>
        `${row.name} ${row.ref.id}`.toLowerCase().replaceAll('_', ' ').includes(needle))
      : copyable;
  });
  const rowCount = $derived(mode === 'template' ? templateRows.length : mode === 'duplicate' ? copyRows.length : 0);

  const ready = $derived(
    id !== '' && !clash &&
    (mode === 'blank' || (mode === 'template' && pickedTemplate !== null) ||
      (mode === 'duplicate' && pickedSource !== null)),
  );

  $effect(() => {
    void mode; void query;
    highlighted = 0;
  });

  function choose(next: Mode): void {
    mode = next;
    query = '';
  }

  function pickAt(index: number): void {
    if (mode === 'template') pickedTemplate = templateRows[index] ?? null;
    else if (mode === 'duplicate') pickedSource = copyRows[index]?.ref ?? null;
  }

  function move(delta: number): void {
    if (rowCount === 0) return;
    highlighted = (highlighted + delta + rowCount) % rowCount;
    list?.querySelector<HTMLElement>(`[data-index="${highlighted}"]`)?.scrollIntoView({ block: 'nearest' });
  }

  function onListKey(event: KeyboardEvent): void {
    if (event.key === 'ArrowDown' || event.key === 'ArrowRight') {
      event.preventDefault();
      move(1);
    } else if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') {
      event.preventDefault();
      move(-1);
    } else if (event.key === 'Enter' && rowCount > 0) {
      event.preventDefault();
      pickAt(highlighted);
      if (id !== '' && !clash) submit();
      else nameInput?.focus();
    } else if (event.key === ' ' && (event.target as HTMLElement).closest('[role="listbox"]')) {
      event.preventDefault();
      pickAt(highlighted);
    }
  }

  function submit(event?: SubmitEvent): void {
    event?.preventDefault();
    if (mode === 'template' && !pickedTemplate) pickAt(highlighted);
    if (mode === 'duplicate' && !pickedSource) pickAt(highlighted);
    if (!ready) return;
    const start = mode === 'template' && pickedTemplate
      ? { kind: 'template' as const, template: pickedTemplate }
      : mode === 'duplicate' && pickedSource
        ? { kind: 'duplicate' as const, source: pickedSource }
        : { kind: 'blank' as const };
    onCreate({ name: name.trim(), id, start, ...(isTemplateTab ? { serves } : {}) });
  }

  const startLabel = $derived(isTemplateTab ? 'an existing template' : `an existing ${singular}`);
</script>

<Dialog title={`New ${singular}`} onClose={onCancel} width="640px">
  <form onsubmit={submit} class="new">
    <Field
      label={`${singular} name`}
      hint={id ? `id: ${id}` : 'players see this name; the id is derived from it'}
      error={clash ? `A ${singular} with the id "${id}" already exists.` : ''}
    >
      <!-- svelte-ignore a11y_autofocus -->
      <input bind:this={nameInput} bind:value={name} autofocus spellcheck="false" autocomplete="off" />
    </Field>

    {#if isTemplateTab}
      <Field label="Used for" hint="the kind of resource that will be based on this template">
        <select bind:value={serves}>
          {#each servable as candidate (candidate.directory)}
            <option value={candidate.directory}>{candidate.displayName}</option>
          {/each}
        </select>
      </Field>
    {/if}

    <fieldset class="start">
      <legend class="field-label">Start from</legend>
      <div class="modes">
        <label class:on={mode === 'blank'}>
          <input type="radio" name="start" checked={mode === 'blank'} onchange={() => choose('blank')} />
          <Icon name="file" size={14} /> Blank
        </label>
        {#if !isTemplateTab}
          <label class:on={mode === 'template'} class:off={available.length === 0}
            title={available.length === 0 ? `No templates for ${singular}s yet` : ''}>
            <input type="radio" name="start" checked={mode === 'template'} disabled={available.length === 0}
              onchange={() => choose('template')} />
            <Icon name="layout-template" size={14} /> A template
            {#if available.length}<span class="count">{available.length}</span>{/if}
          </label>
        {/if}
        <label class:on={mode === 'duplicate'} class:off={(isTemplateTab ? available : copyable).length === 0}>
          <input type="radio" name="start" checked={mode === 'duplicate'}
            disabled={(isTemplateTab ? available : copyable).length === 0}
            onchange={() => choose('duplicate')} />
          <Icon name="layers" size={14} /> {isTemplateTab ? 'An existing template' : 'An existing one'}
        </label>
      </div>
    </fieldset>

    {#if mode === 'blank'}
      <p class="note">
        {isTemplateTab
          ? 'A new template starts with one field; add the rest in its studio.'
          : `A fresh ${singular} with the common fields filled in; nothing is inherited.`}
      </p>
    {:else}
      <div class="pick">
        <div class="search">
          <Icon name="search" size={14} />
          <input
            type="search"
            bind:value={query}
            placeholder={mode === 'template' ? 'Search templates…' : `Search ${startLabel}…`}
            aria-label={mode === 'template' ? 'Search templates' : `Search ${startLabel}`}
            spellcheck="false"
            autocomplete="off"
            onkeydown={onListKey}
          />
        </div>
        <div
          class="cards"
          role="listbox"
          tabindex="0"
          aria-label={mode === 'template' ? 'Templates' : startLabel}
          aria-activedescendant={rowCount ? `pick-${highlighted}` : undefined}
          bind:this={list}
          onkeydown={onListKey}
        >
          {#if mode === 'template'}
            {#each templateRows as entry, index (entry.pack + ':' + entry.id)}
              {@const schema = servedSchema(entry, workspace.types)}
              {@const sets = templateSets(entry, schema)}
              <div
                id={`pick-${index}`}
                class="card"
                class:picked={pickedTemplate === entry}
                class:highlight={highlighted === index}
                role="option"
                aria-selected={pickedTemplate === entry}
                tabindex="-1"
                data-index={index}
                data-template={entry.id}
                onclick={() => { highlighted = index; pickAt(index); }}
                ondblclick={() => { pickAt(index); submit(); }}
                onkeydown={() => {}}
              >
                <ResourceThumbnail material={templateMaterial(entry)} label={templateTitle(entry)} size={44} />
                <span class="body">
                  <span class="title">{templateTitle(entry)}</span>
                  <code class="ident">{entry.id}</code>
                  <span class="sets">
                    {#each sets.slice(0, 5) as set (set.field)}
                      <Chip tone={set.module ? 'accent' : 'neutral'}>{set.label}</Chip>
                    {/each}
                    {#if sets.length > 5}<Chip>+{sets.length - 5}</Chip>{/if}
                    {#if sets.length === 0}<em>sets nothing yet</em>{/if}
                  </span>
                  <span class="meta">{usersLabel(entry.usedBy)}{entry.pack !== workspace.activePack ? ` · from ${entry.pack}` : ''}</span>
                </span>
              </div>
            {/each}
          {:else}
            {#each copyRows as row, index (row.ref.path + '#' + row.ref.id)}
              <div
                id={`pick-${index}`}
                class="card"
                class:picked={pickedSource === row.ref}
                class:highlight={highlighted === index}
                role="option"
                aria-selected={pickedSource === row.ref}
                tabindex="-1"
                data-index={index}
                data-source={row.ref.id}
                onclick={() => { highlighted = index; pickAt(index); }}
                ondblclick={() => { pickAt(index); submit(); }}
                onkeydown={() => {}}
              >
                <ResourceThumbnail visual={row.summary?.visual} render={row.summary?.render}
                  material={row.summary?.material} label={row.name} size={44} />
                <span class="body">
                  <span class="title">{row.name}</span>
                  <code class="ident">{row.ref.id}</code>
                  <span class="meta">everything is copied, comments included</span>
                </span>
              </div>
            {/each}
          {/if}
          {#if rowCount === 0}
            <p class="none">Nothing matches “{query}”.</p>
          {/if}
        </div>
      </div>
    {/if}

    <div class="dialog-actions">
      <Button onclick={onCancel}>Cancel</Button>
      <Button type="submit" variant="primary" disabled={!ready}>Create</Button>
    </div>
  </form>
</Dialog>

<style>
  .new {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }
  .start {
    border: none;
    margin: 0;
    padding: 0;
  }
  .modes {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-top: var(--space-1);
  }
  .modes label {
    position: relative;
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: var(--space-1) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-pill);
    color: var(--muted);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .modes label.on {
    border-color: var(--accent);
    color: var(--fg);
    background: var(--accent-low);
  }
  .modes label.off {
    opacity: 0.5;
    cursor: default;
  }
  .modes input {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    margin: 0;
    opacity: 0;
    cursor: inherit;
  }
  .modes label:focus-within {
    outline: 2px solid var(--focus-ring);
    outline-offset: 1px;
  }
  .count {
    color: var(--faint);
    font-size: var(--text-xs);
  }
  .note {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .pick {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  .search {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 0 var(--space-2);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-input);
    color: var(--faint);
  }
  .search input {
    flex: 1;
    min-width: 0;
    border: none;
    background: none;
    box-shadow: none;
  }
  .cards {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: var(--space-2);
    max-height: 280px;
    overflow-y: auto;
    padding: var(--space-half);
  }
  .cards:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
  .card {
    display: flex;
    gap: var(--space-3);
    padding: var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-panel);
    cursor: pointer;
    min-width: 0;
    transition:
      background-color var(--motion-fast) var(--ease-standard),
      border-color var(--motion-fast) var(--ease-standard),
      box-shadow var(--motion-fast) var(--ease-standard);
  }
  .card:hover,
  .card.highlight {
    background: var(--bg-hover);
  }
  .card.picked {
    border-color: var(--accent);
    box-shadow: 0 0 0 1px var(--accent);
  }
  .body {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    min-width: 0;
  }
  .title {
    font-weight: 600;
    font-size: var(--text-md);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .ident {
    color: var(--faint);
    font: var(--text-xs) var(--mono);
  }
  .sets {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1);
  }
  .sets em,
  .meta,
  .none {
    color: var(--faint);
    font-size: var(--text-xs);
    font-style: normal;
  }
  .none {
    grid-column: 1 / -1;
    margin: var(--space-3) 0;
    text-align: center;
  }
</style>
