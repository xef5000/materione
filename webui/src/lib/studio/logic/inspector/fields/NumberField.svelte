<script lang="ts">
  /**
   * A number parameter (logic.md section 7.3): a plain field that keeps the digits as typed until it commits, clamps
   * to the description's range, and hands any other text on as it is, so `${price}` typed here becomes a template
   * (the inspector then draws it as one) instead of being lost.
   */
  import { numberText, numberValue } from '../../../../logic/params';
  import type { ParamInfo } from '../../../../logic/vocabulary';

  interface Props {
    param: ParamInfo;
    value: unknown;
    id?: string;
    label: string;
    disabled?: boolean;
    invalid?: boolean;
    onchange: (value: unknown) => void;
  }

  let { param, value, id, label, disabled = false, invalid = false, onchange }: Props = $props();

  let text = $state('');
  let focused = $state(false);
  $effect(() => {
    const authored = value;
    if (!focused) text = numberText(authored);
  });

  function commit(): void {
    focused = false;
    const next = numberValue(text, param);
    if (next !== value && !(next === undefined && (value === undefined || value === ''))) onchange(next);
    text = numberText(value);
  }
</script>

<span class="number">
  <input
    {id}
    type="text"
    inputmode="decimal"
    aria-label={label}
    aria-invalid={invalid ? true : undefined}
    spellcheck="false"
    autocomplete="off"
    placeholder={param.default ?? param.example ?? ''}
    {disabled}
    value={text}
    oninput={(event) => (text = event.currentTarget.value)}
    onfocus={() => (focused = true)}
    onblur={commit}
    onkeydown={(event) => {
      if (event.key === 'Enter') event.currentTarget.blur();
      else if (event.key === 'Escape') {
        text = numberText(value);
        event.stopPropagation();
        event.currentTarget.blur();
      }
    }}
  />
  {#if param.min !== undefined && param.max !== undefined}
    <span class="range">{param.min} to {param.max}</span>
  {:else if param.min !== undefined}
    <span class="range">at least {param.min}</span>
  {/if}
</span>

<style>
  .number {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
  }
  input {
    box-sizing: border-box;
    width: 100%;
    max-width: 12em;
    height: 28px;
    padding: 0 var(--space-2);
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font: var(--text-sm) var(--mono);
  }
  input:focus {
    border-color: var(--accent);
    outline: none;
  }
  input[aria-invalid='true'] {
    border-color: var(--error);
  }
  .range {
    color: var(--faint);
    font-size: var(--text-xs);
    white-space: nowrap;
  }
</style>
