<script lang="ts">
  /**
   * The item studio's one Appearance component (issue 173): the item drawn once
   * and large, with the controls that change its look on it. Texture and model
   * are chosen from the asset picker the two buttons under the render open;
   * name and lore are edited in place inside the Minecraft-style tooltip,
   * with the rich-text toolbar on selection. Any leftover appearance options
   * sit behind the small menu in the render's corner. It is a client of the
   * studio's draft like every other control: each change is one field edit
   * (D6), and fields it does not model stay untouched in the file.
   */
  import type { Snippet } from 'svelte';
  import Field from './Field.svelte';
  import AssetExplorer from './AssetExplorer.svelte';
  import Icon from '../components/Icon.svelte';
  import ResourceThumbnail from '../components/ResourceThumbnail.svelte';
  import RichTextEditor from '../components/RichTextEditor.svelte';
  import type { DiagnosticEntry, ResourceSummary, TypeParam } from '../api';
  import { normalizedReference } from '../asset-choice';
  import { materialLabel } from '../materials';
  import { plainText } from '../rich-text/model';
  import { workspace } from '../state.svelte';

  interface Props {
    pack: string;
    /** The draft's root values. */
    values: Record<string, unknown>;
    /** What the saved template chain supplies (read-only). */
    inherited?: Record<string, unknown>;
    name?: TypeParam;
    lore?: TypeParam;
    texture?: TypeParam;
    model?: TypeParam;
    /** Appearance-category options that are not the texture or model (the model style). */
    options?: TypeParam[];
    visual?: ResourceSummary['visual'];
    render?: ResourceSummary['render'];
    material: string;
    /** Bumped when asset bytes changed without a new reference. */
    version: number;
    set: (field: string, value: unknown) => void;
    removeField: (field: string) => void;
    /** The studio's inherited / overrides line for one field. */
    provenance: Snippet<[string]>;
    missingTexture: (field: TypeParam) => string | null;
    fieldIssue: (fieldId: string) => DiagnosticEntry | undefined;
    onDrawTexture: (field: TypeParam, fresh: boolean) => void;
    onEditModel: (field: TypeParam) => void;
  }

  let {
    pack, values, inherited = {}, name, lore, texture, model, options = [], visual, render, material, version,
    set, removeField, provenance, missingTexture, fieldIssue, onDrawTexture, onEditModel,
  }: Props = $props();

  /** The value the item has: its own, else the template's. */
  const valueOf = (id: string): unknown => (Object.hasOwn(values, id) ? values[id] : inherited[id]);

  const textureRef = $derived(String(valueOf('texture') ?? ''));
  const modelRef = $derived(String(valueOf('model') ?? ''));
  const title = $derived(plainText(String(valueOf(name?.id ?? 'display_name') ?? '')).trim() || materialLabel(material) || 'Item');

  let picking = $state<'texture' | 'model' | null>(null);
  let menuOpen = $state(false);
  let menu: HTMLDivElement | undefined = $state();
  let menuButton: HTMLButtonElement | undefined = $state();
  let stageWidth = $state(0);
  /** One large render: as wide as its column, within a sensible range. */
  /** A whole multiple of the 128 px live render, so scaled pixels stay even (nearest-neighbour). */
  const size = $derived(128 * Math.max(1, Math.min(3, Math.floor(stageWidth / 128))));
  /** The slot buttons return focus here when a picker closes. */
  let slotButtons = $state<Record<string, HTMLButtonElement | undefined>>({});

  function close(): void {
    const was = picking;
    picking = null;
    queueMicrotask(() => (was ? slotButtons[was]?.focus() : undefined));
  }

  function pick(field: TypeParam | undefined, stored: string): void {
    if (!field) return;
    picking = null;
    if (stored) set(field.id, stored);
    else if (Object.hasOwn(values, field.id)) removeField(field.id);
    else set(field.id, '');
    queueMicrotask(() => slotButtons[field.uiHint === 'model' ? 'model' : 'texture']?.focus());
  }

  function shortRef(value: string): string {
    return value.includes(':') ? value.slice(value.indexOf(':') + 1) : value;
  }

  function onMenuKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || !menuOpen) return;
    event.preventDefault();
    menuOpen = false;
    menuButton?.focus();
  }

  function onWindowPointer(event: PointerEvent): void {
    if (menuOpen && menu && !menu.contains(event.target as Node) && !menuButton?.contains(event.target as Node)) {
      menuOpen = false;
    }
  }
