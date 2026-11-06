<script lang="ts">
  /**
   * The Recipes panel (logic.md section 7.5, 33): the server's recipe registry as a list (a name and one line each), the
   * parameters of the chosen one drawn with the node inspector's typed fields, and an Insert that has the server expand
   * the recipe and puts the resulting rules into the file as one undo step. A recipe the server cannot run here is greyed
   * with the server's reason; inserting it is still allowed, because the server treats that as a warning (section 30).
   */
  import Icon from '../../../components/Icon.svelte';
  import type { LogicCanvasState } from '../../../logic/canvas.svelte';
  import { paramInfo } from '../../../logic/recipes/recipes';
  import type { Json } from '../../../logic/tree';
  import { session } from '../../../state.svelte';
  import { provideInspector } from '../inspector/context';
  import ParamForm from '../inspector/ParamForm.svelte';

  let { canvas }: { canvas: LogicCanvasState } = $props();

  // svelte-ignore state_referenced_locally
  provideInspector(canvas.inspector);

  const recipes = $derived(canvas.recipes);
  const chosen = $derived(recipes.recipe);
  const layers = $derived(canvas.gui.doc.layers.map((layer) => layer.id));
  const infos = $derived(chosen ? chosen.params.map((param) => paramInfo(param, layers)) : []);
  const disabled = $derived(!canvas.editable);
  /** The layer selected in the Design tab, offered as the recipe's `layer`. */
  const selectedLayer = $derived(canvas.gui.selection.length === 1 ? canvas.gui.selection[0] : undefined);
</script>

<aside class="recipes" aria-label="Recipes" data-testid="logic-recipes">
  <header>
    <h3><Icon name="sparkles" size={14} /> Recipes</h3>
    <button type="button" class="close" aria-label="Close the recipes panel" title="Close" onclick={() => (recipes.open = false)}>
      <Icon name="x" size={14} />
    </button>
  </header>

  {#if recipes.list.length === 0}
    <p class="empty" data-testid="logic-recipes-empty">This server lists no recipes.</p>
  {:else}
    <ul class="list">
      {#each recipes.list as recipe (recipe.id)}
        {@const why = recipes.reasonFor(recipe)}
        <li>
          <button
            type="button"
            class="item"
            class:on={recipes.selected === recipe.id}
            class:grey={why !== null}
            data-recipe={recipe.id}
            data-unavailable={why !== null ? 'true' : undefined}
            aria-pressed={recipes.selected === recipe.id}
            title={why ? `${recipe.description}\n\nNot available here: ${why}` : recipe.description}
            onclick={() => recipes.choose(recipes.selected === recipe.id ? null : recipe.id, selectedLayer)}
          >
            <span class="name">{recipe.displayName}</span>
            <span class="line">{recipe.description}</span>
            {#if why}<span class="why" data-testid="recipe-reason"><Icon name="triangle-alert" size={11} /> {why}</span>{/if}
          </button>
          {#if chosen && recipes.selected === recipe.id}
            {#key chosen.id}
              <section class="detail" aria-label="{chosen.displayName} parameters" data-testid="logic-recipe-form">
                <p class="about">{chosen.description}</p>
                <ParamForm params={infos} payload={recipes.values} {disabled} write={(next) => recipes.setValues(next as Json)} />
                {#if recipes.error}
                  <p class="error" role="alert" data-testid="recipe-error">{recipes.error}</p>
                {/if}
                {#each recipes.warnings as warning (warning.message)}
                  <p class="warn" role="status" data-testid="recipe-warning"><Icon name="triangle-alert" size={12} /> {warning.code}: {warning.message}</p>
                {/each}
                {#if recipes.inserted}
                  <p class="done" role="status" data-testid="recipe-inserted"><Icon name="check" size={12} /> {recipes.inserted}</p>
                {/if}
                <button
                  type="button"
                  class="insert"
                  disabled={disabled || recipes.busy || recipes.missing.length > 0}
                  title={recipes.missing.length > 0 ? `Fill in ${recipes.missing.join(', ')} first` : 'Expand the recipe on the server and add its rules'}
                  data-testid="recipe-insert"
                  onclick={() => void recipes.insert(session.channel)}
                >
                  <Icon name="plus" size={14} /> {recipes.busy ? 'Inserting…' : why ? 'Insert anyway' : 'Insert'}
                </button>
              </section>
            {/key}
          {/if}
        </li>
      {/each}
    </ul>
  {/if}
</aside>

<style>
  .recipes {
    display: flex;
    flex: none;
    flex-direction: column;
    width: clamp(260px, 28%, 360px);
    min-width: 0;
    min-height: 0;
    overflow-y: auto;
    overflow-x: hidden;
    background: var(--bg-panel);
    border-right: 1px solid var(--border);
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  h3 {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin: 0;
    font-size: var(--text-sm);
  }
  .close {
    display: inline-grid;
    place-items: center;
    width: 24px;
    height: 24px;
    background: none;
    border: 0;
    border-radius: var(--radius-sm);
    color: var(--muted);
    cursor: pointer;
  }
  .close:hover {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .empty {
    margin: var(--space-3);
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .list {
    display: grid;
    gap: var(--space-1);
    margin: 0;
    padding: var(--space-2);
    list-style: none;
  }
  .item {
    display: grid;
    gap: 2px;
    width: 100%;
    padding: var(--space-2) var(--space-3);
    background: var(--bg-raised);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    color: var(--fg);
    text-align: left;
    cursor: pointer;
  }
  .item:hover {
    border-color: var(--border-strong);
  }
  .item.on {
    border-color: var(--accent);
    background: var(--accent-low);
  }
  .item.grey .name,
  .item.grey .line {
    opacity: 0.55;
  }
  .name {
    font-size: var(--text-sm);
    font-weight: 600;
  }
  .line {
    overflow: hidden;
    color: var(--muted);
    font-size: var(--text-xs);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .why {
    display: flex;
    align-items: center;
    gap: var(--space-1);
    color: var(--warn);
    font-size: var(--text-xs);
  }
  .detail {
    margin-top: var(--space-1);
    padding: var(--space-3);
    background: var(--bg-raised);
    border: 1px solid var(--accent);
    border-radius: var(--radius-sm);
  }
  .about {
    margin: 0 0 var(--space-3);
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .warn,
  .error,
  .done {
    display: flex;
    gap: var(--space-1);
    margin: 0 0 var(--space-2);
    font-size: var(--text-xs);
  }
  .warn {
    color: var(--warn);
  }
  .error {
    color: var(--error);
  }
  .done {
    color: var(--ok);
  }
  .insert {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    height: 28px;
    padding: 0 var(--space-3);
    background: var(--accent);
    border: 0;
    border-radius: var(--radius);
    color: var(--accent-ink);
    font: 600 var(--text-sm) var(--font-sans);
    cursor: pointer;
  }
  .insert:disabled {
    background: none;
    border: 1px solid var(--border);
    color: var(--faint);
    cursor: default;
  }
</style>
