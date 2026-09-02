<script lang="ts">
  /**
   * The embedded Blockbench workbench (architecture.md §9.3): the pinned,
   * unmodified web build in a same-origin iframe, with our bridge injected
   * after load. All UI lives here — the bridge only speaks postMessage.
   *
   * Save-back: collects the Java block/item model JSON + referenced
   * textures + the .bbmodel source, lets you set the upload targets, then
   * rides assets.put / edit.create / edit.setScalar — the usual targeted
   * reload closes the loop in game. Import loads an external .bbmodel into
   * the embedded Blockbench for further editing.
   */
  import { assetOps, ops, unwrap } from '../api';
  import {
    BRIDGE_MARK,
    dataUrlToBase64,
    defaultTextureTarget,
    isBridgeFrame,
    normalizeModel,
    sanitizeId,
    type SaveData,
    type TextureTarget,
  } from '../blockbench/protocol';
  import { errorMessage, logActivity, refreshPack, session, workspace } from '../state.svelte';

  let frame = $state<HTMLIFrameElement | null>(null);
  let bridgeReady = $state(false);
  let error = $state('');
  let busy = $state(false);
  let saveData = $state<SaveData | null>(null);
  let nextId = 1;

  // save-back dialog state
  let dialog = $state(false);
  let saving = $state(false);
  let pack = $state('');
  let modelPath = $state('');
  let textureTargets = $state<{ link: string; target: string }[]>([]);
  let saveSource = $state(true);
  let sourcePath = $state('');
  let attach = $state<'none' | 'existing' | 'new'>('none');
  let attachPath = $state('');
  let candidates = $state<{ path: string; id: string }[]>([]);

  const projectName = $derived(saveData ? sanitizeId(saveData.projectName) : 'model');

  // --- bridge plumbing -----------------------------------------------------

  function onFrameLoad() {
    const doc = frame?.contentDocument;
    if (!doc) {
      return;
    }
    const script = doc.createElement('script');
    script.src = '/materione-bridge.js';
    doc.body.appendChild(script);
  }

  $effect(() => {
    function onMessage(event: MessageEvent) {
      if (event.source !== frame?.contentWindow || !isBridgeFrame(event.data)) {
        return;
      }
      const type = String(event.data.type);
      if (type === 'ready') {
        bridgeReady = true;
        error = '';
        send('configure', { origin: location.origin });
      } else if (type === 'bridgeError') {
        error = String(event.data.error ?? 'bridge failure');
      }
    }
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  });

  function send(type: string, extra: Record<string, unknown> = {}): void {
    frame?.contentWindow?.postMessage({ [BRIDGE_MARK]: true, type, ...extra }, '*');
  }

  /** One correlated bridge call: resolves with the matching reply frame. */
  function callBridge(type: string, extra: Record<string, unknown> = {}): Promise<Record<string, unknown>> {
    return new Promise((resolve, reject) => {
      const win = frame?.contentWindow;
      if (!win) {
        reject(new Error('Blockbench is not loaded yet'));
        return;
      }
      const id = `m${nextId++}`;
      const onMessage = (event: MessageEvent) => {
        if (event.source !== win || !isBridgeFrame(event.data) || event.data.id !== id) {
          return;
        }
        cleanup();
        if (event.data.type === 'bridgeError') {
          reject(new Error(String(event.data.error ?? 'bridge failure')));
        } else {
          resolve(event.data as Record<string, unknown>);
        }
      };
      const timer = setTimeout(() => {
        cleanup();
        reject(new Error(`${type}: bridge did not answer`));
      }, 120_000);
      function cleanup() {
        clearTimeout(timer);
        window.removeEventListener('message', onMessage);
      }
      window.addEventListener('message', onMessage);
      win.postMessage({ [BRIDGE_MARK]: true, id, type, ...extra }, '*');
    });
  }

  // --- actions -------------------------------------------------------------

  async function collect() {
    error = '';
    busy = true;
    try {
      const reply = await callBridge('collect');
      const data: SaveData = {
        projectName: String(reply.projectName ?? 'model'),
        model: String(reply.model ?? ''),
        textures: (reply.textures as SaveData['textures']) ?? [],
        project: String(reply.project ?? ''),
      };
      openDialog(data);
    } catch (e) {
      error = errorMessage(e);
    } finally {
      busy = false;
    }
  }

  function openDialog(data: SaveData) {
    saveData = data;
    pack = workspace.activePack ?? '';
    modelPath = `assets/models/${projectName}.json`;
    textureTargets = data.textures.map((texture) => ({
      link: texture.link,
      target: defaultTextureTarget(texture),
    }));
    saveSource = data.project.length > 0;
    sourcePath = `assets/models/source/${projectName}.bbmodel`;
    attach = 'none';
    attachPath = '';
    candidates = [];
    dialog = true;
  }

  async function loadCandidates() {
    if (!session.channel || !pack) return;
    try {
      const resources =
        (unwrap(await ops.resources(session.channel, pack)).resources as {
          path: string;
          id: string;
        }[]) ?? [];
      const loadable = resources.filter((r) => /^items\/|^blocks\//.test(r.path));
      candidates = loadable.map((r) => ({ path: r.path, id: r.id }));
    } catch (e) {
      error = errorMessage(e);
    }
  }

  async function doSave() {
    if (!session.channel || !saveData) return;
    saving = true;
    error = '';
    try {
      const targetByLink = new Map(
        textureTargets
          .filter((entry) => entry.target.trim() !== '')
          .map((entry) => [entry.link, entry.target.trim()]),
      );
      const targets: TextureTarget[] = [...targetByLink].map(([link, target]) => ({ link, target }));
      for (const texture of saveData.textures) {
        const target = targetByLink.get(texture.link);
        if (!target) continue;
        await assetOps.putBase64(
          session.channel,
          pack,
          `assets/textures/${target}.png`,
          dataUrlToBase64(texture.dataUrl),
        );
      }
      const normalized = normalizeModel(saveData.model, pack, targets);
      await assetOps.put(
        session.channel,
        pack,
        modelPath,
        new TextEncoder().encode(normalized.model),
      );
      if (saveSource) {
        await assetOps.put(
          session.channel,
          pack,
          sourcePath,
          new TextEncoder().encode(saveData.project),
        );
      }
      if (normalized.unresolved.length > 0) {
        logActivity(
          'info',
          `model saved with unresolved texture refs: ${normalized.unresolved.join(', ')}`,
        );
      }
      const modelRef = `${pack}:${modelPath.slice('assets/models/'.length).replace(/\.json$/i, '')}`;
      if (attach === 'existing') {
        await attachToExisting(modelRef);
      } else if (attach === 'new') {
        await attachToNew(modelRef);
      }
      logActivity('save', `Blockbench model saved to ${pack}/${modelPath}; targeted reload queued`);
      dialog = false;
      saveData = null;
      void refreshPack();
    } catch (e) {
      error = errorMessage(e);
    } finally {
      saving = false;
    }
  }

  async function attachToExisting(modelRef: string) {
    const doc = unwrap(await ops.get(session.channel!, pack, attachPath));
    const parsed = (doc.parsed as Record<string, unknown>) ?? {};
    const rootId = String(Object.keys(parsed)[0] ?? '');
    await ops.setScalar(session.channel!, pack, attachPath, Number(doc.rev ?? 0), [rootId, 'model'], modelRef);
  }

  async function attachToNew(modelRef: string) {
    const path = `items/${attachPath.replace(/\.yml$/i, '')}.yml`;
    await ops.create(session.channel!, pack, path);
    const doc = unwrap(await ops.get(session.channel!, pack, path));
    const rootId = String(Object.keys((doc.parsed as Record<string, unknown>) ?? {})[0] ?? '');
    await ops.setScalar(
      session.channel!,
      pack,
      path,
      Number(doc.rev ?? 0),
      [rootId, 'display_name'],
      saveData?.projectName ?? rootId,
    );
    const fresh = unwrap(await ops.get(session.channel!, pack, path));
    await ops.setScalar(session.channel!, pack, path, Number(fresh.rev ?? 0), [rootId, 'model'], modelRef);
  }

  async function importProject(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    error = '';
    busy = true;
    try {
      const project = await file.text();
      await callBridge('importProject', { project });
      logActivity('info', `imported ${file.name} into the embedded Blockbench`);
    } catch (e) {
      error = errorMessage(e);
    } finally {
      busy = false;
    }
  }
</script>

<article>
  <header>
    <h2>Blockbench</h2>
    <span class="meta">
      {#if bridgeReady}pinned build · bridge ready{:else}starting the pinned build…{/if}
    </span>
    <span class="spacer"></span>
    <label class="ghost">
      import .bbmodel
      <input type="file" accept=".bbmodel,application/json" onchange={(e) => void importProject(e)} />
    </label>
    <button class="primary" disabled={!bridgeReady || busy} onclick={() => void collect()}>
      {busy ? 'working…' : 'save to Materione'}
    </button>
  </header>

  {#if error}
    <p class="error">{error}</p>
  {/if}

  <iframe
    bind:this={frame}
    title="Blockbench"
    src="/blockbench/index.html"
    onload={onFrameLoad}
  ></iframe>

  {#if dialog && saveData}
    <div class="overlay" role="dialog" aria-label="save model to Materione">
      <div class="sheet">
        <h3>Save model to Materione</h3>
        <div class="grid">
          <label>
            pack
            <select bind:value={pack}>
              {#each workspace.packs as option (option)}
                <option value={option}>{option}</option>
              {/each}
            </select>
          </label>
          <label>
            model path
            <input bind:value={modelPath} spellcheck="false" />
          </label>
          <label class="check">
            <input type="checkbox" bind:checked={saveSource} />
            also save the .bbmodel project source
          </label>
          {#if saveSource}
            <label>
              source path
              <input bind:value={sourcePath} spellcheck="false" />
            </label>
          {/if}
        </div>

        {#if textureTargets.length > 0}
          <h4>textures</h4>
          <div class="grid">
            {#each textureTargets as entry (entry.link)}
              <label>
                {entry.link}
                <input bind:value={entry.target} spellcheck="false" placeholder="assets/textures/…" />
              </label>
            {/each}
          </div>
        {/if}

        <h4>use the model</h4>
        <div class="attach">
          <label class="check">
            <input type="radio" name="attach" value="none" bind:group={attach} />
            save as assets only
          </label>
          <label class="check">
            <input type="radio" name="attach" value="new" bind:group={attach} />
            scaffold a new item with it
          </label>
          <label class="check">
            <input type="radio" name="attach" value="existing" bind:group={attach} />
            wire onto an existing item/block
          </label>
          {#if attach === 'existing'}
            <select
              bind:value={attachPath}
              onchange={() => void loadCandidates()}
              onfocus={() => void loadCandidates()}
            >
              <option value="" disabled selected>choose a document…</option>
              {#each candidates as option (option.path)}
                <option value={option.path}>{option.path} ({option.id})</option>
              {/each}
            </select>
          {:else if attach === 'new'}
            <input bind:value={attachPath} placeholder="item id, e.g. fancy_chair" spellcheck="false" />
          {/if}
        </div>

        <footer>
          <button onclick={() => (dialog = false)}>cancel</button>
          <button class="primary" disabled={saving} onclick={() => void doSave()}>
            {saving ? 'saving…' : 'save'}
          </button>
        </footer>
      </div>
    </div>
  {/if}
</article>

<style>
  article {
    background: var(--bg-panel);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: 14px 18px;
    height: 100%;
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
    position: relative;
  }
  header {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  h2 {
    margin: 0 auto 0 0;
    font-size: 16px;
  }
  .meta {
    color: var(--muted);
    font-size: 12px;
  }
  .spacer {
    flex: 1;
  }
  iframe {
    flex: 1;
    min-height: 480px;
    margin-top: 10px;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: #1e1e26;
    width: 100%;
  }
  button,
  .ghost {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: 4px 12px;
    cursor: pointer;
    font-size: 13px;
  }
  button.primary {
    background: var(--primary);
    border-color: var(--primary);
    color: white;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .ghost {
    position: relative;
    overflow: hidden;
    display: inline-block;
  }
  .ghost input[type='file'] {
    position: absolute;
    inset: 0;
    opacity: 0;
    cursor: pointer;
  }
  .error {
    background: var(--error-bg);
    color: var(--error);
    border-radius: 8px;
    padding: 8px 12px;
    white-space: pre-wrap;
  }
  .overlay {
    position: absolute;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    display: grid;
    place-items: center;
    z-index: 5;
  }
  .sheet {
    background: var(--bg-panel);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-lg);
    padding: 18px 22px;
    width: min(620px, 92%);
    max-height: 90%;
    overflow-y: auto;
  }
  .sheet h3 {
    margin: 0 0 12px;
  }
  .sheet h4 {
    margin: 14px 0 6px;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--muted);
  }
  .grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px 16px;
  }
  label {
    display: block;
    font-size: 12.5px;
  }
  label.check {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  input:not([type='checkbox']):not([type='radio']),
  select {
    width: 100%;
    box-sizing: border-box;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: 6px 8px;
    font: 13px var(--mono);
    margin-top: 3px;
  }
  .attach {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  footer {
    margin-top: 16px;
    display: flex;
    justify-content: flex-end;
    gap: 10px;
  }
</style>
