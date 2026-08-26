<script lang="ts">
  import { closeTab, workspace } from '../state.svelte';
</script>

{#if workspace.tabs.length > 0}
  <div class="strip" role="tablist">
    {#each workspace.tabs as tab (tab.key)}
      {@const label = tab.kind === 'doc' ? tab.id : tab.kind === 'asset' ? tab.name : 'settings'}
      {@const title = tab.kind === 'settings' ? 'plugin settings' : tab.path}
      {@const dirty = tab.kind !== 'asset' && tab.dirty}
      <div
        class="tab"
        class:active={tab.key === workspace.activeKey}
        role="tab"
        aria-selected={tab.key === workspace.activeKey}
        {title}
      >
        <button class="main" onclick={() => (workspace.activeKey = tab.key)}>
          {#if dirty}<span class="dot" title="unsaved changes"></span>{/if}
          <span class="label">{label}</span>
        </button>
        <button class="close" aria-label="close {label}" onclick={() => closeTab(tab.key)}>
          ✕
        </button>
      </div>
    {/each}
  </div>
{/if}

<style>
  .strip {
    display: flex;
    background: var(--bg-app);
    border-bottom: 1px solid var(--border);
    overflow-x: auto;
  }
  .tab {
    display: flex;
    align-items: center;
    background: none;
    border-right: 1px solid var(--border);
    border-top: 2px solid transparent;
    white-space: nowrap;
  }
  .tab:hover {
    background: var(--bg-hover);
  }
  .tab.active {
    background: var(--bg-panel);
    border-top-color: var(--accent);
  }
  .tab .main {
    display: flex;
    align-items: center;
    gap: 7px;
    background: none;
    border: none;
    color: var(--muted);
    font-size: 12.5px;
    padding: 7px 6px 7px 12px;
    cursor: pointer;
  }
  .tab.active .main {
    color: var(--fg);
  }
  .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--warn);
    flex: none;
  }
  .close {
    background: none;
    border: none;
    color: var(--faint);
    font-size: 11px;
    padding: 3px 7px;
    margin-right: 4px;
    border-radius: 4px;
    cursor: pointer;
  }
  .close:hover {
    color: var(--fg);
    background: var(--bg-raised);
  }
</style>
