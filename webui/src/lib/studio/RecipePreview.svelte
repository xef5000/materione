<script lang="ts">
  /**
   * The recipe as a player sees it: the ingredients laid out, an arrow, and the
   * result as a card with its icon and name. It reads the draft, so it follows
   * the mode switch and every pick as they are made.
   */
  import Icon from '../components/Icon.svelte';
  import ItemIcon from './ItemIcon.svelte';
  import { itemLabel } from '../items';
  import { gridFrom } from '../studio';

  interface Props {
    pack: string;
    kind: string;
    shape: unknown;
    ingredients: unknown;
    input: string;
    result: string;
    experience: unknown;
    cookingTime: unknown;
  }

  let { pack, kind, shape, ingredients, input, result, experience, cookingTime }: Props = $props();

  const cells = $derived(
    kind === 'shapeless'
      ? Array.from({ length: 9 }, (_, index) =>
        Array.isArray(ingredients) && typeof ingredients[index] === 'string' ? ingredients[index] as string : '')
      : gridFrom(shape, ingredients).cells,
  );
  const seconds = $derived(
    typeof cookingTime === 'number' && cookingTime > 0 ? Math.round((cookingTime / 20) * 10) / 10 : 10,
  );
</script>

<section class="recipe-preview" aria-label="Recipe preview">
  <header><strong>{kind === 'furnace' ? 'Smelting' : kind === 'shapeless' ? 'Shapeless crafting' : 'Crafting table'}</strong></header>
  <div class="layout">
    {#if kind === 'furnace'}
      <div class="furnace">
        <span class="cell">{#if input}<ItemIcon value={input} {pack} size={32} />{/if}</span>
        <Icon name="flame" size={16} />
      </div>
    {:else}
      <div class="grid" aria-hidden="true">
        {#each cells as cell, index (index)}
          <span class="cell">{#if cell}<ItemIcon value={cell} {pack} size={32} />{/if}</span>
        {/each}
      </div>
    {/if}
    <Icon name="arrow-right" size={16} />
    <figure class="result" aria-label="Result">
      <span class="cell big">{#if result}<ItemIcon value={result} {pack} size={48} />{/if}</span>
      <figcaption>{result ? itemLabel(result, pack) : 'Pick a result'}</figcaption>
    </figure>
  </div>
  {#if kind === 'furnace'}
    <p class="meta">{experience ?? 0} xp · {seconds} s</p>
  {/if}
</section>

<style>
  .recipe-preview {
    display: grid;
    gap: var(--space-3);
    min-width: 0;
  }
  header {
    font-size: var(--text-sm);
    color: var(--muted);
  }
  .layout {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    flex-wrap: wrap;
    color: var(--muted);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(3, 36px);
    gap: var(--space-half);
  }
  .furnace {
    display: grid;
    justify-items: center;
    gap: var(--space-1);
  }
  .cell {
    display: grid;
    place-items: center;
    width: 36px;
    height: 36px;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
  }
  .cell.big {
    width: 56px;
    height: 56px;
    background: var(--bg-raised);
  }
  .result {
    margin: 0;
    display: grid;
    justify-items: center;
    gap: var(--space-1);
    min-width: 0;
  }
  figcaption {
    color: var(--fg);
    font-size: var(--text-sm);
    text-align: center;
    overflow-wrap: anywhere;
  }
  .meta {
    margin: 0;
    font-size: var(--text-xs);
    color: var(--faint);
  }
</style>
