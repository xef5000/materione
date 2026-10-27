<script lang="ts">
  /**
   * A pack texture drawn small and crisp: the frame strip, the placeholder
   * previews. The bytes come from the same asset read the pixel editor uses;
   * a texture the pack lacks shows an honest placeholder instead of nothing.
   */
  import { assetOps, unwrap } from '../api';
  import { session } from '../state.svelte';

  interface Props {
    pack: string;
    /** Texture reference as authored (`gui/heart`, no extension). */
    reference: string;
    size?: number;
    nonce?: number;
  }

  let { pack, reference, size = 40, nonce = 0 }: Props = $props();
  let image = $state('');
  let missing = $state(false);

  $effect(() => {
    void load(reference, nonce);
  });

  async function load(ref: string, _nonce: number) {
    image = '';
    missing = false;
    const channel = session.channel;
    if (!ref || !channel) return;
    const cleaned = ref.replace(/\.png$/i, '');
    const local = cleaned.includes(':') ? cleaned.slice(cleaned.indexOf(':') + 1) : cleaned;
    try {
      const payload = unwrap(await assetOps.get(channel, pack, `assets/textures/${local}.png`));
      image = `data:image/png;base64,${String(payload.data)}`;
    } catch {
      missing = true;
    }
  }
</script>

<span class="thumb" class:missing style:width="{size}px" style:height="{size}px"
  title={missing ? `${reference}: not in this pack` : reference}>
  {#if image}<img src={image} alt={reference} />{:else if missing}?{/if}
</span>

<style>
  .thumb {
    display: inline-grid;
    place-items: center;
    flex: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background:
      conic-gradient(from 90deg, var(--checker-a) 25%, var(--checker-b) 0 50%, var(--checker-a) 0 75%, var(--checker-b) 0) 0 0 / 8px
        8px;
    color: var(--warn);
    font-size: var(--text-sm);
    overflow: hidden;
  }
  .thumb.missing {
    border-color: var(--warn);
  }
  img {
    width: 100%;
    height: 100%;
    object-fit: contain;
    image-rendering: pixelated;
  }
</style>
