<script lang="ts">
  /**
   * A list of short strings as removable chips with one input: type a value,
   * press Enter (or comma) to add it, Backspace on an empty input removes the
   * last. The value is the plain YAML list. Entries that are not strings are
   * not chips; they ride along unchanged after the chips.
   */
  import Icon from '../components/Icon.svelte';

  interface Props {
    value: unknown;
    label: string;
    placeholder?: string;
    onChange: (value: unknown) => void;
  }

  let { value, label, placeholder = 'Type and press Enter', onChange }: Props = $props();

  let draft = $state('');
  const list = $derived(Array.isArray(value) ? value : []);
  const chips = $derived(list.filter((entry): entry is string => typeof entry === 'string'));
  const opaque = $derived(list.filter((entry) => typeof entry !== 'string'));

  function commit(next: string[]) {
    onChange([...next, ...opaque]);
  }

  function add() {
    const text = draft.trim();
    draft = '';
    if (!text || chips.includes(text)) return;
    commit([...chips, text]);
  }

  function remove(index: number) {
    commit(chips.filter((_, position) => position !== index));
  }

  function keydown(event: KeyboardEvent) {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault();
      add();
    } else if (event.key === 'Backspace' && draft === '' && chips.length > 0) {
      remove(chips.length - 1);
    }
  }
</script>

<div class="chips" data-field-control tabindex="-1">
  <ul aria-label={label}>
    {#each chips as chip, index (chip)}
      <li class="chip-item" data-chip={chip}>
        <code>{chip}</code>
        <button type="button" aria-label={`Remove ${chip}`} onclick={() => remove(index)}>
          <Icon name="x" size={12} />
        </button>
      </li>
    {/each}
    <li class="entry">
      <input type="text" aria-label={`Add to ${label}`} {placeholder} spellcheck="false"
        value={draft} oninput={(event) => (draft = event.currentTarget.value)}
        onkeydown={keydown} onblur={add} />
    </li>
  </ul>
  {#if opaque.length > 0}
    <p class="note">{opaque.length} entr{opaque.length === 1 ? 'y' : 'ies'} the chips can't show {opaque.length === 1 ? 'is' : 'are'} kept as written; edit in Files.</p>
  {/if}
</div>

<style>
  .chips {
    min-width: 0;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: var(--space-1);
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1);
    background: var(--bg-input);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
  }
  .chip-item {
    display: flex;
    align-items: center;
    gap: var(--space-1);
    padding: var(--space-half) var(--space-2);
    background: var(--bg-raised);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-pill);
    font-size: var(--text-sm);
  }
  code {
    font: var(--text-sm) var(--mono);
  }
  .chip-item button {
    display: grid;
    place-items: center;
    width: 16px;
    height: 16px;
    padding: 0;
    background: none;
    border: none;
    color: var(--muted);
    cursor: pointer;
  }
  .chip-item button:hover {
    color: var(--fg);
  }
  .entry {
    flex: 1;
    min-width: 10em;
  }
  .entry input {
    width: 100%;
    background: none;
    border: none;
    color: inherit;
    font-size: var(--text-base);
    padding: var(--space-half) var(--space-1);
    outline: none;
  }
  .note {
    margin: var(--space-1) 0 0;
    font-size: var(--text-xs);
    color: var(--warn);
  }
</style>
