<script lang="ts">
  /**
   * One labelled parameter (logic.md section 7.3, 31): its name from the self-description, a star when it is required,
   * its description as the hover hint, the control the value calls for, and the diagnostics the compiler reports
   * against it. A number, a toggle or a fixed-choice parameter can be switched to an expression (`${...}`), and a value
   * the control cannot hold is shown as the template it is, with a way back to the typed control.
   */
  import Icon from '../../../components/Icon.svelte';
  import { baseKind, isBlank, kindFor } from '../../../logic/params';
  import type { NodeFlag } from '../../../logic/verdicts';
  import type { ParamInfo } from '../../../logic/vocabulary';
  import FieldBody from './FieldBody.svelte';

  interface Props {
    param: ParamInfo;
    value: unknown;
    disabled?: boolean;
    flags?: NodeFlag[];
    suggestions?: string[];
    /** A short note under the control (what the value is for). */
    onchange: (value: unknown) => void;
    ondraft?: (value: unknown) => void;
  }

  let { param, value, disabled = false, flags = [], suggestions = [], onchange, ondraft }: Props = $props();

  const uid = $props.id();
  const id = `${uid}-control`;
  const label = $derived(param.label || param.id);
  const typedKind = $derived(baseKind(param));
  const shown = $derived(kindFor(param, value));
  let expression = $state(false);
  const switchable = $derived(typedKind === 'number' || typedKind === 'boolean' || typedKind === 'enum');
  const asExpression = $derived(switchable && (expression || shown.kind === 'template'));
  const kind = $derived(asExpression ? 'template' : shown.kind);
  const unset = $derived(isBlank(value));
  const hasLabelInside = $derived(kind === 'color');
</script>

<div class="param" data-param={param.id} class:flagged={flags.length > 0} title={param.description || undefined}>
  {#if !hasLabelInside}
    <div class="label">
      <label for={id}>{label}{#if param.required}<em title="required">*</em>{/if}</label>
      {#if switchable}
        <button
          type="button"
          class="expr"
          {disabled}
          aria-pressed={asExpression}
          title={asExpression ? 'Use a plain value' : 'Use an expression'}
          aria-label={asExpression ? `Use a plain value for ${label}` : `Use an expression for ${label}`}
          onclick={() => {
            if (asExpression) {
              expression = false;
              if (shown.kind === 'template') onchange(undefined);
            } else expression = true;
          }}
        >{'${}'}</button>
      {/if}
      {#if !param.required && !unset}
        <button type="button" class="reset" {disabled} title="Remove {label} (back to its default)" aria-label="Remove {label}" onclick={() => onchange(undefined)}>
          <Icon name="x" size={11} />
        </button>
      {/if}
    </div>
  {/if}
  <div class="body">
    <FieldBody {param} {kind} element={shown.element} {value} {id} {label} {disabled} {flags} {suggestions} {onchange} {ondraft} />
  </div>
  {#if kind !== 'template' && kind !== 'variable' && kind !== 'item' && kind !== 'sound' && kind !== 'list'}
    {#each flags as flag, index (index)}
      <p class="flag {flag.severity}" data-verdict><Icon name={flag.severity === 'info' ? 'info' : 'triangle-alert'} size={12} /> {flag.message}</p>
    {/each}
  {/if}
</div>

<style>
  .param {
    display: grid;
    gap: var(--space-1);
    min-width: 0;
    margin-bottom: var(--space-3);
  }
  .label {
    display: flex;
    align-items: center;
    gap: var(--space-1);
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .label em {
    color: var(--accent);
    font-style: normal;
  }
  .expr,
  .reset {
    margin-left: auto;
    padding: 0 var(--space-1);
    background: none;
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    color: var(--faint);
    font: var(--text-xs) var(--mono);
    cursor: pointer;
  }
  .reset {
    margin-left: 0;
    display: inline-grid;
    place-items: center;
    padding: 1px;
  }
  .expr + .reset {
    margin-left: 0;
  }
  .expr:hover:not(:disabled),
  .reset:hover:not(:disabled) {
    border-color: var(--border-strong);
    color: var(--fg);
  }
  .expr[aria-pressed='true'] {
    color: var(--accent);
    border-color: var(--accent);
  }
  .body {
    min-width: 0;
  }
  .flag {
    display: flex;
    gap: var(--space-1);
    margin: 0;
    font-size: var(--text-xs);
  }
  .flag.error {
    color: var(--error);
  }
  .flag.warn {
    color: var(--warn);
  }
  .flag.info {
    color: var(--muted);
  }
</style>
