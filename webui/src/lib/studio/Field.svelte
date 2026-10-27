<script lang="ts">
  /**
   * One labelled control, chosen by the registry's widget. The studio never
   * decides what a field means — the type's self-description does (D5).
   */
  import AssetPicker from '../components/AssetPicker.svelte';
  import RichTextEditor from '../components/RichTextEditor.svelte';
  import MaterialPicker from '../components/MaterialPicker.svelte';
  import type { TypeParam } from '../api';

  interface Props {
    field: TypeParam;
    value: unknown;
    pack: string;
    onChange: (value: unknown) => void;
    /** Rendered next to a texture/model slot: "Edit", "Edit in Blockbench". */
    action?: { label: string; run: () => void };
  }

  let { field, value, pack, onChange, action }: Props = $props();
  const controlId = $props.id();

  const widget = $derived(field.uiHint ?? 'text');
  const text = $derived(value === undefined || value === null ? '' : String(value));
  const lines = $derived(Array.isArray(value) ? value.map((item) => String(item)).join('\n') : '');

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
  <label class="label" for={controlId}>
    {field.label || field.id}
    {#if field.required}<em title="required">*</em>{/if}
  </label>

  {#if widget === 'material'}
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
  {:else if field.allowedValues && field.allowedValues.length > 1 && field.allowedValues.length <= 4}
    <!-- Few enum values read as one choice, not a dropdown (module cards' smart input). -->
    <div class="segments" role="radiogroup" aria-label={field.label || field.id}>
      {#if !field.required}
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
      {#each field.allowedValues as option (option)}
        <button
          type="button"
          role="radio"
          aria-checked={text === option}
          class:off={text !== option}
          onclick={() => onChange(option)}
        >{option.replaceAll('_', ' ')}</button>
      {/each}
    </div>
  {:else if field.allowedValues && field.allowedValues.length > 0}
    <select id={controlId} value={text} onchange={(event) => onChange(event.currentTarget.value)}>
      {#if !field.required}<option value=""></option>{/if}
      {#each field.allowedValues as option (option)}
        <option value={option}>{option.replaceAll('_', ' ')}</option>
      {/each}
    </select>
  {:else if field.type === 'boolean'}
    <input
      id={controlId}
      type="checkbox"
      checked={value === true || text === 'true'}
      onchange={(event) => onChange(event.currentTarget.checked)}
    />
  {:else if field.type === 'integer' || field.type === 'decimal'}
    <input
      id={controlId}
      type="number"
      step={field.type === 'decimal' ? 'any' : '1'}
      value={text}
      placeholder={field.example ?? ''}
      onblur={(event) => onChange(Number(event.currentTarget.value))}
      onkeydown={(event) => {
        if (event.key === 'Enter') event.currentTarget.blur();
      }}
    />
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

  {#if field.description}
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
