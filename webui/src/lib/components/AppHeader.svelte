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

  const hasUnpublishedCandidate = $derived(
    delivery.loaded &&
      Boolean(delivery.candidateSha256) &&
      delivery.candidateSha256 !== delivery.publishedSha256,
  );

  const release = $derived(
    !delivery.loaded
      ? {
          label: delivery.error ? 'Status unavailable' : 'Checking status',
          tone: 'idle',
        }
      : hasUnpublishedCandidate
        ? { label: 'Candidate available', tone: 'ready' }
        : delivery.publishedSha256
          ? { label: 'Published', tone: 'live' }
          : { label: 'Not published', tone: 'idle' },
  );

  function releaseDetails(): string {
    if (!delivery.loaded) {
      return delivery.error
        ? `Pack status could not be loaded: ${delivery.error}`
        : 'Checking current pack status; no cached hash is shown.';
    }
    return [
      `Candidate SHA-256: ${delivery.candidateSha256 || 'none'}`,
      `Published SHA-256: ${delivery.publishedSha256 || 'none'}`,
      'Candidate availability does not confirm player delivery or acceptance.',
    ].join('\n');
  }

  function shortHash(hash: string): string {
    return hash.slice(0, 8);
  }

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
    const channel = session.channel;
    const candidate = delivery.candidateSha256;
    if (!channel || !hasUnpublishedCandidate || !candidate) return;
    busy = true;
    try {
      unwrap(await ops.publishCandidate(channel, candidate));
      await refreshDelivery();
      logActivity('save', `published pack candidate ${shortHash(candidate)}`);
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

  <div class="guidance">
    <span class="brand">materione</span>
    <p>Save &amp; apply changes gameplay server-wide. Publish pack releases the resource pack to other players.</p>
  </div>

  <div class="release">
    <div class="release-status">
      <span class="chip {release.tone}" title={releaseDetails()} aria-live="polite">
        {release.label}
      </span>
      {#if delivery.loaded && delivery.candidateSha256}
        <code title={`Candidate SHA-256 ${delivery.candidateSha256}`}>
          candidate {shortHash(delivery.candidateSha256)}
        </code>
      {/if}
      {#if delivery.loaded && delivery.publishedSha256}
        <code title={`Published SHA-256 ${delivery.publishedSha256}`}>
          published {shortHash(delivery.publishedSha256)}
        </code>
      {/if}
    </div>
    <button
      class="primary"
      disabled={busy || !hasUnpublishedCandidate || !session.channel}
      title={!delivery.loaded
        ? 'Pack status is not verified; publishing is unavailable.'
        : hasUnpublishedCandidate
          ? 'Promote the currently shown candidate pack for player delivery.'
          : delivery.candidateSha256
            ? 'The current candidate is already published.'
            : 'No candidate pack is available to publish.'}
      onclick={() => void publish()}
    >Publish pack</button>
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
    grid-template-columns: minmax(0, 1fr) minmax(220px, 1.5fr) minmax(0, 1fr);
    align-items: stretch;
    gap: 14px;
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
  .guidance {
    min-width: 0;
    display: flex;
    flex-direction: column;
    justify-content: center;
    align-items: center;
    gap: 1px;
    text-align: center;
  }
  .brand {
    flex: none;
    font-size: 12px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--faint);
  }
  .guidance p {
    margin: 0;
    color: var(--muted);
    font-size: 10.5px;
    line-height: 1.3;
  }
  .release {
    display: flex;
    gap: 10px;
    align-items: center;
    justify-content: flex-end;
    min-width: 0;
  }
  .release-status {
    min-width: 0;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 2px;
  }
  .chip {
    max-width: 100%;
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
  .release-status code {
    max-width: 100%;
    overflow: hidden;
    color: var(--faint);
    font-family: var(--mono);
    font-size: 9px;
    text-overflow: ellipsis;
    white-space: nowrap;
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
  @media (max-width: 900px) {
    .appheader {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 12px;
    }
    .packs {
      flex: 1 1 40%;
    }
    .release {
      flex: 1 1 auto;
    }
    .guidance {
      order: 3;
      flex: 1 0 100%;
      align-items: flex-start;
      text-align: left;
    }
    .release-status {
      align-items: flex-end;
    }
  }
  @media (max-width: 560px) {
    .appheader {
      padding-inline: 8px;
    }
    .packs {
      flex-basis: 100%;
    }
    .packs select {
      flex: 1;
      max-width: none;
    }
    .guidance p {
      font-size: 10px;
    }
    .release {
      flex: 1 1 100%;
      justify-content: space-between;
    }
    .release-status {
      align-items: flex-start;
    }
  }
</style>
