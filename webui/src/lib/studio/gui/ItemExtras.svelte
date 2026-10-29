<script lang="ts">
  /**
   * What an `item` layer needs beyond its schema fields: the item picker (Materione items with their
   * thumbnails, vanilla materials with their icons), the compiler's verdict on the layer, and
   * Customize. Customize gives the layer its own ordinary item and opens its appearance right here;
   * it is one authoring transaction (`GuiStudioState.customize`), never a GUI-local concept.
   */
  import Icon from '../../components/Icon.svelte';
  import ItemPicker from '../ItemPicker.svelte';
  import GuiItemAppearance from './GuiItemAppearance.svelte';
  import { getDocumentDraft } from '../../drafts.svelte';
  import { isCustomized } from '../../gui/customize';
  import { itemRefOf, storedItemRef } from '../../gui/items';
  import type { GuiLayer } from '../../gui/model';
  import type { GuiStudioState } from '../../gui/studio.svelte';
  import { refreshPack, session, workspace } from '../../state.svelte';

  let { studio, layer, disabled }: { studio: GuiStudioState; layer: GuiLayer; disabled: boolean } = $props();

  let busy = $state(false);
  const reference = $derived(itemRefOf(layer));
  const environment = $derived({
    pack: studio.pack,
    guiPath: studio.path,
    resources: workspace.resources,
  });
  const custom = $derived(reference ? isCustomized(reference, environment) : undefined);
  /** The compiler's problems with this layer, from the server (gui.validate). */
  const problems = $derived(studio.flagsFor(layer.id).map((flag) => flag.message));
  /** Customize needs the server's GUI API; without it the button says why. */
  const unavailable = $derived(studio.checks === 'unavailable');

  async function customize(): Promise<void> {
    const channel = session.channel;
    if (!channel || busy) return;
    busy = true;
    try {
      const created = await studio.customize(channel, getDocumentDraft(studio.pack, studio.path), layer.id);
      if (created) await refreshPack();
    } finally {
      busy = false;
    }
  }
</script>

<section class="group" data-testid="item-extras">
  <h3>Item</h3>
  <ItemPicker
    pack={studio.pack}
    label="Item"
    value={reference}
    required
    onPick={(value) => studio.setField(layer.id, 'item', storedItemRef(value, studio.pack))}
  />
  {#each problems as problem (problem)}
    <p class="problem" role="note"><Icon name="triangle-alert" size={14} /><span>{problem}</span></p>
  {/each}
</section>

<section class="group" data-testid="item-appearance">
  <h3>Appearance</h3>
  {#if custom}
    <p class="note"><Icon name="check" size={14} /><span>This layer has its own item, <code>{custom.path}</code>.</span></p>
    {#key custom.path}<GuiItemAppearance resource={custom} />{/key}
  {:else}
    <p class="note">
      <span>The layer shows {reference ? 'the item as it is in your pack or in Minecraft' : 'nothing yet'}. Customize gives it
      its own item (name, lore, texture, model) at <code>items/gui/…</code> without touching the original.</span>
    </p>
    <button
      type="button"
      class="btn"
      disabled={disabled || busy || studio.saving || unavailable}
      title={unavailable
        ? 'This server does not offer the GUI API (the gui capability): update the Materione plugin to customize items'
        : 'Create an item for this layer and edit its appearance here'}
      onclick={customize}
    >
      <Icon name="sparkles" size={14} /> {busy ? 'Customizing…' : 'Customize'}
    </button>
    {#if unavailable}<p class="note"><span>Customize needs a Materione plugin with the GUI API.</span></p>{/if}
  {/if}
</section>

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
  .note,
  .problem {
    display: flex;
    align-items: flex-start;
    gap: var(--space-2);
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .problem {
    color: var(--warn);
  }
  code {
    font-family: var(--mono);
    font-size: var(--text-xs);
    overflow-wrap: anywhere;
  }
  .btn {
    align-self: flex-start;
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-3);
    background: var(--accent-low);
    border: 1px solid var(--accent);
    border-radius: var(--radius-sm);
    color: var(--accent-hover);
    font-size: var(--text-sm);
    font-weight: 600;
    cursor: pointer;
  }
  .btn:hover:not(:disabled) {
    background: var(--bg-hover);
  }
  .btn:disabled {
    opacity: 0.5;
    cursor: default;
  }
</style>
