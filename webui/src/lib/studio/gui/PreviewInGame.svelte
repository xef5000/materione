<script lang="ts">
  /**
   * "Preview in game" (gui.md §5, §7): opens this GUI's applied version in front of the session's
   * approved player, the one who opened this editor in game. It exists only when the connected jar
   * advertises the `gui` capability (an older release does not register the preview ops), and it is
   * disabled, with the reason as its tooltip, while that player is not online or the session has none.
   * Saving is what applies a change, so the preview shows the saved version; a dirty studio says so in
   * the tooltip.
   */
  import Icon from '../../components/Icon.svelte';
  import { ops, RpcError, unwrap } from '../../api';
  import type { ResourceRef } from '../../api';
  import { hasCapability } from '../../protocol/session';
  import { session } from '../../state.svelte';

  interface Props {
    resource: ResourceRef;
    /** The studio has edits that are not saved yet. */
    dirty?: boolean;
  }

  let { resource, dirty = false }: Props = $props();

  /** Whether the connected jar advertises the preview ops; false until the first answer. */
  let supported = $state(false);
  /** The approved player's standing: `null` until the first answer. */
  let target = $state<{ online: true; player: string } | { online: false } | null>(null);
  let busy = $state(false);
  let note = $state('');
  let noteIsError = $state(false);

  async function askTarget(): Promise<void> {
    const channel = session.channel;
    // The gate: an older jar never gets a preview op from the studio (editor-op-gates.json). A jar that
    // sends no capability list predates the list, so it cannot have these ops either: no list, no button.
    const blob = channel?.blob;
    supported = blob !== undefined && blob.capabilities !== undefined && hasCapability(blob, 'gui');
    if (!channel || !supported) {
      target = { online: false };
      return;
    }
    try {
      const data = unwrap(await ops.previewTarget(channel)) as { online?: unknown; player?: unknown };
      target = data.online === true && typeof data.player === 'string'
        ? { online: true, player: data.player }
        : { online: false };
    } catch {
      // a dropped channel, or a jar that refused the question: there is no preview to offer
      target = { online: false };
    }
  }

  // Ask on mount, then every few seconds: `session` is not reactive, so the poll is what notices a
  // channel that arrives later and a player who joins or leaves.
  $effect(() => {
    void askTarget();
    const timer = setInterval(() => void askTarget(), 5_000);
    return () => clearInterval(timer);
  });

  const enabled = $derived(target?.online === true && !busy);

  const reason = $derived.by(() => {
    if (!session.channel) return 'Connect to a server to preview in game';
    if (target === null) return 'Checking for the approved player…';
    if (!target.online) {
      return 'No approved player is online. Open this editor from the game with /materione editor, '
        + 'stay online, and the preview becomes available.';
    }
    if (dirty) return `Shows the saved version in game for ${target.player}. Save to see your changes.`;
    return `Open this GUI in game for ${target.player}`;
  });

  async function preview(): Promise<void> {
    const channel = session.channel;
    if (!channel || !supported || target?.online !== true) return;
    const player = target.player;
    busy = true;
    note = '';
    try {
      const data = unwrap(await ops.openGui(channel, resource.id)) as { queued?: unknown };
      noteIsError = false;
      note = data.queued === true ? `Opening for ${player}…` : `Opened for ${player}`;
    } catch (error) {
      noteIsError = true;
      note = error instanceof RpcError ? error.message : error instanceof Error ? error.message : String(error);
    } finally {
      busy = false;
    }
  }
</script>

{#if supported}
  <span class="preview">
    <button
      type="button"
      class="ghost"
      data-preview
      disabled={!enabled}
      title={reason}
      aria-label="Preview in game"
      onclick={preview}
    >
      <Icon name="play" size={14} /> Preview in game
    </button>
    {#if note}<span class="note" class:error={noteIsError} role="status">{note}</span>{/if}
  </span>
{/if}

<style>
  .preview {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
  }
  .ghost {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-3);
    font-size: var(--text-sm);
    cursor: pointer;
    white-space: nowrap;
  }
  .ghost:hover:not(:disabled) {
    background: var(--bg-hover);
  }
  .ghost:disabled {
    border-color: var(--border);
    color: var(--faint);
    cursor: default;
  }
  .note {
    max-width: 24rem;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .note.error {
    color: var(--error);
  }
</style>
