<script lang="ts">
  /**
   * The sources of a sound as a list you can hear: every row plays its file,
   * draws its waveform and shows its length, and can have its file replaced or
   * be removed; "Add" uploads a new .ogg or picks one already in the pack. The
   * value is the plain `sounds:` list of references (the file on disk stays
   * the source of truth, D1); decoding happens in the browser only.
   */
  import { untrack } from 'svelte';
  import Icon from '../components/Icon.svelte';
  import { assetOps, unwrap } from '../api';
  import { bytesFromBase64, formatDuration, peaksOf } from '../audio';
  import { errorMessage, logActivity, session } from '../state.svelte';
  import { sanitizeSoundName, soundAssetPath } from '../studio';

  interface Props {
    value: unknown;
    pack: string;
    label: string;
    onChange: (value: unknown) => void;
  }

  let { value, pack, label, onChange }: Props = $props();

  /** Mirrors AssetOps.MAX_AUDIO_BYTES for .ogg/.oga uploads. */
  const MAX_AUDIO_BYTES = 8 * 1024 * 1024;
  const BINS = 64;

  interface Loaded {
    duration: number;
    peaks: number[];
    buffer: AudioBuffer;
  }

  const sources = $derived(
    Array.isArray(value) ? value.map((entry) => (typeof entry === 'string' ? entry : null)) : [],
  );

  let context: AudioContext | undefined;
  let loaded = $state<Record<string, Loaded | 'missing' | 'undecodable' | 'loading'>>({});
  let version = $state(0);
  let busy = $state(false);
  let playing = $state<string | null>(null);
  let current: AudioBufferSourceNode | undefined;
  let fileInput: HTMLInputElement | null = $state(null);
  /** The row the open file dialog will fill; null means "add a new one". */
  let target = $state<string | null>(null);
  let existing = $state<string[]>([]);

  function audioContext(): AudioContext {
    context ??= new AudioContext();
    return context;
  }

  /** Keys already requested: a plain set, so loading never re-triggers the effect. */
  const requested = new Set<string>();

  $effect(() => {
    const refs = sources.filter((source): source is string => source !== null);
    const stamp = version;
    untrack(() => {
      for (const reference of refs) void load(reference, stamp);
    });
  });

  $effect(() => {
    void listExisting();
    return () => stop();
  });

  async function load(reference: string, stamp: number) {
    const key = `${reference}#${stamp}`;
    const channel = session.channel;
    if (!channel || requested.has(key)) return;
    requested.add(key);
    loaded[key] = 'loading';
    let bytes: ArrayBuffer;
    try {
      bytes = bytesFromBase64(String(unwrap(await assetOps.get(channel, pack, soundAssetPath(reference))).data));
    } catch {
      loaded[key] = 'missing';
      return;
    }
    try {
      const buffer = await audioContext().decodeAudioData(bytes);
      loaded[key] = { duration: buffer.duration, peaks: peaksOf(buffer.getChannelData(0), BINS), buffer };
    } catch {
      loaded[key] = 'undecodable';
    }
  }

  function stateOf(reference: string): Loaded | 'missing' | 'undecodable' | 'loading' | undefined {
    return loaded[`${reference}#${version}`];
  }

  async function listExisting() {
    const channel = session.channel;
    if (!channel) return;
    try {
      const assets = (unwrap(await assetOps.list(channel, pack)).assets as { path: string; kind: string }[]) ?? [];
      existing = assets
        .filter((asset) => asset.path.startsWith('assets/sounds/') && /\.og[ag]$/i.test(asset.path))
        .map((asset) => asset.path.slice('assets/sounds/'.length).replace(/\.og[ag]$/i, ''));
    } catch {
      existing = [];
    }
  }

  function stop() {
    try {
      current?.stop();
    } catch {
      // already stopped
    }
    current = undefined;
    playing = null;
  }

  async function toggle(reference: string) {
    if (playing === reference) {
      stop();
      return;
    }
    stop();
    const state = stateOf(reference);
    if (!state || typeof state === 'string') return;
    const audio = audioContext();
    await audio.resume();
    const node = audio.createBufferSource();
    node.buffer = state.buffer;
    node.connect(audio.destination);
    node.onended = () => {
      if (current === node) {
        current = undefined;
        playing = null;
      }
    };
    current = node;
    playing = reference;
    node.start();
  }

  function remove(index: number) {
    stop();
    onChange((Array.isArray(value) ? value : []).filter((_, position) => position !== index));
  }

  function addExisting(reference: string) {
    if (reference) onChange([...(Array.isArray(value) ? value : []), reference]);
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
      unwrap(await assetOps.put(session.channel, pack, path, new Uint8Array(await file.arrayBuffer())));
      logActivity('save', `uploaded ${path}; targeted reload queued`);
      if (target === null) onChange([...(Array.isArray(value) ? value : []), reference]);
      version += 1;
    } catch (e) {
      logActivity('error', `upload: ${errorMessage(e)}`);
    } finally {
      busy = false;
    }
  }
