<script lang="ts">
  /**
   * The paint tools' options bar: brush size (1 to 8) and shape, the colour (swatch, hex, native
   * picker) and the recent colours. It appears under the toolbar while a paint tool is active, like
   * a paint program's tool options, and is the only place the paint settings live.
   */
  import Icon from '../../components/Icon.svelte';
  import { MAX_BRUSH, MIN_BRUSH } from '../../gui/paint';
  import { alphaOf, argb, cssColor, formatColor, parseColor, pickerValue, redOf, greenOf, blueOf } from '../../gui/raster/color';
  import type { GuiStudioState } from '../../gui/studio.svelte';

  let { studio }: { studio: GuiStudioState } = $props();

  const sizes = Array.from({ length: MAX_BRUSH - MIN_BRUSH + 1 }, (_, index) => MIN_BRUSH + index);
  const usesBrush = $derived(studio.tool === 'brush' || studio.tool === 'eraser');
  let draft = $state('');
  let editing = $state(false);
  let invalid = $state(false);
  const text = $derived(editing ? draft : formatColor(studio.color));

  function commit(): void {
    editing = false;
    const parsed = parseColor(draft);
    invalid = parsed === null;
    if (parsed !== null) studio.setColor(parsed, true);
  }

  function picked(event: Event): void {
    const rgb = parseColor((event.currentTarget as HTMLInputElement).value);
    if (rgb === null) return;
    studio.setColor(argb(alphaOf(studio.color) || 255, redOf(rgb), greenOf(rgb), blueOf(rgb)));
  }
</script>

<div class="options" role="group" aria-label="Paint options" data-testid="paint-options">
  {#if usesBrush}
    <div class="group" role="group" aria-label="Brush size">
      <span class="label">Size</span>
      <div class="sizes">
        {#each sizes as size (size)}
          <button
            type="button"
            class="size"
            class:on={studio.brush.size === size}
            aria-pressed={studio.brush.size === size}
            aria-label="Brush size {size}"
            title="Brush size {size} ([ and ] change it)"
            onclick={() => studio.setBrush({ size })}
          >{size}</button>
        {/each}
      </div>
    </div>
    <div class="group" role="group" aria-label="Brush shape">
      <span class="label">Shape</span>
      <div class="shapes">
        <button type="button" class="shape" class:on={studio.brush.shape === 'square'} aria-pressed={studio.brush.shape === 'square'} aria-label="Square brush" title="Square brush" onclick={() => studio.setBrush({ shape: 'square' })}><Icon name="square" size={14} /></button>
        <button type="button" class="shape" class:on={studio.brush.shape === 'round'} aria-pressed={studio.brush.shape === 'round'} aria-label="Round brush" title="Round brush" onclick={() => studio.setBrush({ shape: 'round' })}><Icon name="circle" size={14} /></button>
      </div>
    </div>
  {/if}
  {#if studio.tool !== 'eraser'}
    <div class="group" role="group" aria-label="Colour">
      <span class="label">Colour</span>
      <label class="swatch" title="Pick a colour">
        <span class="chip" style:background={cssColor(studio.color)} data-testid="paint-swatch"></span>
        <input type="color" aria-label="Paint colour picker" value={pickerValue(studio.color)} oninput={picked} />
      </label>
      <input
        class="hex"
        class:invalid
        type="text"
        aria-label="Paint colour hex"
        spellcheck="false"
        autocomplete="off"
        maxlength="9"
        value={text}
        onfocus={(event) => {
          editing = true;
          draft = formatColor(studio.color);
          event.currentTarget.select();
        }}
        oninput={(event) => (draft = event.currentTarget.value)}
        onblur={commit}
        onkeydown={(event) => {
          event.stopPropagation();
          if (event.key === 'Enter') event.currentTarget.blur();
          if (event.key === 'Escape') {
            editing = false;
            invalid = false;
            event.currentTarget.blur();
          }
        }}
      />
    </div>
    {#if studio.recent.length}
      <div class="group" role="group" aria-label="Recent colours">
        <span class="label">Recent</span>
        <div class="recent">
          {#each studio.recent as color (color)}
            <button
              type="button"
              class="dot"
              class:on={color === studio.color}
              aria-label="Use {formatColor(color)}"
              title={formatColor(color)}
              style:background={cssColor(color)}
              onclick={() => studio.setColor(color, true)}
            ></button>
          {/each}
        </div>
      </div>
    {/if}
  {/if}
  <span class="tip">
    {#if studio.tool === 'fill'}Fill: contiguous, exact colour match{:else if studio.tool === 'picker'}Click the canvas to pick the colour the player sees{:else}Hold Alt to pick a colour · Shift-click for a straight line{/if}
  </span>
</div>

<style>
  .options {
    display: flex;
    flex: none;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2) var(--space-4);
    padding: var(--space-1) var(--space-3);
    border-bottom: 1px solid var(--border);
    background: var(--bg-raised);
    min-width: 0;
  }
  .group {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
  }
  .label {
    color: var(--muted);
    font-size: var(--text-xs);
    text-transform: uppercase;
    letter-spacing: 0.06em;
  }
  .sizes,
  .shapes {
    display: flex;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    overflow: hidden;
  }
  .size,
  .shape {
    min-width: 24px;
    height: 24px;
    padding: 0 var(--space-1);
    display: inline-grid;
    place-items: center;
    background: var(--bg-input);
    border: 0;
    border-right: 1px solid var(--border-strong);
    color: var(--muted);
    font-size: var(--text-sm);
    font-variant-numeric: tabular-nums;
    cursor: pointer;
  }
  .size:last-child,
  .shape:last-child {
    border-right: 0;
  }
  .size:hover:not(.on),
  .shape:hover:not(.on) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .size.on,
  .shape.on {
    background: var(--accent-low);
    color: var(--accent-hover);
    font-weight: 600;
  }
  .swatch {
    position: relative;
    flex: none;
    width: 28px;
    height: 24px;
    border: 1px solid var(--control-line);
    border-radius: var(--radius-sm);
    overflow: hidden;
    cursor: pointer;
    background: conic-gradient(var(--checker-a) 25%, var(--checker-b) 0 50%, var(--checker-a) 0 75%, var(--checker-b) 0) 0 0 / 8px 8px;
  }
  .swatch:focus-within {
    border-color: var(--accent);
  }
  .chip {
    position: absolute;
    inset: 0;
  }
  .swatch input {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    opacity: 0;
    cursor: pointer;
  }
  .hex {
    width: 92px;
    height: 24px;
    box-sizing: border-box;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    padding: var(--space-1) var(--space-2);
    font-family: var(--mono);
    font-size: var(--text-sm);
    outline: none;
  }
  .hex:focus {
    border-color: var(--accent);
  }
  .hex.invalid {
    border-color: var(--error);
  }
  .recent {
    display: flex;
    flex-wrap: wrap;
    gap: 3px;
  }
  .dot {
    width: 18px;
    height: 18px;
    padding: 0;
    border: 1px solid var(--control-line);
    border-radius: var(--radius-sm);
    cursor: pointer;
    transition: transform var(--motion-fast) var(--ease-standard);
  }
  .dot:hover {
    transform: scale(1.18);
  }
  .dot.on {
    outline: 2px solid var(--accent);
    outline-offset: 1px;
  }
  .tip {
    margin-left: auto;
    color: var(--faint);
    font-size: var(--text-xs);
  }
</style>
