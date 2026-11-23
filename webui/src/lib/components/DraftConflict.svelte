<script lang="ts">
  /**
   * The conflict banner of a document draft (web.md section 4 "Caches and refresh"): the server's copy moved (an approved
   * code-mode transaction or its revert, another editor session, a hand edit) while this tab held unsaved edits. Neither
   * side is overwritten: the draft is kept and flagged `stale`, Save is blocked, and the owner picks here, seeing both.
   * A flagged draft nothing was typed into is re-read on its own and never shows this.
   */
  import { keepDraftOverServer, readServerVersion, reloadDocumentDraft, draftDirty, rawDraftDirty, type DocumentDraft } from '../drafts.svelte';
  import { session } from '../state.svelte';
  import Button from '../ui/Button.svelte';

  interface Props {
    draft: DocumentDraft;
    /** Replaces the plain reload for "Use server version" (a surface with local input state resets it first). */
    onUseServer?: () => Promise<void> | void;
  }

  let { draft, onUseServer }: Props = $props();

  let comparing = $state(false);
  let serverCopy = $state<{ rev: number; raw: string } | null>(null);
  let readError = $state('');

  const dirty = $derived(draftDirty(draft));
  const busy = $derived(draft.loading || draft.saving);
  /** What the draft would write: the YAML text typed over the file, or the staged field edits. */
  const draftText = $derived(
    rawDraftDirty(draft)
      ? draft.raw
      : draft.edits.map((edit) => `${edit.field.join('.')}: ${edit.remove ? '(removed)' : JSON.stringify(edit.value)}`).join('\n'),
  );

  // A different document state is a different question: a stale server copy is never shown for a new conflict.
  $effect(() => {
    void draft.rev;
    void draft.stale;
    serverCopy = null;
    comparing = false;
  });

  async function toggleCompare(): Promise<void> {
    comparing = !comparing;
    if (!comparing || serverCopy !== null || !session.channel) return;
    readError = '';
    try {
      serverCopy = await readServerVersion(draft, session.channel);
    } catch (error) {
      readError = error instanceof Error ? error.message : String(error);
    }
  }

  async function useServer(): Promise<void> {
    if (onUseServer) await onUseServer();
    else if (session.channel) await reloadDocumentDraft(draft, session.channel, true);
  }

  async function keepMine(): Promise<void> {
    if (session.channel) await keepDraftOverServer(draft, session.channel);
  }
</script>

{#if draft.stale}
  <div class="conflict" role="alert" data-testid="draft-conflict">
    <p>
      {#if dirty}
        This file changed on the server while you had unsaved edits. Your draft is kept and nothing was overwritten. Choose which to keep.
      {:else}
        This file changed on the server. Use the server version to continue.
      {/if}
    </p>
    <div class="actions">
      <Button size="sm" disabled={busy || !session.channel} onclick={() => void useServer()}>Use server version</Button>
      {#if dirty}
        <Button size="sm" disabled={busy || !session.channel} onclick={() => void keepMine()}>Keep my draft</Button>
        <Button size="sm" variant="ghost" aria-expanded={comparing} onclick={() => void toggleCompare()}>
          {comparing ? 'Hide comparison' : 'Compare'}
        </Button>
      {/if}
    </div>
    {#if comparing}
      <div class="compare">
        <section aria-label="Your draft">
          <h4>Your draft{rawDraftDirty(draft) ? '' : ' (staged field edits)'}</h4>
          <pre>{draftText}</pre>
        </section>
        <section aria-label="Server version">
          <h4>Server version{serverCopy ? ` (revision ${serverCopy.rev})` : ''}</h4>
          {#if readError}
            <p class="read-error">Could not read the server's copy: {readError}</p>
          {:else if serverCopy}
            <pre>{serverCopy.raw}</pre>
          {:else}
            <p>Reading…</p>
          {/if}
        </section>
      </div>
    {/if}
  </div>
{/if}

<style>
  .conflict {
    margin-top: var(--space-3);
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    background: var(--warn-bg);
    color: var(--warn);
    border-radius: var(--radius);
    padding: var(--space-2) var(--space-3);
    font-size: var(--text-base);
  }
  p {
    margin: 0;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .compare {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
    gap: var(--space-3);
  }
  h4 {
    margin: 0 0 var(--space-1);
    font-size: var(--text-sm);
  }
  pre {
    margin: 0;
    max-height: 14rem;
    overflow: auto;
    white-space: pre-wrap;
    word-break: break-word;
    font-size: var(--text-sm);
    background: var(--bg-input);
    color: var(--fg-code);
    border-radius: var(--radius);
    padding: var(--space-2);
  }
</style>
