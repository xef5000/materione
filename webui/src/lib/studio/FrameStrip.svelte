<script lang="ts">
  /**
   * The animation frames of a glyph as a strip of thumbnails: reorder, remove,
   * add another texture. The value is the plain `frames:` list of texture
   * references — the strip is only a view of it. Entries that are not plain
   * strings are kept in place and shown as opaque cells.
   */
  import AssetPicker from '../components/AssetPicker.svelte';
  import Icon from '../components/Icon.svelte';
  import TextureThumb from './TextureThumb.svelte';

  interface Props {
    value: unknown;
    pack: string;
    label: string;
    onChange: (value: unknown) => void;
  }

  let { value, pack, label, onChange }: Props = $props();

  const frames = $derived(Array.isArray(value) ? value : []);

  function move(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= frames.length) return;
    const next = [...frames];
    [next[index], next[target]] = [next[target]!, next[index]!];
    onChange(next);
  }

  function remove(index: number) {
    onChange(frames.filter((_, position) => position !== index));
  }

  function add(reference: string) {
    if (reference) onChange([...frames, reference]);
  }
</script>

<div class="strip" data-field-control tabindex="-1">
  <ol aria-label={label}>
    {#each frames as frame, index (index)}
      <li data-frame={index}>
        {#if typeof frame === 'string'}
          <TextureThumb {pack} reference={frame} size={48} />
          <span class="name" title={frame}>{frame.slice(frame.lastIndexOf('/') + 1)}</span>
        {:else}
          <span class="opaque" title="Edit this entry in Files">?</span>
        {/if}
        <span class="controls">
          <button type="button" aria-label={`Move frame ${index + 1} earlier`} disabled={index === 0}
            onclick={() => move(index, -1)}><Icon name="chevron-left" size={12} /></button>
          <span class="index">{index + 1}</span>
          <button type="button" aria-label={`Move frame ${index + 1} later`} disabled={index === frames.length - 1}
            onclick={() => move(index, 1)}><Icon name="chevron-right" size={12} /></button>
          <button type="button" aria-label={`Remove frame ${index + 1}`} onclick={() => remove(index)}>
            <Icon name="x" size={12} />
          </button>
        </span>
      </li>
    {/each}
  </ol>
  <div class="add">
    <span>Add a frame</span>
    <AssetPicker {pack} hint="texture" value="" onChange={add} />
  </div>
</div>

<style>
  .strip {
    display: grid;
    gap: var(--space-2);
    min-width: 0;
  }
  ol {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  li {
    display: grid;
    justify-items: center;
    gap: var(--space-1);
    padding: var(--space-1);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
  }
  .name {
    max-width: 8ch;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: var(--text-xs);
    color: var(--muted);
  }
  .opaque {
    display: grid;
    place-items: center;
    width: 48px;
    height: 48px;
    color: var(--warn);
  }
  .controls {
    display: inline-flex;
    align-items: center;
    gap: var(--space-half);
  }
  .controls button {
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
    padding: 0;
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--muted);
    cursor: pointer;
  }
  .controls button:hover:not(:disabled) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .controls button:disabled {
    opacity: 0.4;
    cursor: default;
  }
  .index {
    min-width: 2ch;
    text-align: center;
    font-size: var(--text-xs);
    color: var(--fg);
  }
  .add {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-sm);
    color: var(--muted);
  }
</style>
