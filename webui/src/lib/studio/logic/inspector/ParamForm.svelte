<script lang="ts">
  /**
   * The parameters of one action, condition or trigger (logic.md section 7.3, 31), drawn from its self-description: one
   * field per parameter, required ones first, advanced ones folded away. The form edits a payload as the compiler reads
   * it (a scalar fills the shorthand parameter, a mapping is its own parameters), and every change writes the whole new
   * payload back, so the form never keeps a copy. Keys the description does not list stay in the file and are shown
   * read-only below, so nothing is dropped.
   */
  import type { Attributed } from '../../../logic/diagnose';
  import { jsonLine, readPayload, unknownKeys, writeParam } from '../../../logic/params';
  import type { NodeFlag } from '../../../logic/verdicts';
  import type { ParamInfo } from '../../../logic/vocabulary';
  import ParamField from './ParamField.svelte';

  interface Props {
    params: readonly ParamInfo[];
    payload: unknown;
    shorthand?: string;
    /** How the payload is stored, when it is not a plain action payload (a rule keeps its filters as sibling keys). */
    flags?: Attributed<NodeFlag>;
    disabled?: boolean;
    suggestionsFor?: (param: ParamInfo) => string[] | undefined;
    /** The payload after a change; the form calls this once per final edit. */
    write?: (next: unknown) => void;
    draft?: (next: unknown) => void;
    /** For keys that live beside others instead of in a payload of their own (a rule's filters): one key, one value. */
    writeKey?: (id: string, next: unknown) => void;
    draftKey?: (id: string, next: unknown) => void;
  }

  let { params, payload, shorthand, flags, disabled = false, suggestionsFor, write, draft, writeKey, draftKey }: Props = $props();

  function commit(id: string, next: unknown): void {
    if (writeKey) writeKey(id, next);
    else write?.(writeParam(payload, shorthand, id, next));
  }

  function typing(id: string, next: unknown): void {
    if (draftKey) draftKey(id, next);
    else draft?.(writeParam(payload, shorthand, id, next));
  }

  const values = $derived(readPayload(payload, shorthand));
  const main = $derived(params.filter((param) => !param.advanced));
  const advanced = $derived(params.filter((param) => param.advanced));
  const stray = $derived(unknownKeys(payload, params));
</script>

{#each main as param (param.id)}
  <ParamField
    {param}
    value={values[param.id]}
    {disabled}
    flags={flags?.fields.get(param.id) ?? []}
    suggestions={suggestionsFor?.(param)}
    onchange={(next) => commit(param.id, next)}
    ondraft={draft || draftKey ? (next) => typing(param.id, next) : undefined}
  />
{/each}
{#if advanced.length > 0}
  <details class="advanced">
    <summary>Advanced ({advanced.length})</summary>
    {#each advanced as param (param.id)}
      <ParamField
        {param}
        value={values[param.id]}
        {disabled}
        flags={flags?.fields.get(param.id) ?? []}
        onchange={(next) => commit(param.id, next)}
      />
    {/each}
  </details>
{/if}
{#if stray.length > 0}
  <section class="stray" aria-label="Other keys" data-testid="stray-keys">
    <h5>Other keys</h5>
    <p class="hint">This server does not describe these, so they are kept exactly as written.</p>
    {#each stray as key (key)}
      <div class="key"><code>{key}</code><code class="val">{jsonLine((values as Record<string, unknown>)[key])}</code></div>
    {/each}
  </section>
{/if}

<style>
  .advanced {
    margin-bottom: var(--space-3);
  }
  summary {
    margin-bottom: var(--space-2);
    color: var(--muted);
    font-size: var(--text-xs);
    cursor: pointer;
  }
  .stray {
    margin-top: var(--space-2);
    padding: var(--space-2);
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-sm);
  }
  h5 {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .hint {
    margin: var(--space-half) 0 var(--space-2);
    color: var(--faint);
    font-size: var(--text-xs);
  }
  .key {
    display: flex;
    gap: var(--space-2);
    font-size: var(--text-xs);
    min-width: 0;
  }
  .val {
    min-width: 0;
    overflow: hidden;
    color: var(--muted);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
