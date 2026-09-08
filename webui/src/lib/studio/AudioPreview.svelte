<script lang="ts">
  /**
   * A custom sound as the client hears it: the first source, playable inline,
   * and every source with an upload control — a sound is fully authorable
   * here, with no detour through Advanced Files.
   */
  import { assetOps, unwrap } from '../api';
  import { errorMessage, logActivity, session } from '../state.svelte';
  import { sanitizeSoundName, soundAssetPath } from '../studio';

  interface Props {
    pack: string;
    sounds: unknown;
    /** Appends an uploaded file's ref to the sources list (the studio draft). */
    onChange?: (value: unknown) => void;
  }

  let { pack, sounds, onChange }: Props = $props();

  /** Mirrors AssetOps.MAX_AUDIO_BYTES for .ogg/.oga uploads. */
  const MAX_AUDIO_BYTES = 8 * 1024 * 1024;

  let audio = $state('');
  let failed = $state(false);
  /** Bumped on upload so the player re-reads the bytes it just replaced. */
  let version = $state(0);
  let busy = $state(false);
  let fileInput: HTMLInputElement | null = $state(null);
  /** The source row the open file dialog will fill; null means "add a new one". */
  let target = $state<string | null>(null);

  const sourceList = $derived(
    Array.isArray(sounds) ? sounds.map((entry) => String(entry)) : [],
  );
  const shown = $derived(sourceList[0] ?? '');

  $effect(() => {
    version;
    void resolve(shown);
  });

  async function resolve(reference: string) {
    audio = '';
    failed = false;
    if (!reference || !session.channel) return;
    try {
      const payload = unwrap(await assetOps.get(session.channel, pack, soundAssetPath(reference)));
      audio = `data:audio/ogg;base64,${String(payload.data)}`;
    } catch {
      failed = true;
    }
  }

  function pick(reference: string | null) {
    target = reference;
    fileInput?.click();
  }

  async function upload(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !session.channel) return;
    const reference = target ?? sanitizeSoundName(file.name);
    if (!reference) {
      logActivity('error', 'upload: file name has no characters a sound id can use');
      return;
    }
    if (file.size > MAX_AUDIO_BYTES) {
      logActivity('error', `upload: ${file.name} is over the 8 MiB audio limit`);
      return;
    }
    const path = soundAssetPath(reference);
    busy = true;
    try {
      unwrap(
        await assetOps.put(session.channel, pack, path, new Uint8Array(await file.arrayBuffer())),
      );
      logActivity('save', `uploaded ${path}; targeted reload queued`);
      if (target === null) onChange?.([...sourceList, reference]);
      version += 1;
    } catch (e) {
      logActivity('error', `upload: ${errorMessage(e)}`);
    } finally {
      busy = false;
    }
  }
</script>

<div class="preview">
  <div class="player">
    {#if audio}
      <audio controls preload="none" src={audio}></audio>
    {:else if failed}
      <span class="empty">file missing — upload it below</span>
    {:else}
      <span class="empty">no source</span>
    {/if}
  </div>
  <div class="sources">
    <span class="head">sources · {sourceList.length}</span>
    {#each sourceList as source (source)}
      <div class="row">
        <span class="ref" title={source}>{source}</span>
        <button type="button" class="up" disabled={busy} onclick={() => pick(source)}>
          Upload
        </button>
      </div>
    {/each}
    <div class="row">
      <span class="ref faint">.ogg up to 8 MiB</span>
      <button
        type="button"
        class="up"
        disabled={busy || !onChange}
        onclick={() => pick(null)}
        title="upload a new file and add it to the sources list"
      >
        Add…
      </button>
    </div>
  </div>
  <input bind:this={fileInput} type="file" accept=".ogg,.oga,audio/ogg" hidden onchange={upload} />
</div>

<style>
  .preview {
    display: grid;
    gap: 10px;
    min-width: 0;
  }
  .player {
    aspect-ratio: 1;
    display: grid;
    place-items: center;
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background:
      conic-gradient(from 90deg, #1a1e26 25%, #151920 0 50%, #1a1e26 0 75%, #151920 0) 0 0 / 16px
        16px;
    overflow: hidden;
    padding: 12px;
  }
  audio {
    width: 100%;
  }
  .empty {
    color: var(--faint);
    font-size: 12px;
  }
  .sources {
    display: grid;
    gap: 4px;
    min-width: 0;
  }
  .head {
    font-size: 11px;
    color: var(--faint);
  }
  .row {
    display: flex;
    gap: 6px;
    align-items: center;
    min-width: 0;
  }
  .ref {
    flex: 1;
    min-width: 0;
    font-family: var(--mono);
    font-size: 11px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .faint {
    color: var(--faint);
    font-family: inherit;
  }
  .up {
    background: none;
    border: 1px solid var(--border-strong);
    color: inherit;
    border-radius: var(--radius);
    padding: 3px 8px;
    font-size: 11px;
    cursor: pointer;
    white-space: nowrap;
  }
  .up:hover:not(:disabled) {
    background: var(--bg-hover);
  }
  .up:disabled {
    color: var(--faint);
    cursor: default;
  }
</style>
