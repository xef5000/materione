<script lang="ts">
  import ContextMenu from '../components/ContextMenu.svelte';
  import type { MenuItem } from '../components/ContextMenu.svelte';
  import PromptDialog from '../components/PromptDialog.svelte';
  import { assetOps, ops, unwrap } from '../api';
  import type { ResourceRef } from '../api';
  import { buildTree, filterTree } from '../tree';
  import type { TreeNode } from '../tree';
  import {
    closeTab,
    errorMessage,
    loadWorkspace,
    logActivity,
    openAsset,
    openDoc,
    refreshPack,
    selectPack,
    session,
    workspace,
  } from '../state.svelte';

  const NEW_KINDS: { label: string; dir: string }[] = [
    { label: 'New Item', dir: 'items' },
    { label: 'New Block', dir: 'blocks' },
    { label: 'New Furniture', dir: 'furnitures' },
    { label: 'New Recipe', dir: 'recipes' },
  ];

  let filter = $state('');
  let menu = $state<{ x: number; y: number; items: MenuItem[] } | null>(null);
  let dialog =
    $state<null | { title: string; label: string; initial: string; hint: string; onOk: (value: string) => void }>(
      null,
    );
  let uploadTarget = $state<string | null>(null);
  let fileInput: HTMLInputElement;

  const tree = $derived(buildTree(workspace.resources, workspace.assets));

  // pack.yml is editable but browse.resources doesn't list it — synthesize it.
  const nodes = $derived(
    filterTree(
      [
        ...tree,
        {
          name: 'pack.yml',
          path: 'pack.yml',
          kind: 'packfile' as const,
          children: [],
        },
      ],
      filter,
    ),
  );

  // ---------------------------------------------------------------- actions

  async function currentRev(pack: string, path: string): Promise<number> {
    if (!session.channel) throw new Error('no channel');
    return Number(unwrap(await ops.get(session.channel, pack, path)).rev ?? 0);
  }

  async function createResource(dirPath: string) {
    dialog = {
      title: `Create in ${dirPath}/`,
      label: 'resource id',
      initial: '',
      hint: 'a-z, 0-9, _ or — the scaffold opens right after',
      onOk: async (id: string) => {
        dialog = null;
        const pack = workspace.activePack!;
        const path = `${dirPath}/${id}.yml`;
        try {
          unwrap(await ops.create(session.channel!, pack, path, id));
          logActivity('save', `created ${path} from scaffold`);
          await refreshPack();
          const created = workspace.resources.find(
            (candidate) => candidate.pack === pack && candidate.path === path,
          );
          if (created) openDoc(created);
        } catch (e) {
          logActivity('error', `edit.create: ${errorMessage(e)}`);
        }
      },
    };
  }

  function renameNode(node: TreeNode) {
    const pack = workspace.activePack!;
    const isAsset = node.kind === 'asset';
    dialog = {
      title: `Rename ${node.name}`,
      label: 'new pack-relative path',
      initial: node.path,
      hint: 'moving between directories is allowed',
      onOk: async (to: string) => {
        dialog = null;
        try {
          if (isAsset) {
            unwrap(await assetOps.move(session.channel!, pack, node.path, to));
            closeTab(`a:${pack}/${node.path}`);
          } else {
            const rev = await currentRev(pack, node.path);
            unwrap(await ops.move(session.channel!, pack, node.path, to, rev));
            closeTab(`${pack}/${node.path}`);
          }
          logActivity('save', `${node.path} → ${to}`);
          await refreshPack();
        } catch (e) {
          logActivity('error', `move: ${errorMessage(e)}`);
        }
      },
    };
  }

  function deleteNode(node: TreeNode) {
    const pack = workspace.activePack!;
    if (!confirm(`Delete ${node.path}? This cannot be undone.`)) return;
    void (async () => {
      try {
        if (node.kind === 'asset') {
          unwrap(await assetOps.remove(session.channel!, pack, node.path));
          closeTab(`a:${pack}/${node.path}`);
        } else {
          const rev = await currentRev(pack, node.path);
          unwrap(await ops.remove(session.channel!, pack, node.path, rev));
          closeTab(`${pack}/${node.path}`);
        }
        logActivity('info', `deleted ${node.path}`);
        await refreshPack();
      } catch (e) {
        logActivity('error', `delete: ${errorMessage(e)}`);
      }
    })();
  }

  function newPack() {
    dialog = {
      title: 'New pack',
      label: 'pack id',
      initial: '',
      hint: '2..32 chars of a-z 0-9 _ -',
      onOk: async (id: string) => {
        dialog = null;
        try {
          unwrap(await ops.createPack(session.channel!, id));
          logActivity('info', `created pack ${id}`);
          await loadWorkspace();
          await selectPack(id);
        } catch (e) {
          logActivity('error', `edit.createPack: ${errorMessage(e)}`);
        }
      },
    };
  }

  function uploadInto(path: string) {
    uploadTarget = path;
    fileInput.click();
  }

  async function doUpload(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !uploadTarget || !session.channel) return;
    const path = `${uploadTarget}/${file.name}`;
    const bytes = new Uint8Array(await file.arrayBuffer());
    try {
      unwrap(await assetOps.put(session.channel, workspace.activePack!, path, bytes));
      logActivity('save', `uploaded ${path} (${bytes.length} B); targeted reload queued`);
      await refreshPack();
    } catch (e) {
      logActivity('error', `upload: ${errorMessage(e)}`);
    }
  }

  // ------------------------------------------------------------ context menu

  function openFile(node: TreeNode): void {
    const pack = workspace.activePack!;
    if (node.kind === 'asset') {
      if (['png', 'jpg', 'jpeg'].includes(node.assetKind ?? '')) {
        openAsset(pack, node.path, node.name);
      }
      return;
    }
    const resource: ResourceRef = {
      pack,
      type: node.kind === 'packfile' ? '' : node.path.split('/')[0],
      path: node.path,
      id: node.resourceIds?.[0] ?? node.name.replace(/\.ya?ml$/, ''),
    };
    openDoc(resource);
  }

  function itemsFor(node: TreeNode): MenuItem[] {
    const items: MenuItem[] = [];
    if (node.kind === 'dir') {
      for (const kind of NEW_KINDS) {
        if (node.path === kind.dir || node.path.startsWith(kind.dir + '/')) {
          items.push({ label: `${kind.label}…`, run: () => createResource(node.path) });
          break;
        }
      }
      if (node.path === 'assets' || node.path.startsWith('assets/')) {
        items.push({ label: 'Upload here…', run: () => uploadInto(node.path) });
      }
    } else if (node.kind === 'packfile') {
      items.push({ label: 'Open', run: () => openFile(node) });
    } else {
      items.push({ label: 'Open', run: () => openFile(node) });
      items.push({ label: 'Rename / Move…', run: () => renameNode(node) });
      items.push({
        label: 'Delete…',
        danger: true,
        run: () => deleteNode(node),
      });
    }
    return items;
  }

  function showMenu(node: TreeNode, event: MouseEvent): void {
    event.preventDefault();
    const items = itemsFor(node);
    if (items.length > 0) {
      menu = { x: event.clientX, y: event.clientY, items };
    }
  }

  function toggleDir(node: TreeNode): void {
    if (node.kind !== 'dir') return;
    workspace.collapsed[node.path] = !workspace.collapsed[node.path];
  }
