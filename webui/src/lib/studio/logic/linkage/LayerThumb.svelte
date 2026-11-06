<script lang="ts">
  /**
   * A layer's thumbnail (logic.md section 7.4): the part of the Design tab's composited GUI the layer covers, drawn small,
   * so a trigger node shows which button, item or list it answers.
   */
  import { layerBounds } from '../../../gui/scene';
  import type { GuiLayer } from '../../../gui/model';
  import type { GuiStudioState } from '../../../gui/studio.svelte';
  import { paintComposite } from './paint';

  let { studio, layer, size = 20 }: { studio: GuiStudioState; layer: GuiLayer; size?: number } = $props();

  let canvas = $state<HTMLCanvasElement>();
  const bounds = $derived(layerBounds(layer, studio.scene));

  $effect(() => {
    void studio.imageVersion;
    const target = canvas;
    if (target) paintComposite(target, studio, bounds);
  });
</script>

<canvas bind:this={canvas} class="thumb" width={size} height={size} style="width:{size}px;height:{size}px" aria-hidden="true" data-testid="layer-thumb"></canvas>

<style>
  .thumb {
    flex: none;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    image-rendering: pixelated;
  }
</style>
