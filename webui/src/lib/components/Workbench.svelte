<script lang="ts">
  /**
   * The shell: header, rail, and one visible full-area workspace. Initialized
   * workspaces remain mounted while hidden so navigation does not reset editor
   * state; active props let them gate global shortcuts.
   */
  import AppHeader from './AppHeader.svelte';
  import AdvancedView from '../views/AdvancedView.svelte';
  import ContentView from '../views/ContentView.svelte';
  import DiagnosticsView from '../views/DiagnosticsView.svelte';
  import ServerView from '../views/ServerView.svelte';
  import ActivityDock from '../dock/ActivityDock.svelte';
  import { activityLog, connection, diagnostics, server, ui } from '../state.svelte';

  interface Props {
    browserLabel: string;
    onDisconnect: () => void;
  }

  let { browserLabel, onDisconnect }: Props = $props();

  type RailView = 'content' | 'advanced' | 'diagnostics' | 'server';
  type RailTarget = RailView | 'activity';
  type RailEntry = { id: RailTarget; label: string; icon: string };

  const authoringViews: RailEntry[] = [
    { id: 'content', label: 'Content', icon: 'M4 5.5h16v14H4zM7 2h10v3H7z' },
    {
      id: 'advanced',
      label: 'Files',
      icon: 'M3 4.5A1.5 1.5 0 0 1 4.5 3h4l2 2h9A1.5 1.5 0 0 1 21 6.5v13a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19.5z',
    },
  ];

  const supportViews: RailEntry[] = [
    {
      id: 'diagnostics',
      label: 'Problems',
      icon: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm0 4v6m0 3.5v.5',
    },
    { id: 'activity', label: 'Activity', icon: 'M3 12h4l3-8 4 16 3-8h4' },
    {
      id: 'server',
      label: 'Server',
      icon: 'M4 4h16v6H4zm0 10h16v6H4zM7.5 7h.01M7.5 17h.01',
    },
  ];

  let initialized = $state<Record<RailView, boolean>>({
    content: true,
    advanced: false,
    diagnostics: false,
    server: false,
  });

  $effect(() => {
    const activeView = ui.view as RailView;
    if (!initialized[activeView]) initialized[activeView] = true;
  });

  const problems = $derived(
    diagnostics.entries.filter((entry) => entry.severity.toLowerCase() === 'error').length,
  );
  const activityErrors = $derived(
    activityLog.entries.filter((entry) => entry.kind === 'error').length,
  );
  const connectionStatus = $derived(
    connection.connected
      ? { label: browserLabel, title: 'Editor session active' }
      : connection.error
        ? { label: 'Connection lost', title: connection.error }
        : { label: 'Disconnected', title: 'No active editor session' },
  );

  function activate(target: RailTarget): void {
    if (target === 'activity') {
      ui.activityOpen = true;
      return;
    }
    ui.view = target;
  }

  function errorCount(entry: RailEntry): number {
    if (entry.id === 'diagnostics') return problems;
    if (entry.id === 'activity') return activityErrors;
    return 0;
  }

  function accessibleLabel(entry: RailEntry): string {
    const count = errorCount(entry);
    return count > 0 ? `${entry.label}, ${count} ${count === 1 ? 'error' : 'errors'}` : entry.label;
  }
</script>

