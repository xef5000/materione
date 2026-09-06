<script lang="ts">
  /**
   * A glyph as the client will render it: the texture scaled to its declared
   * pixel height on a checkerboard, with the allocated codepoint (the server
   * computes it — the file on disk never contains one).
   */
  import { assetOps, unwrap } from '../api';
  import { session } from '../state.svelte';

  interface Props {
    pack: string;
    texture: string;
    frames: unknown;
    ascent: number;
    height: number;
    /** From browse.get's computed block; null until the pack has compiled once. */
    codepoint: number | null;
    /** Bumped when the pixel editor writes, so the preview re-reads the bytes. */
    nonce?: number;
  }

  let { pack, texture, frames, ascent, height, codepoint, nonce = 0 }: Props = $props();

  let image = $state('');

  const frameList = $derived(
    Array.isArray(frames) ? frames.map((frame) => String(frame)) : [],
  );
  const shown = $derived(frameList[0] ?? texture);
  const scale = $derived(Math.max(2, Math.min(12, Math.floor(128 / Math.max(1, height)))));
  const codepointHex = $derived(
    codepoint != null ? `U+${codepoint.toString(16).toUpperCase().padStart(4, '0')}` : '',
  );

  $effect(() => {
    void resolve(shown, nonce);
  });

  async function resolve(reference: string, _nonce: number) {
    image = '';
    if (!reference || !session.channel) return;
    try {
      const payload = unwrap(await assetOps.get(session.channel, pack, `assets/textures/${reference}.png`));
      image = `data:image/png;base64,${String(payload.data)}`;
    } catch {
      image = '';
    }
  }
</script>

<div class="preview">
  <div class="canvas">
    {#if image}
      <div class="glyph" style="height: {height * scale}px; margin-top: {(height - ascent) * scale}px;">
        <img src={image} alt="glyph texture" style="height: {height * scale}px;" />
      </div>
    {:else}
      <span class="empty">no texture</span>
    {/if}
  </div>
  {#if frameList.length > 1}
    <p class="note">{frameList.length} frames — the client shows frame 1 until a surface cycles them</p>
  {/if}
  <dl>
    <dt>codepoint</dt>
    <dd>{codepoint != null ? `${codepointHex} · ${String.fromCodePoint(codepoint ?? 0)}` : '— (compiles on save)'}</dd>
    <dt>ascent × height</dt>
    <dd>{ascent} × {height} px</dd>
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
  .glyph img {
    image-rendering: pixelated;
    display: block;
  }
  .empty {
    color: var(--faint);
    font-size: 12px;
  }
  .note {
    margin: 0;
    font-size: 11px;
    color: var(--faint);
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
