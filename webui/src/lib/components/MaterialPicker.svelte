<script lang="ts">
  import { materialLabel, matchingMaterials } from '../materials';
  interface Props {
    id: string;
    label: string;
    value: string;
    choices?: readonly string[];
    required?: boolean;
    onChange: (value: string) => void;
  }
  let { id, label, value, choices, required = false, onChange }: Props = $props();
  let open = $state(false);
  let query = $state('');
  let selected = $state(0);
  const matches = $derived(matchingMaterials(choices ?? [], query));
  const visible = $derived(matches.slice(0, 60));
  const uniqueId = $props.id();
  const listId = `${uniqueId}-materials`;
  let root: HTMLDivElement | undefined = $state();

  function show() {
    if (open) return;
    open = true;
    query = '';
    selected = 0;
  }
  function choose(next: string) {
    onChange(next);
    open = false;
    query = '';
  }
  function keyboard(event: KeyboardEvent) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!open) show();
      else selected = Math.max(0, Math.min(visible.length - 1, selected + (event.key === 'ArrowDown' ? 1 : -1)));
      root?.querySelector(`#${CSS.escape(listId)} [data-index="${selected}"]`)?.scrollIntoView({ block: 'nearest' });
    } else if (event.key === 'Enter' && open) {
      event.preventDefault();
      const next = visible[selected];
      if (next) choose(next);
    } else if (event.key === 'Escape' && open) {
      event.preventDefault();
      event.stopPropagation();
      open = false;
      query = '';
    }
  }
</script>

<div class="material-picker" bind:this={root} onfocusout={(event) => {
  if (!(event.relatedTarget instanceof Node) || !root?.contains(event.relatedTarget)) open = false;
}}>
  {#if choices?.length}
    <input {id} data-field-control role="combobox" aria-label={label} aria-expanded={open}
      aria-controls={listId} aria-autocomplete="list" autocomplete="off" spellcheck="false"
      aria-activedescendant={open && visible[selected] ? `${listId}-${selected}` : undefined}
      value={open ? query : materialLabel(value)}
      placeholder="Search this server's materials..."
      onfocus={show} onclick={show} onkeydown={keyboard}
      oninput={(event) => { query = event.currentTarget.value; selected = 0; open = true; }} />
    {#if open}
      <div class="options">
        <div class="results" role="listbox" id={listId} aria-label={`${label} choices`}>
          {#each visible as option, index (option)}
            <button type="button" role="option" id={`${listId}-${index}`} data-index={index}
              tabindex="-1" aria-selected={index === selected}
              onpointerdown={(event) => event.preventDefault()} onclick={() => choose(option)}>
              <span>{materialLabel(option)}</span><code>{option}</code>
            </button>
          {/each}
        </div>
        {#if !matches.length}<p>No matching material on this server.</p>
        {:else if matches.length > visible.length}<p>Showing {visible.length} of {matches.length}.</p>{/if}
      </div>
    {/if}
  {:else}
    <input {id} data-field-control aria-label={label} value={value} spellcheck="false"
      oninput={(event) => onChange(event.currentTarget.value)} />
    <small>Server material choices are unavailable. Enter a technical ID; the server validates it on apply.</small>
  {/if}
  <div class="current">
    <code>{value || 'No material selected'}</code>
    {#if !required && value}<button type="button" onclick={() => choose('')}>Clear</button>{/if}
  </div>
  {#if value && choices?.length && !choices.includes(value)}
    <small>The current ID is not in this server's material list. It is preserved until you choose another.</small>
  {/if}
</div>

<style>
  .material-picker { position: relative; min-width: 0; display: grid; gap: var(--space-1); }
  input { width: 100%; min-width: 0; box-sizing: border-box; background: var(--bg-input); border: 1px solid var(--border-strong); border-radius: var(--radius); padding: var(--space-2) var(--space-2); font-size: var(--text-base); }
  .options { position: absolute; top: 33px; left: 0; right: 0; z-index: 3; background: var(--bg-raised); border: 1px solid var(--border-strong); border-radius: var(--radius); }
  .results { max-height: 230px; overflow-y: auto; }
  .results button:not(.btn) { display: grid; gap: var(--space-half); background: none; border: none; width: 100%; text-align: left; cursor: pointer; padding: var(--space-2) var(--space-2); font-size: var(--text-sm); }
  .results button:not(.btn):hover, .results button:not(.btn)[aria-selected='true'] { background: var(--bg-input); }
  code { font: var(--text-xs) var(--mono); color: var(--muted); overflow-wrap: anywhere; }
  small, p { font-size: var(--text-xs); color: var(--muted); margin: 0; }
  p { padding: var(--space-2) var(--space-2); }
  .current { display: flex; justify-content: space-between; gap: var(--space-1); }
  .current button:not(.btn) { padding: 0; font-size: var(--text-xs); background: none; border: none; color: var(--accent); cursor: pointer; }
</style>
