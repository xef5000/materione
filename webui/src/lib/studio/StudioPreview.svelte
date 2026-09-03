<script lang="ts">
  /**
   * What the entry looks like, without booting a 3D application: the texture
   * at 1:1 and the model's cached thumbnail when one exists.
   */
  import { assetOps, unwrap } from '../api';
  import { session } from '../state.svelte';

  interface Props {
    pack: string;
    texture: string;
    model: string;
    label: string;
    /** Bumped when the bytes changed but the reference did not. */
    nonce?: number;
  }

  let { pack, texture, model, label, nonce = 0 }: Props = $props();

  let image = $state('');

  $effect(() => {
    void resolve(texture, nonce);
  });

  async function resolve(reference: string, _nonce: number) {
    image = '';
    if (!reference || !session.channel) return;
    const path = `assets/textures/${reference}.png`;
    try {
      const payload = unwrap(await assetOps.get(session.channel, pack, path));
      image = `data:image/png;base64,${String(payload.data)}`;
    } catch {
      image = '';
    }
  }
</script>

<div class="preview">
  <div class="canvas">
    {#if image}
      <img src={image} alt={label} />
    {:else}
      <span class="empty">no texture</span>
    {/if}
  </div>
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
    gap: 10px;
    min-width: 0;
  }
  .canvas {
    aspect-ratio: 1;
    display: grid;
    place-items: center;
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background:
      conic-gradient(from 90deg, #1a1e26 25%, #151920 0 50%, #1a1e26 0 75%, #151920 0) 0 0 / 16px
      16px;
    overflow: hidden;
  }
  img {
    width: 70%;
    height: 70%;
    object-fit: contain;
    image-rendering: pixelated;
  }
  .empty {
    color: var(--faint);
    font-size: 12px;
  }
  dl {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    gap: 2px 8px;
    margin: 0;
    font-size: 11px;
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
