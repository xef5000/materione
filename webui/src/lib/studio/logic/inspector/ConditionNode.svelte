<script lang="ts">
  /**
   * One row of the condition stack (logic.md section 2.3, 31), and, for a composite, the rows nested under it. A row is
   * a registered predicate with the typed fields its self-description lists, a `${expression}` with the template
   * field's autocomplete, a constant, or a composite (`all`, `any`, `not`, `at_least`) holding more rows. Every change
   * is the whole new condition value, computed by `condition.ts` from the authored value, so a row the editor does not
   * understand is shown as JSON and survives every edit around it.
   */
  import Icon from '../../../components/Icon.svelte';
  import {
    addChild, convert, freshCondition, moveBy, removeAt, rowKindOf, setAt, setCount, type CondView, type RowKind,
  } from '../../../logic/condition';
  import { jsonLine } from '../../../logic/params';
  import type { NodeFlag } from '../../../logic/verdicts';
  import { useInspector } from './context';
  import ParamForm from './ParamForm.svelte';
  import Self from './ConditionNode.svelte';
  import TemplateField from './fields/TemplateField.svelte';

  interface Props {
    view: CondView;
    root: unknown;
    /** The whole condition after a change. */
    apply: (next: unknown) => void;
    /** Typing in an expression: the compiler is asked about it, the document is not touched. */
    draft?: (next: unknown) => void;
    flags?: NodeFlag[];
    disabled?: boolean;
    /** Whether this row sits in a group (it can move, and removing it leaves the group). */
    inGroup?: boolean;
    index?: number;
    count?: number;
    /** The root of a stack that must hold a condition cannot be removed, only changed. */
    removable?: boolean;
  }

  let { view, root, apply, draft, flags = [], disabled = false, inGroup = false, index = 0, count = 1, removable = true }: Props = $props();

  const inspector = useInspector();
  const uid = $props.id();
  const vocabulary = $derived(inspector.canvas.vocabulary);
  const shape = $derived(view.shape);
  const kind = $derived(rowKindOf(shape));
  const entry = $derived(shape.t === 'pred' ? vocabulary?.conditions.get(shape.id) : undefined);
  const predicates = $derived([...(vocabulary?.conditions.values() ?? [])]);

  const KINDS: { value: RowKind; label: string }[] = [
    { value: 'predicate', label: 'Check' },
    { value: 'expression', label: 'Expression' },
    { value: 'bool', label: 'Always true / false' },
    { value: 'all', label: 'All of' },
    { value: 'any', label: 'Any of' },
    { value: 'not', label: 'None of (not)' },
    { value: 'at_least', label: 'At least N of' },
  ];

  function change(next: unknown): void {
    apply(next);
  }

  function setKind(next: RowKind): void {
    if (next !== kind) change(convert(root, view.path, next, vocabulary));
  }

  function setPredicate(id: string): void {
    change(setAt(root, view.path, freshCondition('predicate', vocabulary, id)));
  }

  const nested = $derived(shape.t === 'group' || shape.t === 'atLeast');
  function add(what: RowKind): void {
    change(addChild(root, view.path, freshCondition(what, vocabulary)));
  }

  function setBool(text: string): void {
    change(setAt(root, view.path, text === 'true'));
  }
</script>

