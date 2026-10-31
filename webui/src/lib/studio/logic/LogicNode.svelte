<script lang="ts">
  /**
   * One node of the Logic canvas (logic.md §7.2, §7.3): a coloured header, the node's own value, and its
   * ports on the edges. It draws; every gesture on it (select, drag, wire) is read from the data attributes
   * by the canvas, so a node has no pointer logic of its own. Its one control is the value field, which
   * edits the kind's own value (an action's shorthand, an `if` condition, a `wait`'s ticks) through the
   * same edit commands as everything else. The full inspector is issue 224.
   */
  import Icon from '../../components/Icon.svelte';
  import type { IconName } from '../../components/icons';
  import type { LogicCanvasState } from '../../logic/canvas.svelte';
  import { HEAD, portOffset, ROW, type Rect } from '../../logic/layout';
  import { portKey, type GraphNode } from '../../logic/project';
  import { isLine, summarize } from '../../logic/summary';
  import { valueKey } from '../../logic/plan';
  import { categoryIcon } from '../../logic/vocabulary';

  interface Props {
    node: GraphNode;
    rect: Rect;
    canvas: LogicCanvasState;
    selected: boolean;
    /** Roving tab stop: one node is reachable by Tab, the arrows walk the rest. */
    tab: boolean;
  }

  let { node, rect, canvas, selected, tab }: Props = $props();

  const KIND_ICONS: Partial<Record<GraphNode['kind'], IconName>> = {
    if: 'git-branch',
    require: 'shield',
    choose: 'shuffle',
    wait: 'timer',
    stop: 'square',
    call: 'workflow',
    entry: 'workflow',
    trigger: 'zap',
  };

  const category = $derived(canvas.categoryOf(node));
  const color = $derived(canvas.colorOf(node));
  const icon = $derived(KIND_ICONS[node.kind] ?? categoryIcon(category));
  const title = $derived(canvas.nameOf(node));
  const flags = $derived(canvas.flagsOf(node));
  const worst = $derived(flags.find((flag) => flag.severity === 'error') ?? flags.find((flag) => flag.severity === 'warn') ?? flags[0]);
  const key = $derived(valueKey(node));
  const editable = $derived(canvas.editable && key !== null && isLine(node.kind === 'action' ? node.value : node.kind === 'trigger' ? node.op : node.value));
  const raw = $derived(node.kind === 'trigger' ? node.op : node.value);
  const text = $derived(isLine(raw) ? String(raw) : summarize(raw));
  const description = $derived(
    node.kind === 'action'
      ? canvas.vocabulary?.actions.get(node.op)?.description
      : node.kind === 'trigger'
        ? canvas.vocabulary?.triggers.get(node.op.split(':')[0]!)?.description
        : canvas.vocabulary?.steps.get(node.kind)?.description,
  );
  const options = $derived(
    Object.entries(node.options)
      .slice(0, 3)
      .map(([name, value]) => `${name}: ${summarize(value, 40)}`),
  );
  const extras = $derived(node.kind === 'trigger' ? [...(node.condition !== undefined ? [`if ${summarize(node.condition, 44)}`] : []), ...options].slice(0, 3) : []);
  const labelled = $derived(node.ports.length > 1);
  const hasInput = $derived(node.kind !== 'trigger' && node.kind !== 'entry');
  const notice = $derived(
    node.kind === 'require' ? 'all must succeed'
    : node.kind === 'stop' ? 'ends the run'
    : node.kind === 'choose' ? 'first match runs'
    : node.kind === 'entry' ? 'named flow'
    : node.authored.literal && node.kind === 'action' ? 'no settings'
    : '',
  );

  function commit(event: Event): void {
    const input = event.currentTarget as HTMLInputElement;
    canvas.setValue(node.id, input.value);
    input.value = text; // the document decides what the node shows; a refused edit puts the old text back
  }

  function field(event: KeyboardEvent): void {
    const input = event.currentTarget as HTMLInputElement;
    if (event.key === 'Escape') {
      input.value = text;
      input.blur();
      event.stopPropagation();
    } else if (event.key === 'Enter') {
      input.blur();
    }
  }
</script>

<div
  class="node"
  class:selected
  class:trigger={node.kind === 'trigger'}
  role="treeitem"
  aria-selected={selected}
  aria-label="{title}{text ? `: ${text}` : ''}"
  data-node={node.id}
  data-kind={node.kind}
  data-op={node.op}
  tabindex={tab ? 0 : -1}
  style="left:{rect.x}px;top:{rect.y}px;width:{rect.w}px;height:{rect.h}px;--c:{color}"
  title={description}
