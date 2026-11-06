<script lang="ts">
  /**
   * The template field (logic.md section 2.5, 7.3, 31): a literal, a `${expression}` or text with `%placeholders%`.
   * Typing `${` completes from the rule's trigger context, the declared variables and the expression functions (with
   * their signatures); typing `%` completes from the placeholder expansions the scope knows. A name the field would
   * not offer is the name it flags, locally and at once; the compiler's verdict on the same text arrives through
   * `logic.validate` once typing pauses and is drawn here too (`flags`). The value commits on Enter or when the field
   * loses focus, so a half-typed template never reaches the document.
   */
  import Icon from '../../../../components/Icon.svelte';
  import { worstOf } from '../../../../logic/diagnose';
  import { applyCompletion, completeAt, lintTemplate, type CompletionResult } from '../../../../logic/template';
  import type { NodeFlag } from '../../../../logic/verdicts';
  import { useInspector } from '../context';

  interface Props {
    label: string;
    value: string;
    id?: string;
    placeholder?: string;
    /** The text is an expression (a condition row): bare names complete without a `${`. */
    bare?: boolean;
    disabled?: boolean;
    /** The compiler's verdicts on this field. */
    flags?: NodeFlag[];
    /** Values the field offers as plain suggestions (layer ids, flow ids). */
    suggestions?: string[];
    onchange: (value: string) => void;
    ondraft?: (value: string) => void;
  }

  let { label, value, id, placeholder = '', bare = false, disabled = false, flags = [], suggestions = [], onchange, ondraft }: Props = $props();

  const inspector = useInspector();
  const uid = $props.id();
  const listId = `${uid}-options`;
  const suggestionId = `${uid}-suggestions`;

  let text = $state('');
  let focused = $state(false);
  let caret = $state(0);
  let active = $state(0);
  let dismissed = $state(false);
  let input = $state<HTMLInputElement>();

  // the document decides what the field shows: an undo or a refused edit puts the authored text back
  $effect(() => {
    const authored = value;
    if (!focused) text = authored;
  });

  const scope = $derived(inspector.scope);
  const result = $derived<CompletionResult | null>(focused && !dismissed ? completeAt(text, caret, scope, { bare }) : null);
  const open = $derived(result !== null);
  const problems = $derived(lintTemplate(text, scope, { bare }));
  const worst = $derived(problems.some((p) => p.severity === 'error') ? 'error' : (worstOf(flags) ?? (problems.length > 0 ? 'warn' : null)));

  function sync(): void {
    caret = input?.selectionStart ?? text.length;
  }

  function typed(): void {
    text = input!.value;
    dismissed = false;
    active = 0;
    sync();
    ondraft?.(text);
  }

  function accept(index: number): void {
    if (!result) return;
    const item = result.items[index];
    if (!item) return;
    const next = applyCompletion(text, result, item);
    text = next.text;
    queueMicrotask(() => {
      input?.setSelectionRange(next.caret, next.caret);
      sync();
    });
    ondraft?.(text);
  }

  function keydown(event: KeyboardEvent): void {
    if (event.ctrlKey && event.key === ' ') {
      event.preventDefault();
      dismissed = false;
      sync();
      return;
    }
    if (open && result) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        event.stopPropagation();
        const count = result.items.length;
        active = (active + (event.key === 'ArrowDown' ? 1 : count - 1)) % count;
        return;
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault();
        event.stopPropagation();
        accept(active);
        return;
      }
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        dismissed = true;
        return;
      }
    }
    if (event.key === 'Enter') {
      input?.blur();
    } else if (event.key === 'Escape') {
      event.stopPropagation();
      text = value;
      ondraft?.(text);
      input?.blur();
    }
  }

  function blur(): void {
    focused = false;
    if (text !== value) onchange(text);
    else inspector.clearDraft();
    text = value;
  }
</script>

