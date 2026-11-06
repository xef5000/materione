<script lang="ts">
  /**
   * The inspector of the engine's own steps (logic.md section 2.2, 31): `if` and each case of a `choose` get the
   * condition stack, `require` explains its guard, `wait` takes its ticks, `stop` its two switches and `call` the flow
   * it runs and the arguments it passes. The keys of these steps are part of the grammar, so they are described in
   * `control-params.ts` with the same shape every registry entry uses.
   */
  import Icon from '../../../components/Icon.svelte';
  import { attribute } from '../../../logic/diagnose';
  import { callParams, STOP_PARAMS, WAIT_PARAMS } from '../../../logic/control-params';
  import { planCondition, planStepValue } from '../../../logic/edit-plan';
  import type { GraphNode } from '../../../logic/project';
  import { isRecord, nodeAt } from '../../../logic/tree';
  import ConditionEditor from './ConditionEditor.svelte';
  import { useInspector } from './context';
  import ParamForm from './ParamForm.svelte';

  interface Props {
    node: GraphNode;
    disabled?: boolean;
  }

  let { node, disabled = false }: Props = $props();

  const inspector = useInspector();
  const canvas = $derived(inspector.canvas);
  const flows = $derived(Object.keys(canvas.block.flows ?? {}));
  const cases = $derived.by(() => {
    const raw = nodeAt(canvas.block, node.id);
    return isRecord(raw) && Array.isArray(raw.choose) ? raw.choose : [];
  });

  const flags = $derived(
    attribute(node.id, inspector.flagsFor(node), ['if', 'wait', 'call', 'stop'], { if: JSON.stringify(node.kind === 'if' ? node.value : cases) }),
  );
  function draft(payload: unknown): void {
    inspector.draft(node.id, planStepValue(canvas.graph, node.id, payload, canvas.nameOf));
  }

  function draftCondition(next: unknown, index?: number): void {
    inspector.draft(node.id, planCondition(canvas.graph, canvas.block, node.id, next, canvas.nameOf, index));
  }
</script>

<section aria-label="Step">
  {#each flags.node as flag, index (index)}
    <p class="flag {flag.severity}" data-verdict><Icon name={flag.severity === 'info' ? 'info' : 'triangle-alert'} size={12} /> {flag.message}</p>
  {/each}

  {#if node.kind === 'if'}
    <p class="about">Runs the <strong>then</strong> steps when the condition holds, otherwise the <strong>else</strong> steps (if there are any). Either way the flow carries on with what follows.</p>
    <h4>If</h4>
    <ConditionEditor
      condition={node.value}
      {disabled}
      flags={flags.fields.get('if') ?? []}
      onchange={(next) => inspector.setCondition(node, next)}
      ondraft={(next) => draftCondition(next)}
    />
  {:else if node.kind === 'require'}
    <p class="about" data-testid="require-note">
      Runs the <strong>steps</strong> in order until one fails. A failure runs the <strong>failed</strong> steps and stops the whole
      flow, so a payment that cannot be taken never hands over the goods. There is nothing to set here: put the steps on the
      <strong>steps</strong> output.
    </p>
  {:else if node.kind === 'choose'}
    <p class="about">Runs the first case whose condition holds, otherwise the <strong>else</strong> steps.</p>
    <div class="cases">
      <button type="button" {disabled} onclick={() => canvas.addCase(node.id)}><Icon name="plus" size={12} /> Add a case</button>
      <button type="button" disabled={disabled || cases.length === 0} onclick={() => canvas.removeCase(node.id)}><Icon name="minus" size={12} /> Remove the last</button>
    </div>
    {#each cases as entry, index (index)}
      <h4>Case {index + 1}</h4>
      <ConditionEditor
        condition={isRecord(entry) ? entry.if : undefined}
        {disabled}
        onchange={(next) => inspector.setCondition(node, next, index)}
        ondraft={(next) => draftCondition(next, index)}
      />
    {/each}
  {:else if node.kind === 'wait'}
    <p class="about">Pauses the flow, then carries on. It never holds up the server.</p>
    <ParamForm params={WAIT_PARAMS} payload={node.value} shorthand="ticks" {disabled} {flags} write={(next) => inspector.setPayload(node, next)} />
  {:else if node.kind === 'stop'}
    <p class="about">Ends this run. Steps after it on the canvas do not run.</p>
    <ParamForm params={STOP_PARAMS} payload={node.value} {disabled} {flags} write={(next) => inspector.setPayload(node, next)} />
  {:else if node.kind === 'call'}
    <p class="about">Runs a named flow of this resource inline, then carries on.</p>
    <ParamForm params={callParams(flows)} payload={node.value} shorthand="flow" {disabled} {flags} write={(next) => inspector.setPayload(node, next)} {draft} />
  {/if}
</section>

<style>
  .about {
    margin: 0 0 var(--space-3);
    color: var(--muted);
    font-size: var(--text-xs);
  }
  h4 {
    margin: var(--space-3) 0 var(--space-2);
    color: var(--muted);
    font-size: var(--text-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .cases {
    display: flex;
    gap: var(--space-1);
  }
  .cases button {
    display: inline-flex;
    align-items: center;
    gap: var(--space-half);
    padding: var(--space-half) var(--space-2);
    background: none;
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--accent);
    font-size: var(--text-xs);
    cursor: pointer;
  }
  .cases button:disabled {
    opacity: 0.4;
    cursor: default;
  }
  .flag {
    display: flex;
    gap: var(--space-1);
    margin: 0 0 var(--space-2);
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
