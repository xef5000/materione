<script lang="ts">
  /**
   * The shell: header, rail, and one full-area view. Each rail view owns the
   * entire main area — the tab bar and editors belong to Advanced Files, not
   * to the shell, so a browsing surface is not squeezed into a sidebar.
   */
  import AppHeader from './AppHeader.svelte';
  import AdvancedView from '../views/AdvancedView.svelte';
  import ContentView from '../views/ContentView.svelte';
  import DiagnosticsView from '../views/DiagnosticsView.svelte';
  import ServerView from '../views/ServerView.svelte';
  import ActivityDock from '../dock/ActivityDock.svelte';
  import { connection, diagnostics, server, ui } from '../state.svelte';

  interface Props {
    browserLabel: string;
    onDisconnect: () => void;
  }

  let { browserLabel, onDisconnect }: Props = $props();

  type RailView = 'content' | 'advanced' | 'diagnostics' | 'server';

  const views: { id: RailView; label: string; icon: string }[] = [
    { id: 'content', label: 'Content', icon: 'M4 5.5h16v14H4zM7 2h10v3H7z' },
    {
      id: 'advanced',
      label: 'Advanced Files',
      icon: 'M3 4.5A1.5 1.5 0 0 1 4.5 3h4l2 2h9A1.5 1.5 0 0 1 21 6.5v13a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19.5z',
    },
    {
      id: 'diagnostics',
      label: 'Diagnostics',
      icon: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm0 4v6m0 3.5v.5',
    },
    {
      id: 'server',
      label: 'Server',
      icon: 'M4 4h16v6H4zm0 10h16v6H4zM7.5 7h.01M7.5 17h.01',
    },
  ];

  const problems = $derived(
    diagnostics.entries.filter((entry) => entry.severity === 'ERROR').length,
  );
</script>

<div class="workbench">
  <AppHeader />

  <div class="main">
    <nav class="rail" aria-label="views">
      {#each views as entry (entry.id)}
        <button
          class:active={ui.view === entry.id}
          title={entry.label}
          aria-label={entry.label}
          onclick={() => (ui.view = entry.id)}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
            <path d={entry.icon} />
          </svg>
          {#if entry.id === 'diagnostics' && problems > 0}
            <em class="badge">{problems > 9 ? '9+' : problems}</em>
          {/if}
        </button>
      {/each}
    </nav>

    <main class="viewhost">
      {#if ui.view === 'content'}
        <ContentView />
      {:else if ui.view === 'advanced'}
        <AdvancedView />
      {:else if ui.view === 'diagnostics'}
        <div class="panel"><DiagnosticsView /></div>
      {:else}
        <div class="panel"><ServerView /></div>
      {/if}
    </main>
  </div>

  <ActivityDock />

  <footer class="statusbar">
    <span class="brand">materione <em>editor</em></span>
    <span class="conn ok" title="session active">● {browserLabel}</span>
    {#if connection.lastReload}
      <span class="reload" title="last pipeline outcome">{connection.lastReload}</span>
    {/if}
    <span class="spacer"></span>
    {#if server.version}
      <span class="version">server {server.version}</span>
    {/if}
    <button class="ghost" onclick={onDisconnect}>disconnect</button>
  </footer>
</div>

<style>
  .workbench {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-rows: auto minmax(0, 1fr) auto auto;
  }
  .main {
    min-height: 0;
    min-width: 0;
    display: grid;
    grid-template-columns: 44px minmax(0, 1fr);
  }
  .rail {
    background: var(--bg-app);
    border-right: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding-top: 8px;
  }
  .rail button {
    position: relative;
    background: none;
    border: none;
    border-left: 2px solid transparent;
    color: var(--muted);
    padding: 9px 0;
    cursor: pointer;
    display: grid;
    place-items: center;
  }
  .rail button svg {
    width: 20px;
    height: 20px;
  }
  .rail button:hover {
    color: var(--fg);
  }
  .rail button.active {
    color: var(--accent);
    border-left-color: var(--accent);
    background: var(--bg-hover);
  }
  .badge {
    position: absolute;
    right: 5px;
    top: 5px;
    font-style: normal;
    font-size: 9px;
    line-height: 1;
    padding: 2px 3px;
    border-radius: 6px;
    background: var(--error, #b4413c);
    color: white;
  }
  .viewhost {
    min-width: 0;
    min-height: 0;
    overflow: hidden;
    background: var(--bg-app);
  }
  .panel {
    height: 100%;
    overflow-y: auto;
    overflow-x: hidden;
    padding: 14px 18px;
  }
  .statusbar {
    display: flex;
    align-items: center;
    gap: 14px;
    border-top: 1px solid var(--border);
    background: var(--bg-panel);
    padding: 4px 12px;
    font-size: 12px;
    color: var(--muted);
  }
  .brand em {
    color: var(--accent);
    font-style: normal;
  }
  .conn.ok {
    color: var(--ok);
  }
  .reload {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 40vw;
  }
  .spacer {
    flex: 1;
  }
  .ghost {
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: 2px 10px;
    cursor: pointer;
    color: inherit;
  }
  .ghost:hover {
    color: var(--fg);
  }
</style>
