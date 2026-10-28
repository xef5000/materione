<script lang="ts">
  /**
   * The Appearance component's asset picker (issue 173): a compact file
   * explorer over the pack's textures or models — search, thumbnails, one row
   * per file grouped by folder — with the way to make a new one beside it
   * (texture: upload or draw; model: create or edit in Blockbench). It only
   * chooses a reference; the owning field is written through the studio's one
   * draft path. Focus stays in the search field: arrows move the active row,
   * Enter chooses it.
   */
  import Dialog from '../ui/Dialog.svelte';
  import Icon from '../components/Icon.svelte';
  import ResourceThumbnail from '../components/ResourceThumbnail.svelte';
  import { assetOps, unwrap } from '../api';
  import {
    assetChoices,
    matchesChoice,
    normalizedReference,
    type AssetChoice,
    type AssetKind,
  } from '../asset-choice';
  import { localStudioVisual } from '../studio-visual';
  import {
    connection,
    errorMessage,
    logActivity,
    refreshPack,
    session,
    showAssetUsage,
    workspace,
  } from '../state.svelte';

  interface Props {
    pack: string;
    kind: AssetKind;
    /** The field's stored value now. */
    current: string;
    /** Bumped when asset bytes changed without a new reference. */
    version?: number;
    onPick: (stored: string) => void;
    onClose: () => void;
    /** Texture: open the pixel editor on a new texture / on the current one. */
    onDraw?: (fresh: boolean) => void;
    /** Model: open Blockbench on this entry's model. */
    onBlockbench?: () => void;
  }

  let { pack, kind, current, version = 0, onPick, onClose, onDraw, onBlockbench }: Props = $props();

  const noun = $derived(kind === 'texture' ? 'texture' : '3D model');
  let query = $state('');
  let cursor = $state(0);
  let uploading = $state(false);
  let error = $state('');
  let fileInput = $state<HTMLInputElement>();
  let search = $state<HTMLInputElement>();
  let listElement: HTMLUListElement | undefined;
  const listId = $props.id();

  const choices = $derived(assetChoices(workspace.assets, pack, kind));
  const currentKey = $derived(normalizedReference(current, pack, kind));
  const selected = $derived(choices.find((choice) => normalizedReference(choice.stored, pack, kind) === currentKey));

  /** The rows: "None" first, then every file matching the search. */
  type Row = { kind: 'none' } | { kind: 'asset'; choice: AssetChoice };
  const rows = $derived<Row[]>([
    ...(query.trim() ? [] : [{ kind: 'none' } as Row]),
    ...choices.filter((choice) => matchesChoice(choice, query)).map((choice) => ({ kind: 'asset', choice }) as Row),
  ]);

  $effect(() => {
    if (cursor >= rows.length) cursor = Math.max(0, rows.length - 1);
    listElement?.querySelector<HTMLElement>('[data-active="true"]')?.scrollIntoView({ block: 'nearest' });
  });

  // Focus starts in the search field, so the keyboard works the moment the picker opens.
  $effect(() => search?.focus());

  // Start on the chosen file, so the picker opens where the item already is.
  let placed = false;
  $effect(() => {
    if (placed || !choices.length) return;
    placed = true;
    const at = rows.findIndex((row) => row.kind === 'asset' && row.choice === selected);
    if (at >= 0) cursor = at;
  });

  function rowId(index: number): string {
    return `${listId}-row-${index}`;
  }

  function choose(row: Row | undefined): void {
    if (!row) return;
    onPick(row.kind === 'none' ? '' : row.choice.stored);
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      // Close the picker only: the studio must not read this Escape as "leave".
      event.preventDefault();
      return;
    }
    if (event.defaultPrevented || event.isComposing) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      cursor = (cursor + step + Math.max(rows.length, 1)) % Math.max(rows.length, 1);
    } else if (event.key === 'Enter' && event.target instanceof HTMLInputElement) {
      event.preventDefault();
      choose(rows[cursor]);
    }
  }

  async function upload(event: Event): Promise<void> {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    const channel = session.channel;
    if (!file || !channel) return;
    const path = `assets/textures/${file.name}`;
    if (workspace.assets.some((asset) => asset.path === path) &&
        !confirm('Replace this shared texture now? This can immediately affect every resource that uses it. Pending form fields will not be applied.')) return;
    uploading = true;
    error = '';
    try {
      unwrap(await assetOps.put(channel, pack, path, new Uint8Array(await file.arrayBuffer())));
      logActivity('save', `uploaded ${path}; targeted reload queued`);
      await refreshPack();
      onPick(path.slice('assets/textures/'.length).replace(/\.(png|jpe?g)$/i, ''));
    } catch (e) {
      error = `Texture upload: ${errorMessage(e)}`;
      logActivity('error', error);
    } finally {
      uploading = false;
    }
  }

  function usage(): void {
    if (selected) showAssetUsage(selected.path);
  }
</script>

