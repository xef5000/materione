<script lang="ts">
  import type { ResourceSummary } from '../api';
  import LegacyText from '../components/LegacyText.svelte';
  import ResourceThumbnail from '../components/ResourceThumbnail.svelte';
  import { materialLabel } from '../materials';
  import { plainLegacyText } from '../legacy-text';
  import { connection, delivery, ui, workspace } from '../state.svelte';

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
  const plainTitle = $derived(plainLegacyText(title));
  const lines = $derived(Array.isArray(lore) ? lore.filter((line): line is string => typeof line === 'string') : []);
  const unsupportedLore = $derived(lore !== undefined && lore !== null &&
    (!Array.isArray(lore) || lore.some((line) => typeof line !== 'string')));
</script>

<section class="item-preview" aria-label="Item preview">
  <header><strong>Your item</strong><span>Browser approximation</span></header>
  <div class="inventory">
    <div class="slot" title="32 CSS-pixel inventory slot; actual game size depends on GUI scale.">
      <ResourceThumbnail {visual} {render} {material} label={plainTitle} size={32} version={version + workspace.visualVersion} />
    </div>
    <div><strong>Inventory scale</strong><small>32 px slot</small></div>
  </div>
  <div class="inspection">
    <ResourceThumbnail {visual} {render} {material} label={plainTitle} size={128} version={version + workspace.visualVersion} />
  </div>
  <p class="visual-kind">{render ? 'Model preview drawn in the browser - not a client render' :
    visual?.kind === 'model-thumbnail' ? 'Cached model thumbnail - not interactive 3D' :
    visual ? 'Enlarged texture inspection - not a model render' :
    material ? 'Vanilla item icon where available - no custom visual set' : 'No supported visual available'}</p>

  <div class="tooltip" aria-label="Item tooltip preview">
    <button type="button" class="item-name" disabled={!canFocusName} onclick={() => onFocus('display_name')}
      aria-label="Edit item name" title="Focus the display name editor"><LegacyText text={title} /></button>
    <button type="button" class="lore" disabled={!canFocusLore} onclick={() => onFocus('lore')}
      aria-label="Edit item lore" title="Focus the lore editor">
      {#each lines as line}<span class="lore-line"><LegacyText text={line} /></span>{/each}
      {#if !lines.length}<span class="add-lore">Add a description...</span>{/if}
    </button>
    {#if unsupportedLore}<small>Some lore values need Files and are not previewed.</small>{/if}
  </div>
  <small class="disclaimer">Draft name and lore update here without saving. Fonts, glyphs, effects, obfuscation and gameplay need an in-game check.</small>
  <div class="game-check">
    <strong>Check in game</strong>
    <span>{connection.connected ? 'Server connected' : 'Server disconnected; status unknown'}</span>
    <span>{!delivery.loaded ? 'Pack status unknown' : delivery.candidateSha256
      ? 'Candidate available; client acceptance unknown' : 'No candidate pack reported'}</span>
    <button type="button" onclick={() => { ui.view = 'server'; }}>View server and pack status</button>
    <small>Save &amp; apply changes gameplay server-wide. Publish pack releases the pack to other players.</small>
  </div>
</section>

<style>
  .item-preview { display: grid; gap: var(--space-3); min-width: 0; }
  header, .inventory { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
  header { justify-content: space-between; font-size: var(--text-base); }
  header span, small, .visual-kind { font-size: var(--text-xs); color: var(--muted); }
  .inventory { font-size: var(--text-sm); }
  .inventory small { display: block; }
  .slot { padding: var(--space-1); border: 2px solid var(--border-strong); background: var(--bg-input); }
  .inspection { display: grid; place-items: center; min-height: 150px; background: var(--bg-input); border: 1px solid var(--border); border-radius: var(--radius-lg); }
  .visual-kind { margin: -6px 0 0; }
  .tooltip { background: var(--bg-input); border: 2px solid var(--border-strong); border-radius: var(--radius); padding: var(--space-3); min-width: 0; }
  .item-name, .lore { display: block; width: 100%; min-width: 0; background: none; border: 0; text-align: left; padding: 0; cursor: pointer; font-size: var(--text-base); }
  .item-name { color: var(--legacy-f); }
  .lore { color: var(--legacy-5); margin-top: var(--space-1); }
  .lore-line { display: block; min-height: 1.5em; }
  .add-lore { color: var(--muted); font-size: var(--text-sm); }
  .item-name:disabled, .lore:disabled { cursor: default; }
  .item-name:not(:disabled):hover, .lore:not(:disabled):hover { outline: 1px solid var(--border-strong); outline-offset: 3px; }
  .disclaimer { line-height: 1.5; }
  .game-check { display: grid; gap: var(--space-1); font-size: var(--text-xs); border-top: 1px solid var(--border); padding-top: var(--space-3); color: var(--muted); }
  .game-check strong { color: var(--fg); }
  .game-check button:not(.btn) { justify-self: start; background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: var(--text-xs); text-align: left; }
</style>
