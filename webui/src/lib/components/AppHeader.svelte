<script lang="ts">
  import Chip from '../ui/Chip.svelte';
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

  type ReleaseTone = 'neutral' | 'warn' | 'ok';
  const release = $derived<{ label: string; tone: ReleaseTone }>(
    !delivery.loaded
      ? {
          label: delivery.error ? 'Status unavailable' : 'Checking status',
          tone: 'neutral',
        }
      : hasUnpublishedCandidate
        ? { label: 'Candidate available', tone: 'warn' }
        : delivery.publishedSha256
          ? { label: 'Published', tone: 'ok' }
          : { label: 'Not published', tone: 'neutral' },
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
    <button class="btn" disabled={busy} onclick={() => (newProject = true)}>
      New project
    </button>
  </div>

  <div class="guidance">
    <span class="brand"><img src="/materione-logo.svg" alt="" width="16" height="16" />materione</span>
  </div>

  <div class="release">
    <div class="release-status">
      <Chip tone={release.tone} title={releaseDetails()} aria-live="polite">
        {release.label}
      </Chip>
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
      class="btn btn-primary"
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
    gap: var(--space-4);
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
    background: var(--bg-panel);
  }
  .packs {
    display: flex;
    gap: var(--space-2);
    align-items: center;
    min-width: 0;
  }
  select {
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-sm);
    max-width: 220px;
  }
  .guidance {
    min-width: 0;
    display: flex;
    flex-direction: column;
    justify-content: center;
    align-items: center;
    gap: var(--space-half);
    text-align: center;
  }
  .brand {
    display: inline-flex;
    flex: none;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-sm);
    font-weight: 600;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--muted);
  }
  .brand img {
    display: block;
    border-radius: var(--radius-sm);
  }
  .release {
    display: flex;
    gap: var(--space-3);
    align-items: center;
    justify-content: flex-end;
    min-width: 0;
  }
  .release-status {
    min-width: 0;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: var(--space-half);
  }
  .release-status code {
    max-width: 100%;
    overflow: hidden;
    color: var(--faint);
    font-family: var(--mono);
    font-size: var(--text-xs);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  @media (max-width: 900px) {
    .appheader {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2) var(--space-3);
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
      padding-inline: var(--space-2);
    }
    .packs {
      flex-basis: 100%;
    }
    .packs select {
      flex: 1;
      max-width: none;
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
