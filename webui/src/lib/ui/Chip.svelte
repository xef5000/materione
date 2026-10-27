<script lang="ts">
  /**
   * A small status or category label. `tone` carries meaning (ok, warn,
   * error); a neutral chip only labels.
   */
  import type { Snippet } from 'svelte';
  import type { HTMLAttributes } from 'svelte/elements';

  interface Props extends HTMLAttributes<HTMLSpanElement> {
    tone?: 'neutral' | 'accent' | 'ok' | 'warn' | 'error';
    children: Snippet;
  }

  let { tone = 'neutral', class: extra = '', children, ...rest }: Props = $props();
  const classes = $derived(
    ['chip', tone !== 'neutral' && `chip-${tone}`, extra].filter(Boolean).join(' '),
  );
</script>

<span {...rest} class={classes}>{@render children()}</span>
