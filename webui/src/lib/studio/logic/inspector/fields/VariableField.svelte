<script lang="ts">
  /**
   * A variable parameter (logic.md section 3, 7.3, 31): `set_var`'s `var`, the `var` condition, `prompt`'s answer and
   * any parameter the self-description draws as a variable. The picker lists the declared player and server variables
   * (written `pack:key`, so the scope comes from the declaration and a typo cannot create a second key), the session
   * variables the GUI's flows use (`session.name`) and the run-local names written earlier in the flow. Typing a name
   * that is none of these is allowed: a run-local or session variable needs no declaration. "Declare a new variable"
   * writes a declaration into the pack's variables file through the authoring ops.
   */
  import Icon from '../../../../components/Icon.svelte';
  import { describeDefault, type DeclaredVariable } from '../../../../logic/variables';
  import type { NodeFlag } from '../../../../logic/verdicts';
  import { session } from '../../../../state.svelte';
  import { useInspector } from '../context';
  import DeclareVariable from '../DeclareVariable.svelte';

  interface Props {
    value: unknown;
    id?: string;
    label: string;
    disabled?: boolean;
    flags?: NodeFlag[];
    onchange: (value: unknown) => void;
  }

  let { value, id, label, disabled = false, flags = [], onchange }: Props = $props();

  const inspector = useInspector();
  const uid = $props.id();

  let open = $state(false);
  let declaring = $state(false);
  let text = $state('');
  let focused = $state(false);
  let query = $state('');

  $effect(() => {
    const authored = value;
    if (!focused) text = typeof authored === 'string' ? authored : '';
  });
  // the declared variables are read the first time a picker is shown
  $effect(() => {
    if (inspector.declaredStatus === 'idle') void inspector.loadDeclared(session.channel);
  });

  const current = $derived(typeof value === 'string' ? value : '');
  const scopeNames = $derived(inspector.scope.paths);
  const sessionNames = $derived(
    scopeNames.filter((entry) => entry.origin === 'session' && !entry.path.endsWith('*')).map((entry) => `session.${entry.path.slice('vars.session.'.length)}`),
  );
  const localNames = $derived(scopeNames.filter((entry) => entry.origin === 'local').map((entry) => entry.path.slice('vars.'.length)));
  const needle = $derived(query.trim().toLowerCase());
  const declared = $derived(inspector.declared.filter((variable) => needle === '' || variable.id.toLowerCase().includes(needle)));
  const sessions = $derived(sessionNames.filter((name) => needle === '' || name.includes(needle)));
  const locals = $derived(localNames.filter((name) => needle === '' || name.includes(needle)));
  const known = $derived(inspector.declared.find((variable) => variable.id === current));
  const empty = $derived(declared.length + sessions.length + locals.length === 0);

  function choose(name: string): void {
    open = false;
    query = '';
    if (name !== current) onchange(name === '' ? undefined : name);
  }

  function commit(): void {
    focused = false;
    const next = text.trim();
    if (next !== current) onchange(next === '' ? undefined : next);
    text = current;
  }

  function made(variable: DeclaredVariable): void {
    declaring = false;
    choose(variable.id);
  }
</script>

