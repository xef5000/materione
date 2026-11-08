<script lang="ts">
  /**
   * What a `paint` layer needs beyond its schema fields: how to draw on it. The layer owns its pixels and their box
   * (gui.md section 11.2): the brush, eraser and fill in the toolbar paint it anywhere within the art cap, the box
   * grows as you paint, each stroke is one undo step, and Save trims it to the painted pixels and writes the PNG in
   * the same transaction as the layout. A layer with no pixel saves no PNG.
   */
  import Icon from '../../components/Icon.svelte';
  import type { GuiLayer } from '../../gui/model';
  import type { GuiStudioState } from '../../gui/studio.svelte';

  let { studio, layer, disabled }: { studio: GuiStudioState; layer: GuiLayer; disabled: boolean } = $props();

  const bounds = $derived(studio.boundsOf(layer));
</script>

<section class="group" data-testid="paint-extras">
  <h3>Pixels</h3>
  <p class="note">
    {#if bounds}
      {bounds.w} x {bounds.h} px at {bounds.x}, {bounds.y}, stored as <code>{typeof layer.image === 'string' ? layer.image : 'gui/…'}.png</code>.
    {:else}
      No pixels yet: nothing is saved for this layer until something is painted on it.
    {/if}
    Pick the brush (B), eraser (E) or fill (G) in the toolbar and paint on the canvas, on the screen or around it.
  </p>
  <div class="row">
    <button type="button" class="btn" disabled={disabled} onclick={() => studio.chooseTool('brush')}><Icon name="pencil" size={14} /> Paint</button>
    <button type="button" class="btn danger" disabled={disabled} onclick={() => studio.clearPaint(layer.id)}><Icon name="trash-2" size={14} /> Clear layer</button>
  </div>
</section>

<style>
  .group {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  h3 {
    margin: 0;
    font-size: var(--text-xs);
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--muted);
  }
  .note {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
  }
  code {
    font-family: var(--mono);
    font-size: var(--text-xs);
    overflow-wrap: anywhere;
  }
  .row {
    display: flex;
    gap: var(--space-2);
  }
  .btn {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-3);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .btn:hover:not(:disabled) {
    background: var(--bg-hover);
  }
  .btn.danger:hover:not(:disabled) {
    border-color: var(--error);
    color: var(--error);
  }
  .btn:disabled {
    opacity: 0.5;
    cursor: default;
  }
</style>
