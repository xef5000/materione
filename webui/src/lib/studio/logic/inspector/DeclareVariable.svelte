<script lang="ts">
  /**
   * The "declare new variable" shortcut (logic.md section 3.2, 31). It writes the declaration into the pack's
   * `variables/` file through the ordinary authoring ops (creating `variables/<pack>.yml` first when the pack has none),
   * so the new variable is a file edit like any other, saved by the server and checked by the compiler. Nothing is kept in
   * the editor. Only persistent variables are declared; a run-local or session variable needs no declaration.
   */
  import Icon from '../../../components/Icon.svelte';
  import { session } from '../../../state.svelte';
  import {
    defaultOf, keyProblem, VARIABLE_SCOPES, VARIABLE_TYPES, type DeclaredVariable, type VariableScope, type VariableType,
  } from '../../../logic/variables';
  import { useInspector } from './context';

  interface Props {
    /** A hint for the key, taken from what the author had typed. */
    start?: string;
    ondone: (made: DeclaredVariable) => void;
    oncancel: () => void;
  }

  let { start = '', ondone, oncancel }: Props = $props();

  const inspector = useInspector();
  const pack = $derived(inspector.canvas.gui.pack);
  const uid = $props.id();

  // the hint seeds the field once; the author edits it from there
  // svelte-ignore state_referenced_locally
  let key = $state(start.replace(/^[^:]*:/, '').replace(/^(player|server|session|local)\./, '').toLowerCase().replace(/[^a-z0-9_]/g, '_'));
  let scope = $state<VariableScope>('player');
  let type = $state<VariableType>('number');
  let initial = $state('');
  let description = $state('');
  let busy = $state(false);
  let failure = $state('');

  const problem = $derived(key === '' ? null : keyProblem(key, inspector.declared, pack));
  const valid = $derived(key !== '' && problem === null && (type !== 'number' || initial === '' || Number.isFinite(Number(initial))));

  function initialValue(): unknown {
    if (type === 'number') return initial === '' ? 0 : Number(initial);
    if (type === 'boolean') return initial === 'true';
    return initial;
  }

  async function declare(): Promise<void> {
    if (!valid || busy) return;
    busy = true;
    failure = '';
    try {
      const made = await inspector.declare(session.channel, { key, scope, type, default: initialValue(), description: description.trim() || undefined });
      ondone(made);
    } catch (error) {
      failure = error instanceof Error ? error.message : String(error);
    } finally {
      busy = false;
    }
  }
</script>

<form class="declare" data-testid="declare-variable" onsubmit={(event) => { event.preventDefault(); void declare(); }}>
  <h4>Declare a variable in {pack}</h4>
  <div class="row">
    <label for="{uid}-key">Name</label>
    <input id="{uid}-key" type="text" bind:value={key} spellcheck="false" autocomplete="off" aria-invalid={problem ? true : undefined} />
  </div>
  {#if problem}<p class="problem" role="alert">{problem}</p>{/if}
  <div class="row">
    <label for="{uid}-scope">Stored</label>
    <select id="{uid}-scope" bind:value={scope}>
      {#each VARIABLE_SCOPES as option (option)}<option value={option}>{option === 'player' ? 'per player' : 'for the server'}</option>{/each}
    </select>
  </div>
  <div class="row">
    <label for="{uid}-type">Type</label>
    <select id="{uid}-type" bind:value={type} onchange={() => (initial = type === 'boolean' ? 'false' : '')}>
      {#each VARIABLE_TYPES as option (option)}<option value={option}>{option}</option>{/each}
    </select>
  </div>
  <div class="row">
    <label for="{uid}-default">Starts as</label>
    {#if type === 'boolean'}
      <select id="{uid}-default" bind:value={initial}>
        <option value="false">false</option>
        <option value="true">true</option>
      </select>
    {:else}
      <input id="{uid}-default" type="text" bind:value={initial} spellcheck="false" autocomplete="off" placeholder={String(defaultOf(type)) || 'empty'} />
    {/if}
  </div>
  <div class="row">
    <label for="{uid}-description">Note</label>
    <input id="{uid}-description" type="text" bind:value={description} autocomplete="off" placeholder="optional" />
  </div>
  {#if failure}<p class="problem" role="alert">{failure}</p>{/if}
  <div class="actions">
    <button type="submit" class="primary" disabled={!valid || busy}><Icon name="plus" size={13} /> Declare</button>
    <button type="button" onclick={oncancel}>Cancel</button>
  </div>
</form>

<style>
  .declare {
    display: grid;
    gap: var(--space-2);
    padding: var(--space-3);
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
  }
  h4 {
    margin: 0;
    font-size: var(--text-sm);
  }
  .row {
    display: grid;
    grid-template-columns: 5.5em minmax(0, 1fr);
    align-items: center;
    gap: var(--space-2);
  }
  label {
    color: var(--muted);
    font-size: var(--text-xs);
  }
  input,
  select {
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    height: 26px;
    padding: 0 var(--space-2);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font: var(--text-sm) var(--font-sans);
  }
  input[aria-invalid='true'] {
    border-color: var(--error);
  }
  .problem {
    margin: 0;
    color: var(--error);
    font-size: var(--text-xs);
  }
  .actions {
    display: flex;
    gap: var(--space-2);
  }
  button {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: var(--space-1) var(--space-3);
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  button.primary {
    background: var(--accent);
    border-color: var(--accent);
    color: var(--accent-ink);
    font-weight: 600;
  }
  button:disabled {
    opacity: 0.45;
    cursor: default;
  }
</style>
