<script lang="ts">
  /**
   * The mini preview (logic.md section 7.4): the whole GUI drawn small with one layer outlined, shown on the Logic canvas
   * while a trigger that answers that layer is hovered or focused, so the author sees where on the screen the rule lives
   * without leaving the graph.
   */
  import { layerBounds } from '../../../gui/scene';
  import type { GuiStudioState } from '../../../gui/studio.svelte';
  import { layerLabel } from '../../../gui/model';
  import { paintComposite } from './paint';

  let { studio, layerId }: { studio: GuiStudioState; layerId: string } = $props();

  const WIDTH = 176;
  const layer = $derived(studio.doc.layers.find((entry) => entry.id === layerId) ?? null);
  const bounds = $derived(layer ? layerBounds(layer, studio.scene) : null);
  const height = $derived(Math.max(1, Math.round((WIDTH * studio.size.height) / studio.size.width)));
  let canvas = $state<HTMLCanvasElement>();
  let mapping = $state({ scale: 1, offsetX: 0, offsetY: 0 });

  $effect(() => {
    void studio.imageVersion;
    const target = canvas;
    if (!target) return;
    const placed = paintComposite(target, studio, null);
    if (placed) mapping = placed;
  });

  const box = $derived(
    bounds
      ? {
          left: mapping.offsetX + bounds.x * mapping.scale,
          top: mapping.offsetY + bounds.y * mapping.scale,
          width: Math.max(3, bounds.w * mapping.scale),
          height: Math.max(3, bounds.h * mapping.scale),
        }
      : null,
  );
</script>

{#if layer}
  <figure class="mini" data-testid="logic-layer-preview" data-layer={layerId} aria-label="Where {layerLabel(layer)} is on the screen">
    <div class="shot" style="width:{WIDTH}px;height:{height}px">
      <canvas bind:this={canvas} width={WIDTH} height={height} aria-hidden="true"></canvas>
      {#if box}
        <span
          class="hl"
          data-testid="logic-layer-highlight"
          data-box="{bounds?.x},{bounds?.y},{bounds?.w},{bounds?.h}"
          style="left:{box.left}px;top:{box.top}px;width:{box.width}px;height:{box.height}px"
        ></span>
      {/if}
    </div>
    <figcaption>{layerLabel(layer)}</figcaption>
  </figure>
{/if}

<style>
  .mini {
    position: absolute;
    left: var(--space-3);
    bottom: var(--space-3);
    z-index: 3;
    margin: 0;
    padding: var(--space-1);
    background: var(--bg-panel);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-2);
    pointer-events: none;
  }
  .shot {
    position: relative;
  }
  canvas {
    display: block;
    width: 100%;
    height: 100%;
    image-rendering: pixelated;
    background: var(--bg-input);
  }
  .hl {
    position: absolute;
    box-sizing: border-box;
    border: 2px solid var(--logic-trigger);
    background: color-mix(in srgb, var(--logic-trigger) 22%, transparent);
    box-shadow: 0 0 0 1px var(--logic-wire-shade);
  }
  figcaption {
    margin-top: var(--space-1);
    color: var(--muted);
    font-size: var(--text-xs);
    text-align: center;
  }
</style>
