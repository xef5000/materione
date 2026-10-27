<script lang="ts">
  /**
   * The shell: header, rail, and one visible full-area workspace. Initialized
   * workspaces remain mounted while hidden so navigation does not reset editor
   * state; active props let them gate global shortcuts.
   */
  import AppHeader from './AppHeader.svelte';
  import AdvancedView from '../views/AdvancedView.svelte';
  import AssetsView from '../views/AssetsView.svelte';
  import CommandPalette from './CommandPalette.svelte';
  import ContentView from '../views/ContentView.svelte';
  import DiagnosticsView from '../views/DiagnosticsView.svelte';
  import ServerView from '../views/ServerView.svelte';
  import StartView from '../views/StartView.svelte';
  import ActivityDock from '../dock/ActivityDock.svelte';
  import { activityLog, diagnostics, ui } from '../state.svelte';

  interface Props {
    onDisconnect: () => void;
  }

  let { onDisconnect }: Props = $props();

  type RailView = 'start' | 'content' | 'assets' | 'advanced' | 'diagnostics' | 'server';
  type RailTarget = RailView | 'activity';
  type RailEntry = { id: RailTarget; label: string; icon: string };

  const authoringViews: RailEntry[] = [
    { id: 'start', label: 'Start', icon: 'M12 3 3 10v11h6v-6h6v6h6V10z' },
    { id: 'content', label: 'Content', icon: 'M4 5.5h16v14H4zM7 2h10v3H7z' },
    { id: 'assets', label: 'Assets', icon: 'M4 4h16v16H4zM4 15l5-5 4 4 3-3 4 4M15 8.5h.01' },
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
    start: false,
    content: true,
    assets: false,
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
  function activate(target: RailTarget): void {
    if (target === 'activity') {
      ui.activityOpen = !ui.activityOpen;
      return;
    }
    ui.returnTo = null;
    ui.view = target;
  }

  // Ctrl/Cmd+K opens the palette from anywhere; the palette owns the keys while it is open.
  function onWindowKey(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      ui.paletteOpen = !ui.paletteOpen;
    }
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

<svelte:window onkeydown={onWindowKey} />

<div class="workbench">
  <AppHeader />

  <div class="main" class:rail-collapsed={ui.railCollapsed}>
    <nav class="rail" class:collapsed={ui.railCollapsed} aria-label="Workspace navigation">
      <div class="nav-group" role="group" aria-label="Authoring">
        <h2 class="group-label">Authoring</h2>
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
        <h2 class="group-label">Support</h2>
        {#each supportViews as entry (entry.id)}
          {@const count = errorCount(entry)}
          <button
            type="button"
            class:active={entry.id === 'activity' ? ui.activityOpen : ui.view === entry.id}
            title={accessibleLabel(entry)}
            aria-label={accessibleLabel(entry)}
            aria-current={entry.id !== 'activity' && ui.view === entry.id ? 'page' : undefined}
            aria-controls={entry.id === 'activity' && ui.activityOpen ? 'activity-dock' : undefined}
            aria-expanded={entry.id === 'activity' ? ui.activityOpen : undefined}
            id={entry.id === 'activity' ? 'activity-toggle' : undefined}
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
      {#if initialized.start}
        <section
          class="workspace"
          hidden={ui.view !== 'start'}
          aria-hidden={ui.view !== 'start'}
          aria-label="Start"
        >
          <StartView />
        </section>
      {/if}
      {#if ui.returnTo && ui.view === 'content'}
        <div class="return-bar">
          <button
            type="button"
            onclick={() => { ui.view = 'assets'; ui.returnTo = null; }}
          >← Back to {ui.returnTo.label}</button>
        </div>
      {/if}
      {#if initialized.content}
        <div
          class="workspace"
          hidden={ui.view !== 'content'}
          aria-hidden={ui.view !== 'content'}
        >
          <ContentView active={ui.view === 'content'} />
        </div>
      {/if}
      {#if initialized.assets}
        <section
          class="workspace"
          hidden={ui.view !== 'assets'}
          aria-hidden={ui.view !== 'assets'}
          aria-label="Assets"
        >
          <AssetsView active={ui.view === 'assets'} />
        </section>
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
          <ServerView {onDisconnect} />
        </section>
      {/if}
    </main>
  </div>

  {#if ui.activityOpen}
    <ActivityDock bind:open={ui.activityOpen} />
  {/if}

  {#if ui.paletteOpen}
    <CommandPalette onClose={() => (ui.paletteOpen = false)} />
  {/if}
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
    gap: var(--space-2);
    padding: var(--space-2) var(--space-2);
    background: var(--bg-app);
    border-right: 1px solid var(--border);
  }
  .nav-group {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }
  .support-group {
    margin-top: var(--space-2);
    padding-top: var(--space-2);
    border-top: 1px solid var(--border);
  }
  .group-label {
    margin: var(--space-1) var(--space-2) var(--space-1);
    color: var(--faint);
    font-size: var(--text-xs);
    font-weight: 600;
    letter-spacing: 0.09em;
    /* Fixed, so a collapsed rail (height: 14px) keeps every row where it was. */
    line-height: 14px;
    text-transform: uppercase;
  }
  .rail button:not(.btn) {
    position: relative;
    width: 100%;
    min-width: 0;
    min-height: 36px;
    display: flex;
    align-items: center;
    gap: var(--space-3);
    border: none;
    border-left: 2px solid transparent;
    border-radius: 0 var(--radius) var(--radius) 0;
    background: none;
    color: var(--muted);
    padding: var(--space-2) var(--space-2);
    cursor: pointer;
    text-align: left;
  }
  .rail button:not(.btn) svg {
    width: 19px;
    height: 19px;
    flex: none;
  }
  .rail button:not(.btn):hover {
    color: var(--fg);
    background: var(--bg-hover);
  }
  .rail button:not(.btn).active {
    color: var(--accent);
    border-left-color: var(--accent);
    background: var(--bg-hover);
  }
  .nav-label {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: var(--text-sm);
  }
  .rail.collapsed {
    padding-inline: var(--space-1);
  }
  .rail.collapsed .group-label {
    height: 14px;
    overflow: hidden;
    color: transparent;
    white-space: nowrap;
  }
  .rail.collapsed .nav-label {
    display: none;
  }
  .rail.collapsed button:not(.btn) {
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
    font-size: var(--text-xs);
    font-weight: 700;
    line-height: 1;
    padding: var(--space-half) var(--space-1);
    border-radius: var(--radius);
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
  .viewhost {
    display: flex;
    flex-direction: column;
  }
  .viewhost > .workspace {
    flex: 1 1 0;
    height: auto;
  }
  .return-bar {
    flex: none;
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
    background: var(--bg-panel);
  }
  .return-bar button:not(.btn) {
    background: none;
    border: none;
    color: var(--accent);
    cursor: pointer;
    font-size: var(--text-sm);
    padding: 0;
  }
  .panel {
    overflow-y: auto;
    overflow-x: hidden;
    padding: var(--space-4) var(--space-5);
  }
  .panel h2 {
    margin: 0 0 var(--space-3);
    font-size: var(--text-lg);
    font-weight: 600;
  }
  @media (max-width: 700px) {
    .main:not(.rail-collapsed) {
      grid-template-columns: minmax(138px, 38vw) minmax(0, 1fr);
    }
  }
</style>
