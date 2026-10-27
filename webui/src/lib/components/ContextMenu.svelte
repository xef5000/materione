<script lang="ts">
  /** A right-click menu anchored at the cursor; closes on any outside click. Opens with the .menu pop-in (app.css) and fades out. */
  import { fade } from 'svelte/transition';
  import { motionMs } from '../motion';
  export interface MenuItem {
    label: string;
    danger?: boolean;
    run: () => void;
  }

  interface Props {
    x: number;
    y: number;
    items: MenuItem[];
    onClose: () => void;
  }

  let { x, y, items, onClose }: Props = $props();

  $effect(() => {
    const dismiss = () => onClose();
    window.addEventListener('click', dismiss);
    window.addEventListener('blur', dismiss);
    return () => {
      window.removeEventListener('click', dismiss);
      window.removeEventListener('blur', dismiss);
    };
  });
</script>

<div
  class="menu context-menu"
  style:left="{x}px"
  style:top="{y}px"
  role="menu"
  out:fade|global={{ duration: motionMs('fast') }}
>
  {#each items as item (item.label)}
    <button
      class="menu-item"
      class:danger={item.danger}
      role="menuitem"
      onclick={(event) => {
        event.stopPropagation();
        item.run();
        onClose();
      }}
    >
      {item.label}
    </button>
  {/each}
</div>

<style>
  .context-menu {
    position: fixed;
    z-index: 70;
  }
  .menu-item.danger {
    color: var(--error);
  }
</style>
