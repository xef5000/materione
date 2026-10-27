<script lang="ts">
  /**
   * A small modal prompt — replaces window.prompt() for pack creation,
   * new resources, renames. Enter confirms; Escape cancels.
   */
  import Button from '../ui/Button.svelte';
  import Dialog from '../ui/Dialog.svelte';
  import Field from '../ui/Field.svelte';

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

<Dialog {title} onClose={onCancel}>
  <form onsubmit={submit}>
    <Field {label} {hint}>
      <!-- svelte-ignore a11y_autofocus -->
      <input bind:value {placeholder} autofocus spellcheck="false" />
    </Field>
    <div class="dialog-actions">
      <Button onclick={onCancel}>cancel</Button>
      <Button type="submit" variant="primary" disabled={!value.trim()}>ok</Button>
    </div>
  </form>
</Dialog>
