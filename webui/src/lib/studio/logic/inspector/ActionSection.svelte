<script lang="ts">
  /**
   * The inspector of an action step (logic.md section 2.2, 4.1, 31): every parameter of the action's self-description
   * as the field its type calls for, then the options any action takes (`chance`, `delay`). An action the server does
   * not register, or cannot run here, says so with the server's reason, and its payload stays editable as raw JSON: the
   * file is never made unreachable by what the server happens to know.
   */
  import Icon from '../../../components/Icon.svelte';
  import { attribute } from '../../../logic/diagnose';
  import { planStepValue } from '../../../logic/edit-plan';
  import { textValues } from '../../../logic/params';
  import type { GraphNode } from '../../../logic/project';
  import type { ParamInfo } from '../../../logic/vocabulary';
  import { useInspector } from './context';
  import ParamField from './ParamField.svelte';
  import ParamForm from './ParamForm.svelte';

  interface Props {
    node: GraphNode;
    disabled?: boolean;
  }

  let { node, disabled = false }: Props = $props();

  const inspector = useInspector();
  const canvas = $derived(inspector.canvas);
  const vocabulary = $derived(canvas.vocabulary);
  const entry = $derived(vocabulary?.actions.get(node.op));
  const options = $derived<ParamInfo[]>(vocabulary?.actionOptions ?? []);
  const flags = $derived(
    attribute(
      node.id,
      inspector.flagsFor(node),
      [...(entry?.parameters.map((param) => param.id) ?? []), ...options.map((option) => option.id)],
      textValues(node.value, entry?.shorthand),
    ),
  );

  /** The step's keys that are options: everything but the action's own key, its branches and its position. */
  const optionValues = $derived(node.options);

  const rawParam = $derived<ParamInfo>({ id: node.op, label: 'Settings', type: 'raw', required: false, description: 'This server does not describe the action, so its settings are shown as written.' });

  function draft(payload: unknown): void {
    inspector.draft(node.id, planStepValue(canvas.graph, node.id, payload, canvas.nameOf));
  }
</script>

<section aria-label="Action">
  {#if entry}
    {#if entry.description}<p class="about">{entry.description}</p>{/if}
    {#if !entry.available}
      <p class="why" role="note"><Icon name="triangle-alert" size={13} /> {entry.reason}</p>
    {/if}
    {#each flags.node as flag, index (index)}
      <p class="flag {flag.severity}" data-verdict><Icon name={flag.severity === 'info' ? 'info' : 'triangle-alert'} size={12} /> {flag.message}</p>
    {/each}
    <ParamForm
      params={entry.parameters}
      payload={node.value}
      shorthand={entry.shorthand}
      {disabled}
      {flags}
      write={(payload) => inspector.setPayload(node, payload)}
      {draft}
    />
    {#if entry.takesElse}
      <p class="about" data-testid="takes-else">
        This step asks a question. When it goes unanswered (declined, cancelled or timed out), the steps on its
        <strong>else</strong> output run and the flow ends there.
      </p>
    {/if}
  {:else}
    <p class="why" role="note"><Icon name="triangle-alert" size={13} /> No action called <code>{node.op}</code> is registered on this server. It is kept as written.</p>
    {#each flags.node as flag, index (index)}
      <p class="flag {flag.severity}" data-verdict>{flag.message}</p>
    {/each}
    <ParamField param={rawParam} value={node.value} {disabled} onchange={(next) => inspector.setPayload(node, next)} />
  {/if}

  <h4>Options</h4>
  <ParamForm
    params={options}
    payload={optionValues}
    {disabled}
    {flags}
    writeKey={(id, next) => inspector.setKey(node, id, next)}
  />
</section>

<style>
  .about {
    margin: 0 0 var(--space-3);
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .why {
    display: flex;
    gap: var(--space-1);
    margin: 0 0 var(--space-3);
    color: var(--warn);
    font-size: var(--text-xs);
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
  h4 {
    margin: var(--space-3) 0 var(--space-2);
    color: var(--muted);
    font-size: var(--text-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
</style>