>
  <header data-node-head>
    <Icon name={icon} size={14} />
    <span class="title">{title}</span>
    {#if worst}
      <span class="flag {worst.severity}" title={flags.map((flag) => flag.message).join('\n')} role="img" aria-label="{worst.severity}: {worst.message}">
        <Icon name={worst.severity === 'info' ? 'info' : 'triangle-alert'} size={13} />
      </span>
    {/if}
    {#if node.kind === 'choose' && selected && canvas.editable}
      <button type="button" class="mini" title="Add a case" aria-label="Add a case" onclick={() => canvas.addCase(node.id)}><Icon name="plus" size={12} /></button>
      <button type="button" class="mini" title="Remove the last case" aria-label="Remove the last case" onclick={() => canvas.removeCase(node.id)}><Icon name="minus" size={12} /></button>
    {/if}
  </header>

  <div class="body" class:labelled style="top:{HEAD}px;height:{ROW}px">
    {#if node.kind === 'trigger'}
      <code class="selector" title="the rule's on:">{node.op || 'no trigger'}</code>
    {:else if editable}
      <input
        class="value"
        type="text"
        spellcheck="false"
        autocomplete="off"
        value={text}
        aria-label="{title} value"
        onchange={commit}
        onkeydown={field}
      />
    {:else if notice}
      <span class="note">{notice}</span>
    {:else}
      <span class="value plain" title={text}>{text}</span>
    {/if}
  </div>

  {#each extras as line, index (index)}
    <div class="extra" style="top:{HEAD + (index + 1) * ROW}px;height:{ROW}px" title={line}>{line}</div>
  {/each}

  {#if hasInput}
    <span class="port in" data-port-in data-node={node.id} aria-hidden="true" style="top:{portOffset(0)}px"></span>
  {/if}

  {#each node.ports as port, index (port.id)}
    {@const taken = canvas.graph.leaving.has(portKey(node.id, port.id))}
    <div class="plabel" style="top:{HEAD + index * ROW}px;height:{ROW}px">
      {#if labelled}<span class="label">{port.label}</span>{/if}
    </div>
    <button
      type="button"
      class="port out"
      class:taken
      data-port-out
      data-node={node.id}
      data-port={port.id}
      tabindex={tab ? 0 : -1}
      style="top:{portOffset(index)}px"
      aria-label="{title}, {port.label} output, {taken ? 'wired' : 'free'}. Press Enter to insert a node here."
      title={taken ? `${port.label}: wired. Press Enter to insert a node ahead of the wire` : `${port.label}: drag out to add or connect, or press Enter`}
    ><span class="plus" aria-hidden="true">+</span></button>
  {/each}
</div>

<style>
  .node {
    position: absolute;
    box-sizing: border-box;
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-top: 3px solid var(--c);
    border-radius: var(--radius);
    box-shadow: var(--shadow-1);
    color: var(--fg);
    font-size: var(--text-sm);
    user-select: none;
    touch-action: none;
    cursor: grab;
    transition:
      border-color var(--motion-fast) var(--ease-standard),
      box-shadow var(--motion-fast) var(--ease-standard);
  }
  .node:hover {
    border-color: color-mix(in srgb, var(--c) 60%, var(--border-strong));
  }
  .node.selected {
    border-color: var(--c);
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--c) 55%, transparent), var(--shadow-2);
  }
  .node:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
  header {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    height: calc(28px - 3px);
    padding: 0 var(--space-2);
    background: color-mix(in srgb, var(--c) 16%, var(--bg-raised));
    border-radius: var(--radius-sm) var(--radius-sm) 0 0;
    color: color-mix(in srgb, var(--c) 70%, var(--fg));
    font-weight: 600;
    white-space: nowrap;
    cursor: grab;
  }
  .title {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--fg);
  }
  .flag {
    display: inline-flex;
    flex: none;
  }
  .flag.warn {
    color: var(--warn);
  }
  .flag.error {
    color: var(--error);
  }
  .flag.info {
    color: var(--muted);
  }
  .mini {
    display: inline-grid;
    place-items: center;
    flex: none;
    width: 18px;
    height: 18px;
    padding: 0;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    cursor: pointer;
  }
  .mini:hover {
    border-color: var(--c);
  }
  .body,
  .extra,
  .plabel {
    position: absolute;
    left: 0;
    right: 0;
    display: flex;
    align-items: center;
    box-sizing: border-box;
    padding: 0 var(--space-3) 0 var(--space-2);
    min-width: 0;
  }
  .plabel {
    justify-content: flex-end;
    pointer-events: none;
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .body.labelled {
    padding-right: calc(var(--space-10) + var(--space-3));
  }
  .body {
    padding-right: var(--space-5);
  }
  .extra {
    color: var(--muted);
    font-size: var(--text-xs);
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
    display: block;
    line-height: 28px;
  }
  .selector {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font: 600 var(--text-sm) var(--mono);
    color: color-mix(in srgb, var(--c) 55%, var(--fg));
  }
  .value {
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    height: 22px;
    padding: 0 var(--space-2);
    background: var(--bg-input);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font: var(--text-sm) var(--mono);
    cursor: text;
    user-select: text;
  }
  .value:hover {
    border-color: var(--border-strong);
  }
  .value:focus {
    border-color: var(--c);
    outline: none;
  }
  .value.plain {
    display: block;
    height: auto;
    padding: 0;
    background: none;
    border: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    cursor: inherit;
    user-select: none;
  }
  .note {
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .port {
    position: absolute;
    box-sizing: border-box;
    width: 12px;
    height: 12px;
    margin-top: -6px;
    padding: 0;
    background: var(--bg-input);
    border: 2px solid var(--c);
    border-radius: 0;
    color: var(--c);
  }
  .port.in {
    left: -7px;
  }
  .port.out {
    right: -7px;
    display: grid;
    place-items: center;
    cursor: crosshair;
  }
  .port.out.taken {
    background: var(--c);
  }
  .port.out:hover,
  .port.out:focus-visible {
    background: var(--c);
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
  .plus {
    display: none;
    font: 700 var(--text-xs) var(--mono);
    line-height: 1;
    color: var(--bg-app);
  }
  .port.out:hover .plus,
  .port.out:focus-visible .plus {
    display: block;
  }
  .label {
    padding-right: var(--space-2);
  }
</style>
