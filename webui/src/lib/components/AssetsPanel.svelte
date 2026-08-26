<script lang="ts">
  import { assetOps, unwrap } from '../api';
  import type { AssetRef } from '../api';
  import { errorMessage, logActivity, session } from '../state.svelte';

  let { pack }: { pack: string } = $props();

  let assets = $state<AssetRef[]>([]);
  let previews = $state<Record<string, string>>({});

  async function load() {
    if (!session.channel) return;
    try {
      assets = (unwrap(await assetOps.list(session.channel, pack)).assets as AssetRef[]) ?? [];
      // Inline previews for images; the wire payload is already base64.
      for (const asset of assets) {
        if (['png', 'jpg', 'jpeg'].includes(asset.kind) && !previews[asset.path]) {
          try {
            const payload = unwrap(await assetOps.get(session.channel, pack, asset.path));
            const mime = asset.kind === 'png' ? 'image/png' : 'image/jpeg';
            previews = { ...previews, [asset.path]: `data:${mime};base64,${String(payload.data)}` };
          } catch {
            // preview is best-effort
          }
        }
      }
    } catch (e) {
      logActivity('error', `assets.list: ${errorMessage(e)}`);
    }
  }

  async function upload(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || !session.channel) return;
    const path = `assets/textures/${file.name}`;
    const bytes = new Uint8Array(await file.arrayBuffer());
    try {
      unwrap(await assetOps.put(session.channel, pack, path, bytes));
      logActivity('save', `uploaded ${path} (${bytes.length} B); targeted reload queued`);
      await load();
    } catch (e) {
      logActivity('error', `upload: ${errorMessage(e)}`);
    }
    input.value = '';
  }

  async function remove(path: string) {
    if (!session.channel) return;
    try {
      unwrap(await assetOps.remove(session.channel, pack, path));
      delete previews[path];
      assets = assets.filter((a) => a.path !== path);
      logActivity('info', `deleted ${path}`);
    } catch (e) {
      logActivity('error', `delete: ${errorMessage(e)}`);
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
  .panel h3 {
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--muted);
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
    border-radius: var(--radius);
  }
  li:hover {
    background: var(--bg-hover);
  }
  img {
    image-rendering: pixelated;
    border-radius: 4px;
  }
  .dot {
    width: 24px;
    height: 24px;
    border-radius: 4px;
    background: var(--border);
    display: inline-block;
  }
  .path {
    flex: 1;
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  li button {
    background: none;
    border: none;
    color: var(--error);
    cursor: pointer;
  }
  .empty {
    color: var(--faint);
    font-style: italic;
  }
  .head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .upload {
    font-size: 11px;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: 2px 8px;
    cursor: pointer;
  }
</style>
