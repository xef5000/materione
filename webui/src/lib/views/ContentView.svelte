<script lang="ts">
  /**
   * The Content tab: one type at a time, browsed like a file explorer. Every
   * row is a thing a player will see; the YAML behind it stays in Advanced
   * Files. Nothing here scrolls sideways and nothing here explains itself in
   * prose — the toolbar is the explanation.
   */
  import ContextMenu from '../components/ContextMenu.svelte';
  import type { MenuItem } from '../components/ContextMenu.svelte';
  import PromptDialog from '../components/PromptDialog.svelte';
  import Studio from '../studio/Studio.svelte';
  import { ops, unwrap } from '../api';
  import type { ResourceRef } from '../api';
  import {
    breadcrumbs,
    currentFolder,
    humanise,
    idFromName,
    listing,
    parentFolder,
    typeTabs,
    type BrowserEntry,
  } from '../browser';
  import {
    browseTo,
    closeTab,
    content,
    errorMessage,
    loadWorkspace,
    logActivity,
    openStudio,
    refreshPack,
    revealInAdvanced,
    selectPack,
    session,
    workspace,
  } from '../state.svelte';

  let error = $state('');
  let busy = $state(false);
  let menu = $state<{ x: number; y: number; items: MenuItem[] } | null>(null);
  let dialog = $state<
    null | { title: string; label: string; initial: string; hint: string; onOk: (value: string) => void }
  >(null);
  let list = $state<HTMLDivElement>();
  let history = $state<string[]>([]);
  let typeahead = { text: '', at: 0 };

  const tabs = $derived(typeTabs(workspace.types));
  const tab = $derived(
    tabs.find((candidate) => candidate.directory === content.type)
      ?? tabs.find((candidate) => candidate.enabled),
  );
  const folder = $derived(currentFolder(content.type, content.folder));
  const crumbs = $derived(breadcrumbs(content.type, content.folder));
  const rows = $derived(
    listing(content.type, content.folder, workspace.resources, workspace.folders, content.search),
  );
  const selected = $derived(rows.find((row) => row.path === content.selected) ?? null);

  // A tab the registry no longer offers must not strand the browser.
  $effect(() => {
    if (tabs.length && !tabs.some((candidate) => candidate.directory === content.type)) {
      content.type = tabs[0]!.directory;
    }
  });

  // ---------------------------------------------------------------- navigation

  function go(target: string): void {
    history.push(folder);
    browseTo(content.type, target);
    content.search = '';
  }

  function back(): void {
    const previous = history.pop();
    if (previous !== undefined) browseTo(content.type, previous);
  }

  function up(): void {
    const parent = parentFolder(content.type, content.folder);
    if (parent) go(parent);
  }

  function switchType(directory: string): void {
    history = [];
    browseTo(directory, '');
    content.search = '';
  }

  function refOf(entry: BrowserEntry): ResourceRef {
    return {
      pack: workspace.activePack!,
      type: content.type,
      path: entry.path,
      id: entry.id,
    };
  }

  function open(entry: BrowserEntry): void {
    if (entry.kind === 'folder') {
      go(entry.path);
    } else {
      openStudio(refOf(entry));
    }
  }

  // ------------------------------------------------------------------- actions

  async function currentRev(path: string): Promise<number> {
    return Number(unwrap(await ops.get(session.channel!, workspace.activePack!, path)).rev ?? 0);
  }

  async function run(what: string, action: () => Promise<void>): Promise<void> {
    busy = true;
    error = '';
    try {
      await action();
    } catch (e) {
      error = `${what}: ${errorMessage(e)}`;
    } finally {
      busy = false;
    }
  }

  function createEntry(): void {
    const singular = tab?.singular ?? 'entry';
    dialog = {
      title: `New ${singular}`,
      label: `${singular} name`,
      initial: '',
      hint: 'players see this name; the id is derived from it',
      onOk: (name) => {
        dialog = null;
        void run('create', async () => {
          const id = idFromName(name);
          if (!id) throw new Error('use at least one letter or number');
          let pack = workspace.activePack;
          let createPack = false;
          if (!pack) {
            pack = 'default';
            createPack = true;
          }
          const created = unwrap(await ops.createResource(
            session.channel!, pack, id, name.trim(), createPack, content.type,
            content.folder || content.type,
          ));
          if (createPack) {
            await loadWorkspace();
            await selectPack(pack);
          }
          await refreshPack();
          logActivity('save', `created ${pack}:${id}; preview is building`);
          openStudio({ pack, type: content.type, path: String(created.path), id });
        });
      },
    };
  }

  function createFolder(): void {
    dialog = {
      title: 'New folder',
      label: 'folder name',
      initial: '',
      hint: `created inside ${folder}/`,
      onOk: (name) => {
        dialog = null;
        void run('new folder', async () => {
          unwrap(await ops.createFolder(session.channel!, workspace.activePack!, `${folder}/${name}`));
          await refreshPack();
        });
      },
    };
  }

  function rename(entry: BrowserEntry): void {
    const isFolder = entry.kind === 'folder';
    dialog = {
      title: `Rename ${entry.name}`,
      label: isFolder ? 'folder name' : 'id',
      initial: entry.name,
      hint: isFolder ? 'the entries inside move with it' : 'a-z, 0-9 and _',
      onOk: (value) => {
        dialog = null;
        void run('rename', async () => {
          const pack = workspace.activePack!;
          if (isFolder) {
            const target = `${folder}/${value}`;
            unwrap(await ops.moveFolder(session.channel!, pack, entry.path, target));
          } else {
            const id = idFromName(value);
            if (!id) throw new Error('use at least one letter or number');
            const rev = await currentRev(entry.path);
            unwrap(await ops.move(session.channel!, pack, entry.path, `${folder}/${id}.yml`, rev));
            closeTab(`${pack}/${entry.path}`);
          }
          await refreshPack();
        });
      },
    };
  }

  function duplicate(entry: BrowserEntry): void {
    dialog = {
      title: `Duplicate ${entry.name}`,
      label: 'new id',
      initial: `${entry.id}_copy`,
      hint: 'everything is copied, including anything only Advanced Files can edit',
      onOk: (value) => {
        dialog = null;
        void run('duplicate', async () => {
          const id = idFromName(value);
          if (!id) throw new Error('use at least one letter or number');
          unwrap(await ops.duplicateResource(
            session.channel!, workspace.activePack!, entry.path, id, folder,
          ));
          await refreshPack();
        });
      },
    };
  }

  function moveTo(entry: BrowserEntry): void {
    dialog = {
      title: `Move ${entry.name}`,
      label: 'destination folder',
      initial: folder,
      hint: `folders in this tab: ${
        workspace.folders.filter((f) => f.startsWith(content.type)).join(', ') || content.type
      }`,
      onOk: (value) => {
        dialog = null;
        void run('move', async () => {
          const pack = workspace.activePack!;
          const target = value.replace(/\/+$/, '');
          if (entry.kind === 'folder') {
            unwrap(await ops.moveFolder(session.channel!, pack, entry.path, `${target}/${entry.name}`));
          } else {
            const rev = await currentRev(entry.path);
            const file = entry.path.slice(entry.path.lastIndexOf('/') + 1);
            unwrap(await ops.move(session.channel!, pack, entry.path, `${target}/${file}`, rev));
            closeTab(`${pack}/${entry.path}`);
          }
          await refreshPack();
        });
      },
    };
  }

  function remove(entry: BrowserEntry): void {
    const what = entry.kind === 'folder' ? `folder ${entry.name}` : entry.name;
    if (!confirm(`Delete ${what}? This cannot be undone.`)) return;
    void run('delete', async () => {
      const pack = workspace.activePack!;
      if (entry.kind === 'folder') {
        unwrap(await ops.deleteFolder(session.channel!, pack, entry.path));
      } else {
        const rev = await currentRev(entry.path);
        unwrap(await ops.remove(session.channel!, pack, entry.path, rev));
        closeTab(`${pack}/${entry.path}`);
      }
      if (content.selected === entry.path) content.selected = null;
      await refreshPack();
    });
  }

  // -------------------------------------------------------------- interaction

  function itemsFor(entry: BrowserEntry): MenuItem[] {
    const items: MenuItem[] = [{ label: 'Open', run: () => open(entry) }];
    items.push({ label: 'Rename…', run: () => rename(entry) });
    if (entry.kind === 'doc') {
      items.push({ label: 'Duplicate…', run: () => duplicate(entry) });
    }
    items.push({ label: 'Move to…', run: () => moveTo(entry) });
    if (entry.kind === 'doc') {
      items.push({ label: 'Reveal in Advanced Files', run: () => revealInAdvanced(refOf(entry)) });
    }
    items.push({ label: 'Delete…', danger: true, run: () => remove(entry) });
    return items;
  }

  function showMenu(entry: BrowserEntry, event: MouseEvent): void {
    event.preventDefault();
    content.selected = entry.path;
    menu = { x: event.clientX, y: event.clientY, items: itemsFor(entry) };
  }

  function onKey(event: KeyboardEvent): void {
    if (dialog) return;
    const index = rows.findIndex((row) => row.path === content.selected);
    const move = (next: number) => {
      const row = rows[Math.max(0, Math.min(rows.length - 1, next))];
      if (row) {
        content.selected = row.path;
        list?.querySelector<HTMLElement>(`[data-path="${CSS.escape(row.path)}"]`)
          ?.scrollIntoView({ block: 'nearest' });
      }
    };
    switch (event.key) {
      case 'ArrowDown': event.preventDefault(); move(index + 1); return;
      case 'ArrowUp': event.preventDefault(); move(index - 1); return;
      case 'Home': event.preventDefault(); move(0); return;
      case 'End': event.preventDefault(); move(rows.length - 1); return;
      case 'Enter': if (selected) { event.preventDefault(); open(selected); } return;
      case 'Backspace': event.preventDefault(); up(); return;
      case 'F2': if (selected) { event.preventDefault(); rename(selected); } return;
      case 'Delete': if (selected) { event.preventDefault(); remove(selected); } return;
      default: break;
    }
    // Type-ahead: the way every file manager jumps to a name.
    if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      const now = Date.now();
      typeahead = {
        text: (now - typeahead.at < 900 ? typeahead.text : '') + event.key.toLowerCase(),
        at: now,
      };
      const hit = rows.find((row) => row.name.toLowerCase().startsWith(typeahead.text));
      if (hit) move(rows.indexOf(hit));
    }
  }
