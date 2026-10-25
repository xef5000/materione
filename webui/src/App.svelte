<script lang="ts">
  import Login from './lib/components/Login.svelte';
  import Workbench from './lib/components/Workbench.svelte';
  import { openChannel } from './lib/protocol/connect';
  import type { ChannelState, EditorChannel } from './lib/protocol/session';
  import { encodeBase64Url } from './lib/protocol/wire';
  import { draftStore, ensureDocumentDraft, reconcileDocumentDraft } from './lib/drafts.svelte';
  import {
    confirmDisconnect,
    connection,
    handlePush,
    hasUnsavedChanges,
    loadWorkspace,
    markConnectionLost,
    refreshDelivery,
    resetWorkspace,
    session,
  } from './lib/state.svelte';

  type Phase = 'login' | 'connecting' | 'awaiting' | 'active' | 'closed' | 'error';

  let phase = $state<Phase>('login');
  let error = $state('');
  let noncePrefix = $state('');
  let channel = $state<EditorChannel | null>(null);
  let hasWorkspace = $state(false);
  let reconnecting = $state(false);
  let serverIdentity = '';
  let browserLabel = navigator.userAgent.replace(/^.*\)\s*/, '').slice(0, 40) || 'browser';

  // Fires mid-handshake, before EditorChannel.open resolves.
  function handleHandshakeState(state: ChannelState): void {
    if (state.kind === 'awaiting-approval') {
      noncePrefix = state.noncePrefix;
      phase = 'awaiting';
    }
  }

  async function connect(code: string, fragmentKey: string, origin?: string) {
    phase = 'connecting';
    error = '';
    try {
      // Transport selection lives in connect.ts: the blob decides relay vs
      // direct, with a reachable local listener preferred (LAN auto-preference).
      const opened = await openChannel(code, fragmentKey, browserLabel, handleHandshakeState, origin);
      const identity = encodeBase64Url(opened.channel.blob.pluginSigningPubKey);
      if (serverIdentity && identity !== serverIdentity) {
        opened.channel.close();
        throw new Error('This session belongs to a different server. Existing drafts were kept; disconnect and discard them before switching servers.');
      }
      serverIdentity = identity;
      channel = opened.channel;
      session.channel = channel;
      connection.error = '';
      // The channel allows a single listener per kind; everything below
      // subscribes through the fan-out signals instead.
      channel.onState((state: ChannelState) => {
        if (state.kind === 'closed') {
          if (channel !== opened.channel) return;
          markConnectionLost(state.reason);
          phase = 'closed';
          error = state.reason;
        }
      });
      channel.onPush((push) => {
        if (channel === opened.channel) {
          handlePush({ op: push.op, payload: push.payload as Record<string, unknown> });
        }
      });
      noncePrefix = '';
      await loadWorkspace();
      if (hasWorkspace) {
        for (const draft of draftStore.documents) {
          if (draft.loaded) await reconcileDocumentDraft(draft, opened.channel);
          else await ensureDocumentDraft(draft, opened.channel);
        }
      }
      await refreshDelivery();
      if (session.channel !== opened.channel) {
        throw new Error('The connection closed while loading server state. Drafts were kept.');
      }
      connection.connected = true;
      hasWorkspace = true;
      reconnecting = false;
      phase = 'active';
    } catch (e) {
      if (!hasWorkspace) resetWorkspace();
      phase = 'error';
      error = e instanceof Error ? e.message : String(e);
    }
  }

  function disconnect() {
    if (!confirmDisconnect()) return;
    const closing = channel;
    channel = null;
    closing?.close();
    resetWorkspace();
    serverIdentity = '';
    hasWorkspace = false;
    reconnecting = false;
    phase = 'login';
  }

  function warnBeforeUnload(event: BeforeUnloadEvent) {
    if (!hasUnsavedChanges()) return;
    event.preventDefault();
    event.returnValue = '';
  }

  // Auto-connect when opened straight from the capability URL: /?s=<code>#<key>.
  $effect(() => {
    if (phase !== 'login') {
      return;
    }
    const params = new URLSearchParams(location.search);
    const code = params.get('s');
    const key = location.hash.length > 1 ? location.hash.slice(1) : '';
    if (code && key) {
      history.replaceState(null, '', location.pathname); // scrub code+key from the address bar
      void connect(code, key);
    }
  });

</script>

<svelte:window onbeforeunload={warnBeforeUnload} />

<main>
  {#if hasWorkspace}
    {#if !connection.connected}
      <div class="offline" role="status">
        Disconnected. Drafts remain in this tab; runtime and pack status are unknown.
        <button onclick={() => (reconnecting = !reconnecting)}>Reconnect</button>
      </div>
    {/if}
    {#if reconnecting}
      <div class="reconnect">
        <Login phase={phase === 'active' ? 'closed' : phase} {error} {noncePrefix} onConnect={connect} />
      </div>
    {/if}
    <Workbench {browserLabel} onDisconnect={disconnect} />
  {:else}
    <Login
      phase={phase === 'active' ? 'login' : phase}
      {error}
      {noncePrefix}
      onConnect={connect}
    />
  {/if}
</main>

<style>
  main {
    height: 100vh;
    display: flex;
    flex-direction: column;
  }
  .offline {
    background: var(--warn-bg);
    color: var(--warn);
    padding: 8px 14px;
    font-size: 13px;
  }
  .offline button { margin-left: 12px; }
  .reconnect { max-height: 55vh; overflow: auto; flex: none; }
</style>
