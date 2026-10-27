<script lang="ts">
  /**
   * A real 3×3 crafting table. Click a slot, pick an ingredient from the item
   * picker (this pack's items plus vanilla, with icons); the studio writes back
   * `shape` + `ingredients` so the YAML stays the canonical form (D1) and the
   * grid is only a view of it.
   */
  import ItemPicker from './ItemPicker.svelte';
  import { gridFrom, gridTo } from '../studio';

  interface Props {
    shape: unknown;
    ingredients: unknown;
    pack: string;
    onChange: (next: { shape: string[]; ingredients: Record<string, string> }) => void;
  }

  let { shape, ingredients, pack, onChange }: Props = $props();

  const cells = $derived(gridFrom(shape, ingredients).cells);

  function set(index: number, value: string) {
    const next = [...cells];
    next[index] = value;
    onChange(gridTo(next));
  }
</script>

<div class="grid" role="group" aria-label="Crafting grid">
  {#each cells as cell, index (index)}
    <ItemPicker {pack} mode="slot" value={cell}
      label={`Slot ${Math.floor(index / 3) + 1},${(index % 3) + 1}`}
      onPick={(next) => set(index, next)} />
  {/each}
</div>

<style>
  .grid {
    display: grid;
    grid-template-columns: repeat(3, 56px);
    gap: var(--space-1);
    width: max-content;
  }
</style>