<div class="tf" class:error={worst === 'error'} class:warn={worst === 'warn'}>
  <!-- the role stays when no suggestion list is attached: the field is a combobox for the completion popup -->
  <!-- svelte-ignore a11y_no_redundant_roles -->
  <input
    bind:this={input}
    {id}
    class="control"
    type="text"
    role="combobox"
    aria-label={label}
    aria-autocomplete="list"
    aria-expanded={open}
    aria-controls={open ? listId : undefined}
    aria-activedescendant={open ? `${listId}-${active}` : undefined}
    aria-invalid={worst === 'error' ? true : undefined}
    list={suggestions.length > 0 ? suggestionId : undefined}
    spellcheck="false"
    autocomplete="off"
    {placeholder}
    {disabled}
    value={text}
    oninput={typed}
    onkeydown={keydown}
    onkeyup={sync}
    onclick={sync}
    onfocus={() => {
      focused = true;
      dismissed = false;
      sync();
    }}
    onblur={blur}
  />
  {#if suggestions.length > 0}
    <datalist id={suggestionId}>
      {#each suggestions as suggestion (suggestion)}<option value={suggestion}></option>{/each}
    </datalist>
  {/if}
  {#if open && result}
    <ul id={listId} class="pop" role="listbox" aria-label="{label} suggestions">
      {#each result.items as item, index (item.insert + item.kind)}
        <!-- the input keeps focus: a pick must not blur it, so it is taken on mousedown -->
        <li
          id="{listId}-{index}"
          role="option"
          aria-selected={index === active}
          class:on={index === active}
          data-completion={item.insert}
          onmousedown={(event) => {
            event.preventDefault();
            accept(index);
          }}
        >
          <span class="label {item.kind}">{item.label}</span>
          {#if item.detail}<span class="detail">{item.detail}</span>{/if}
          {#if item.description}<span class="desc">{item.description}</span>{/if}
        </li>
      {/each}
    </ul>
  {/if}
  {#if problems.length > 0 || flags.length > 0}
    <ul class="problems" data-testid="field-problems">
      {#each problems as problem, index (`l${index}`)}
        <li class={problem.severity} data-lint><Icon name="triangle-alert" size={12} /> {problem.message}</li>
      {/each}
      {#each flags as flag, index (`f${index}`)}
        <li class={flag.severity} data-verdict><Icon name={flag.severity === 'info' ? 'info' : 'triangle-alert'} size={12} /> {flag.message}</li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .tf {
    position: relative;
    min-width: 0;
  }
  .control {
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    height: 28px;
    padding: 0 var(--space-2);
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font: var(--text-sm) var(--mono);
  }
  .control:focus {
    border-color: var(--accent);
    outline: none;
  }
  .tf.error .control {
    border-color: var(--error);
  }
  .tf.warn .control {
    border-color: var(--warn);
  }
  .pop {
    position: absolute;
    z-index: 20;
    left: 0;
    right: 0;
    top: 30px;
    max-height: 240px;
    margin: 0;
    padding: var(--space-1) 0;
    overflow-y: auto;
    list-style: none;
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    box-shadow: var(--shadow-2);
  }
  .pop li {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    column-gap: var(--space-2);
    padding: var(--space-half) var(--space-2);
    cursor: pointer;
    font-size: var(--text-sm);
  }
  .pop li.on,
  .pop li:hover {
    background: var(--bg-hover);
  }
  .label {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-family: var(--mono);
  }
  .label.function {
    color: var(--logic-data);
  }
  .label.root {
    color: var(--logic-player);
  }
  .label.placeholder {
    color: var(--logic-economy);
  }
  .detail {
    color: var(--faint);
    font: var(--text-xs) var(--mono);
  }
  .desc {
    grid-column: 1 / -1;
    color: var(--muted);
    font-size: var(--text-xs);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .problems {
    margin: var(--space-1) 0 0;
    padding: 0;
    list-style: none;
    font-size: var(--text-xs);
  }
  .problems li {
    display: flex;
    gap: var(--space-1);
    align-items: flex-start;
  }
  .problems .error {
    color: var(--error);
  }
  .problems .warn {
    color: var(--warn);
  }
  .problems .info {
    color: var(--muted);
  }
</style>
