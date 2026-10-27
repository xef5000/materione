<script lang="ts">
  /**
   * A list of `#RRGGBB` colors as swatch chips: click a swatch to change its
   * color with the system picker, x removes it, "Add color" appends one. The
   * value is the plain YAML list; entries that are not exactly `#RRGGBB`
   * strings keep their place as an opaque chip and are never rewritten.
   */
  import Icon from '../components/Icon.svelte';
  import { parseHex, toHex } from '../effects';

  interface Props {
    value: unknown;
    label: string;
    /** The schema's example color: what a new chip starts as. */
    seed?: string;
    onChange: (value: unknown) => void;
  }

  let { value, label, seed, onChange }: Props = $props();

  const list = $derived(Array.isArray(value) ? value : []);

  function validHex(entry: unknown): entry is string {
    return typeof entry === 'string' && parseHex(entry) !== null;
  }

  function replace(index: number, color: string) {
    onChange(list.map((entry, position) => (position === index ? color.toUpperCase() : entry)));
  }

  function remove(index: number) {
    onChange(list.filter((_, position) => position !== index));
  }

  function add() {
    const last = [...list].reverse().find(validHex);
    onChange([...list, (seed && parseHex(seed) ? seed : last ?? toHex(255, 0, 0)).toUpperCase()]);
  }
</script>

<div class="palette" data-field-control tabindex="-1">
  <ul aria-label={label}>
    {#each list as entry, index (index)}
      <li class="swatch-item" data-color={typeof entry === 'string' ? entry : ''}>
        {#if validHex(entry)}
          <label class="swatch" style:background={entry} title={entry}>
            <input type="color" value={entry.toLowerCase()} aria-label={`Color ${index + 1}: ${entry}`}
              onchange={(event) => replace(index, event.currentTarget.value)} />
          </label>
          <code>{entry}</code>
        {:else}
          <span class="swatch opaque" title="Not a #RRGGBB color; edit it in Files">?</span>
          <code>{typeof entry === 'string' ? entry : 'entry'}</code>
        {/if}
        <button type="button" aria-label={`Remove color ${index + 1}`} onclick={() => remove(index)}>
          <Icon name="x" size={12} />
        </button>
      </li>
    {/each}
  </ul>
  <button type="button" class="btn btn-sm" onclick={add}><Icon name="plus" size={14} /> Add color</button>
</div>

<style>
  .palette {
    display: grid;
    gap: var(--space-2);
    justify-items: start;
    min-width: 0;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .swatch-item {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-2);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-pill);
  }
  .swatch {
    position: relative;
    display: grid;
    place-items: center;
    width: 20px;
    height: 20px;
    border: 1px solid var(--control-line);
    border-radius: var(--radius-pill);
    overflow: hidden;
    cursor: pointer;
  }
  .swatch input {
    position: absolute;
    inset: 0;
    opacity: 0;
    cursor: pointer;
  }
  .swatch.opaque {
    color: var(--warn);
    font-size: var(--text-xs);
  }
  code {
    font: var(--text-sm) var(--mono);
  }
  .swatch-item button {
    display: grid;
    place-items: center;
    width: 16px;
    height: 16px;
    padding: 0;
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
  }
  .swatch-item button:hover {
    color: var(--fg);
  }
</style>
