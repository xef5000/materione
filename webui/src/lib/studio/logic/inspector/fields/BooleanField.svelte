<script lang="ts">
  /** A boolean parameter: a checkbox. The text spellings `true` and `false` read as booleans; the field writes a real one. */
  interface Props {
    value: unknown;
    label: string;
    id?: string;
    disabled?: boolean;
    onchange: (value: unknown) => void;
  }

  let { value, label, id, disabled = false, onchange }: Props = $props();
  const checked = $derived(value === true || value === 'true');
</script>

<label class="toggle">
  <input {id} type="checkbox" {checked} {disabled} aria-label={label} onchange={(event) => onchange(event.currentTarget.checked)} />
  <span>{checked ? 'Yes' : 'No'}</span>
</label>

<style>
  .toggle {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    color: var(--muted);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  input {
    width: 16px;
    height: 16px;
    margin: 0;
    accent-color: var(--accent);
  }
</style>
