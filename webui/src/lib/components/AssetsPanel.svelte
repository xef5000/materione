<script lang="ts">
  import type { EditorChannel } from '../protocol/session';
  import { assetOps, unwrap } from '../api';
  import type { AssetRef } from '../api';

  let { channel, pack, onChanged }: { channel: EditorChannel; pack: string; onChanged?: (message: string) => void } =
    $props();

  let assets = $state<AssetRef[]>([]);
  let previews = $state<Record<string, string>>({});

  async function load() {
    try {
      assets = (unwrap(await assetOps.list(channel, pack)).assets as AssetRef[]) ?? [];
      // Inline previews for images small enough to bother with.
      for (const asset of assets) {
        if (['png', 'jpg', 'jpeg'].includes(asset.kind) && !previews[asset.path]) {
          try {
            const payload = unwrap(await assetOps.get(channel, pack, asset.path));
            const bytes = atob(String(payload.data));
            const binary = Uint8Array.from(bytes, (ch) => ch.charCodeAt(0));
            const mime = asset.kind === 'png' ? 'image/png' : 'image/jpeg';
            previews = {
              ...previews,
              [asset.path]: `data:${mime};base64,` +
                btoa(Array.from(binary, (b) => String.fromCharCode(b)).join('')),
            };
          } catch {
            // preview is best-effort
          }
        }
      }
    } catch (e) {
      onChanged?.(`⚠ assets.list: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  async function upload(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const path = `assets/textures/${file.name}`;
    const bytes = new Uint8Array(await file.arrayBuffer());
    try {
      unwrap(await assetOps.put(channel, pack, path, bytes));
      onChanged?.(`uploaded ${path} (${bytes.length} B); targeted reload queued`);
      await load();
    } catch (e) {
      onChanged?.(`⚠ upload: ${e instanceof Error ? e.message : String(e)}`);
    }
    input.value = '';
  }

  async function remove(path: string) {
    try {
      unwrap(await assetOps.remove(channel, pack, path));
      delete previews[path];
      assets = assets.filter((a) => a.path !== path);
      onChanged?.(`deleted ${path}`);
    } catch (e) {
      onChanged?.(`⚠ delete: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  $effect(() => {
    void load();
  });
</script>

<div class="panel">
  <div class="head">
    <h3>Assets · {pack}</h3>
    <label class="upload">
      + upload
      <input type="file" accept=".png,.jpg,.jpeg,.json,.ogg" onchange={upload} hidden />
    </label>
  </div>
  <ul>
    {#each assets as asset (asset.path)}
      <li>
        {#if previews[asset.path]}
          <!--#svelte ignore a11y -->
          <img src={previews[asset.path]} alt={asset.path} width="24" height="24" />
        {:else}
          <span class="dot"></span>
        {/if}
        <span class="path" title={asset.path}>{asset.path.replace('assets/', '')}</span>
        <button onclick={() => void remove(asset.path)} title="delete">✕</button>
      </li>
    {/each}
    {#if assets.length === 0}
      <li class="empty">no assets yet — upload a PNG texture</li>
    {/if}
  </ul>
</div>

<style>
  .panel :global(h3) {
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: #8b93a5;
    margin: 18px 0 6px;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    max-height: 260px;
    overflow-y: auto;
  }
  li {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 3px 4px;
    border-radius: 6px;
  }
  li:hover {
    background: #1c2129;
  }
  img {
    image-rendering: pixelated;
    border-radius: 4px;
  }
  .dot {
    width: 24px;
    height: 24px;
    border-radius: 4px;
    background: #262c37;
    display: inline-block;
  }
  .path {
    flex: 1;
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  button {
    background: none;
    border: none;
    color: #f7768e;
    cursor: pointer;
  }
  .empty {
    color: #586074;
    font-style: italic;
  }
  .head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .upload {
    font-size: 11px;
    border: 1px solid #3b4453;
    border-radius: 6px;
    padding: 2px 8px;
    cursor: pointer;
  }
</style>
