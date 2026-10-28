<script lang="ts">
  import type { ResourceSummary } from '../api';
  import RichText from '../components/RichText.svelte';
  import ResourceThumbnail from '../components/ResourceThumbnail.svelte';
  import { materialLabel } from '../materials';
  import { plainText } from '../rich-text/model';
  import { workspace } from '../state.svelte';

  interface Props {
    name: unknown;
    lore: unknown;
    material: string;
    visual?: ResourceSummary['visual'];
    render?: ResourceSummary['render'];
    version: number;
    canFocusName: boolean;
    canFocusLore: boolean;
    onFocus: (field: 'display_name' | 'lore') => void;
  }
  let { name, lore, material, visual, render, version, canFocusName, canFocusLore, onFocus }: Props = $props();
  const title = $derived(typeof name === 'string' && name.trim() ? name : materialLabel(material) || 'Unnamed item');
  const plainTitle = $derived(plainText(title));
  const lines = $derived(Array.isArray(lore) ? lore.filter((line): line is string => typeof line === 'string') : []);
  const unsupportedLore = $derived(lore !== undefined && lore !== null &&
    (!Array.isArray(lore) || lore.some((line) => typeof line !== 'string')));
</script>

<section class="item-preview" aria-label="Item preview">
  <div class="inventory">
    <div class="slot" title="32 CSS-pixel inventory slot; actual game size depends on GUI scale.">
      <ResourceThumbnail {visual} {render} {material} label={plainTitle} size={32} version={version + workspace.visualVersion} />
    </div>
    <div><strong>Inventory scale</strong><small>32 px slot</small></div>
  </div>
  <div class="inspection">
    <ResourceThumbnail {visual} {render} {material} label={plainTitle} size={128} version={version + workspace.visualVersion} />
  </div>
  <div class="tooltip mc-tooltip" aria-label="Item tooltip preview">
    <button type="button" class="item-name" disabled={!canFocusName} onclick={() => onFocus('display_name')}
      aria-label="Edit item name" title="Focus the display name editor"><RichText text={title} /></button>
    <button type="button" class="lore" disabled={!canFocusLore} onclick={() => onFocus('lore')}
      aria-label="Edit item lore" title="Focus the lore editor">
      {#each lines as line}<span class="lore-line"><RichText text={line} tone="lore" /></span>{/each}
      {#if !lines.length}<span class="add-lore">Add a description...</span>{/if}
    </button>
    {#if unsupportedLore}<small>Some lore values need Files and are not previewed.</small>{/if}
  </div>
</section>

<style>
  .item-preview { display: grid; gap: var(--space-3); min-width: 0; }
  .inventory { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; font-size: var(--text-sm); }
  small { font-size: var(--text-xs); color: var(--muted); }
  .slot { padding: var(--space-1); border: 2px solid var(--border-strong); background: var(--bg-input); }
  .inspection { display: grid; place-items: center; min-height: 150px; background: var(--bg-input); border: 1px solid var(--border); border-radius: var(--radius-lg); }
  .inventory small { display: block; }
  .tooltip { min-width: 0; }
  .item-name, .lore { display: block; width: 100%; min-width: 0; background: none; border: 0; text-align: left; padding: 0; cursor: pointer; }
  .item-name { color: var(--legacy-f); }
  .lore { color: var(--legacy-5); margin-top: var(--space-1); }
  .lore-line { display: block; min-height: 1.25em; }
  .add-lore { color: var(--muted); font-size: var(--text-sm); }
  .item-name:disabled, .lore:disabled { cursor: default; }
  .item-name:not(:disabled):hover, .lore:not(:disabled):hover { outline: 1px solid var(--border-strong); outline-offset: 3px; }
</style>
