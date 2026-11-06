<script lang="ts">
  /**
   * A parameter with a fixed set of values (logic.md section 7.3): the self-description's allowed values, drawn as one
   * choice when there are few and as a select when there are many. An optional parameter can be put back to "not set",
   * which removes its key instead of writing a blank.
   */
  import { choicesOf } from '../../../../logic/params';
  import type { ParamInfo } from '../../../../logic/vocabulary';

  interface Props {
    param: ParamInfo;
    value: unknown;
    id?: string;
    label: string;
    disabled?: boolean;
    onchange: (value: unknown) => void;
  }

  let { param, value, id, label, disabled = false, onchange }: Props = $props();

  const choices = $derived(choicesOf(param));
  const text = $derived(value === undefined || value === null ? '' : String(value));
  const numeric = $derived(param.type === 'integer' || param.type === 'decimal');

  function pick(choice: string): void {
    onchange(choice === '' ? undefined : numeric ? Number(choice) : choice);
  }
</script>

{#if choices.length > 1 && choices.length <= 4}
  <div class="segments" role="radiogroup" aria-label={label} {id}>
    {#if !param.required}
      <button type="button" role="radio" aria-checked={text === ''} class:off={text !== ''} {disabled} title="Not set (the default applies)" onclick={() => pick('')}>
        {param.default ? `default: ${param.default}` : 'not set'}
      </button>
    {/if}
    {#each choices as choice (choice.value)}
      <button
        type="button"
        role="radio"
        aria-checked={text === choice.value}
        class:off={text !== choice.value}
        {disabled}
        title={choice.description || undefined}
        onclick={() => pick(choice.value)}
      >{choice.label}</button>
    {/each}
  </div>
{:else}
  <select {id} aria-label={label} {disabled} value={text} onchange={(event) => pick(event.currentTarget.value)}>
    {#if !param.required || text === ''}<option value="">{param.default ? `default: ${param.default}` : 'not set'}</option>{/if}
    {#each choices as choice (choice.value)}
      <option value={choice.value} title={choice.description || undefined}>{choice.label}</option>
    {/each}
  </select>
{/if}

<style>
  select {
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    height: 28px;
    padding: 0 var(--space-2);
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font: var(--text-sm) var(--font-sans);
  }
  select:focus {
    border-color: var(--accent);
    outline: none;
  }
  .segments {
    display: inline-flex;
    flex-wrap: wrap;
    gap: var(--space-1);
    min-width: 0;
  }
  button {
    padding: var(--space-half) var(--space-3);
    background: none;
    border: 1px solid var(--control-line);
    border-radius: var(--radius-sm);
    color: var(--muted);
    font-size: var(--text-sm);
    cursor: pointer;
    white-space: nowrap;
  }
  button:hover:not(:disabled) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  button[aria-checked='true'] {
    background: var(--accent-low);
    border-color: var(--accent);
    color: var(--fg);
  }
  button.off {
    border-color: var(--border-strong);
  }
</style>