<Dialog title={`Choose ${noun}`} onClose={onClose} width="560px" dismissOnScrim={false}>
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div class="explorer" onkeydown={onKeydown}>
    <input
      type="search"
      aria-label={`Search ${noun}s`}
      placeholder={`Search ${noun}s…`}
      aria-controls={listId}
      aria-activedescendant={rows.length ? rowId(cursor) : undefined}
      bind:this={search}
      bind:value={query}
    />
    <ul id={listId} bind:this={listElement} role="listbox" aria-label={kind === 'texture' ? 'Textures' : '3D models'}>
      {#each rows as row, index (row.kind === 'none' ? '\0none' : row.choice.path)}
        {#if row.kind === 'asset' && (index === 0 || rows[index - 1]!.kind === 'none' || (rows[index - 1] as { choice: AssetChoice }).choice.dir !== row.choice.dir)}
          <li class="dir" role="presentation">{row.choice.dir || '/'}</li>
        {/if}
        <li role="presentation">
          <button
            type="button"
            id={rowId(index)}
            role="option"
            tabindex="-1"
            aria-selected={index === cursor}
            data-active={index === cursor}
            data-current={row.kind === 'none' ? currentKey === '' : row.choice === selected}
            onclick={() => choose(row)}
          >
            {#if row.kind === 'none'}
              <span class="none" aria-hidden="true"><Icon name="image-off" size={18} /></span>
              <span class="name">None</span>
            {:else}
              {@const choice = row.choice}
              {#if kind === 'texture'}
                <ResourceThumbnail visual={{ pack, path: choice.path, kind: 'texture' }} label={choice.base}
                  size={36} version={version + workspace.visualVersion} />
              {:else}
                <ResourceThumbnail render={{ pack, path: choice.path }}
                  visual={localStudioVisual(pack, '', choice.stored, workspace.assets)}
                  label={choice.base} size={36} version={version + workspace.visualVersion} />
              {/if}
              <span class="name">{choice.base}</span>
            {/if}
            {#if row.kind === 'none' ? currentKey === '' : row.choice === selected}
              <span class="state">Current</span>
            {/if}
          </button>
        </li>
      {:else}
        <li class="empty">No {noun} matches “{query}”.</li>
      {/each}
    </ul>

    {#if error}<p class="error" role="alert">{error}</p>{/if}

    <div class="dialog-actions">
      {#if selected}
        <button type="button" class="btn" onclick={usage}>Used by</button>
      {/if}
      <span class="grow"></span>
      {#if kind === 'texture'}
        <button type="button" class="btn" disabled={uploading || !connection.connected}
          onclick={() => fileInput?.click()}>
          <Icon name="upload" size={14} />{uploading ? 'Uploading…' : 'Upload'}
        </button>
        <input bind:this={fileInput} type="file" accept=".png,.jpg,.jpeg" onchange={upload} hidden
          aria-label="Texture file" />
        {#if onDraw && currentKey}
          <button type="button" class="btn" onclick={() => onDraw(false)}>
            <Icon name="pencil" size={14} />Edit current
          </button>
        {/if}
        {#if onDraw}
          <button type="button" class="btn" onclick={() => onDraw(true)}>
            <Icon name="plus" size={14} />New texture
          </button>
        {/if}
      {:else if onBlockbench}
        <button type="button" class="btn" onclick={onBlockbench}>
          <Icon name={currentKey ? 'pencil' : 'plus'} size={14} />{currentKey ? 'Edit in Blockbench' : 'New in Blockbench'}
        </button>
      {/if}
      <button type="button" class="btn" onclick={onClose}>Close</button>
    </div>
  </div>
</Dialog>

<style>
  .explorer {
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
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--space-1);
    max-height: min(360px, 48vh);
    overflow-y: auto;
  }
  li {
    min-width: 0;
  }
  .dir {
    position: sticky;
    top: 0;
    z-index: 1;
    padding: var(--space-1) var(--space-2) 0;
    background: var(--bg-raised);
    color: var(--faint);
    font: var(--text-xs) var(--mono);
    letter-spacing: 0.04em;
  }
  li button[role='option'] {
    display: flex;
    align-items: center;
    gap: var(--space-3);
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
  li button[role='option']:hover,
  li button[data-active='true'] {
    background: var(--bg-hover);
    border-color: var(--border-strong);
  }
  li button[data-active='true'] {
    outline: 2px solid var(--focus-ring);
    outline-offset: 1px;
  }
  li button[data-current='true'] {
    border-color: var(--accent);
  }
  .none {
    display: grid;
    place-items: center;
    width: 36px;
    height: 36px;
    flex: none;
    color: var(--faint);
    border: 1px dashed var(--control-line);
    border-radius: var(--radius);
  }
  .name {
    flex: 1 1 0;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-family: var(--mono);
    font-size: var(--text-sm);
  }
  .state {
    color: var(--muted);
    font-size: var(--text-xs);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-pill);
    padding: 0 var(--space-2);
  }
  .empty {
    color: var(--muted);
    font-size: var(--text-sm);
    padding: var(--space-2);
  }
  .error {
    margin: 0;
    color: var(--error);
    font-size: var(--text-sm);
  }
  .dialog-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
  .grow {
    flex: 1 1 0;
  }
  .btn :global(svg) {
    margin-right: var(--space-1);
  }
</style>
