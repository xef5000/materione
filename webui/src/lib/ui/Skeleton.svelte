<script lang="ts">
  /**
   * A loading placeholder: shimmering bars in the shape the content will take, so the
   * layout is in place before the data is. The label is the status line for screen
   * readers; sighted users see the bars, not a bare ellipsis. Under reduced motion the
   * bars stay still (app.css, "Motion").
   */
  interface Props {
    label: string;
    /** How many bars; they step down in width so the placeholder reads as text. */
    lines?: number;
  }

  let { label, lines = 1 }: Props = $props();
  const STEPS = ['100%', '86%', '64%'];
  const bars = $derived(Array.from({ length: lines }, (_, index) => STEPS[index % STEPS.length]));
</script>

<span class="loading" role="status" aria-live="polite">
  <span class="sr-only">{label}</span>
  {#each bars as width, index (index)}
    <span class="skeleton" style:width={width} aria-hidden="true"></span>
  {/each}
</span>

<style>
  .loading {
    display: grid;
    gap: var(--space-2);
    min-width: 0;
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
  }
</style>
