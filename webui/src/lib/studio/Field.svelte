<script lang="ts">
  /**
   * One labelled control, chosen by the registry's widget. The studio never
   * decides what a field means — the type's self-description does (D5).
   */
  import AssetPicker from '../components/AssetPicker.svelte';
  import RichTextEditor from '../components/RichTextEditor.svelte';
  import MaterialPicker from '../components/MaterialPicker.svelte';
  import ChipsEditor from './ChipsEditor.svelte';
  import FrameStrip from './FrameStrip.svelte';
  import ItemChips from './ItemChips.svelte';
  import ItemPicker from './ItemPicker.svelte';
  import RecipeGrid from './RecipeGrid.svelte';
  import SpatialEditor from './SpatialEditor.svelte';
  import StructuredList from './StructuredList.svelte';
  import type { TypeParam } from '../api';

  interface Props {
    field: TypeParam;
    value: unknown;
    pack: string;
    onChange: (value: unknown) => void;
    /** Rendered next to a texture/model slot: "Edit", "Edit in Blockbench". */
    action?: { label: string; run: () => void };
    /** A module card already names the field: keep the label for assistive tech only. */
    hideLabel?: boolean;
    /**
     * The resource's other values and a setter, for composite widgets that write
     * more than one key (the crafting grid writes `shape` and `ingredients`).
     */
    siblings?: { values: Record<string, unknown>; set: (field: string, value: unknown) => void };
  }

  let { field, value, pack, onChange, action, hideLabel = false, siblings }: Props = $props();
  const controlId = $props.id();

  const widget = $derived(field.uiHint ?? 'text');
  const text = $derived(value === undefined || value === null ? '' : String(value));
  const lines = $derived(Array.isArray(value) ? value.map((item) => String(item)).join('\n') : '');

  /** Composite widgets carry their own heading (the module card names them). */
  const composite = $derived(widget === 'structured-list' || widget === 'spatial-list');
  /** Labelled choices: the schema's options, else the raw allowed values. */
  const choices = $derived(
    field.options?.length
      ? field.options
      : (field.allowedValues ?? []).map((value) => ({ value, label: value.replaceAll('_', ' '), description: '' })),
  );
  const numeric = $derived(field.type === 'integer' || field.type === 'decimal');
  const chosen = $derived(choices.find((option) => option.value === text));

  /** A choice's stored value in the field's own type (rotation 90 is a number, not "90"). */
  function coerce(choice: string): unknown {
    return numeric && choice !== '' ? Number(choice) : choice;
  }

  function commitNumber(raw: string) {
    let next = Number(raw);
    if (field.min !== undefined) next = Math.max(field.min, next);
    if (field.max !== undefined) next = Math.min(field.max, next);
    onChange(next);
  }

  function commitLines(raw: string) {
    onChange(
      raw
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean),
    );
  }
</script>

