<script lang="ts">
  /**
   * The input method of a `prompt` step (logic.md section 29, 31): the methods the server registers, read from the
   * vocabulary. A method the server cannot use here (the sign editor on a build without the 26.x API) is listed greyed
   * with the server's reason and cannot be picked; one the file already names stays selected and carries a warning, so
   * an existing step is never silently rewritten.
   */
  import { useInspector } from '../context';
  import type { ParamInfo } from '../../../../logic/vocabulary';
  import EnumField from './EnumField.svelte';

  interface Props {
    param: ParamInfo;
    value: unknown;
    id?: string;
    label: string;
    disabled?: boolean;
    onchange: (value: unknown) => void;
  }

  let { param, value, id, label, disabled = false, onchange }: Props = $props();

  const inspector = useInspector();
  const methods = $derived([...(inspector.canvas.vocabulary?.inputMethods.values() ?? [])]);
  const current = $derived(typeof value === 'string' ? value : (param.default ?? ''));
  const unavailable = $derived(methods.filter((method) => !method.available));
</script>

{#if methods.length === 0}
  <EnumField {param} {value} {id} {label} {disabled} {onchange} />
{:else}
  <div class="methods" role="radiogroup" aria-label={label} {id}>
    {#each methods as method (method.id)}
      <button
        type="button"
        role="radio"
        aria-checked={current === method.id}
        aria-disabled={!method.available}
        class:off={current !== method.id}
        class:greyed={!method.available}
        {disabled}
        title={method.available ? method.description : method.reason}
        data-method={method.id}
        onclick={() => {
          if (method.available) onchange(method.id === param.default ? undefined : method.id);
        }}
      >{method.displayName}</button>
    {/each}
  </div>
  {#each unavailable as method (method.id)}
    <p class="why" data-method-reason={method.id}><strong>{method.displayName}</strong> is not available here: {method.reason}</p>
  {/each}
{/if}

<style>
  .methods {
    display: inline-flex;
    flex-wrap: wrap;
    gap: var(--space-1);
  }
  button {
    padding: var(--space-half) var(--space-3);
    background: none;
    border: 1px solid var(--control-line);
    border-radius: var(--radius-sm);
    color: var(--muted);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  button:hover:not(.greyed) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  button[aria-checked='true'] {
    background: var(--accent-low);
    border-color: var(--accent);
    color: var(--fg);
  }
  button.greyed {
    opacity: 0.5;
    cursor: not-allowed;
    border-style: dashed;
  }
  .why {
    margin: var(--space-1) 0 0;
    color: var(--warn);
    font-size: var(--text-xs);
  }
</style>
