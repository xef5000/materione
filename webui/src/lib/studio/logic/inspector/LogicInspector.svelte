<script lang="ts">
  /**
   * The node inspector (logic.md section 7.3, 31): select a node and its every parameter is a field, drawn from the
   * node type's self-description. It sits beside the canvas and edits the same document through the same plans, so a
   * value typed here is one history step, one `patchStep`, and shows on the node at once. It holds no state of its own
   * (D1): the panel can be collapsed, and what it shows is always read back from the file.
   */
  import Icon from '../../../components/Icon.svelte';
  import type { IconName } from '../../../components/icons';
  import type { LogicCanvasState } from '../../../logic/canvas.svelte';
  import { categoryIcon } from '../../../logic/vocabulary';
  import ActionSection from './ActionSection.svelte';
  import ControlSection from './ControlSection.svelte';
  import { provideInspector } from './context';
  import TriggerSection from './TriggerSection.svelte';

  let { canvas }: { canvas: LogicCanvasState } = $props();

  const inspector = $derived(canvas.inspector);
  // the context is read once, at mount: the inspector of a canvas never changes
  // svelte-ignore state_referenced_locally
  provideInspector(canvas.inspector);

  const node = $derived(canvas.selected);
  const disabled = $derived(!canvas.editable);
  const KIND_ICONS: Partial<Record<string, IconName>> = {
    if: 'git-branch', require: 'shield', choose: 'shuffle', wait: 'timer', stop: 'square', call: 'workflow', entry: 'workflow', trigger: 'zap',
  };
  const icon = $derived(node ? (KIND_ICONS[node.kind] ?? categoryIcon(canvas.categoryOf(node))) : 'info');
  const callers = $derived(
    node?.kind === 'entry'
      ? canvas.graph.nodes.filter((other) => other.kind === 'call' && (other.value === node.op || (typeof other.value === 'object' && other.value !== null && (other.value as { flow?: unknown }).flow === node.op))).length
      : 0,
  );
</script>

{#if !inspector.open}
  <aside class="rail" aria-label="Inspector (collapsed)">
    <button type="button" class="toggle" aria-label="Show the inspector" title="Show the inspector" aria-expanded="false" onclick={() => inspector.toggle()}>
      <Icon name="chevron-left" size={16} />
    </button>
  </aside>
{:else}
  <aside class="inspector" aria-label="Inspector" data-testid="logic-inspector">
    <header>
      {#if node}
        <span class="swatch" style="--c:{canvas.colorOf(node)}"><Icon name={icon} size={14} /></span>
        <div class="title">
          <h3>{canvas.nameOf(node)}</h3>
          <code>{node.id}</code>
        </div>
      {:else}
        <h3 class="idle">Inspector</h3>
      {/if}
      <button type="button" class="toggle" aria-label="Hide the inspector" title="Hide the inspector" aria-expanded="true" onclick={() => inspector.toggle()}>
        <Icon name="chevron-right" size={16} />
      </button>
    </header>

    <div class="body">
      {#if !node}
        <p class="empty" data-testid="inspector-empty">Select a node to edit its parameters. Every field comes from what the server says the node takes.</p>
      {:else}
        {#key node.id}
          {#if node.kind === 'trigger'}
            <TriggerSection {node} {disabled} />
          {:else if node.kind === 'action'}
            <ActionSection {node} {disabled} />
          {:else if node.kind === 'entry'}
            <section aria-label="Flow">
              <p class="about">A named flow: steps a rule can run with a <strong>call</strong>, written once and reused.</p>
              <div class="name">
                <label for="flow-name">Name</label>
                <input
                  id="flow-name"
                  type="text"
                  spellcheck="false"
                  autocomplete="off"
                  {disabled}
                  value={node.op}
                  onchange={(event) => {
                    if (!inspector.rename(node, event.currentTarget.value)) event.currentTarget.value = node.op;
                  }}
                />
              </div>
              <p class="about">{callers === 0 ? 'Nothing calls this flow yet.' : `Called from ${callers} step${callers === 1 ? '' : 's'}; renaming updates them.`}</p>
            </section>
          {:else}
            <ControlSection {node} {disabled} />
          {/if}
        {/key}
      {/if}
    </div>
  </aside>
{/if}

<style>
  .inspector {
    display: flex;
    flex: none;
    flex-direction: column;
    width: clamp(280px, 30%, 380px);
    min-height: 0;
    background: var(--bg-panel);
    border-left: 1px solid var(--border);
  }
  .rail {
    display: flex;
    flex: none;
    flex-direction: column;
    align-items: center;
    width: 32px;
    padding-top: var(--space-2);
    background: var(--bg-panel);
    border-left: 1px solid var(--border);
  }
  header {
    display: flex;
    flex: none;
    align-items: center;
    gap: var(--space-2);
    min-height: 40px;
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  .swatch {
    display: inline-grid;
    flex: none;
    place-items: center;
    width: 24px;
    height: 24px;
    background: color-mix(in srgb, var(--c) 18%, var(--bg-raised));
    border: 1px solid var(--c);
    border-radius: var(--radius-sm);
    color: var(--c);
  }
  .title {
    flex: 1;
    min-width: 0;
  }
  h3 {
    margin: 0;
    overflow: hidden;
    font-size: var(--text-base);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  h3.idle {
    flex: 1;
    color: var(--muted);
  }
  code {
    color: var(--faint);
    font: var(--text-xs) var(--mono);
  }
  .toggle {
    display: inline-grid;
    flex: none;
    place-items: center;
    width: 26px;
    height: 26px;
    padding: 0;
    background: none;
    border: 0;
    border-radius: var(--radius-sm);
    color: var(--muted);
    cursor: pointer;
  }
  .toggle:hover {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .body {
    flex: 1;
    min-height: 0;
    padding: var(--space-3);
    overflow-y: auto;
  }
  .empty,
  .about {
    margin: 0 0 var(--space-3);
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .about {
    font-size: var(--text-xs);
  }
  .name {
    display: grid;
    gap: var(--space-1);
    margin-bottom: var(--space-3);
  }
  .name label {
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .name input {
    box-sizing: border-box;
    width: 100%;
    height: 28px;
    padding: 0 var(--space-2);
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font: var(--text-sm) var(--mono);
  }
</style>
