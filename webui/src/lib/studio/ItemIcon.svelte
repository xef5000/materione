<script lang="ts">
  /**
   * The icon for an item reference: a pack item's own visual, or the vanilla
   * item's icon (the pinned jsDelivr package, vanilla-icons.ts). Chips, recipe
   * slots and the result card all draw items through this one component.
   */
  import ResourceThumbnail from '../components/ResourceThumbnail.svelte';
  import { itemLabel, parseItemRef, summaryFor } from '../items';
  import { workspace } from '../state.svelte';

  interface Props {
    value: string;
    pack: string;
    size?: number;
  }

  let { value, pack, size = 24 }: Props = $props();
  const parsed = $derived(parseItemRef(value, pack));
  const summary = $derived(summaryFor(workspace.summaries, value, pack));
  const label = $derived(itemLabel(value, pack) || 'No item');
</script>

{#if parsed.vanilla}
  <ResourceThumbnail material={parsed.id} {label} {size} />
{:else if summary}
  <ResourceThumbnail visual={summary.visual} render={summary.render} material={summary.material}
    {label} {size} version={workspace.visualVersion} />
{:else}
  <ResourceThumbnail {label} {size} />
{/if}
