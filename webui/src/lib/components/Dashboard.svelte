<script lang="ts">
  import type { EditorChannel } from '../protocol/session';
  import type { Response } from '../protocol/wire';
  import { ops, unwrap } from '../api';
  import type { DiagnosticEntry, ResourceRef, TypeInfo } from '../api';
  import DocEditor from './DocEditor.svelte';
  import AssetsPanel from './AssetsPanel.svelte';

  let { channel }: { channel: EditorChannel } = $props();

  let packs = $state<string[]>([]);
  let activePack = $state<string | null>(null);
  let resources = $state<ResourceRef[]>([]);
  let types = $state<TypeInfo[]>([]);
  let diagnostics = $state<DiagnosticEntry[]>([]);
  let selected = $state<ResourceRef | null>(null);
  let activity = $state<string[]>([]);

  function log(line: string) {
    const stamp = new Date().toLocaleTimeString();
    activity = [`${stamp} — ${line}`, ...activity].slice(0, 50);
  }

  function fail(scope: string, error: unknown): null {
    log(`⚠ ${scope}: ${error instanceof Error ? error.message : String(error)}`);
    return null;
  }

  async function loadPacks() {
    try {
      packs = (unwrap(await ops.packs(channel)).packs as string[]) ?? [];
      if (packs.length && !activePack) {
        activePack = packs[0]!;
        await loadResources();
      }
    } catch (e) {
      fail('browse.packs', e);
    }
  }

  async function loadResources() {
    if (!activePack) {
      return;
    }
    try {
      resources = (unwrap(await ops.resources(channel, activePack)).resources as ResourceRef[]) ?? [];
    } catch (e) {
      fail('browse.resources', e);
    }
  }

  async function createPack() {
    const id = prompt('New pack id (a-z, 0-9, _ or -):');
    if (!id) {
      return;
    }
    try {
      unwrap(await ops.createPack(channel, id));
      log(`created pack ${id}`);
      await loadPacks();
      activePack = id;
      await loadResources();
    } catch (e) {
      fail('edit.createPack', e);
    }
  }

  $effect(() => {
    // Live preview loop's last mile: the server pushes reload outcomes here.
    channel.onPush((push) => {
      if (push.op === 'push.reload') {
        const payload = push.payload as Record<string, unknown>;
        log(
          `reload ${payload.accepted ? 'applied' : 'rejected'} — items live: ${payload.itemsLive}` +
            ` (${String(payload.files)})`,
        );
      }
    });
    void (async () => {
      await loadPacks();
      try {
        types = (unwrap(await ops.types(channel)).types as TypeInfo[]) ?? [];
        diagnostics =
          (unwrap(await ops.diagnostics(channel)).diagnostics as DiagnosticEntry[]) ?? [];
      } catch (e) {
        fail('metadata', e);
      }
    })();
  });

  function typeFor(path: string): TypeInfo | undefined {
    const prefix = path.split('/')[0];
    return types.find((t) => t.name === prefix);
  }

  function saved(message: string) {
    log(message);
    void loadResources();
  }
</script>

<div class="layout">
  <aside>
    <div class="head">
      <h3>Packs</h3>
      <button onclick={createPack}>+ new</button>
    </div>
    <ul>
      {#each packs as pack (pack)}
        <li>
          <button class:active={pack === activePack} onclick={() => void (async () => {
            activePack = pack;
            selected = null;
            await loadResources();
          })()}>
            {pack}
          </button>
        </li>
      {/each}
    </ul>

    <h3>Resources</h3>
    <ul class="resources">
      {#each resources as resource (resource.pack + '/' + resource.path + '#' + resource.id)}
        <li>
          <button
            class:active={selected?.path === resource.path && selected?.id === resource.id}
            onclick={() => (selected = resource)}
          >
            <span class="id">{resource.id}</span>
            <span class="path">{resource.path}</span>
          </button>
        </li>
      {/each}
      {#if resources.length === 0}
        <li class="empty">nothing here yet</li>
      {/if}
    </ul>

    <h3>Diagnostics</h3>
    <ul class="diag">
      {#each diagnostics.slice(0, 12) as entry (entry.code + entry.message)}
        <li class={entry.severity}>
          <code>{entry.code}</code> {entry.message}
        </li>
      {/each}
      {#if diagnostics.length === 0}
        <li class="empty">all clear</li>
      {/if}
    </ul>

    {#if activePack}
      <AssetsPanel {channel} pack={activePack} onChanged={log} />
    {/if}
  </aside>

  <section class="main">
    {#if selected}
      <DocEditor {channel} resource={selected} schema={typeFor(selected.path)} onSaved={saved} />
    {:else}
      <p class="placeholder">
        Pick a resource on the left — or create a pack and add files through the raw editor.
      </p>
    {/if}
  </section>

  <aside class="feed">
    <h3>Activity</h3>
    <ul>
      {#each activity as line, index (index + line)}
        <li>{line}</li>
      {/each}
    </ul>
  </aside>
</div>

<style>
  .layout {
    display: grid;
    grid-template-columns: 260px minmax(0, 1fr) 280px;
    gap: 16px;
    align-items: start;
  }
  h3 {
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: #8b93a5;
    margin: 18px 0 6px;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  li button {
    width: 100%;
    text-align: left;
    background: none;
    border: none;
    color: inherit;
    border-radius: 6px;
    padding: 5px 8px;
    cursor: pointer;
    display: block;
  }
  li button:hover,
  li button.active {
    background: #212734;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .head button {
    font-size: 11px;
    border: 1px solid #3b4453;
    background: none;
    color: inherit;
    border-radius: 6px;
    cursor: pointer;
    padding: 2px 8px;
  }
  .id {
    font-weight: 600;
    display: block;
  }
  .path {
    font-size: 11px;
    color: #8b93a5;
  }
  .empty {
    color: #586074;
    font-style: italic;
  }
  .diag li {
    font-size: 12px;
    padding: 4px 6px;
    border-left: 3px solid #3b4453;
    margin-bottom: 4px;
  }
  .diag li.error {
    border-color: #f7768e;
  }
  .diag li.warn {
    border-color: #e0af68;
  }
  .diag code {
    color: #7aa2f7;
  }
  .main {
    min-height: 300px;
  }
  .placeholder {
    color: #586074;
    margin-top: 40px;
    text-align: center;
  }
  .feed li {
    font-family: ui-monospace, monospace;
    font-size: 11.5px;
    padding: 3px 0;
    border-bottom: 1px dotted #262c37;
  }
</style>
