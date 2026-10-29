<script lang="ts">
  /**
   * In-place text editing on the canvas. The text is drawn by the canvas itself (the shared
   * smallcaps font, pixel for pixel, as it is compiled), so the editor never shows a second
   * rendering of it: a hidden input receives the keyboard, and this overlay draws only the caret
   * and the selection, positioned from the font's own advances. Typing previews live without
   * touching the history; Enter or leaving the field commits ONE undo step, Escape takes it back.
   */
  import { onMount, untrack } from 'svelte';
  import { MAX_TEXT_LENGTH, advanceOf, fontOf, textOf } from '../../gui/text';
  import { smallcaps } from '../../gui/font/render';
  import { DEFAULT_CELL_HEIGHT } from '../../gui/font/default-font';
  import type { GuiLayer } from '../../gui/model';
  import type { GuiStudioState } from '../../gui/studio.svelte';

  let { studio, layer }: { studio: GuiStudioState; layer: GuiLayer } = $props();

  const original = untrack(() => textOf(layer));
  const id = untrack(() => layer.id);
  let value = $state(original);
  let caret = $state({ start: original.length, end: original.length });
  let input = $state<HTMLInputElement>();
  let finished = false;

  const view = $derived(studio.view);
  const zoom = $derived(view.zoom);
  const font = $derived(fontOf(layer) ?? 'smallcaps');
  const x = $derived(typeof layer.x === 'number' ? Math.floor(layer.x) : 0);
  const y = $derived(typeof layer.y === 'number' ? Math.floor(layer.y) : 0);
  /** The caret is a little taller than the letters, like a text cursor over small caps. */
  const artHeight = $derived(font === 'smallcaps' ? smallcaps.artRows : DEFAULT_CELL_HEIGHT - 1);
  const left = $derived(Math.round(view.panX + x * zoom));
  const top = $derived(Math.round(view.panY + (y - 1) * zoom));
  const boxHeight = $derived((artHeight + 2) * zoom);
  const caretX = $derived(advanceOf(value.slice(0, caret.end), font) * zoom);
  const fromX = $derived(advanceOf(value.slice(0, Math.min(caret.start, caret.end)), font) * zoom);
  const toX = $derived(advanceOf(value.slice(0, Math.max(caret.start, caret.end)), font) * zoom);
  const hitWidth = $derived(Math.max(48 * zoom / 3, advanceOf(value, font) * zoom + 8 * zoom));

  onMount(() => {
    input?.focus({ preventScroll: true });
    input?.select();
    syncCaret();
    const onSelection = (): void => {
      if (document.activeElement === input) syncCaret();
    };
    document.addEventListener('selectionchange', onSelection);
    return () => document.removeEventListener('selectionchange', onSelection);
  });

  function syncCaret(): void {
    if (!input) return;
    const start = input.selectionStart ?? value.length;
    const end = input.selectionEnd ?? start;
    // the "end" is the moving side of a selection, whichever way it was made
    caret = input.selectionDirection === 'backward' ? { start: end, end: start } : { start, end };
  }

  function finish(commit: boolean): void {
    if (finished) return;
    finished = true;
    if (commit && value !== original) studio.commitText(id, value);
    else studio.cancelTextEdit();
    queueMicrotask(() => document.querySelector<HTMLElement>('[data-testid=gui-stage]')?.focus({ preventScroll: true }));
  }

  function onKeydown(event: KeyboardEvent): void {
    event.stopPropagation();
    if (event.key === 'Enter') {
      event.preventDefault();
      finish(true);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      finish(false);
    }
    queueMicrotask(syncCaret);
  }

  /** The index whose boundary is nearest to a pointer x (GUI pixels from the text's left edge). */
  function indexAt(offset: number): number {
    let best = 0;
    let bestDistance = Infinity;
    for (let index = 0; index <= value.length; index++) {
      const distance = Math.abs(advanceOf(value.slice(0, index), font) - offset);
      if (distance < bestDistance) {
        best = index;
        bestDistance = distance;
      }
    }
    return best;
  }

  function pointerDown(event: PointerEvent): void {
    event.stopPropagation();
    event.preventDefault();
    const box = (event.currentTarget as HTMLElement).getBoundingClientRect();
    const index = indexAt((event.clientX - box.left) / zoom);
    input?.focus({ preventScroll: true });
    if (event.shiftKey) input?.setSelectionRange(Math.min(caret.start, index), Math.max(caret.start, index));
    else input?.setSelectionRange(index, index);
    syncCaret();
  }
</script>

<div class="edit" style="left:{left}px;top:{top}px;width:{hitWidth}px;height:{boxHeight}px" data-testid="gui-text-edit">
  <input
    bind:this={input}
    bind:value
    class="field"
    type="text"
    aria-label="Text on the canvas"
    maxlength={MAX_TEXT_LENGTH}
    spellcheck="false"
    autocomplete="off"
    style:width="{hitWidth}px"
    oninput={() => {
      studio.previewText(id, value);
      syncCaret();
    }}
    onkeydown={onKeydown}
    onkeyup={syncCaret}
    onblur={() => finish(true)}
  />
  <div class="hit" role="presentation" style:width="{hitWidth}px" onpointerdown={pointerDown} ondblclick={() => input?.select()}></div>
  {#if caret.start !== caret.end}
    <div class="selection" style="left:{fromX}px;width:{Math.max(1, toX - fromX)}px"></div>
  {/if}
  <div class="caret" style="left:{caretX - Math.max(1, Math.round(zoom / 2))}px;width:{Math.max(1, Math.round(zoom / 2))}px"></div>
</div>

<style>
  .edit {
    position: absolute;
    z-index: 3;
    pointer-events: none;
  }
  .field {
    position: absolute;
    inset: 0 auto 0 0;
    margin: 0;
    padding: 0;
    border: 0;
    outline: 0;
    background: transparent;
    color: transparent;
    caret-color: transparent;
    opacity: 0.01;
    font-size: var(--text-md);
  }
  .hit {
    position: absolute;
    inset: 0 auto 0 0;
    cursor: text;
    pointer-events: auto;
    outline: 1px dashed var(--accent);
    outline-offset: 1px;
  }
  .selection {
    position: absolute;
    top: 0;
    bottom: 0;
    background: var(--accent-low);
    outline: 1px solid var(--accent);
    pointer-events: none;
  }
  .caret {
    position: absolute;
    top: 0;
    bottom: 0;
    background: var(--accent-hover);
    pointer-events: none;
    animation: blink 1.05s steps(1) infinite;
  }
  @keyframes blink {
    50% {
      opacity: 0;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .caret {
      animation: none;
    }
  }
</style>
