<script lang="ts">
  /**
   * What an `image` layer needs beyond its schema fields: where the texture comes from (upload a
   * file, or pick one from the Assets workspace) and how it is drawn. Images scale with nearest
   * neighbour, so the useful sizes are whole multiples of the natural size; "Natural size" puts the
   * layer back to 1:1.
   */
  import Icon from '../../components/Icon.svelte';
  import AssetExplorer from '../AssetExplorer.svelte';
  import type { GuiLayer } from '../../gui/model';
  import type { GuiStudioState } from '../../gui/studio.svelte';
  import { session } from '../../state.svelte';

  let { studio, layer, textures, disabled }: { studio: GuiStudioState; layer: GuiLayer; textures: string[]; disabled: boolean } = $props();

  let picking = $state(false);
  let file = $state<HTMLInputElement>();
  const reference = $derived(typeof layer.image === 'string' ? layer.image : '');
  const natural = $derived(studio.naturalSize(layer));
  const width = $derived(typeof layer.width === 'number' ? layer.width : natural?.w);
  const height = $derived(typeof layer.height === 'number' ? layer.height : natural?.h);
  const atNatural = $derived(!!natural && width === natural.w && height === natural.h);
  const scale = $derived(natural && width && height && width % natural.w === 0 && height % natural.h === 0 && width / natural.w === height / natural.h ? width / natural.w : null);

  async function chosen(event: Event): Promise<void> {
    const input = event.currentTarget as HTMLInputElement;
    const picked = input.files?.[0];
    input.value = '';
    if (picked) await studio.replaceImageFromFile(layer.id, picked, textures);
  }
</script>

<section class="group" data-testid="image-extras">
  <h3>Texture</h3>
  <div class="current" class:empty={!reference}>
    <Icon name="image" size={16} />
    <code title={reference}>{reference || 'No texture yet'}</code>
  </div>
  <div class="row">
    <button type="button" class="btn" {disabled} onclick={() => file?.click()}><Icon name="upload" size={14} /> Upload…</button>
    <button type="button" class="btn" {disabled} onclick={() => (picking = true)}><Icon name="layout-grid" size={14} /> From assets…</button>
    <input bind:this={file} type="file" accept="image/*" hidden aria-label="Upload an image" onchange={chosen} />
  </div>
  {#if !reference}
    <p class="note">Upload a PNG (it is stored beside this GUI's other textures) or pick one the pack already has. You can also drop an image file on the canvas.</p>
  {/if}
  {#if natural}
    <p class="size" data-testid="image-size">
      {width} x {height} px · natural {natural.w} x {natural.h}{scale ? ` · ${scale}x` : ' · stretched'}
    </p>
    <div class="row">
      <button type="button" class="btn" data-testid="natural-size" disabled={disabled || atNatural} onclick={() => studio.resetNaturalSize(layer.id)}>
        <Icon name="scan" size={14} /> Natural size
      </button>
      <div class="scales" role="group" aria-label="Scale">
        {#each [1, 2, 3, 4] as factor (factor)}
          <button type="button" class:on={scale === factor} {disabled} aria-pressed={scale === factor} title="{factor}x the natural size" onclick={() => studio.scaleImage(layer.id, factor)}>{factor}x</button>
        {/each}
      </div>
    </div>
  {:else if reference}
    <p class="note">The texture is not loaded: it may be missing from the pack.</p>
  {/if}
</section>

{#if picking}
  <AssetExplorer
    pack={studio.pack}
    kind="texture"
    current={reference}
    onPick={(stored) => {
      picking = false;
      if (stored) void studio.useTexture(session.channel, layer.id, stored);
    }}
    onClose={() => (picking = false)}
  />
{/if}

<style>
  .group {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  h3 {
    margin: 0;
    font-size: var(--text-xs);
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--muted);
  }
  .current {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
    color: var(--muted);
  }
  .current code {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-family: var(--mono);
    font-size: var(--text-sm);
    color: var(--fg);
  }
  .current.empty code {
    color: var(--faint);
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .btn {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-3);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .btn:hover:not(:disabled) {
    background: var(--bg-hover);
  }
  .btn:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .scales {
    display: flex;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    overflow: hidden;
  }
  .scales button {
    padding: var(--space-1) var(--space-2);
    background: var(--bg-input);
    border: 0;
    border-right: 1px solid var(--border-strong);
    color: var(--muted);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .scales button:last-child {
    border-right: 0;
  }
  .scales button.on {
    background: var(--accent-low);
    color: var(--accent-hover);
    font-weight: 600;
  }
  .scales button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .size,
  .note {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
    font-variant-numeric: tabular-nums;
  }
</style>
