<script lang="ts">
  import { assetOps, unwrap } from '../api';
  import { errorMessage, logActivity, session } from '../state.svelte';

  interface Props {
    pack: string;
    path: string;
    name: string;
  }

  let { pack, path, name }: Props = $props();

  let dataUrl = $state('');

  $effect(() => {
    const channel = session.channel;
    if (!channel) return;
    void (async () => {
      try {
        const payload = unwrap(await assetOps.get(channel, pack, path));
        const mime = path.endsWith('.png') ? 'image/png' : 'image/jpeg';
        dataUrl = `data:${mime};base64,${String(payload.data)}`;
      } catch (e) {
        logActivity('error', `assets.get: ${errorMessage(e)}`);
      }
    })();
  });
</script>

<article>
  <header>
    <h2>{name}</h2>
    <span class="meta">{path}</span>
  </header>
  {#if dataUrl}
    <div class="frame">
      <img src={dataUrl} alt={path} />
    </div>
    <p class="hint">pixel-perfect at 1× — textures are referenced from item/block forms</p>
  {:else}
    <p class="hint">loading…</p>
  {/if}
</article>

<style>
  article {
    background: var(--bg-panel);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: 14px 18px;
  }
  header {
    display: flex;
    align-items: baseline;
    gap: 12px;
  }
  h2 {
    margin: 0 auto 0 0;
    font-size: 16px;
  }
  .meta {
    color: var(--muted);
    font-size: 12px;
  }
  .frame {
    margin-top: 12px;
    padding: 24px;
    background:
      repeating-conic-gradient(var(--bg-raised) 0% 25%, var(--bg-input) 0% 50%) 0 / 16px 16px;
    border-radius: var(--radius);
    display: grid;
    place-items: center;
  }
  img {
    image-rendering: pixelated;
    max-width: min(320px, 100%);
  }
  .hint {
    color: var(--faint);
    font-size: 12px;
  }
</style>
