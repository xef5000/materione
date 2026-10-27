<script lang="ts">
  /**
   * The modal shell every dialog shares: a scrim, a labelled panel, Escape and
   * a scrim click to cancel. Bodies compose `Field` and `Button`; the footer
   * is a `.dialog-actions` row, right-aligned, primary action last.
   */
  import type { Snippet } from 'svelte';
  import { fade, scale } from 'svelte/transition';
  import { cubicIn, cubicOut } from 'svelte/easing';
  import { motionMs } from '../motion';

  interface Props {
    title: string;
    onClose: () => void;
    /** A CSS width; the panel never exceeds the viewport. */
    width?: string;
    /** A scrim click cancels; dialogs holding unsaved work turn it off (Escape still cancels). */
    dismissOnScrim?: boolean;
    children: Snippet;
  }

  let { title, onClose, width = '420px', dismissOnScrim = true, children }: Props = $props();
  const titleId = $props.id();

  function onKey(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onClose();
    }
  }
</script>

<svelte:window onkeydown={onKey} />

<div
  class="scrim"
  role="presentation"
  in:fade|global={{ duration: motionMs('base') }}
  out:fade|global={{ duration: motionMs('fast') }}
  onclick={(event) => {
    if (dismissOnScrim && event.target === event.currentTarget) onClose();
  }}
>
  <div
    class="dialog"
    role="dialog"
    aria-modal="true"
    aria-labelledby={titleId}
    style:width={`min(${width}, calc(100vw - 48px))`}
    in:scale|global={{ duration: motionMs('base'), start: 0.96, easing: cubicOut }}
    out:scale|global={{ duration: motionMs('fast'), start: 0.98, easing: cubicIn }}
  >
    <h2 id={titleId}>{title}</h2>
    {@render children()}
  </div>
</div>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    z-index: 60;
    display: grid;
    place-items: center;
    background: var(--scrim);
  }
  .dialog {
    max-height: calc(100vh - 64px);
    overflow-y: auto;
    padding: var(--space-5) var(--space-6) var(--space-6);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-3);
  }
  h2 {
    margin: 0 0 var(--space-4);
    font-size: var(--text-lg);
    font-weight: 600;
    letter-spacing: -0.01em;
  }
</style>
