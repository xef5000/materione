<script lang="ts">
  import TabBar from './TabBar.svelte';
  import DocEditor from './DocEditor.svelte';
  import AssetPreviewTab from './AssetPreviewTab.svelte';
  import SettingsTab from './SettingsTab.svelte';
  import ExplorerView from '../views/ExplorerView.svelte';
  import DiagnosticsView from '../views/DiagnosticsView.svelte';
  import ServerView from '../views/ServerView.svelte';
  import ActivityDock from '../dock/ActivityDock.svelte';
  import { activeTab, workspace } from '../state.svelte';

  interface Props {
    browserLabel: string;
    onDisconnect: () => void;
  }

  let { browserLabel, onDisconnect }: Props = $props();

  type SideView = 'explorer' | 'diagnostics' | 'server';

  let view = $state<SideView>('explorer');

  const views: { id: SideView; label: string; icon: string }[] = [
    {
      id: 'explorer',
      label: 'Explorer',
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

  const tab = $derived(activeTab());
</script>

<div class="workbench">
  <div class="main">
    <nav class="rail" aria-label="views">
      {#each views as entry (entry.id)}
        <button
          class:active={view === entry.id}
          title={entry.label}
          aria-label={entry.label}
          onclick={() => (view = entry.id)}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
            <path d={entry.icon} />
          </svg>
        </button>
      {/each}
    </nav>

    <aside class="sidebar">
      <header>{views.find((entry) => entry.id === view)?.label}</header>
      <div class="viewhost">
        {#if view === 'explorer'}
          <ExplorerView />
        {:else if view === 'diagnostics'}
          <DiagnosticsView />
        {:else}
          <ServerView />
        {/if}
      </div>
    </aside>

    <section class="center">
      <TabBar />
      <div class="tabhost">
        {#if tab}
          {#each workspace.tabs as t (t.key)}
            <div class="pane" hidden={t.key !== workspace.activeKey}>
              {#if t.kind === 'doc'}
                <DocEditor resource={{ pack: t.pack, type: t.type, path: t.path, id: t.id }} tabKey={t.key} />
              {:else if t.kind === 'asset'}
                <AssetPreviewTab pack={t.pack} path={t.path} name={t.name} />
              {:else if t.kind === 'settings'}
                <SettingsTab />
              {/if}
            </div>
          {/each}
        {:else}
          <p class="welcome">
            Open a file from the explorer — or create one.<br />
            <span>Saves hot-reload the server and show up in the pipeline log below.</span>
          </p>
        {/if}
      </div>
    </section>
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
    grid-template-rows: minmax(0, 1fr) auto auto;
  }
  .main {
    min-height: 0;
    display: grid;
    grid-template-columns: 44px 264px minmax(0, 1fr);
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
  .sidebar {
    background: var(--bg-panel);
    border-right: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .sidebar header {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--muted);
    padding: 10px 14px 8px;
  }
  .viewhost {
    overflow-y: auto;
    padding: 0 10px 12px;
    min-height: 0;
  }
  .center {
    display: flex;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
    background: var(--bg-app);
  }
  .tabhost {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 12px 14px;
  }
  .pane {
    height: 100%;
  }
  .welcome {
    color: var(--faint);
    text-align: center;
    margin-top: 12vh;
  }
  .welcome span {
    font-size: 12.5px;
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
