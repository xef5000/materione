<script lang="ts">
  /** A right-click menu anchored at the cursor; closes on any outside click. */
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

<div class="menu" style:left="{x}px" style:top="{y}px" role="menu">
  {#each items as item (item.label)}
    <button
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
  .menu {
    position: fixed;
    z-index: 70;
    min-width: 160px;
    background: var(--bg-panel);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: 4px;
    box-shadow: 0 8px 28px rgba(0, 0, 0, 0.45);
  }
  .menu button {
    display: block;
    width: 100%;
    text-align: left;
    background: none;
    border: none;
    color: inherit;
    font-size: 12.5px;
    padding: 5px 10px;
    border-radius: 4px;
    cursor: pointer;
  }
  .menu button:hover {
    background: var(--bg-raised);
  }
  .menu button.danger {
    color: var(--error);
  }
</style>
