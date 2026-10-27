<script lang="ts">
  import { untrack } from 'svelte';
  import Icon from './Icon.svelte';
  /**
   * The embedded Blockbench workbench (architecture.md §9.3): the pinned,
   * unmodified web build in a same-origin iframe, with our bridge injected
   * after load. All UI lives here — the bridge only speaks postMessage.
   *
   * Save-back: collects the Java block/item model JSON + referenced
   * textures + the .bbmodel source, lets you set the upload targets, then
   * saves through one authoring transaction. Import loads an external .bbmodel into
   * the embedded Blockbench for further editing.
   */
  import { assetOps, ops, RpcError, unwrap } from '../api';
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
  import { draftStore, reconcileDocumentDraft } from '../drafts.svelte';
  import { rootIdForResource, rootIdForResourceSelection } from '../studio';
  import {
    connection,
    errorMessage,
    logActivity,
    refreshPack,
    registerEditorGuard,
    session,
    workspace,
  } from '../state.svelte';

  interface Props {
    pack: string;
    ownerPath: string;
    rootId?: string;
    expectedRev: number;
    modelRef: string;
    tabKey: string;
    active?: boolean;
  }

  let {
    pack: ownerPack,
    ownerPath,
    rootId = '',
    expectedRev,
    modelRef,
    tabKey,
    active = true,
  }: Props = $props();
  const openedContext = untrack(() => ({
    pack: ownerPack,
    path: ownerPath,
    rootId: rootIdForResource(rootId),
    rev: expectedRev,
    modelRef,
  }));
  let currentRev = $state(openedContext.rev);
  let acceptedModelRef = $state(openedContext.modelRef);

  let frame = $state<HTMLIFrameElement | null>(null);
  let bridgeReady = $state(false);
  let projectReady = $state(false);
  let error = $state('');
  let busy = $state(false);
  let saveData = $state<SaveData | null>(null);
  let nextId = 1;
  let loadedSourceFor = $state('');

  // save-back dialog state
  let dialog = $state(false);
  let saving = $state(false);
  // Briefly true after a save lands: the header button shows the result before it settles back.
  let applied = $state(false);
  let appliedTimer: ReturnType<typeof setTimeout> | undefined;
  let pack = $state('');
  let modelPath = $state('');
  let textureTargets = $state<{ link: string; target: string }[]>([]);
  let saveSource = $state(true);
  let sourcePath = $state('');
  let attach = $state<'none' | 'existing' | 'new'>('none');
  let attachPath = $state('');
  let attachRootId = $state('');
  let attachSelection = $state('');
  let candidates = $state<{ path: string; id: string }[]>([]);
  let candidatesPack = $state('');

  const projectName = $derived(saveData ? sanitizeId(saveData.projectName) : 'model');

  $effect(() => {
    return registerEditorGuard(tabKey, () => {
      if (saving) {
        error = 'Wait for the model asset save to finish before closing this tab.';
        return false;
      }
      return confirm('Leave Blockbench? Unsaved model work may be lost; already-applied assets stay applied.');
    });
  });

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
    const isActive = active;
    function onMessage(event: MessageEvent) {
      if (event.origin !== location.origin || event.source !== frame?.contentWindow || !isBridgeFrame(event.data)) {
        return;
      }
      const type = String(event.data.type);
      if (type === 'ready') {
        bridgeReady = true;
      } else if (isActive && type === 'bridgeError') {
        error = String(event.data.error ?? 'bridge failure');
      }
    }
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  });

  $effect(() => {
    if (active && bridgeReady) send('configure', { origin: location.origin });
  });

  function send(type: string, extra: Record<string, unknown> = {}): void {
    frame?.contentWindow?.postMessage({ [BRIDGE_MARK]: true, type, ...extra }, location.origin);
  }

  $effect(() => {
    if (!active || !bridgeReady || !connection.connected || !session.channel) return;
    if (!openedContext.modelRef) {
      projectReady = true;
      return;
    }
    if (loadedSourceFor !== openedContext.modelRef) {
      loadedSourceFor = openedContext.modelRef;
      void loadExistingSource();
    }
  });

  async function loadExistingSource() {
    const channel = session.channel;
    if (!connection.connected || !channel) return;
    projectReady = false;
    if (!openedContext.modelRef) {
      projectReady = true;
      return;
    }
    const modelPath = openedContext.modelRef.includes(':')
      ? openedContext.modelRef.slice(openedContext.modelRef.indexOf(':') + 1)
      : openedContext.modelRef;
    const name = modelPath.slice(modelPath.lastIndexOf('/') + 1);
    const source = `assets/models/source/${name}.bbmodel`;
    try {
      const asset = unwrap(await assetOps.get(channel, openedContext.pack, source));
      const binary = atob(String(asset.data ?? ''));
      const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
      await callBridge('importProject', { project: new TextDecoder().decode(bytes) });
      projectReady = true;
      logActivity('info', `loaded ${source} for editing`);
    } catch (e) {
      if (e instanceof RpcError && e.code === 'MTR-0009') {
        projectReady = true;
        return;
      }
      error = `Could not load the saved model source. No model can be applied until it is loaded safely. Close and reopen to retry. ${errorMessage(e)}`;
    }
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
        if (event.origin !== location.origin || event.source !== win || !isBridgeFrame(event.data) || event.data.id !== id) {
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
      win.postMessage({ [BRIDGE_MARK]: true, id, type, ...extra }, location.origin);
    });
  }

  // --- actions -------------------------------------------------------------

  async function collect() {
    if (!active) return;
    if (!connection.connected || !session.channel) {
      error = 'Connect to the server before collecting model assets.';
      return;
    }
    if (!projectReady) {
      error = 'Wait for the saved model source to load before collecting assets.';
      return;
    }
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
    pack = openedContext.pack;
    modelPath = `assets/models/${projectName}.json`;
    textureTargets = data.textures.map((texture) => ({
      link: texture.link,
      target: defaultTextureTarget(texture),
    }));
    saveSource = data.project.length > 0;
    sourcePath = `assets/models/source/${projectName}.bbmodel`;
    attach = 'existing';
    attachPath = openedContext.path;
    attachRootId = openedContext.rootId;
    attachSelection = openedContext.rootId
      ? selectionKey(openedContext.path, openedContext.rootId)
      : '';
    candidates = [];
    candidatesPack = '';
    dialog = true;
  }

  function selectionKey(path: string, selectedRootId: string): string {
    return JSON.stringify([path, selectedRootId]);
  }

  function chooseExisting(event: Event): void {
    const value = (event.currentTarget as HTMLSelectElement).value;
    attachSelection = value;
    try {
      const selected = JSON.parse(value) as unknown;
      if (
        Array.isArray(selected) &&
        typeof selected[0] === 'string' &&
        typeof selected[1] === 'string'
      ) {
        attachPath = selected[0];
        attachRootId = selected[1];
        return;
      }
    } catch {
      // An empty selection uses the disabled placeholder below.
    }
    attachPath = '';
    attachRootId = '';
  }

  function changePack(): void {
    candidates = [];
    candidatesPack = '';
    attachPath = '';
    attachRootId = '';
    attachSelection = '';
  }

  async function loadCandidates() {
    const channel = session.channel;
    const requestedPack = pack;
    if (!channel || !requestedPack) return;
    try {
      const resources =
        (unwrap(await ops.resources(channel, requestedPack)).resources as {
          path: string;
          id: string;
        }[]) ?? [];
      const loadable = resources.filter((r) => /^items\/|^blocks\//.test(r.path));
      if (pack !== requestedPack) return;
      candidates = loadable.map((r) => ({ path: r.path, id: r.id }));
      candidatesPack = requestedPack;
      if (
        !attachRootId &&
        requestedPack === openedContext.pack &&
        attachPath === openedContext.path
      ) {
        const ownerMatches = candidates.filter((candidate) => candidate.path === openedContext.path);
        if (ownerMatches.length === 1) {
          attachPath = ownerMatches[0]!.path;
          attachRootId = rootIdForResource(ownerMatches[0]!.id);
          attachSelection = selectionKey(attachPath, attachRootId);
        }
      }
    } catch (e) {
      error = errorMessage(e);
    }
  }

  function openingContextChanged(): boolean {
    return ownerPack !== openedContext.pack ||
      ownerPath !== openedContext.path ||
      rootIdForResource(rootId) !== openedContext.rootId ||
      (modelRef !== openedContext.modelRef && modelRef !== acceptedModelRef) ||
      (expectedRev !== openedContext.rev && expectedRev !== currentRev);
  }

  async function doSave() {
    const channel = session.channel;
    if (!active || !saveData) return;
    if (!connection.connected || !channel) {
      error = 'Reconnect before applying these model assets.';
      return;
    }
    if (!projectReady) {
      error = 'The saved model source is not verified; no assets were applied.';
      return;
    }
    saving = true;
    error = '';
    try {
      if (openingContextChanged()) {
        throw new Error('The owning document or model changed while Blockbench was open. No assets were saved; close and reopen the editor.');
      }
      const openedOwner = unwrap(await ops.get(channel, openedContext.pack, openedContext.path));
      if (openingContextChanged()) {
        throw new Error('The owning document or model changed while Blockbench was open. No assets were saved; close and reopen the editor.');
      }
      if (Number(openedOwner.rev ?? 0) !== currentRev) {
        throw new Error('The owning document changed while Blockbench was open. No assets were saved; close and reopen the editor.');
      }
      const targetByLink = new Map(
        textureTargets
          .filter((entry) => entry.target.trim() !== '')
          .map((entry) => [entry.link, entry.target.trim()]),
      );
      const targets: TextureTarget[] = [...targetByLink].map(([link, target]) => ({ link, target }));
      const assets: { path: string; data: string }[] = [];
      for (const texture of saveData.textures) {
        const target = targetByLink.get(texture.link);
        if (!target) continue;
        assets.push({
          path: `assets/textures/${target}.png`,
          data: dataUrlToBase64(texture.dataUrl),
        });
      }
      const normalized = normalizeModel(saveData.model, pack, targets);
      assets.push({ path: modelPath, data: btoa(unescape(encodeURIComponent(normalized.model))) });
      if (saveSource) {
        assets.push({ path: sourcePath, data: btoa(unescape(encodeURIComponent(saveData.project))) });
      }
      if (normalized.unresolved.length > 0) {
        logActivity(
          'info',
          `model saved with unresolved texture refs: ${normalized.unresolved.join(', ')}`,
        );
      }
      const savedModelRef = `${pack}:${modelPath.slice('assets/models/'.length).replace(/\.json$/i, '')}`;
      let committedOwner: { pack: string; path: string; field: string[] } | null = null;
      if (attach === 'existing') {
        const doc = unwrap(await ops.get(channel, pack, attachPath));
        const ownerSelection =
          pack === openedContext.pack &&
          attachPath === openedContext.path &&
          attachRootId === openedContext.rootId;
        const selectedRootId = ownerSelection && openedContext.rootId
          ? openedContext.rootId
          : candidatesPack === pack
            ? rootIdForResourceSelection(candidates, attachPath, attachRootId)
            : null;
        if (!selectedRootId) {
          throw new Error('Choose a specific registered item or block before attaching this model.');
        }
        const field = [selectedRootId, 'model'];
        if (openingContextChanged()) {
          throw new Error('The owning document or model changed while Blockbench was open. No assets were saved; close and reopen the editor.');
        }
        const saved = unwrap(await ops.saveModel(channel, {
          pack,
          ownerPath: attachPath,
          rev: pack === openedContext.pack && attachPath === openedContext.path
            ? currentRev
            : Number(doc.rev ?? 0),
          field,
          modelRef: savedModelRef,
          sourcePath: saveSource ? sourcePath : undefined,
          assets,
        }));
        committedOwner = { pack, path: attachPath, field };
        if (pack === openedContext.pack && attachPath === openedContext.path) {
          currentRev = Number(saved.rev ?? currentRev);
          if (selectedRootId === openedContext.rootId) acceptedModelRef = savedModelRef;
        }
      } else if (attach === 'new') {
        if (!attachPath.trim()) {
          throw new Error('Enter an item id before saving.');
        }
        if (openingContextChanged()) {
          throw new Error('The owning document or model changed while Blockbench was open. No assets were saved; close and reopen the editor.');
        }
        const ownerId = sanitizeId(attachPath.replace(/\.yml$/i, ''));
        unwrap(await ops.saveModel(channel, {
          pack,
          ownerPath: '',
          createOwner: true,
          ownerId,
          ownerName: saveData.projectName || ownerId,
          modelRef: savedModelRef,
          sourcePath: saveSource ? sourcePath : undefined,
          assets,
        }));
      } else {
        if (openingContextChanged()) {
          throw new Error('The owning document or model changed while Blockbench was open. No assets were saved; close and reopen the editor.');
        }
        unwrap(await ops.saveModel(channel, { pack, ownerPath: '', assets }));
      }
      const owner = committedOwner;
      if (owner) {
        const draft = draftStore.documents.find((entry) =>
          entry.pack === owner.pack && entry.path === owner.path);
        if (draft) {
          await reconcileDocumentDraft(draft, channel, [owner.field]);
        }
      }
      logActivity('save', `Blockbench model saved to ${pack}/${modelPath}; targeted reload queued`);
      applied = true;
      clearTimeout(appliedTimer);
      appliedTimer = setTimeout(() => (applied = false), 1600);
      dialog = false;
      saveData = null;
      void refreshPack();
    } catch (e) {
      error = errorMessage(e);
    } finally {
      saving = false;
    }
  }

  async function importProject(event: Event) {
    if (!active) return;
    if (!connection.connected || !session.channel) {
      error = 'Connect to the server before importing a model project.';
      return;
    }
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    error = '';
    busy = true;
    try {
      const project = await file.text();
      await callBridge('importProject', { project });
      projectReady = true;
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
      <input
        type="file"
        accept=".bbmodel,application/json"
        disabled={!active || !connection.connected || !session.channel || !projectReady}
        onchange={(e) => void importProject(e)}
      />
    </label>
    <button
      class="btn btn-primary save"
      class:busy
      class:done={applied && !busy}
      disabled={!active || !bridgeReady || !projectReady || !connection.connected || !session.channel || busy}
      onclick={() => void collect()}
    >
      {#if applied && !busy}<Icon name="check" size={14} />{/if}
      {busy ? 'Working…' : applied ? 'Applied' : 'Save & apply…'}
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
  {#if !connection.connected || !session.channel || !projectReady}
    <div class="connection-cover" role="status">
      {#if !connection.connected || !session.channel}
        Connect to the server before editing immediate-write model assets.
      {:else if error}
        The saved model source could not be verified; close and reopen this tab to retry.
      {:else}
        Loading the saved model source…
      {/if}
    </div>
  {/if}

  {#if dialog && saveData}
    <div class="overlay" role="dialog" aria-label="save model to Materione">
      <div class="sheet">
        <h3>Save model to Materione</h3>
        <div class="grid">
          <label>
            pack
            <select bind:value={pack} onchange={changePack}>
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
              value={attachSelection}
              onchange={chooseExisting}
              onfocus={() => void loadCandidates()}
            >
              <option value="" disabled>choose a resource…</option>
              {#if pack === openedContext.pack && openedContext.rootId &&
                !candidates.some((candidate) =>
                  candidate.path === openedContext.path &&
                  rootIdForResource(candidate.id) === openedContext.rootId)}
                <option value={selectionKey(openedContext.path, openedContext.rootId)}>
                  {openedContext.path} ({openedContext.rootId} — current owner)
                </option>
              {/if}
              {#each candidates as option (selectionKey(option.path, rootIdForResource(option.id)))}
                <option value={selectionKey(option.path, rootIdForResource(option.id))}>
                  {option.path} ({option.id})
                </option>
              {/each}
            </select>
          {:else if attach === 'new'}
            <input bind:value={attachPath} placeholder="item id, e.g. fancy_chair" spellcheck="false" />
          {/if}
        </div>

        <footer>
          <button disabled={saving} onclick={() => (dialog = false)}>cancel</button>
          <button class="btn btn-primary save" class:busy={saving} disabled={!active || saving} onclick={() => void doSave()}>
            {saving ? 'Saving…' : 'Save & apply'}
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
    padding: var(--space-4) var(--space-5);
    height: 100%;
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
    position: relative;
  }
  header {
    display: flex;
    align-items: center;
    gap: var(--space-3);
  }
  h2 {
    margin: 0 auto 0 0;
    font-size: var(--text-lg);
  }
  .meta {
    color: var(--muted);
    font-size: var(--text-sm);
  }
  .spacer {
    flex: 1;
  }
  iframe {
    flex: 1;
    min-height: 480px;
    margin-top: var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-raised);
    width: 100%;
  }
  .connection-cover {
    position: absolute;
    inset: 0;
    z-index: 4;
    display: grid;
    place-items: center;
    padding: var(--space-6);
    background: var(--bg-app);
    color: var(--muted);
    text-align: center;
  }
  button:not(.btn),
  .ghost {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-3);
    cursor: pointer;
    font-size: var(--text-base);
  }
  button:not(.btn):disabled {
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
    border-radius: var(--radius);
    padding: var(--space-2) var(--space-3);
    white-space: pre-wrap;
  }
  .overlay {
    position: absolute;
    inset: 0;
    background: var(--scrim);
    display: grid;
    place-items: center;
    z-index: 5;
  }
  .sheet {
    background: var(--bg-panel);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-lg);
    padding: var(--space-5) var(--space-6);
    width: min(620px, 92%);
    max-height: 90%;
    overflow-y: auto;
  }
  .sheet h3 {
    margin: 0 0 var(--space-3);
  }
  .sheet h4 {
    margin: var(--space-4) 0 var(--space-2);
    font-size: var(--text-sm);
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--muted);
  }
  .grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--space-2) var(--space-4);
  }
  label {
    display: block;
    font-size: var(--text-sm);
  }
  label.check {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  input:not([type='checkbox']):not([type='radio']),
  select {
    width: 100%;
    box-sizing: border-box;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: var(--space-2) var(--space-2);
    font: var(--text-base) var(--mono);
    margin-top: var(--space-1);
  }
  .attach {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  footer {
    margin-top: var(--space-4);
    display: flex;
    justify-content: flex-end;
    gap: var(--space-3);
  }
  /* Save & apply: a sweep along the button while the save runs, then a green settle when it lands. */
  .save {
    position: relative;
    overflow: hidden;
  }
  .save.busy::after {
    content: '';
    position: absolute;
    inset: auto 0 0;
    height: 2px;
    background: var(--accent-ink);
    opacity: 0.6;
    animation: save-sweep var(--motion-slow) var(--ease-standard) infinite;
  }
  .save.done {
    border-color: var(--ok);
    background: var(--ok);
    color: var(--bg-app);
    animation: save-settle var(--motion-slow) var(--ease-emphasized) both;
  }
  @keyframes save-sweep {
    from { transform: translateX(-100%); }
    to { transform: translateX(100%); }
  }
  @keyframes save-settle {
    from { transform: scale(0.96); }
    to { transform: none; }
  }
</style>
