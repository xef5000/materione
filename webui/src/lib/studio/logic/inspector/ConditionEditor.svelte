<script lang="ts">
  /**
   * The condition stack of a rule's `if:`, an `if` step or one `choose` case (logic.md section 2.3, 7.2, 31). The
   * condition is edited as the authored value itself: one row, or a stack of rows (a list is `all`), with `all`, `any`,
   * `not` and `at_least` nesting to any depth, and rows added, removed and reordered. Each change writes the whole new
   * condition through the inspector, so it is one history step and one `patchStep`, never a second copy.
   */
  import Icon from '../../../components/Icon.svelte';
  import { freshCondition, pushRoot, viewOf } from '../../../logic/condition';
  import type { NodeFlag } from '../../../logic/verdicts';
  import { useInspector } from './context';
  import ConditionNode from './ConditionNode.svelte';

  interface Props {
    condition: unknown;
    /** A rule's `if:` may be absent; an `if` step and a `choose` case always hold one. */
    optional?: boolean;
    disabled?: boolean;
    flags?: NodeFlag[];
    onchange: (next: unknown) => void;
    ondraft?: (next: unknown) => void;
  }

  let { condition, optional = false, disabled = false, flags = [], onchange, ondraft }: Props = $props();

  const inspector = useInspector();
  const vocabulary = $derived(inspector.canvas.vocabulary);
  const absent = $derived(condition === undefined || condition === null);
  const view = $derived(viewOf(condition));
  const grouped = $derived(view.shape.t === 'group' && view.shape.op === 'all');
</script>

<div class="stack" data-testid="condition-stack">
  {#if absent}
    <p class="none">{optional ? 'No condition: the rule runs every time.' : 'No condition.'}</p>
    <div class="adds">
      <button type="button" {disabled} onclick={() => onchange(freshCondition('predicate', vocabulary))}><Icon name="plus" size={12} /> Check</button>
      <button type="button" {disabled} onclick={() => onchange(freshCondition('expression', vocabulary))}><Icon name="plus" size={12} /> Expression</button>
    </div>
  {:else}
    <ConditionNode {view} root={condition} apply={onchange} draft={ondraft} {flags} {disabled} />
    {#if !grouped && view.shape.t !== 'group'}
      <div class="adds">
        <button type="button" {disabled} onclick={() => onchange(pushRoot(condition, freshCondition('predicate', vocabulary)))}>
          <Icon name="plus" size={12} /> Also require a check
        </button>
        <button type="button" {disabled} onclick={() => onchange(pushRoot(condition, freshCondition('expression', vocabulary)))}>
          <Icon name="plus" size={12} /> Also require an expression
        </button>
      </div>
    {/if}
  {/if}
</div>

<style>
  .stack {
    display: grid;
    gap: var(--space-2);
    min-width: 0;
  }
  .none {
    margin: 0;
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .adds {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1);
  }
  .adds button {
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
</style>
