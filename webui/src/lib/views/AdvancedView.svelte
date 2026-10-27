<script lang="ts">
  /**
   * Advanced Files: the power surface. The pack tree, the tab bar and the
   * editors live here and nowhere else, so the Content tab is free to be a
   * content browser rather than a sidebar.
   */
  import TabBar from '../components/TabBar.svelte';
  import DocEditor from '../components/DocEditor.svelte';
  import AssetPreviewTab from '../components/AssetPreviewTab.svelte';
  import SettingsTab from '../components/SettingsTab.svelte';
  import BlockbenchTab from '../components/BlockbenchTab.svelte';
  import FileTree from './FileTree.svelte';
  import { activeTab, workspace } from '../state.svelte';

  let { active = true }: { active?: boolean } = $props();
  const tab = $derived(activeTab());
</script>

<div class="advanced">
  <aside class="tree">
    <FileTree />
  </aside>

  <section class="center">
    <TabBar />
    <div class="tabhost">
      {#if tab}
        {#each workspace.tabs as t (t.key)}
          <div class="pane" hidden={t.key !== workspace.activeKey}>
            {#if t.kind === 'doc'}
              <DocEditor
                resource={{ pack: t.pack, type: t.type, path: t.path, id: t.id }}
                tabKey={t.key}
                source={t.source}
                active={active && t.key === workspace.activeKey}
              />
            {:else if t.kind === 'asset'}
              <AssetPreviewTab pack={t.pack} path={t.path} name={t.name}
                active={active && t.key === workspace.activeKey} tabKey={t.key} />
            {:else if t.kind === 'settings'}
              <SettingsTab />
            {:else if t.kind === 'blockbench'}
              <BlockbenchTab
                pack={t.pack}
                ownerPath={t.path}
                expectedRev={t.rev}
                modelRef={t.modelRef}
                rootId={t.rootId}
                active={active && t.key === workspace.activeKey}
                tabKey={t.key}
              />
            {/if}
          </div>
        {/each}
      {:else}
        <p class="welcome">
          Open a file from the tree — or create one.<br />
          <span>Saves hot-reload the server and show up in the pipeline log below.</span>
        </p>
      {/if}
    </div>
  </section>
</div>

<style>
  .advanced {
    min-height: 0;
    height: 100%;
    display: grid;
    grid-template-columns: 264px minmax(0, 1fr);
  }
  .tree {
    background: var(--bg-panel);
    border-right: 1px solid var(--border);
    overflow-y: auto;
    padding: var(--space-3) var(--space-3) var(--space-3);
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
    min-width: 0;
    overflow: auto;
    padding: var(--space-3) var(--space-4);
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
    font-size: var(--text-sm);
  }
</style>