<div class="workbench">
  <AppHeader />

  <div class="main" class:rail-collapsed={ui.railCollapsed}>
    <nav class="rail" class:collapsed={ui.railCollapsed} aria-label="Workspace navigation">
      <div class="nav-group" role="group" aria-label="Authoring">
        <p class="group-label">Authoring</p>
        {#each authoringViews as entry (entry.id)}
          <button
            type="button"
            class:active={ui.view === entry.id}
            title={accessibleLabel(entry)}
            aria-label={accessibleLabel(entry)}
            aria-current={ui.view === entry.id ? 'page' : undefined}
            onclick={() => activate(entry.id)}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
              <path d={entry.icon} />
            </svg>
            <span class="nav-label">{entry.label}</span>
          </button>
        {/each}
      </div>

      <div class="nav-group support-group" role="group" aria-label="Support">
        <p class="group-label">Support</p>
        {#each supportViews as entry (entry.id)}
          {@const count = errorCount(entry)}
          <button
            type="button"
            class:active={entry.id === 'activity' ? ui.activityOpen : ui.view === entry.id}
            title={accessibleLabel(entry)}
            aria-label={accessibleLabel(entry)}
            aria-current={entry.id !== 'activity' && ui.view === entry.id ? 'page' : undefined}
            aria-controls={entry.id === 'activity' ? 'activity-dock' : undefined}
            aria-expanded={entry.id === 'activity' ? ui.activityOpen : undefined}
            onclick={() => activate(entry.id)}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
              <path d={entry.icon} />
            </svg>
            <span class="nav-label">{entry.label}</span>
            {#if count > 0}
              <em class="badge" aria-hidden="true">{count > 9 ? '9+' : count}</em>
            {/if}
          </button>
        {/each}
      </div>

      <button
        class="rail-toggle"
        type="button"
        aria-label={ui.railCollapsed ? 'Expand navigation labels' : 'Collapse navigation labels'}
        aria-expanded={!ui.railCollapsed}
        title={ui.railCollapsed ? 'Expand navigation labels' : 'Collapse navigation labels'}
        onclick={() => (ui.railCollapsed = !ui.railCollapsed)}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
          <path d={ui.railCollapsed ? 'm9 18 6-6-6-6' : 'm15 18-6-6 6-6'} />
        </svg>
        <span class="nav-label">{ui.railCollapsed ? 'Expand labels' : 'Collapse labels'}</span>
      </button>
    </nav>

    <main class="viewhost">
      {#if initialized.content}
        <div
          class="workspace"
          hidden={ui.view !== 'content'}
          aria-hidden={ui.view !== 'content'}
        >
          <ContentView active={ui.view === 'content'} />
        </div>
      {/if}
      {#if initialized.advanced}
        <div
          class="workspace"
          hidden={ui.view !== 'advanced'}
          aria-hidden={ui.view !== 'advanced'}
        >
          <AdvancedView active={ui.view === 'advanced'} />
        </div>
      {/if}
      {#if initialized.diagnostics}
        <section
          class="workspace panel"
          hidden={ui.view !== 'diagnostics'}
          aria-hidden={ui.view !== 'diagnostics'}
          aria-label="Problems"
        >
          <h2>Problems</h2>
          <DiagnosticsView />
        </section>
      {/if}
      {#if initialized.server}
        <section
          class="workspace panel"
          hidden={ui.view !== 'server'}
          aria-hidden={ui.view !== 'server'}
          aria-label="Server"
        >
          <ServerView />
        </section>
      {/if}
    </main>
  </div>

  <ActivityDock bind:open={ui.activityOpen} />

  <footer class="statusbar">
    <span class="brand">materione <em>editor</em></span>
    <span
      class="conn"
      class:ok={connection.connected}
      class:error={!connection.connected}
      title={connectionStatus.title}
    >● {connectionStatus.label}</span>
    {#if connection.lastReload}
      <span class="reload" title="latest observed pipeline outcome">{connection.lastReload}</span>
    {/if}
    <span class="spacer"></span>
    {#if server.version}
      <span class="version">server {server.version}</span>
    {/if}
    <button class="ghost" type="button" onclick={onDisconnect}>disconnect</button>
  </footer>
</div>

<style>
  .workbench {
    flex: 1;
    min-height: 0;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
  .main {
    flex: 1 1 0;
    min-height: 0;
    min-width: 0;
    display: grid;
    grid-template-columns: 188px minmax(0, 1fr);
  }
  .main.rail-collapsed {
    grid-template-columns: 48px minmax(0, 1fr);
  }
  .rail {
    min-height: 0;
    min-width: 0;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 8px 6px;
    background: var(--bg-app);
    border-right: 1px solid var(--border);
  }
  .nav-group {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }
  .support-group {
    margin-top: 6px;
    padding-top: 8px;
    border-top: 1px solid var(--border);
  }
  .group-label {
    margin: 3px 8px 4px;
    color: var(--faint);
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 0.09em;
    line-height: 1.4;
    text-transform: uppercase;
  }
  .rail button {
    position: relative;
    width: 100%;
    min-width: 0;
    min-height: 36px;
    display: flex;
    align-items: center;
    gap: 10px;
    border: none;
    border-left: 2px solid transparent;
    border-radius: 0 var(--radius) var(--radius) 0;
    background: none;
    color: var(--muted);
    padding: 7px 9px;
    cursor: pointer;
    text-align: left;
  }
  .rail button svg {
    width: 19px;
    height: 19px;
    flex: none;
  }
  .rail button:hover {
    color: var(--fg);
    background: var(--bg-hover);
  }
  .rail button.active {
    color: var(--accent);
    border-left-color: var(--accent);
    background: var(--bg-hover);
  }
  .nav-label {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 12.5px;
  }
  .rail.collapsed {
    padding-inline: 4px;
  }
  .rail.collapsed .group-label,
  .rail.collapsed .nav-label {
    display: none;
  }
  .rail.collapsed button {
    justify-content: center;
    padding-inline: 0;
  }
  .rail-toggle {
    margin-top: auto;
    flex: none;
  }
  .badge {
    position: absolute;
    right: 4px;
    top: 3px;
    font-style: normal;
    font-size: 9px;
    font-weight: 700;
    line-height: 1;
    padding: 2px 3px;
    border-radius: 6px;
    background: var(--error);
    color: var(--bg-app);
  }
  .viewhost {
    min-width: 0;
    min-height: 0;
    overflow: hidden;
    background: var(--bg-app);
  }
  .workspace {
    width: 100%;
    height: 100%;
    min-width: 0;
    min-height: 0;
    overflow: hidden;
    box-sizing: border-box;
  }
  .workspace[hidden] {
    display: none;
  }
  .panel {
    overflow-y: auto;
    overflow-x: hidden;
    padding: 14px 18px;
  }
  .panel h2 {
    margin: 0 0 12px;
    font-size: 16px;
    font-weight: 600;
  }
  .statusbar {
    display: flex;
    flex: none;
    min-width: 0;
    align-items: center;
    gap: 14px;
    border-top: 1px solid var(--border);
    background: var(--bg-panel);
    padding: 4px 12px;
    font-size: 12px;
    color: var(--muted);
  }
  .brand {
    flex: none;
  }
  .brand em {
    color: var(--accent);
    font-style: normal;
  }
  .conn.ok {
    min-width: 0;
    overflow: hidden;
    color: var(--ok);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .conn.error {
    color: var(--error);
  }
  .reload {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 40vw;
  }
  .spacer {
    flex: 1;
  }
  .ghost {
    flex: none;
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
  @media (max-width: 700px) {
    .main:not(.rail-collapsed) {
      grid-template-columns: minmax(138px, 38vw) minmax(0, 1fr);
    }
    .statusbar {
      gap: 7px;
      padding-inline: 8px;
    }
    .reload {
      max-width: 24vw;
    }
    .version {
      display: none;
    }
  }
</style>
