<script lang="ts">
  /**
   * Schema-driven asset picker: `hint='texture'` offers PNG/JPGs under
   * assets/textures/ (values are the texture-relative path without
   * extension, e.g. `item/ruby`); `hint='model'` offers JSON models under
   * assets/models/ as `<pack>:<path>` refs. Includes an inline upload for
   * textures so a new user never leaves the form.
   */
  import { assetOps, unwrap } from '../api';
  import { connection, errorMessage, logActivity, session } from '../state.svelte';

  interface Props {
    pack: string;
    hint: 'texture' | 'model';
    value: string;
    onChange: (value: string) => void;
  }

  let { pack, hint, value, onChange }: Props = $props();

  interface Option {
    stored: string; // the YAML value
    path: string; // asset path for fetching bytes
    label: string;
  }

  let options = $state<Option[]>([]);
  let thumb = $state('');
  let fileInput = $state<HTMLInputElement>();
  let uploading = $state(false);
  let error = $state('');

  const selected = $derived(options.find((option) => option.stored === value));

  $effect(() => {
    void reload();
  });

  async function reload() {
    const channel = session.channel;
    if (!channel) return;
    try {
      const assets =
        (unwrap(await assetOps.list(channel, pack)).assets as { path: string; kind: string }[]) ??
        [];
      options = assets
        .filter((asset) =>
          hint === 'texture'
            ? asset.kind === 'png' || asset.kind === 'jpg' || asset.kind === 'jpeg'
            : asset.kind === 'json',
        )
        .filter((asset) =>
          asset.path.startsWith(hint === 'texture' ? 'assets/textures/' : 'assets/models/'),
        )
        .map((asset) => ({
          stored:
            hint === 'texture'
              ? asset.path
                  .slice('assets/textures/'.length)
                  .replace(/\.(png|jpe?g)$/i, '')
              : `${pack}:${asset.path
                  .slice('assets/models/'.length)
                  .replace(/\.json$/i, '')}`,
          path: asset.path,
          label: asset.path.replace(/^assets\/(textures|models)\//, ''),
        }))
        .sort((a, b) => a.label.localeCompare(b.label));
      void refreshThumb();
    } catch (e) {
      logActivity('error', `assets.list: ${errorMessage(e)}`);
    }
  }

  async function refreshThumb() {
    if (hint !== 'texture' || !selected) {
      thumb = '';
      return;
    }
    try {
      const payload = unwrap(await assetOps.get(session.channel!, pack, selected.path));
      const mime = selected.path.endsWith('.png') ? 'image/png' : 'image/jpeg';
      thumb = `data:${mime};base64,${String(payload.data)}`;
    } catch {
      thumb = '';
    }
  }

  async function upload(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !session.channel) return;
    const path = `assets/textures/${file.name}`;
    if (options.some((option) => option.path === path) &&
        !confirm('Replace this shared texture now? This can immediately affect every resource that uses it. Pending form fields will not be applied.')) return;
    uploading = true;
    error = '';
    try {
      unwrap(
        await assetOps.put(
          session.channel,
          pack,
          path,
          new Uint8Array(await file.arrayBuffer()),
        ),
      );
      logActivity('save', `uploaded ${path}; targeted reload queued`);
      await reload();
      onChange(path.slice('assets/textures/'.length).replace(/\.(png|jpe?g)$/i, ''));
    } catch (e) {
      error = `Texture upload: ${errorMessage(e)}`;
      logActivity('error', error);
    } finally {
      uploading = false;
    }
  }
</script>

<span class="picker">
  {#if thumb}
    <img src={thumb} alt={selected?.label ?? value} width="22" height="22" />
  {/if}
  <select
    value={value}
    onchange={(event) => onChange(event.currentTarget.value)}
    title="pick from uploaded files"
  >
    <option value="">— none —</option>
    {#each options as option (option.path)}
      <option value={option.stored}>{option.label}</option>
    {/each}
    {#if value && !selected}
      <option value={value}>{value} (missing)</option>
    {/if}
  </select>
  {#if hint === 'texture'}
    <button type="button" class="upload"
      disabled={uploading || !connection.connected}
      title="Writes the asset immediately; pending form fields stay unsaved until Save & apply."
      onclick={() => fileInput?.click()}>{uploading ? 'Uploading...' : 'Upload asset'}</button>
    <input bind:this={fileInput} type="file" accept=".png,.jpg,.jpeg" onchange={upload} hidden />
  {/if}
</span>
{#if error}<small class="error" role="alert">{error}</small>{/if}

<style>
  .picker {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  img {
    image-rendering: pixelated;
    border-radius: 4px;
    background: var(--bg-input);
  }
  select {
    flex: 1;
    min-width: 0;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: 6px 8px;
    font: 13px var(--mono);
  }
  .upload {
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: 3px 8px;
    cursor: pointer;
    font-size: 13px;
    color: inherit;
    background: var(--bg-input);
  }
  .error { color: var(--error); }
</style>
