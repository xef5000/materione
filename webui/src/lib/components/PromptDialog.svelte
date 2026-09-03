<script lang="ts">
  /**
   * A small modal prompt — replaces window.prompt() for pack creation,
   * new resources, renames. Enter confirms; Escape cancels.
   */
  interface Props {
    title: string;
    label: string;
    initial?: string;
    placeholder?: string;
    hint?: string;
    onOk: (value: string) => void;
    onCancel: () => void;
  }

  let { title, label, initial = '', placeholder = '', hint = '', onOk, onCancel }: Props =
    $props();

  let value = $state('');

  $effect(() => {
    value = initial;
  });

  function submit(event: SubmitEvent) {
    event.preventDefault();
    const trimmed = value.trim();
    if (trimmed) {
      onOk(trimmed);
    }
  }
</script>

<div class="scrim" role="presentation" onclick={(event) => {
  if (event.target === event.currentTarget) onCancel();
}}>
  <div class="dialog" role="dialog" aria-modal="true" aria-label={title} tabindex="-1">
    <form onsubmit={submit}>
      <h3>{title}</h3>
      <label>
        <span>{label}</span>
        <!-- svelte-ignore a11y_autofocus -->
        <input bind:value {placeholder} autofocus spellcheck="false" />
      </label>
      {#if hint}<small>{hint}</small>{/if}
      <div class="actions">
        <button type="button" class="ghost" onclick={onCancel}>cancel</button>
        <button type="submit" class="primary" disabled={!value.trim()}>ok</button>
      </div>
    </form>
  </div>
</div>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: rgba(8, 10, 14, 0.6);
    display: grid;
    place-items: center;
    z-index: 60;
  }
  .dialog {
    width: min(420px, calc(100vw - 48px));
    background: var(--bg-panel);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-lg);
    padding: 16px 18px;
    box-shadow: 0 12px 40px rgba(0, 0, 0, 0.45);
  }
  .dialog form {
    margin: 0;
  }
  h3 {
    margin: 0 0 10px;
    font-size: 14px;
  }
  label span {
    display: block;
    font-size: 12px;
    color: var(--muted);
    margin-bottom: 4px;
  }
  input {
    width: 100%;
    box-sizing: border-box;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: 7px 9px;
    font: 13px var(--mono);
  }
  small {
    display: block;
    color: var(--faint);
    font-size: 11px;
    margin-top: 6px;
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 14px;
  }
  button {
    border-radius: var(--radius);
    padding: 5px 14px;
    cursor: pointer;
    font-size: 12.5px;
  }
  .primary {
    background: var(--primary);
    border: none;
    color: white;
  }
  .ghost {
    background: none;
    border: 1px solid var(--border-strong);
    color: inherit;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
</style>
