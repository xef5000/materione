<script lang="ts">
  /**
   * What the entry looks like, without booting a 3D application: a live
   * browser render of the saved model when it can be drawn, else the texture
   * at 1:1 or the model's cached thumbnail.
   */
  import type { ResourceSummary } from '../api';
  import ResourceThumbnail from '../components/ResourceThumbnail.svelte';
  import { localStudioVisual } from '../studio-visual';
  import { workspace } from '../state.svelte';

  interface Props {
    pack: string;
    texture: string;
    model: string;
    label: string;
    /** Bumped when the bytes changed but the reference did not. */
    nonce?: number;
    visual?: ResourceSummary['visual'];
    render?: ResourceSummary['render'];
  }

  let { pack, texture, model, label, nonce = 0, visual, render }: Props = $props();
  const shown = $derived(visual ?? localStudioVisual(pack, texture, model, workspace.assets));
</script>

<div class="preview">
  <div class="canvas">
    <ResourceThumbnail visual={shown} {render} {label} size={180} version={nonce + workspace.visualVersion} />
  </div>
  <small>{render ? 'Model preview drawn in the browser; vanilla textures show as grey checkers.' :
    shown?.kind === 'model-thumbnail' ? 'Cached model thumbnail, not interactive 3D.' : 'Texture approximation, not a client render.'}</small>
  <dl>
    <dt>texture</dt>
    <dd title={texture}>{texture || '—'}</dd>
    <dt>model</dt>
    <dd title={model}>{model || '—'}</dd>
  </dl>
</div>

<style>
  .preview {
    display: grid;
    gap: var(--space-3);
    min-width: 0;
  }
  .canvas {
    aspect-ratio: 1;
    display: grid;
    place-items: center;
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background:
      conic-gradient(from 90deg, var(--checker-a) 25%, var(--checker-b) 0 50%, var(--checker-a) 0 75%, var(--checker-b) 0) 0 0 / 16px
      16px;
    overflow: hidden;
  }
  small {
    color: var(--faint);
    font-size: var(--text-sm);
  }
  dl {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    gap: var(--space-half) var(--space-2);
    margin: 0;
    font-size: var(--text-xs);
  }
  dt {
    color: var(--faint);
  }
  dd {
    margin: 0;
    font-family: var(--mono);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
