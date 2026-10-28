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
  import Icon from './Icon.svelte';
  import type { IconName } from './icons';
  import { activityLog, diagnostics, ui } from '../state.svelte';

  interface Props {
    onDisconnect: () => void;
  }

  let { onDisconnect }: Props = $props();

  type RailView = 'start' | 'content' | 'assets' | 'advanced' | 'diagnostics' | 'server';
  type RailTarget = RailView | 'activity';
  type RailEntry = { id: RailTarget; label: string; icon: IconName };

  const authoringViews: RailEntry[] = [
    { id: 'start', label: 'Start', icon: 'house' },
    { id: 'content', label: 'Content', icon: 'layers' },
    { id: 'assets', label: 'Assets', icon: 'image' },
    { id: 'advanced', label: 'Files', icon: 'folder' },
  ];

  const supportViews: RailEntry[] = [
    { id: 'diagnostics', label: 'Problems', icon: 'triangle-alert' },
    { id: 'activity', label: 'Activity', icon: 'activity' },
    { id: 'server', label: 'Server', icon: 'server' },
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
  // Ctrl+Shift+K is the rich-text editor's obfuscated shortcut, so a handled or shifted press is not ours.
  function onWindowKey(event: KeyboardEvent): void {
    if (event.defaultPrevented || event.shiftKey || event.altKey) return;
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
            <Icon name={entry.icon} size={19} />
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
            <Icon name={entry.icon} size={19} />
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
        <Icon name={ui.railCollapsed ? 'chevron-right' : 'chevron-left'} size={19} />
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
          ><Icon name="arrow-left" size={14} /> Back to {ui.returnTo.label}</button>
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
    transition:
      background-color var(--motion-fast) var(--ease-standard),
      color var(--motion-fast) var(--ease-standard),
      border-color var(--motion-fast) var(--ease-standard);
  }
  .rail button:not(.btn) :global(svg) {
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