<div class="row" data-cond-row data-kind={kind} data-path={view.path.join('.')}>
  <div class="head">
    <label class="sr" for="{uid}-kind">Kind of condition</label>
    <select id="{uid}-kind" value={kind === 'raw' ? '' : kind} {disabled} onchange={(event) => setKind(event.currentTarget.value as RowKind)} aria-label="Kind of condition">
      {#if kind === 'raw'}<option value="">As written</option>{/if}
      {#each KINDS as option (option.value)}<option value={option.value}>{option.label}</option>{/each}
    </select>
    {#if shape.t === 'pred'}
      <select value={shape.id} {disabled} aria-label="Check" data-testid="predicate" onchange={(event) => setPredicate(event.currentTarget.value)}>
        {#if !entry}<option value={shape.id}>{shape.id} (unknown)</option>{/if}
        {#each predicates as predicate (predicate.id)}
          <option value={predicate.id} disabled={!predicate.available && predicate.id !== shape.id} title={predicate.available ? predicate.description : predicate.reason}>
            {predicate.displayName}{predicate.available ? '' : ' (unavailable)'}
          </option>
        {/each}
      </select>
    {/if}
    <span class="tools">
      {#if inGroup}
        <button type="button" aria-label="Move up" title="Move up" disabled={disabled || index === 0} onclick={() => change(moveBy(root, view.path, -1))}><Icon name="chevron-up" size={13} /></button>
        <button type="button" aria-label="Move down" title="Move down" disabled={disabled || index === count - 1} onclick={() => change(moveBy(root, view.path, 1))}><Icon name="chevron-down" size={13} /></button>
      {/if}
      {#if removable}
        <button type="button" aria-label="Remove this condition" title="Remove" {disabled} onclick={() => change(removeAt(root, view.path))}><Icon name="x" size={13} /></button>
      {/if}
    </span>
  </div>

  {#if shape.t === 'pred'}
    {#if entry && !entry.available}
      <p class="why"><Icon name="triangle-alert" size={12} /> {entry.reason}</p>
    {/if}
    {#if entry}
      <ParamForm
        params={entry.parameters}
        payload={shape.payload}
        shorthand={entry.shorthand}
        {disabled}
        write={(next) => change(setAt(root, view.path, { [shape.id]: next }))}
      />
    {:else}
      <p class="why"><Icon name="triangle-alert" size={12} /> No condition called <code>{shape.id}</code> is registered on this server. It is kept as written: <code>{jsonLine(shape.payload)}</code></p>
    {/if}
  {:else if shape.t === 'expr'}
    <TemplateField
      label="Expression"
      value={shape.text}
      bare
      {disabled}
      {flags}
      placeholder="player.level >= 10"
      onchange={(next) => change(setAt(root, view.path, next))}
      ondraft={draft ? (next) => draft(setAt(root, view.path, next)) : undefined}
    />
  {:else if shape.t === 'bool'}
    <select value={String(shape.value)} {disabled} aria-label="Constant" onchange={(event) => setBool(event.currentTarget.value)}>
      <option value="true">always true</option>
      <option value="false">always false</option>
    </select>
  {:else if shape.t === 'raw'}
    <p class="why"><Icon name="info" size={12} /> Not a condition this editor draws. Kept as written: <code>{jsonLine(shape.value)}</code></p>
  {/if}

  {#if shape.t === 'atLeast'}
    <label class="count">
      <span>At least</span>
      <input
        type="text"
        inputmode="numeric"
        aria-label="How many conditions must hold"
        value={String(shape.count)}
        {disabled}
        onchange={(event) => {
          const n = Number(event.currentTarget.value);
          if (Number.isInteger(n) && n >= 0) change(setCount(root, view.path, n));
          else event.currentTarget.value = String(shape.count);
        }}
      />
      <span>of these must hold</span>
    </label>
  {/if}

  {#if nested || shape.t === 'not'}
    <div class="children" role="group" aria-label="Conditions inside this group">
      {#each view.children as child, at (child.path.join('.'))}
        <Self
          view={child}
          {root}
          {apply}
          {draft}
          {disabled}
          inGroup={nested}
          index={at}
          count={view.children.length}
          removable={shape.t === 'not' ? false : true}
        />
      {/each}
      {#if nested}
        <div class="adds">
          <button type="button" {disabled} onclick={() => add('predicate')}><Icon name="plus" size={12} /> Check</button>
          <button type="button" {disabled} onclick={() => add('expression')}><Icon name="plus" size={12} /> Expression</button>
          <button type="button" {disabled} onclick={() => add('all')}><Icon name="plus" size={12} /> Group</button>
        </div>
      {/if}
    </div>
  {/if}
</div>

<style>
  .row {
    display: grid;
    gap: var(--space-1);
    min-width: 0;
    padding: var(--space-2);
    background: color-mix(in srgb, var(--logic-flow) 4%, var(--bg-input));
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-1);
  }
  select,
  .count input {
    box-sizing: border-box;
    min-width: 0;
    height: 26px;
    padding: 0 var(--space-2);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font: var(--text-sm) var(--font-sans);
  }
  .head select {
    flex: 1 1 8em;
  }
  .tools {
    display: inline-flex;
    margin-left: auto;
  }
  .tools button {
    display: inline-grid;
    place-items: center;
    width: 22px;
    height: 26px;
    padding: 0;
    background: none;
    border: 0;
    border-radius: var(--radius-sm);
    color: var(--muted);
    cursor: pointer;
  }
  .tools button:hover:not(:disabled) {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .tools button:disabled {
    opacity: 0.3;
    cursor: default;
  }
  .children {
    display: grid;
    gap: var(--space-1);
    margin-left: var(--space-3);
    padding-left: var(--space-2);
    border-left: 2px solid color-mix(in srgb, var(--logic-flow) 35%, transparent);
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
  .count {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .count input {
    width: 3.5em;
    text-align: center;
  }
  .why {
    display: flex;
    gap: var(--space-1);
    margin: 0;
    color: var(--warn);
    font-size: var(--text-xs);
  }
  .sr {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
  }
</style>
