<script lang="ts">
  import TextureEditor from '../studio/TextureEditor.svelte';
  import { assetOps, unwrap } from '../api';
  import { errorMessage, logActivity, session } from '../state.svelte';

  interface Props {
    pack: string;
    path: string;
    name: string;
  }

  let { pack, path, name }: Props = $props();

  let dataUrl = $state('');
  let editing = $state(false);
  /** Re-reads the bytes after the pixel editor wrote them. */
  let version = $state(0);

  const editable = $derived(path.toLowerCase().endsWith('.png'));

  $effect(() => {
    version;
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

<article class:editing>
  {#if editing}
    <TextureEditor
      {pack}
      {path}
      onClose={() => (editing = false)}
      onSaved={() => (version += 1)}
    />
  {:else}
    <header>
      <h2>{name}</h2>
      <span class="meta">{path}</span>
      {#if editable}
        <button type="button" onclick={() => (editing = true)}>Edit pixels</button>
      {/if}
    </header>
    {#if dataUrl}
      <div class="frame">
        <img src={dataUrl} alt={path} />
      </div>
    {:else}
      <p class="hint">loading…</p>
    {/if}
  {/if}
</article>

<style>
  article {
    background: var(--bg-panel);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: 14px 18px;
  }
  article.editing {
    padding: 0;
    overflow: hidden;
    height: min(70vh, 640px);
  }
  header {
    display: flex;
    align-items: baseline;
    gap: 12px;
  }
  header button {
    background: none;
    border: 1px solid var(--border-strong);
    color: inherit;
    border-radius: var(--radius);
    padding: 3px 10px;
    font-size: 12px;
    cursor: pointer;
  }
  header button:hover {
    background: var(--bg-hover);
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
