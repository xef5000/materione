<script lang="ts">
  import PromptDialog from './PromptDialog.svelte';
  import { ops, unwrap } from '../api';
  import {
    delivery,
    errorMessage,
    loadWorkspace,
    logActivity,
    refreshDelivery,
    selectPack,
    session,
    workspace,
  } from '../state.svelte';

  let busy = $state(false);
  let newProject = $state(false);

  const release = $derived(
    !delivery.loaded
      ? { label: 'checking…', tone: 'idle' }
      : delivery.candidateSha256
        ? { label: 'preview ready', tone: 'ready' }
        : delivery.publishedSha256
          ? { label: 'published', tone: 'live' }
          : { label: 'not published', tone: 'idle' },
  );

  async function createProject(id: string) {
    newProject = false;
    busy = true;
    try {
      unwrap(await ops.createPack(session.channel!, id));
      await loadWorkspace();
      await selectPack(id);
      logActivity('info', `created project ${id}`);
    } catch (e) {
      logActivity('error', `new project: ${errorMessage(e)}`);
    } finally {
      busy = false;
    }
  }

  async function publish() {
    if (!session.channel || !delivery.candidateSha256) return;
    busy = true;
    try {
      unwrap(await ops.publishCandidate(session.channel, delivery.candidateSha256));
      await refreshDelivery();
      logActivity('save', 'published the previewed pack to all players');
    } catch (e) {
      logActivity('error', `publish: ${errorMessage(e)}`);
      await refreshDelivery();
    } finally {
      busy = false;
    }
  }
</script>

<header class="appheader">
  <div class="packs">
    {#if workspace.packs.length}
      <select
        aria-label="project"
        value={workspace.activePack ?? ''}
        onchange={(event) => void selectPack(event.currentTarget.value)}
      >
        {#each workspace.packs as pack (pack)}
          <option value={pack}>{pack}</option>
        {/each}
      </select>
    {/if}
    <button class="ghost" disabled={busy} onclick={() => (newProject = true)}>
      New project
    </button>
  </div>

  <span class="brand">materione</span>

  <div class="release">
    <span class="chip {release.tone}" title="pack delivered to players">{release.label}</span>
    <button
      class="primary"
      disabled={busy || !delivery.candidateSha256}
      title={delivery.candidateSha256
        ? 'send the previewed pack to every player'
        : 'nothing new to publish'}
      onclick={() => void publish()}
    >Publish</button>
  </div>
</header>

{#if newProject}
  <PromptDialog
    title="New project"
    label="project id"
    initial=""
    hint="2..32 characters of a-z 0-9 _ -"
    onOk={(value) => void createProject(value)}
    onCancel={() => (newProject = false)}
  />
{/if}

<style>
  .appheader {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, auto) minmax(0, 1fr);
    align-items: center;
    gap: 12px;
    padding: 6px 12px;
    border-bottom: 1px solid var(--border);
    background: var(--bg-panel);
  }
  .packs {
    display: flex;
    gap: 6px;
    align-items: center;
    min-width: 0;
  }
  select {
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: 3px 6px;
    font-size: 12.5px;
    max-width: 220px;
  }
  .brand {
    font-size: 12px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--faint);
  }
  .release {
    display: flex;
    gap: 8px;
    align-items: center;
    justify-content: flex-end;
    min-width: 0;
  }
  .chip {
    font-size: 11px;
    padding: 2px 8px;
    border-radius: 999px;
    border: 1px solid var(--border-strong);
    color: var(--muted);
    white-space: nowrap;
  }
  .chip.ready {
    color: var(--warn, var(--accent));
    border-color: currentColor;
  }
  .chip.live {
    color: var(--ok);
    border-color: currentColor;
  }
  button {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: 3px 10px;
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
  }
  button:hover:not(:disabled) {
    background: var(--bg-hover);
  }
  .primary {
    background: var(--primary);
    border-color: var(--primary);
    color: white;
  }
  button:disabled {
    opacity: 0.45;
    cursor: default;
  }
</style>
