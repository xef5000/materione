<script lang="ts">
  /**
   * A button in one of four roles. `primary` is the accent: one per view, the
   * action the view exists for. `secondary` is the default outlined button,
   * `ghost` is text-weight for low-stakes actions, `danger` is for deletes.
   */
  import type { Snippet } from 'svelte';
  import type { HTMLButtonAttributes } from 'svelte/elements';

  interface Props extends HTMLButtonAttributes {
    variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
    size?: 'md' | 'sm';
    children: Snippet;
  }

  let { variant = 'secondary', size = 'md', type = 'button', class: extra = '', children, ...rest }: Props =
    $props();
  const classes = $derived(
    ['btn', variant !== 'secondary' && `btn-${variant}`, size === 'sm' && 'btn-sm', extra]
      .filter(Boolean)
      .join(' '),
  );
</script>

<button {...rest} {type} class={classes}>{@render children()}</button>
