<script lang="ts">
  /**
   * A labelled control with an optional hint and a validation line. The label
   * wraps the control, so the control is named by its label without an id.
   */
  import type { Snippet } from 'svelte';

  interface Props {
    label: string;
    hint?: string;
    error?: string;
    children: Snippet;
  }

  let { label, hint = '', error = '', children }: Props = $props();
</script>

<label class="field">
  <span class="field-label">{label}</span>
  {@render children()}
  {#if error}<span class="field-error" role="alert">{error}</span>{:else if hint}<span class="field-hint">{hint}</span>{/if}
</label>