</script>

{#if content.open}
  <Studio resource={content.open} onClose={() => openStudio(null)} />
{:else}
  <div class="content">
    <nav class="types" aria-label="content types">
      {#each tabs as entry (entry.directory)}
        <button
          class:active={entry.directory === content.type}
          disabled={!entry.enabled}
          title={entry.note ?? entry.label}
          onclick={() => switchType(entry.directory)}
        >
          {entry.label}{#if !entry.enabled}<em>soon</em>{/if}
        </button>
      {/each}
      <input
        class="search"
        type="search"
        placeholder="search {tab?.label.toLowerCase() ?? ''}…"
        spellcheck="false"
        bind:value={content.search}
      />
    </nav>

    <div class="toolbar">
      <button class="icon" title="back" disabled={!history.length} onclick={back}>←</button>
      <button
        class="icon"
        title="up one folder"
        disabled={folder === content.type}
        onclick={up}
      >↑</button>
      <ol class="crumbs">
        {#each crumbs as crumb, index (crumb.folder)}
          <li>
            <button onclick={() => go(crumb.folder)}>{crumb.label}</button>
            {#if index < crumbs.length - 1}<span aria-hidden="true">/</span>{/if}
          </li>
        {/each}
      </ol>
      <div class="layout" role="group" aria-label="layout">
        <button
          class:active={content.layout === 'details'}
          title="details"
          onclick={() => (content.layout = 'details')}
        >▤</button>
        <button
          class:active={content.layout === 'grid'}
          title="icons"
          onclick={() => (content.layout = 'grid')}
        >⊞</button>
      </div>
      <button class="ghost" disabled={busy || !workspace.activePack} onclick={createFolder}>
        New folder
      </button>
      <button class="primary" disabled={busy} onclick={createEntry}>
        + New {tab?.singular ?? 'entry'}
      </button>
    </div>

    {#if error}<p class="error">{error}</p>{/if}

    <div
      class="list {content.layout}"
      bind:this={list}
      role="listbox"
      tabindex="0"
      aria-label="{tab?.label ?? 'content'} in {folder}"
      onkeydown={onKey}
    >
      {#if content.layout === 'details' && rows.length}
        <div class="head" aria-hidden="true">
          <span>Name</span><span>Id</span><span>Where</span><span>Ids</span>
        </div>
      {/if}
      {#each rows as row (row.path)}
        <div
          class="row"
          class:selected={row.path === content.selected}
          data-path={row.path}
          role="option"
          aria-selected={row.path === content.selected}
          tabindex="-1"
          onclick={() => (content.selected = row.path)}
          ondblclick={() => open(row)}
          oncontextmenu={(event) => showMenu(row, event)}
          onkeydown={() => {}}
        >
          <span class="glyph" aria-hidden="true">{row.kind === 'folder' ? '📁' : '▪'}</span>
          <span class="name">{row.kind === 'folder' ? row.name : humanise(row.id)}</span>
          <span class="id">{row.kind === 'folder' ? `${row.count} inside` : row.id}</span>
          <span class="where">{row.location || (row.kind === 'folder' ? '' : '·')}</span>
          <span class="extra">{row.ids.length > 1 ? `+${row.ids.length - 1} more` : ''}</span>
        </div>
      {/each}

      {#if !rows.length}
        <div class="empty">
          {#if !workspace.activePack}
            <p>No project yet.</p>
            <button class="primary" onclick={createEntry}>+ New {tab?.singular ?? 'entry'}</button>
          {:else if content.search}
            <p>No match for “{content.search}”.</p>
            <button class="ghost" onclick={() => (content.search = '')}>Clear search</button>
          {:else}
            <p>Nothing in {folder}/ yet.</p>
            <button class="primary" onclick={createEntry}>+ New {tab?.singular ?? 'entry'}</button>
          {/if}
        </div>
      {/if}
    </div>
  </div>
{/if}

{#if menu}
  <ContextMenu x={menu.x} y={menu.y} items={menu.items} onClose={() => (menu = null)} />
{/if}

{#if dialog}
  {@const current = dialog}
  <PromptDialog
    title={current.title}
    label={current.label}
    initial={current.initial}
    hint={current.hint}
    onOk={(value) => current.onOk(value)}
    onCancel={() => (dialog = null)}
  />
{/if}

<style>
  .content {
    height: 100%;
    min-width: 0;
    display: grid;
    grid-template-rows: auto auto auto minmax(0, 1fr);
  }

  /* type tabs */
  .types {
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 0 10px;
    border-bottom: 1px solid var(--border);
    overflow: hidden;
  }
  .types button {
    background: none;
    border: none;
    border-bottom: 2px solid transparent;
    color: var(--muted);
    font-size: 13px;
    padding: 9px 12px;
    cursor: pointer;
    white-space: nowrap;
  }
  .types button:hover:not(:disabled) {
    color: var(--fg);
  }
  .types button.active {
    color: var(--fg);
    border-bottom-color: var(--accent);
  }
  .types button:disabled {
    color: var(--faint);
    cursor: default;
  }
  .types em {
    font-style: normal;
    font-size: 9px;
    margin-left: 5px;
    padding: 1px 4px;
    border-radius: 4px;
    border: 1px solid var(--border-strong);
  }
  .search {
    margin-left: auto;
    min-width: 0;
    flex: 0 1 240px;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 4px 8px;
    font-size: 12px;
  }

  /* toolbar */
  .toolbar {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 7px 10px;
    border-bottom: 1px solid var(--border);
    min-width: 0;
    overflow: hidden;
  }
  .crumbs {
    display: flex;
    align-items: center;
    gap: 2px;
    list-style: none;
    margin: 0;
    padding: 0;
    flex: 1;
    min-width: 0;
    overflow: hidden;
  }
  .crumbs li {
    display: flex;
    align-items: center;
    gap: 2px;
    min-width: 0;
  }
  .crumbs button {
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
    font-size: 12.5px;
    padding: 2px 4px;
    border-radius: var(--radius);
    max-width: 180px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .crumbs button:hover {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .crumbs li:last-child button {
    color: var(--fg);
  }
  .crumbs span {
    color: var(--faint);
  }
  .layout {
    display: flex;
    gap: 2px;
  }
  .toolbar button,
  .layout button {
    background: none;
    border: 1px solid transparent;
    color: var(--muted);
    border-radius: var(--radius);
    padding: 3px 8px;
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
  }
  .layout button.active {
    color: var(--fg);
    border-color: var(--border-strong);
  }
  .toolbar .ghost {
    border-color: var(--border-strong);
    color: inherit;
  }
  .toolbar .primary {
    background: var(--primary);
    border-color: var(--primary);
    color: white;
  }
  .toolbar button:disabled {
    opacity: 0.4;
    cursor: default;
  }

  /* listing */
  .list {
    min-width: 0;
    min-height: 0;
    overflow-x: hidden;
    overflow-y: auto;
    padding: 4px 6px 10px;
    outline: none;
  }
  .head,
  .details .row {
    display: grid;
    grid-template-columns: 18px minmax(0, 2fr) minmax(0, 1.6fr) minmax(0, 1.2fr) minmax(0, 90px);
    align-items: center;
    gap: 8px;
    padding: 4px 8px;
    min-width: 0;
  }
  .head {
    grid-template-columns: minmax(0, 2fr) minmax(0, 1.6fr) minmax(0, 1.2fr) minmax(0, 90px);
    padding-left: 34px;
    font-size: 10.5px;
    text-transform: uppercase;
    letter-spacing: 0.07em;
    color: var(--faint);
    border-bottom: 1px solid var(--border);
    margin-bottom: 3px;
  }
  .row {
    border-radius: var(--radius);
    cursor: default;
    font-size: 13px;
  }
  .row:hover {
    background: var(--bg-hover);
  }
  .row.selected {
    background: var(--bg-raised);
    outline: 1px solid var(--border-strong);
  }
  .row span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }
  .id,
  .where,
  .extra {
    color: var(--muted);
    font-size: 11.5px;
  }
  .id {
    font-family: var(--mono);
  }

  /* icon grid */
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(112px, 1fr));
    gap: 6px;
    align-content: start;
  }
  .grid .row {
    display: grid;
    justify-items: center;
    text-align: center;
    padding: 12px 6px;
    gap: 2px;
  }
  .grid .glyph {
    font-size: 26px;
  }
  .grid .where,
  .grid .extra {
    display: none;
  }

  .empty {
    grid-column: 1 / -1;
    display: grid;
    justify-items: center;
    gap: 10px;
    color: var(--muted);
    padding: 14vh 0;
  }
  .empty p {
    margin: 0;
  }
  .empty .primary {
    background: var(--primary);
    border: 1px solid var(--primary);
    color: white;
    border-radius: var(--radius);
    padding: 6px 12px;
    cursor: pointer;
  }
  .empty .ghost {
    background: none;
    border: 1px solid var(--border-strong);
    color: inherit;
    border-radius: var(--radius);
    padding: 6px 12px;
    cursor: pointer;
  }
  .error {
    margin: 8px 10px 0;
    color: var(--error);
    background: var(--error-bg);
    border-radius: var(--radius);
    padding: 7px 10px;
    font-size: 12.5px;
  }
</style>
