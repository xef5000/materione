<script lang="ts">
  import AssetsPanel from '../components/AssetsPanel.svelte';
  import { ops, unwrap } from '../api';
  import {
    errorMessage,
    loadWorkspace,
    logActivity,
    openDoc,
    selectPack,
    session,
    workspace,
  } from '../state.svelte';

  async function createPack() {
    const id = prompt('New pack id (a-z, 0-9, _ or -):');
    if (!id || !session.channel) {
      return;
    }
    try {
      unwrap(await ops.createPack(session.channel, id));
      logActivity('info', `created pack ${id}`);
      await loadWorkspace();
      await selectPack(id);
    } catch (e) {
      logActivity('error', `edit.createPack: ${errorMessage(e)}`);
    }
  }
</script>

<div class="head">
  <h3>Packs</h3>
  <button onclick={() => void createPack()}>+ new</button>
</div>
<ul>
  {#each workspace.packs as pack (pack)}
    <li>
      <button
        class:active={pack === workspace.activePack}
        onclick={() => void selectPack(pack)}
      >
        {pack}
      </button>
    </li>
  {/each}
</ul>

<h3>Resources</h3>
<ul class="resources">
  {#each workspace.resources as resource (resource.pack + '/' + resource.path + '#' + resource.id)}
    <li>
      <button onclick={() => openDoc(resource)} title={resource.path}>
        <span class="id">{resource.id}</span>
        <span class="path">{resource.path}</span>
      </button>
    </li>
  {/each}
  {#if workspace.resources.length === 0}
    <li class="empty">nothing here yet</li>
  {/if}
</ul>

{#if workspace.activePack}
  <AssetsPanel pack={workspace.activePack} />
{/if}

<style>
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  h3 {
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--muted);
    margin: 14px 0 6px;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  li button {
    width: 100%;
    text-align: left;
    background: none;
    border: none;
    color: inherit;
    border-radius: var(--radius);
    padding: 5px 8px;
    cursor: pointer;
    display: block;
  }
  li button:hover,
  li button.active {
    background: var(--bg-raised);
  }
  .head button {
    font-size: 11px;
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    cursor: pointer;
    padding: 2px 8px;
  }
  .resources .id {
    font-weight: 600;
    display: block;
  }
  .resources .path {
    font-size: 11px;
    color: var(--muted);
  }
  .empty {
    color: var(--faint);
    font-style: italic;
    padding: 2px 8px;
  }
</style>