<div class="variable" data-testid="variable-field">
  <div class="row">
    <input
      {id}
      type="text"
      aria-label={label}
      spellcheck="false"
      autocomplete="off"
      placeholder="shop:coins, session.page, or a local name"
      {disabled}
      value={text}
      oninput={(event) => (text = event.currentTarget.value)}
      onfocus={() => (focused = true)}
      onblur={commit}
      onkeydown={(event) => {
        if (event.key === 'Enter') event.currentTarget.blur();
        else if (event.key === 'Escape') {
          text = current;
          event.stopPropagation();
          event.currentTarget.blur();
        }
      }}
    />
    <button
      type="button"
      class="pick"
      {disabled}
      aria-haspopup="listbox"
      aria-expanded={open}
      aria-label="Choose {label}"
      onclick={() => {
        open = !open;
        declaring = false;
      }}
    ><Icon name="chevron-down" size={14} /></button>
  </div>
  {#if known}
    <p class="note">{known.scope} {known.type}, starts as {describeDefault(known)}{known.description ? `. ${known.description}` : ''}</p>
  {/if}
  {#each flags as flag, index (index)}
    <p class="flag {flag.severity}" data-verdict><Icon name="triangle-alert" size={12} /> {flag.message}</p>
  {/each}

  {#if open}
    <div class="popover" role="dialog" aria-label="Variables">
      {#if declaring}
        <DeclareVariable start={text} ondone={made} oncancel={() => (declaring = false)} />
      {:else}
        <input class="search" type="text" aria-label="Search variables" placeholder="Search variables" autocomplete="off" bind:value={query} />
        <div class="list" role="listbox" aria-label="Variables" id="{uid}-list">
          {#if declared.length > 0}
            <h5>Declared</h5>
            {#each declared as variable (variable.id)}
              <button type="button" role="option" aria-selected={variable.id === current} data-variable={variable.id} onclick={() => choose(variable.id)}>
                <span class="name">{variable.id}</span>
                <span class="meta">{variable.scope} {variable.type}</span>
              </button>
            {/each}
          {/if}
          {#if sessions.length > 0}
            <h5>Session</h5>
            {#each sessions as name (name)}
              <button type="button" role="option" aria-selected={name === current} onclick={() => choose(name)}>
                <span class="name">{name}</span>
              </button>
            {/each}
          {/if}
          {#if locals.length > 0}
            <h5>Set earlier in this flow</h5>
            {#each locals as name (name)}
              <button type="button" role="option" aria-selected={name === current} onclick={() => choose(name)}>
                <span class="name">{name}</span>
              </button>
            {/each}
          {/if}
          {#if empty}<p class="none">No variable matches.{inspector.declaredStatus === 'ready' && inspector.declared.length === 0 ? ' None is declared yet.' : ''}</p>{/if}
        </div>
        <button type="button" class="new" data-testid="declare-new" onclick={() => (declaring = true)}>
          <Icon name="plus" size={13} /> Declare a new variable
        </button>
      {/if}
    </div>
  {/if}
</div>

<style>
  .variable {
    position: relative;
    min-width: 0;
  }
  .row {
    display: flex;
    gap: var(--space-1);
  }
  .row input {
    flex: 1;
    min-width: 0;
    height: 28px;
    padding: 0 var(--space-2);
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font: var(--text-sm) var(--mono);
  }
  .row input:focus {
    border-color: var(--accent);
    outline: none;
  }
  .pick {
    display: inline-grid;
    place-items: center;
    width: 30px;
    height: 28px;
    padding: 0;
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    cursor: pointer;
  }
  .pick:hover:not(:disabled) {
    border-color: var(--accent);
  }
  .note {
    margin: var(--space-1) 0 0;
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .flag {
    display: flex;
    gap: var(--space-1);
    margin: var(--space-1) 0 0;
    font-size: var(--text-xs);
  }
  .flag.error {
    color: var(--error);
  }
  .flag.warn {
    color: var(--warn);
  }
  .popover {
    position: absolute;
    z-index: 20;
    left: 0;
    right: 0;
    top: 32px;
    display: grid;
    gap: var(--space-1);
    max-height: 340px;
    padding: var(--space-2);
    overflow-y: auto;
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    box-shadow: var(--shadow-2);
  }
  .search {
    box-sizing: border-box;
    width: 100%;
    height: 26px;
    padding: 0 var(--space-2);
    background: var(--bg-input);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font: var(--text-sm) var(--font-sans);
  }
  .list {
    display: grid;
    max-height: 200px;
    overflow-y: auto;
  }
  h5 {
    margin: var(--space-2) 0 var(--space-half);
    color: var(--muted);
    font-size: var(--text-xs);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .list button {
    display: flex;
    justify-content: space-between;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-2);
    background: none;
    border: 0;
    border-radius: var(--radius-sm);
    color: var(--fg);
    font-size: var(--text-sm);
    text-align: left;
    cursor: pointer;
  }
  .list button:hover,
  .list button[aria-selected='true'] {
    background: var(--bg-hover);
  }
  .name {
    font-family: var(--mono);
  }
  .meta {
    color: var(--faint);
    font-size: var(--text-xs);
  }
  .none {
    margin: var(--space-2);
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .new {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: var(--space-1) var(--space-2);
    background: none;
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--accent);
    font-size: var(--text-sm);
    cursor: pointer;
  }
</style>
