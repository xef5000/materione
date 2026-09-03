<script lang="ts">
  import { ops, unwrap } from '../api';
  import type { ResourceRef } from '../api';
  import { delivery, errorMessage, loadWorkspace, logActivity, openDoc, refreshDelivery, refreshPack, selectPack, session, workspace } from '../state.svelte';

  let creating = $state(false);
  let error = $state('');

  const items = $derived(workspace.resources.filter((resource) => resource.type === 'items'));

  function idFromName(name: string): string {
    return name.trim().toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 48);
  }

  async function createItem() {
    const name = prompt('What should this item be called?');
    if (name === null || name.trim() === '') return;
    let pack = workspace.activePack;
    const id = idFromName(name);
    if (!id) {
      error = 'Use at least one letter or number in the item name.';
      return;
    }
    creating = true;
    error = '';
    try {
      let createPack = false;
      if (!pack) {
        const project = prompt('Name your project. This is only asked once.');
        if (project === null) return;
        pack = idFromName(project);
        if (!pack || pack.length < 2) throw new Error('Project name must contain at least two letters or numbers.');
        createPack = true;
      }
      const created = unwrap(await ops.createResource(
        session.channel!, pack, id, name.trim(), createPack,
      ));
      const path = String(created.path);
      if (createPack) {
        await loadWorkspace();
        await selectPack(pack);
      }
      logActivity('save', `created ${pack}:${id}; preview is building`);
      await refreshPack();
      openDoc({ pack, type: 'items', path, id });
    } catch (e) {
      error = errorMessage(e);
    } finally {
      creating = false;
    }
  }

  async function publish() {
    if (!session.channel || !delivery.candidateSha256) return;
    creating = true;
    error = '';
    try {
      unwrap(await ops.publishCandidate(session.channel, delivery.candidateSha256));
      await refreshDelivery();
      logActivity('save', 'published the previewed pack to all players');
    } catch (e) {
      error = errorMessage(e);
      await refreshDelivery();
    } finally {
      creating = false;
    }
  }
</script>

<div class="content">
  <header>
    <div>
      <h2>Content</h2>
      <p>Create and manage what players experience. Files and YAML are available in Advanced Files.</p>
    </div>
    <div class="actions">
      <div class:ready={delivery.candidateSha256} class="release">
        <strong>
          {delivery.loaded
            ? delivery.candidateSha256
              ? 'Preview ready'
              : delivery.publishedSha256
                ? 'Published'
                : 'Not published'
            : 'Checking release…'}
        </strong>
        {#if delivery.candidateSha256}
          <small>{delivery.candidateSha256.slice(0, 12)}</small>
        {:else if delivery.publishedSha256}
          <small>{delivery.publishedSha256.slice(0, 12)}</small>
        {/if}
      </div>
      <button
        disabled={creating || !delivery.loaded || !delivery.candidateSha256}
        onclick={() => void publish()}
      >Publish preview</button>
      <button class="primary" disabled={creating} onclick={() => void createItem()}>
        {creating ? 'working…' : 'Create item'}
      </button>
    </div>
  </header>

  {#if error}<p class="error">{error}</p>{/if}

  {#if !workspace.activePack}
    <section class="empty">
      <h3>Start your first project</h3>
      <p>Create an item to name the project and begin. Materione organizes its files for you.</p>
      <button class="primary" disabled={creating} onclick={() => void createItem()}>Create your first item</button>
    </section>
  {:else if items.length === 0}
    <section class="empty">
      <h3>Your project has no items yet</h3>
      <p>Give players something they can hold, use, and customize.</p>
      <button class="primary" disabled={creating} onclick={() => void createItem()}>Create item</button>
    </section>
  {:else}
    <div class="cards">
      {#each items as item (`${item.pack}/${item.path}/${item.id}`)}
        <button class="card" onclick={() => openDoc(item)}>
          <strong>{item.id.replaceAll('_', ' ')}</strong>
          <span>{item.pack}</span>
          <small>{item.id}</small>
        </button>
      {/each}
    </div>
  {/if}
</div>

<style>
  .content { padding: 4px; }
  header { display:flex; justify-content:space-between; gap:12px; align-items:start; }
  .actions { display:flex; gap:8px; }
  .release { display:flex; flex-direction:column; align-items:flex-end; justify-content:center; min-width:92px; }
  .release strong { font-size:11px; color:var(--muted); }
  .release.ready strong { color:var(--success); }
  .release small { font-family:var(--font-mono); color:var(--muted); font-size:9px; }
  h2 { margin:0; font-size:16px; }
  h3 { margin:0 0 6px; }
  p { color:var(--muted); font-size:12.5px; margin:5px 0; line-height:1.45; }
  button { border:1px solid var(--border-strong); background:none; color:inherit; border-radius:var(--radius); padding:6px 10px; cursor:pointer; }
  .primary { background:var(--primary); border-color:var(--primary); color:white; white-space:nowrap; }
  button:disabled { opacity:.5; cursor:default; }
  .empty { margin-top:16px; padding:16px; border:1px dashed var(--border-strong); border-radius:var(--radius-lg); }
  .cards { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:8px; margin-top:14px; }
  .card { text-align:left; padding:12px; background:var(--bg-raised); }
  .card:hover { background:var(--bg-hover); border-color:var(--accent); }
  .card strong { display:block; text-transform:capitalize; }
  .card span,.card small { display:block; color:var(--muted); font-size:11px; margin-top:4px; }
  .error { color:var(--error); background:var(--error-bg); border-radius:var(--radius); padding:8px; }
</style>