</script>

<div class="sources" data-field-control tabindex="-1">
  <ol aria-label={label}>
    {#each sources as source, index (index)}
      {@const state = source ? stateOf(source) : undefined}
      <li data-source={source ?? ''}>
        {#if source === null}
          <span class="opaque">This entry is not a file name; edit it in Files.</span>
        {:else}
          <button type="button" class="play" disabled={!state || typeof state === 'string'}
            aria-label={`${playing === source ? 'Stop' : 'Play'} ${source}`}
            aria-pressed={playing === source} onclick={() => toggle(source)}>
            <Icon name={playing === source ? 'pause' : 'play'} size={14} />
          </button>
          <span class="name" title={source}>{source}</span>
          {#if state && typeof state !== 'string'}
            <svg class="wave" viewBox={`0 0 ${BINS} 24`} preserveAspectRatio="none" role="img"
              aria-label={`Waveform of ${source}`}>
              {#each state.peaks as peak, bin (bin)}
                <rect x={bin + 0.15} width="0.7" y={12 - Math.max(0.5, peak * 11)}
                  height={Math.max(1, peak * 22)} />
              {/each}
            </svg>
            <span class="duration" data-duration>{formatDuration(state.duration)}</span>
          {:else}
            <span class="status">
              {state === 'missing' ? 'file missing: upload it' : state === 'undecodable' ? "can't read this file" : 'loading…'}
            </span>
          {/if}
          <button type="button" class="small" disabled={busy} onclick={() => pick(source)}
            aria-label={`Replace the file of ${source}`}>Replace</button>
        {/if}
        <button type="button" class="small" aria-label={`Remove source ${index + 1}`} onclick={() => remove(index)}>
          <Icon name="x" size={12} />
        </button>
      </li>
    {/each}
  </ol>
  <div class="add">
    <button type="button" class="small" disabled={busy} onclick={() => pick(null)}
      title="upload a new .ogg (up to 8 MiB) and add it to the list">
      <Icon name="plus" size={14} /> Upload .ogg
    </button>
    {#if existing.filter((name) => !sources.includes(name)).length > 0}
      <select aria-label="Add a sound file already in this pack" value=""
        onchange={(event) => { addExisting(event.currentTarget.value); event.currentTarget.value = ''; }}>
        <option value="">Add a file from this pack…</option>
        {#each existing.filter((name) => !sources.includes(name)) as name (name)}
          <option value={name}>{name}</option>
        {/each}
      </select>
    {/if}
  </div>
  <input bind:this={fileInput} type="file" accept=".ogg,.oga,audio/ogg" hidden onchange={upload} />
</div>

<style>
  .sources {
    display: grid;
    gap: var(--space-2);
    min-width: 0;
  }
  ol {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--space-1);
  }
  li {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-2);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    min-width: 0;
  }
  .play,
  .small {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: var(--space-1);
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--muted);
    font-size: var(--text-sm);
    padding: var(--space-half) var(--space-2);
    cursor: pointer;
    white-space: nowrap;
  }
  .play {
    width: 28px;
    height: 28px;
    padding: 0;
    border-radius: var(--radius-pill);
    color: var(--fg);
  }
  .play[aria-pressed='true'] {
    background: var(--accent-low);
    border-color: var(--accent);
  }
  .play:disabled {
    opacity: 0.4;
    cursor: default;
  }
  .play:hover:not(:disabled),
  .small:hover:not(:disabled) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .name {
    flex: 0 1 12em;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font: var(--text-sm) var(--mono);
  }
  .wave {
    flex: 1;
    min-width: 60px;
    height: 24px;
    fill: var(--accent);
  }
  .duration {
    color: var(--muted);
    font-size: var(--text-sm);
    font-variant-numeric: tabular-nums;
    min-width: 4ch;
    text-align: right;
  }
  .status,
  .opaque {
    flex: 1;
    color: var(--warn);
    font-size: var(--text-sm);
  }
  .add {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
  select {
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-sm);
  }
</style>
