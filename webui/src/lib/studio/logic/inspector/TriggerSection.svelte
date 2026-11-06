<script lang="ts">
  /**
   * The inspector of a rule (logic.md section 2.1, 7.2, 31): the trigger it answers, that trigger's filters (the layer
   * a `gui.click` is about, the click kind), the rule options every rule may carry (`id`, `cooldown`, ...) and the
   * rule's own condition (`if:`). Filters and options live beside `on:` in the file, so each is written to its own key.
   */
  import { attribute } from '../../../logic/diagnose';
  import { textValues } from '../../../logic/params';
  import type { GraphNode } from '../../../logic/project';
  import { isRecord, type Json } from '../../../logic/tree';
  import type { ParamInfo } from '../../../logic/vocabulary';
  import ConditionEditor from './ConditionEditor.svelte';
  import { useInspector } from './context';
  import ParamForm from './ParamForm.svelte';
  import { planStepKey } from '../../../logic/edit-plan';

  interface Props {
    node: GraphNode;
    disabled?: boolean;
  }

  let { node, disabled = false }: Props = $props();

  const inspector = useInspector();
  const canvas = $derived(inspector.canvas);
  const vocabulary = $derived(canvas.vocabulary);

  const selector = $derived(node.op);
  const base = $derived(selector.split(':')[0] ?? '');
  const detail = $derived(selector.includes(':') ? selector.slice(selector.indexOf(':') + 1) : '');
  const trigger = $derived(vocabulary?.triggers.get(selector) ?? vocabulary?.triggers.get(base));
  const triggers = $derived.by(() => {
    const all = [...(vocabulary?.triggers.values() ?? [])];
    const hosted = all.filter((entry) => entry.firedBy.includes(canvas.host));
    return hosted.length > 0 ? hosted : all;
  });
  const params = $derived<ParamInfo[]>([...(trigger?.filters ?? []), ...(vocabulary?.ruleOptions ?? [])]);

  /** The rule's keys that are filters or options: everything but the trigger, the condition, the steps and the position. */
  const keys = $derived.by((): Json => {
    const own = node.authored.own ?? {};
    const out: Json = {};
    for (const [key, value] of Object.entries(own)) if (!['on', 'if', 'do', 'node'].includes(key)) out[key] = value;
    return out;
  });
  const flags = $derived(attribute(node.id, inspector.flagsFor(node), ['on', 'if', ...params.map((param) => param.id)], { ...textValues(keys), if: JSON.stringify(isRecord(node.authored.own) ? node.authored.own.if ?? '' : '') }));
  const layers = $derived(canvas.gui.doc.layers.map((layer) => layer.id));

  function select(id: string, next: string): void {
    inspector.commit(planStepKey(canvas.graph, node.id, 'on', next === '' ? id : `${id}:${next}`, canvas.nameOf));
  }

  function draftKey(id: string, next: unknown): void {
    inspector.draft(node.id, planStepKey(canvas.graph, node.id, id, next === '' ? undefined : next, canvas.nameOf));
  }
</script>

<section aria-label="Trigger">
  <div class="field">
    <label for="trigger-type">Trigger</label>
    <select id="trigger-type" {disabled} value={trigger?.id ?? base} onchange={(event) => select(event.currentTarget.value, '')}>
      {#if !trigger}<option value={base}>{base || 'No trigger'} (unknown)</option>{/if}
      {#each triggers as entry (entry.id)}<option value={entry.id}>{entry.displayName} ({entry.id})</option>{/each}
    </select>
    {#if trigger?.description}<p class="hint">{trigger.description}</p>{/if}
  </div>
  {#if trigger && trigger.details.length > 0}
    <div class="field">
      <label for="trigger-detail">Only when</label>
      <select id="trigger-detail" {disabled} value={detail} onchange={(event) => select(trigger.id, event.currentTarget.value)}>
        <option value="">any</option>
        {#each trigger.details as option (option)}<option value={option}>{option}</option>{/each}
      </select>
    </div>
  {/if}
  {#each flags.fields.get('on') ?? [] as flag, index (index)}<p class="flag {flag.severity}" data-verdict>{flag.message}</p>{/each}

  {#if trigger && trigger.filters.length > 0}
    <h4>Filters</h4>
  {/if}
  <ParamForm
    {params}
    payload={keys}
    {disabled}
    {flags}
    suggestionsFor={(param) => (param.id === 'layer' ? layers : undefined)}
    writeKey={(id, next) => inspector.setKey(node, id, next)}
    {draftKey}
  />

  <h4>Only if</h4>
  <ConditionEditor
    condition={isRecord(node.authored.own) ? node.authored.own.if : undefined}
    optional
    {disabled}
    flags={flags.fields.get('if') ?? []}
    onchange={(next) => inspector.setCondition(node, next)}
  />
</section>

<style>
  .field {
    display: grid;
    gap: var(--space-1);
    margin-bottom: var(--space-3);
  }
  label {
    color: var(--muted);
    font-size: var(--text-xs);
  }
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
  h4 {
    margin: var(--space-3) 0 var(--space-2);
    color: var(--muted);
    font-size: var(--text-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .hint {
    margin: 0;
    color: var(--faint);
    font-size: var(--text-xs);
  }
  .flag {
    margin: 0 0 var(--space-2);
    font-size: var(--text-xs);
  }
  .flag.error {
    color: var(--error);
  }
  .flag.warn {
    color: var(--warn);
  }
</style>