</script>

<svelte:window onpointerdown={onWindowPointer} />

<article class="card appearance" aria-label="Appearance" data-appearance>
  <div class="stage">
    <div class="render" bind:clientWidth={stageWidth}>
      <div class="canvas">
        <ResourceThumbnail {visual} {render} {material} label={title} {size} version={version + workspace.visualVersion} />
      </div>
      {#if options.length > 0}
        <div class="menu-anchor" onkeydown={onMenuKeydown} role="presentation">
          <button type="button" class="menu-button" bind:this={menuButton} aria-label="More appearance options"
            aria-haspopup="true" aria-expanded={menuOpen} onclick={() => (menuOpen = !menuOpen)}>
            <Icon name="ellipsis" size={16} />
          </button>
          {#if menuOpen}
            <div class="menu" bind:this={menu} role="group" aria-label="Appearance options">
              {#each options as option (option.id)}
                <div class="option">
                  <Field field={option} value={values[option.id]} {pack} onChange={(next) => set(option.id, next)} />
                  {#if Object.hasOwn(values, option.id)}
                    <button type="button" class="link" aria-label={`Remove ${option.label || option.id}`}
                      onclick={() => removeField(option.id)}>Remove</button>
                  {/if}
                  {@render provenance(option.id)}
                </div>
              {/each}
            </div>
          {/if}
        </div>
      {/if}
      <div class="slots">
        {#if texture}
          <div class="slot" data-field={texture.id}>
            <button type="button" class="slot-button" data-field-control bind:this={slotButtons.texture}
              aria-haspopup="dialog" title={textureRef || undefined}
              aria-label={`${texture.label || 'Texture'}: ${textureRef ? shortRef(textureRef) : 'none'}. Change`}
              onclick={() => (picking = 'texture')}>
              <Icon name="image" size={16} />
              <span class="slot-label">{texture.label || 'Texture'}</span>
              <span class="slot-value" class:empty={!textureRef}>{textureRef ? shortRef(textureRef) : 'None'}</span>
            </button>
            {@render provenance(texture.id)}
          </div>
        {/if}
        {#if model}
          <div class="slot" data-field={model.id}>
            <button type="button" class="slot-button" data-field-control bind:this={slotButtons.model}
              aria-haspopup="dialog" title={modelRef || undefined}
              aria-label={`${model.label || '3D model'}: ${modelRef ? shortRef(modelRef) : 'none'}. Change`}
              onclick={() => (picking = 'model')}>
              <Icon name="box" size={16} />
              <span class="slot-label">{model.label || '3D model'}</span>
              <span class="slot-value" class:empty={!modelRef}>{modelRef ? shortRef(modelRef) : 'None'}</span>
            </button>
            {@render provenance(model.id)}
          </div>
        {/if}
      </div>
    </div>

    <div class="tooltip-column">
      <div class="mc-tooltip tooltip" aria-label="Item tooltip">
        {#if name}
          <div class="tooltip-field" data-field={name.id}>
            <RichTextEditor id={`appearance-${name.id}`} label={name.label || 'Display name'} value={valueOf(name.id)}
              placeholder={materialLabel(material) || name.example || ''} bare onChange={(next) => set(name.id, next)} />
          </div>
        {/if}
        {#if lore}
          <div class="tooltip-field lore" data-field={lore.id}>
            <RichTextEditor id={`appearance-${lore.id}`} label={lore.label || 'Lore'} value={valueOf(lore.id)} multiline
              placeholder="Add a description..." bare onChange={(next) => set(lore.id, next)} />
          </div>
        {/if}
      </div>
      {#if name}{@render provenance(name.id)}{/if}
      {#if lore}{@render provenance(lore.id)}{/if}
    </div>
  </div>

  {#each [texture, model] as slot, slotIndex (slotIndex)}
    {#if slot}
      {@const missing = missingTexture(slot)}
      {#if missing}
        {@const issue = fieldIssue(slot.id)}
        <div class="repair" role="group" aria-label={`Repair ${slot.label || slot.id}`}>
          <p>
            <strong>Missing texture.</strong> {missing} does not exist in this pack{issue ? ` (${issue.code})` : ''}, so
            the game shows a missing-texture pattern.
          </p>
          <button type="button" onclick={() => onDrawTexture(slot, false)}>Draw it now</button>
          <button type="button" onclick={() => (picking = 'texture')}>Choose another texture</button>
        </div>
      {:else}
        {@const issue = fieldIssue(slot.id)}
        {#if issue}
          <p class="field-issue" role="note">{issue.code}: {issue.message}{issue.hint ? ` — ${issue.hint}` : ''}</p>
        {/if}
      {/if}
    {/if}
  {/each}
</article>

{#if picking === 'texture' && texture}
  <AssetExplorer {pack} kind="texture" current={textureRef} {version}
    onPick={(stored) => pick(texture, stored)} onClose={close}
    onDraw={(fresh) => { picking = null; onDrawTexture(texture, fresh); }} />
{:else if picking === 'model' && model}
  <AssetExplorer {pack} kind="model" current={normalizedReference(modelRef, pack, 'model')} {version}
    onPick={(stored) => pick(model, stored)} onClose={close}
    onBlockbench={() => { picking = null; onEditModel(model); }} />
{/if}

<style>
  .appearance {
    display: grid;
    gap: var(--space-3);
    min-width: 0;
    padding: var(--space-4);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-lg);
    background: var(--bg-raised);
  }
  /* The render takes a third to half of the content; the tooltip it belongs to sits beside it. */
  .stage {
    display: grid;
    grid-template-columns: minmax(220px, 5fr) minmax(0, 4fr);
    gap: var(--space-5);
    align-items: start;
    min-width: 0;
  }
  .render {
    position: relative;
    display: grid;
    gap: var(--space-2);
    min-width: 0;
    max-width: 440px;
  }
  .canvas {
    display: grid;
    place-items: center;
    aspect-ratio: 1;
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background:
      conic-gradient(from 90deg, var(--checker-a) 25%, var(--checker-b) 0 50%, var(--checker-a) 0 75%, var(--checker-b) 0) 0 0 / 16px
      16px;
    overflow: hidden;
  }
  .canvas :global(.thumbnail) {
    border: none;
    background: transparent;
  }
  .menu-anchor {
    position: absolute;
    top: var(--space-2);
    right: var(--space-2);
    z-index: 3;
  }
  .menu-button {
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    padding: 0;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-raised);
    color: var(--fg);
    cursor: pointer;
  }
  .menu-button:hover,
  .menu-button[aria-expanded='true'] {
    background: var(--bg-hover);
  }
  .menu {
    position: absolute;
    top: calc(100% + var(--space-1));
    right: 0;
    display: grid;
    gap: var(--space-2);
    width: 260px;
    padding: var(--space-3);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-2);
  }
  .option {
    display: grid;
    gap: var(--space-1);
    justify-items: start;
    min-width: 0;
  }
  .option :global(.field) {
    width: 100%;
  }
  .slots {
    display: grid;
    gap: var(--space-2);
    min-width: 0;
  }
  .slot {
    display: grid;
    gap: var(--space-half);
    min-width: 0;
  }
  .slot-button {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    width: 100%;
    min-width: 0;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-input);
    color: inherit;
    font-size: var(--text-sm);
    text-align: left;
    cursor: pointer;
  }
  .slot-button:hover {
    background: var(--bg-hover);
  }
  .slot-button :global(svg) {
    flex: none;
    color: var(--muted);
  }
  .slot-label {
    flex: none;
    color: var(--muted);
  }
  .slot-value {
    flex: 1 1 0;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    text-align: right;
    font-family: var(--mono);
  }
  .slot-value.empty {
    color: var(--faint);
  }
  .tooltip-column {
    display: grid;
    gap: var(--space-1);
    min-width: 0;
    align-content: start;
  }
  .tooltip {
    display: grid;
    gap: var(--space-1);
    min-width: 0;
  }
  .tooltip-field {
    min-width: 0;
  }
  .lore {
    margin-top: var(--space-1);
  }
  .link {
    border: none;
    background: none;
    color: var(--muted);
    font-size: var(--text-xs);
    text-decoration: underline;
    cursor: pointer;
    padding: 0;
  }
  .field-issue {
    margin: 0;
    font-size: var(--text-xs);
    color: var(--warn);
  }
  .repair {
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--warn);
    border-radius: var(--radius);
    font-size: var(--text-sm);
  }
  .repair p {
    margin: 0 0 var(--space-2);
    overflow-wrap: anywhere;
  }
  .repair button {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-sm);
    cursor: pointer;
    margin-right: var(--space-2);
  }
  @media (max-width: 720px) {
    .stage {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