</script>

<div class="packrow">
  <select
    aria-label="active pack"
    value={workspace.activePack ?? ''}
    onchange={(event) => void selectPack(event.currentTarget.value)}
  >
    {#each workspace.packs as pack (pack)}
      <option value={pack}>{pack}</option>
    {/each}
  </select>
  <button title="new pack" onclick={newPack}>+ new</button>
</div>

<input class="filter" placeholder="filter files…" bind:value={filter} spellcheck="false" />

{#snippet row(node: TreeNode, depth: number)}
  <div class="row" style:padding-left="{6 + depth * 14}px">
    <button
      class="label"
      onclick={() => (node.kind === 'dir' ? toggleDir(node) : openFile(node))}
      oncontextmenu={(event) => showMenu(node, event)}
      title={node.resourceIds && node.resourceIds.length > 1
        ? node.resourceIds.join(', ')
        : node.path}
    >
      {#if node.kind === 'dir'}
        <svg class="icon" viewBox="0 0 16 16">
          <path
            d={workspace.collapsed[node.path] ? 'M4 6l4 4 4-4' : 'M4 10l4-4 4 4'}
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
          />
        </svg>
      {:else if node.kind === 'doc' || node.kind === 'packfile'}
        <svg class="icon" viewBox="0 0 16 16">
          <path d="M5 2h4l3 3v9H5z" fill="none" stroke="currentColor" stroke-width="1.2" />
        </svg>
      {:else if ['png', 'jpg', 'jpeg'].includes(node.assetKind ?? '')}
        <svg class="icon accent" viewBox="0 0 16 16">
          <rect x="2.5" y="3.5" width="11" height="9" rx="1" fill="none" stroke="currentColor" />
          <path d="M4 11.5l3-3 2 2 2-2.5 1.5 3.5" fill="none" stroke="currentColor" stroke-width="1.1" />
        </svg>
      {:else}
        <span class="glyph">{(node.assetKind ?? '?') === 'json' ? '{}' : '▪'}</span>
      {/if}
      <span class="name">{node.name}</span>
    </button>
  </div>
  {#if node.kind === 'dir' && !workspace.collapsed[node.path]}
    {#each node.children as child (child.path)}
      {@render row(child, depth + 1)}
    {/each}
  {:else if node.kind === 'doc' && node.resourceIds && node.resourceIds.length > 1}
    {#each node.resourceIds as id (id)}
      <div class="row sub" style:padding-left="{6 + depth * 14 + 22}px">
        <button
          class="label muted"
          onclick={() =>
            openDoc({
              pack: workspace.activePack!,
              type: node.path.split('/')[0],
              path: node.path,
              id,
            })}
          oncontextmenu={(event) => showMenu(node, event)}
        >
          <span class="name">{id}</span>
        </button>
      </div>
    {/each}
  {/if}
{/snippet}

<div class="tree">
  {#if workspace.activePack}
    {#each nodes as node (node.path)}
      {@render row(node, 0)}
    {/each}
  {:else}
    <p class="empty">create a pack to start</p>
  {/if}
</div>

<input type="file" hidden bind:this={fileInput} onchange={doUpload} />

{#if menu}
  <ContextMenu x={menu.x} y={menu.y} items={menu.items} onClose={() => (menu = null)} />
{/if}

{#if dialog}
  <PromptDialog
    title={dialog.title}
    label={dialog.label}
    initial={dialog.initial}
    hint={dialog.hint}
    onOk={(value) => void dialog.onOk(value)}
    onCancel={() => (dialog = null)}
  />
{/if}

<style>
  .packrow {
    display: flex;
    gap: 6px;
    align-items: center;
    margin-bottom: 8px;
  }
  .packrow select {
    flex: 1;
    min-width: 0;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: 4px 6px;
    font-size: 12.5px;
  }
  .packrow button {
    font-size: 11px;
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    cursor: pointer;
    padding: 3px 8px;
    white-space: nowrap;
  }
  .filter {
    width: 100%;
    box-sizing: border-box;
    margin-bottom: 8px;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 4px 8px;
    font-size: 12px;
  }
  .row button.label {
    display: flex;
    align-items: center;
    gap: 6px;
    width: 100%;
    background: none;
    border: none;
    color: inherit;
    font-size: 12.5px;
    padding: 3px 6px;
    border-radius: var(--radius);
    cursor: pointer;
    text-align: left;
  }
  .row button.label:hover {
    background: var(--bg-hover);
  }
  .sub .label {
    font-size: 11px;
  }
  .icon {
    width: 14px;
    height: 14px;
    flex: none;
    color: var(--muted);
  }
  .icon.accent {
    color: var(--accent);
  }
  .glyph {
    width: 14px;
    text-align: center;
    color: var(--muted);
    font-size: 10px;
    flex: none;
  }
  .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .muted {
    color: var(--muted);
  }
  .empty {
    color: var(--faint);
    font-style: italic;
    font-size: 12px;
    padding: 4px 8px;
  }
</style>
