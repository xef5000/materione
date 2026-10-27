<script lang="ts">
  /**
   * One icon from the editor's set (icons.ts). Decorative by default — hidden
   * from assistive tech, so the button or label it sits in carries the name.
   * Pass `label` only when the icon is the one thing that says what it means.
   * It draws in `currentColor`, so it takes the surrounding text colour.
   */
  import { icons, type IconName } from './icons';

  interface Props {
    name: IconName;
    size?: number;
    label?: string;
    class?: string;
  }

  let { name, size = 16, label, class: className }: Props = $props();
  const Glyph = $derived(icons[name]);
</script>

{#if label}
  <Glyph {size} class={className} role="img" aria-label={label} />
{:else}
  <Glyph {size} class={className} aria-hidden="true" />
{/if}

<style>
  /* Lucide renders the svg itself, outside this component's scope, so the
     alignment every icon shares is global — keyed on its `lucide-icon` class. */
  :global(svg.lucide-icon) {
    flex: none;
    vertical-align: middle;
  }
</style>