<div class="field" data-field={field.id}>
  {#if !composite}
    <label class="label" class:sr-only={hideLabel && field.type !== 'boolean'} for={controlId}>
      {field.label || field.id}
      {#if field.required}<em title="required">*</em>{/if}
    </label>
  {/if}

  {#if widget === 'structured-list'}
    <StructuredList {field} {value} {onChange} compact />
  {:else if widget === 'spatial-list'}
    <SpatialEditor {field} {value} {onChange} />
  {:else if widget === 'item'}
    <ItemPicker id={controlId} {pack} label={field.label || field.id} value={text}
      packOnly={field.referenceKind === 'pack-item'}
      placeholder={field.example ? `e.g. ${field.example}` : 'Choose an item'}
      required={field.required} onPick={(next) => onChange(next)} />
  {:else if widget === 'item-list'}
    <ItemChips {value} {pack} label={field.label || field.id} {onChange} />
  {:else if widget === 'recipe-ingredients'}
    <ItemChips {value} {pack} label={field.label || field.id} {onChange} />
  {:else if widget === 'recipe-grid'}
    {#if siblings}
      <RecipeGrid shape={siblings.values.shape} ingredients={siblings.values.ingredients} {pack}
        onChange={(next) => {
          siblings.set('shape', next.shape);
          siblings.set('ingredients', next.ingredients);
        }} />
    {/if}
  {:else if widget === 'chips'}
    <ChipsEditor {value} label={field.label || field.id} placeholder={field.example ? `e.g. ${field.example}` : undefined}
      {onChange} />
  {:else if widget === 'frames'}
    <FrameStrip {value} {pack} label={field.label || field.id} {onChange} />
  {:else if widget === 'presets'}
    <div class="presets">
      <div class="segments" role="radiogroup" aria-label={field.label || field.id}>
        {#each choices as option (option.value)}
          <button type="button" role="radio" aria-checked={text === option.value}
            class:off={text !== option.value} title={option.description || undefined}
            onclick={() => onChange(coerce(option.value))}>{option.label}</button>
        {/each}
      </div>
      <span class="numeric">
        <input id={controlId} type="number" step="1" min={field.min} max={field.max} value={text}
          aria-label={`${field.label || field.id} (custom)`} placeholder={field.example ?? ''}
          onblur={(event) => commitNumber(event.currentTarget.value)}
          onkeydown={(event) => { if (event.key === 'Enter') event.currentTarget.blur(); }} />
        {#if field.unit}<span class="unit">{field.unit}</span>{/if}
      </span>
    </div>
  {:else if widget === 'material'}
    <MaterialPicker id={controlId} label={field.label || field.id} value={text}
      choices={field.allowedValues} required={field.required} onChange={(next) => onChange(next)} />
  {:else if widget === 'formatted-text' || widget === 'formatted-lines'}
    <RichTextEditor id={controlId} label={field.label || field.id} {value} placeholder={field.example ?? ''}
      multiline={widget === 'formatted-lines'} {onChange} />
  {:else if widget === 'texture' || widget === 'model'}
    <span class="slot">
      <AssetPicker id={controlId} {pack} hint={widget} value={text} onChange={(next) => onChange(next)} />
      {#if action}
        <button type="button" class="action" onclick={action.run}>{action.label}</button>
      {/if}
    </span>
  {:else if choices.length > 1 && (choices.length <= 4 || (field.options?.length && choices.length <= 8))}
    <!-- Few choices (or a few labelled ones) read as one choice, not a dropdown. -->
    <div class="segments" role="radiogroup" aria-label={field.label || field.id}>
      {#if !field.required && !numeric && !field.module}
        <button
          type="button"
          role="radio"
          aria-checked={text === ''}
          aria-label="Not set"
          title="Not set"
          class:off={text !== ''}
          onclick={() => onChange('')}
        >—</button>
      {/if}
      {#each choices as option (option.value)}
        <button
          type="button"
          role="radio"
          aria-checked={text === option.value}
          class:off={text !== option.value}
          title={option.description || undefined}
          onclick={() => onChange(coerce(option.value))}
        >{option.label}</button>
      {/each}
    </div>
    {#if chosen?.description}<small>{chosen.description}</small>{/if}
  {:else if choices.length > 0}
    <select id={controlId} value={text} onchange={(event) => onChange(coerce(event.currentTarget.value))}>
      {#if !field.required}<option value=""></option>{/if}
      {#each choices as option (option.value)}
        <option value={option.value}>{option.label}</option>
      {/each}
    </select>
    {#if chosen?.description}<small>{chosen.description}</small>{/if}
  {:else if field.type === 'boolean'}
    <input
      id={controlId}
      type="checkbox"
      checked={value === true || text === 'true'}
      onchange={(event) => onChange(event.currentTarget.checked)}
    />
  {:else if field.type === 'integer' || field.type === 'decimal'}
    <span class="numeric">
      <input
        id={controlId}
        type="number"
        step={field.type === 'decimal' ? 'any' : '1'}
        min={field.min}
        max={field.max}
        value={text}
        placeholder={field.example ?? ''}
        onblur={(event) => commitNumber(event.currentTarget.value)}
        onkeydown={(event) => {
          if (event.key === 'Enter') event.currentTarget.blur();
        }}
      />
      {#if field.unit}<span class="unit">{field.unit}</span>{/if}
      {#if field.min !== undefined && field.max !== undefined}
        <span class="range">{field.min}–{field.max}</span>
      {/if}
    </span>
  {:else if field.type === 'list'}
    <textarea
      id={controlId}
      rows="3"
      spellcheck="false"
      value={lines}
      placeholder={field.example ? `${field.example}` : 'one per line'}
      onblur={(event) => commitLines(event.currentTarget.value)}
    ></textarea>
  {:else}
    <input
      id={controlId}
      type="text"
      value={text}
      placeholder={field.example ?? ''}
      spellcheck="false"
      onblur={(event) => onChange(event.currentTarget.value)}
      onkeydown={(event) => {
        if (event.key === 'Enter') event.currentTarget.blur();
      }}
    />
  {/if}

  {#if field.description && !chosen?.description}
    <small>{field.description}</small>
  {/if}
</div>

<style>
  .field {
    display: grid;
    gap: var(--space-1);
    margin-bottom: var(--space-3);
    min-width: 0;
  }
  .label {
    font-size: var(--text-sm);
    color: var(--muted);
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .label em {
    color: var(--accent);
    font-style: normal;
  }
  .slot {
    display: flex;
    gap: var(--space-2);
    align-items: center;
    min-width: 0;
  }
  .presets {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-3);
    min-width: 0;
  }
  .numeric {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
  }
  .numeric input {
    max-width: 10em;
  }
  .unit,
  .range {
    color: var(--muted);
    font-size: var(--text-sm);
    white-space: nowrap;
  }
  .range {
    color: var(--faint);
    font-size: var(--text-xs);
  }
  .slot :global(.picker) {
    flex: 1;
    min-width: 0;
  }
  input[type='text'],
  input[type='number'],
  select,
  textarea {
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-base);
    font-family: inherit;
    min-width: 0;
    width: 100%;
    box-sizing: border-box;
  }
  input[type='checkbox'] {
    justify-self: start;
    width: 16px;
    height: 16px;
  }
  .segments {
    display: inline-flex;
    gap: var(--space-1);
    justify-self: start;
    min-width: 0;
    flex-wrap: wrap;
  }
  .segments button {
    border: 1px solid var(--control-line);
    background: none;
    color: var(--muted);
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-3);
    font-size: var(--text-sm);
    cursor: pointer;
    white-space: nowrap;
  }
  .segments button:hover {
    background: var(--bg-hover);
    color: inherit;
  }
  .segments button[aria-checked='true'] {
    background: var(--accent-low);
    border-color: var(--accent);
    color: var(--fg);
  }
  .segments button.off {
    border-color: var(--border-strong);
  }
  textarea {
    resize: vertical;
  }
  .action {
    background: none;
    border: 1px solid var(--border-strong);
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-sm);
    cursor: pointer;
    white-space: nowrap;
  }
  .action:hover {
    background: var(--bg-hover);
  }
  small {
    color: var(--muted);
    font-size: var(--text-xs);
    line-height: 1.4;
  }
</style>
