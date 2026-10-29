<script lang="ts">
  /**
   * What a `paint` layer needs beyond its schema fields: how to draw on it. The layer owns one
   * canvas-sized PNG; the brush, eraser, fill and picker in the toolbar paint it, each stroke one
   * undo step, and Save writes the PNG in the same transaction as the layout.
   */
  import Icon from '../../components/Icon.svelte';
  import type { GuiLayer } from '../../gui/model';
  import type { GuiStudioState } from '../../gui/studio.svelte';

  let { studio, layer, disabled }: { studio: GuiStudioState; layer: GuiLayer; disabled: boolean } = $props();
</script>

<section class="group" data-testid="paint-extras">
  <h3>Pixels</h3>
  <p class="note">
    A {studio.size.width} x {studio.size.height} px canvas stored as <code>{typeof layer.image === 'string' ? layer.image : 'gui/…'}.png</code>.
    Pick the brush (B), eraser (E) or fill (G) in the toolbar and paint on the canvas.
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
