<script lang="ts">
  import { untrack } from 'svelte';
  /**
   * Blockbench, opened as "I am editing this entry's model" rather than as a
   * second application. The author never sees the start screen or a format
   * picker: the entry's own `.bbmodel` source is loaded if it has one, and
   * otherwise a minimal project of the right format is synthesised with the
   * entry's texture already attached. Save is one click — every target is
   * derived from the owning resource — with an advanced disclosure for the
   * rare case where they need to differ.
   *
   * The vendored build stays untouched (D9); everything here happens over the
   * postMessage bridge.
   */
  import { assetOps, ops, RpcError, unwrap } from '../api';
  import {
    BRIDGE_MARK,
    dataUrlToBase64,
    defaultTextureTarget,
    isBridgeFrame,
    modelTargetsFor,
    normalizeModel,
    synthesiseProject,
    thumbnailPath,
    type SaveData,
    type TextureTarget,
  } from '../blockbench/protocol';
  import { sameModelOwnerContext } from '../studio';
  import { connection, errorMessage, logActivity, refreshPack, session } from '../state.svelte';

  interface Props {
    pack: string;
    /** The entry that owns this model. */
    ownerPath: string;
    ownerId: string;
    ownerName: string;
    /** Document revision the studio holds, for the conflict check. */
    rev: number;
    /** Field path to the entry's `model:` key, e.g. ['ruby_sword','model']. */
    field: string[];
    /** Current `<pack>:<path>` model reference, empty when unmodelled. */
    modelRef: string;
    /** Current texture reference (`item/ruby`), pre-attached to new projects. */
    textureRef: string;
    typeDirectory: string;
    onClose: () => void;
    onSaved: (modelRef: string) => void | Promise<void>;
    onSavingChange?: (saving: boolean) => void;
    active?: boolean;
  }

  let {
    pack,
    ownerPath,
    ownerId,
    ownerName,
    rev,
    field,
    modelRef,
    textureRef,
    typeDirectory,
    onClose,
    onSaved,
    onSavingChange,
    active = true,
  }: Props = $props();

  const openedContext = untrack(() => ({
    pack,
    ownerPath,
    ownerId,
    ownerName,
    rev,
    field: [...field],
    modelRef,
    textureRef,
    typeDirectory,
  }));

  let frame = $state<HTMLIFrameElement | null>(null);
  let ready = $state(false);
  let opened = $state(false);
  let modelReady = $state(false);
  let status = $state('starting Blockbench…');
  let error = $state('');
  let saving = $state(false);
  let advanced = $state(false);
  let nextId = 1;

  const targets = modelTargetsFor(openedContext.pack, openedContext.ownerId, openedContext.modelRef);
  let modelPath = $state('');
  let sourcePath = $state('');
  let saveSource = $state(true);

  $effect(() => {
    onSavingChange?.(saving);
  });

  $effect(() => {
    modelPath = targets.modelPath;
    sourcePath = targets.sourcePath;
  });

  function onFrameLoad() {
    const doc = frame?.contentDocument;
    if (!doc) return;
    const script = doc.createElement('script');
    script.src = '/materione-bridge.js';
    doc.body.appendChild(script);
  }

  $effect(() => {
    const isActive = active;
    function onMessage(event: MessageEvent) {
      if (
        event.origin !== location.origin ||
        event.source !== frame?.contentWindow ||
        !isBridgeFrame(event.data)
      ) {
        return;
      }
      if (event.data.type === 'ready') {
        ready = true;
      } else if (isActive && event.data.type === 'bridgeError' && !event.data.id) {
        error = String(event.data.error ?? 'bridge failure');
      }
    }
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  });

  $effect(() => {
    if (active && ready) send('configure', { origin: location.origin });
  });

  function send(type: string, extra: Record<string, unknown> = {}) {
    frame?.contentWindow?.postMessage({ [BRIDGE_MARK]: true, type, ...extra }, location.origin);
  }

  function call(type: string, extra: Record<string, unknown> = {}): Promise<Record<string, unknown>> {
    return new Promise((resolve, reject) => {
      const win = frame?.contentWindow;
      if (!win) {
        reject(new Error('Blockbench is not loaded yet'));
        return;
      }
      const id = `m${nextId++}`;
      const onMessage = (event: MessageEvent) => {
        if (
          event.origin !== location.origin ||
          event.source !== win ||
          !isBridgeFrame(event.data) ||
          event.data.id !== id
        ) {
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

  $effect(() => {
    if (active && ready && !opened) {
      opened = true;
      void openModel();
    }
  });

  /** Loads the entry's model, or invents a sane starting point for it. */
  async function openModel() {
    status = 'opening the model…';
    try {
      if (!connection.connected || !session.channel) {
        throw new Error('Connect to the server before opening an immediate-write model editor.');
      }
      const existing = await loadSource();
      if (existing) {
        await call('importProject', { project: existing });
        modelReady = true;
        status = 'editing the saved model';
        return;
      }
      const texture = await loadTexture();
      await call('importProject', {
        project: synthesiseProject({
          name: openedContext.ownerId,
          typeDirectory: openedContext.typeDirectory,
          texture,
        }),
      });
      modelReady = true;
      status = texture ? 'new model, texture attached' : 'new model';
    } catch (e) {
      error = errorMessage(e);
      status = '';
    }
  }

  async function loadSource(): Promise<string | null> {
    if (!openedContext.modelRef) return null;
    const channel = session.channel;
    if (!connection.connected || !channel) throw new Error('Reconnect before loading the saved model source.');
    try {
      const asset = unwrap(await assetOps.get(channel, openedContext.pack, targets.sourcePath));
      const binary = atob(String(asset.data ?? ''));
      return new TextDecoder().decode(Uint8Array.from(binary, (c) => c.charCodeAt(0)));
    } catch (e) {
      if (e instanceof RpcError && e.code === 'MTR-0009') return null;
      throw e;
    }
  }

  async function loadTexture(): Promise<{ link: string; dataUrl: string } | undefined> {
    if (!openedContext.textureRef) return undefined;
    const channel = session.channel;
    if (!connection.connected || !channel) throw new Error('Reconnect before loading the model texture.');
    try {
      const asset = unwrap(
        await assetOps.get(
          channel,
          openedContext.pack,
          `assets/textures/${openedContext.textureRef}.png`,
        ),
      );
      return {
        link: openedContext.textureRef,
        dataUrl: `data:image/png;base64,${String(asset.data)}`,
      };
    } catch (e) {
      if (e instanceof RpcError && e.code === 'MTR-0009') return undefined;
      throw e;
    }
  }

  async function save() {
    const channel = session.channel;
    if (!active || !ready || !modelReady || saving) return;
    if (openingContextChanged()) {
      error = 'The owner document, model reference, or revision changed while this model was open. No assets were saved; close and reopen the editor.';
      return;
    }
    if (!connection.connected || !channel) {
      error = 'Reconnect before applying this model.';
      return;
    }
    saving = true;
    error = '';
    try {
      const reply = await call('collect');
      const data: SaveData = {
        projectName: String(reply.projectName ?? openedContext.ownerId),
        model: String(reply.model ?? ''),
        textures: (reply.textures as SaveData['textures']) ?? [],
        project: String(reply.project ?? ''),
      };

      const textureTargets: TextureTarget[] = data.textures.map((texture) => ({
        link: texture.link,
        // The entry's own texture keeps its path; anything new the author added
        // in Blockbench lands where its link says.
        target:
          data.textures.length === 1 && openedContext.textureRef
            ? openedContext.textureRef
            : defaultTextureTarget(texture),
      }));
      const assets: { path: string; data: string }[] = [];
      data.textures.forEach((texture, index) => {
        assets.push({
          path: `assets/textures/${textureTargets[index]!.target}.png`,
          data: dataUrlToBase64(texture.dataUrl),
        });
      });

      const normalized = normalizeModel(data.model, openedContext.pack, textureTargets);
      assets.push({ path: modelPath, data: encode(normalized.model) });
      if (saveSource && data.project) {
        assets.push({ path: sourcePath, data: encode(data.project) });
      }
      const thumbnail = await renderThumbnail();
      if (thumbnail) {
        assets.push({ path: thumbnailPath(targets.modelRef), data: thumbnail });
      }
      if (openingContextChanged()) {
        throw new Error('The owner document, model reference, or revision changed while this model was open. No assets were saved; close and reopen the editor.');
      }
      if (normalized.unresolved.length > 0) {
        logActivity(
          'info',
          `model saved with unresolved texture refs: ${normalized.unresolved.join(', ')}`,
        );
      }

      unwrap(
        await ops.saveModel(channel, {
          pack: openedContext.pack,
          ownerPath: openedContext.ownerPath,
          rev: openedContext.rev,
          field: openedContext.field,
          modelRef: targets.modelRef,
          sourcePath: saveSource ? sourcePath : undefined,
          assets,
        }),
      );
      logActivity('save', `${openedContext.ownerId}: model saved; targeted reload queued`);
      void refreshPack();
      await onSaved(targets.modelRef);
    } catch (e) {
      const message = errorMessage(e);
      error = message.includes('MTR-0034')
        ? `${openedContext.ownerId} changed on disk while Blockbench was open. Close, reopen the entry, and try again.`
        : message;
    } finally {
      saving = false;
    }
  }

  function openingContextChanged(): boolean {
    return !sameModelOwnerContext(openedContext, {
      pack,
      ownerPath,
      ownerId,
      rev,
      field,
      modelRef,
      textureRef,
      typeDirectory,
    });
  }

  /**
   * Cached under `.materione/`, which the compiler never ships — so studios
   * and the browser grid can show a model without booting Blockbench.
   */
  async function renderThumbnail(): Promise<string | null> {
    try {
      const reply = await call('thumbnail');
      if (reply.ok !== true) return null;
      return dataUrlToBase64(String(reply.dataUrl ?? '')) || null;
    } catch {
      return null;
    }
  }

  function encode(text: string): string {
    return btoa(unescape(encodeURIComponent(text)));
  }

  function close() {
    if (saving) {
      error = 'Wait for this model save to finish before closing.';
      return;
    }
    if (!confirm('Close the model editor? Unsaved model changes will be lost; already-applied assets stay applied.')) return;
    onClose();
  }
</script>

<div class="workbench">
  <header>
    <span class="what">Model</span>
    <span class="owner" title={openedContext.ownerPath}>
      {openedContext.ownerName || openedContext.ownerId}
    </span>
    <span class="status">{error ? '' : status}</span>
    <span class="spacer"></span>
    <button type="button" class="ghost" onclick={() => (advanced = !advanced)}>
      Targets {advanced ? '▴' : '▾'}
    </button>
    <button type="button" class="ghost" onclick={close}>Close</button>
    <button type="button" class="primary" disabled={!active || !ready || !modelReady || saving} onclick={save}>
      {saving ? 'Saving…' : 'Save & apply'}
    </button>
  </header>

  {#if advanced}
    <div class="targets">
      <label>
        model
        <input bind:value={modelPath} spellcheck="false" />
      </label>
      <label>
        .bbmodel source
        <input bind:value={sourcePath} spellcheck="false" disabled={!saveSource} />
      </label>
      <label class="check">
        <input type="checkbox" bind:checked={saveSource} />
        keep the editable source
      </label>
    </div>
  {/if}

  {#if error}<p class="error">{error}</p>{/if}

  <iframe bind:this={frame} title="Blockbench" src="/blockbench/index.html" onload={onFrameLoad}
  ></iframe>
</div>

<style>
  .workbench {
    /* Flex, not grid tracks: the targets bar and the error line are optional,
       and with positional rows the frame silently lands in an `auto` track and
       collapses to an iframe's intrinsic 150px. */
    display: flex;
    flex-direction: column;
    height: 100%;
    min-width: 0;
    background: var(--bg-app);
  }
  header {
    display: flex;
    flex: none;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
    border-bottom: 1px solid var(--border);
    min-width: 0;
  }
  .what {
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--faint);
  }
  .owner {
    font-size: 13px;
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }
  .status {
    font-size: 11px;
    color: var(--muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }
  .spacer {
    flex: 1;
    min-width: 0;
  }
  button {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: 4px 11px;
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
  }
  button.primary {
    background: var(--primary);
    border-color: transparent;
    color: #fff;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .targets {
    display: grid;
    flex: none;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) minmax(0, auto);
    gap: 8px 14px;
    align-items: end;
    padding: 8px 10px;
    border-bottom: 1px solid var(--border);
  }
  .targets label {
    font-size: 11px;
    color: var(--muted);
    min-width: 0;
  }
  .targets label.check {
    display: flex;
    align-items: center;
    gap: 6px;
    white-space: nowrap;
  }
  .targets input:not([type='checkbox']) {
    display: block;
    width: 100%;
    box-sizing: border-box;
    background: var(--bg-input);
    color: var(--fg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: 4px 6px;
    font: 12px var(--mono);
    margin-top: 3px;
    min-width: 0;
  }
  iframe {
    flex: 1 1 0;
    min-height: 0;
    width: 100%;
    border: none;
    background: #1e1e26;
  }
  .error {
    margin: 0;
    flex: none;
    padding: 6px 10px;
    background: var(--error-bg);
    color: var(--error);
    font-size: 12px;
  }
</style>
