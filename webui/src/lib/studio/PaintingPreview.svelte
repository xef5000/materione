<script lang="ts">
  /** A painting at its true aspect: the texture shown against the block grid it needs. */
  import { assetOps, unwrap } from '../api';
  import { session } from '../state.svelte';

  interface Props {
    pack: string;
    texture: string;
    width: number;
    height: number;
    /** Bumped when the pixel editor writes, so the preview re-reads the bytes. */
    nonce?: number;
  }

  let { pack, texture, width, height, nonce = 0 }: Props = $props();

  let image = $state('');

  const shown = $derived(texture || '');
  const aspect = $derived(`${Math.max(1, width)} / ${Math.max(1, height)}`);

  $effect(() => {
    void resolve(shown, nonce);
  });

  async function resolve(reference: string, _nonce: number) {
    image = '';
    if (!reference || !session.channel) return;
    const cleaned = reference.replace(/\.png$/i, '');
    const path = cleaned.includes('/')
      ? `assets/textures/${cleaned}.png`
      : `assets/textures/painting/${cleaned}.png`;
    try {
      const payload = unwrap(await assetOps.get(session.channel, pack, path));
      image = `data:image/png;base64,${String(payload.data)}`;
    } catch {
      image = '';
    }
  }
</script>

<div class="preview">
  <div class="canvas" style="aspect-ratio: {aspect};">
    {#if image}
      <img src={image} alt="painting texture" />
    {:else}
      <span class="empty">no texture</span>
    {/if}
  </div>
  <dl>
    <dt>size</dt>
    <dd>{width} × {height} blocks · {width * 16} × {height * 16} px</dd>
    <dt>texture</dt>
    <dd title={shown}>{shown || '—'}</dd>
  </dl>
</div>

<style>
  .preview {
    display: grid;
    gap: 10px;
    min-width: 0;
  }
  .canvas {
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
    width: 100%;
    height: 100%;
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
