<script lang="ts">
  import Landing from './lib/components/Landing.svelte';
  import Login from './lib/components/Login.svelte';
  import Workbench from './lib/components/Workbench.svelte';
  import { openChannel } from './lib/protocol/connect';
  import type { ChannelState, EditorChannel } from './lib/protocol/session';
  import { encodeBase64Url } from './lib/protocol/wire';
  import { detectHost, startIntent, type EditorHost } from './lib/host';
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
  // null until /host.json answers; only a cloud host shows the landing card and the demo.
  let host = $state<EditorHost | null>(null);
  let demo = $state(false);
  let demoStarting = $state(false);
  let demoError = $state('');
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

  // The demo is this same editor over an in-tab DemoServer (lib/demo/): nothing is sent anywhere.
  async function startDemo() {
    if (demoStarting) return;
    demoStarting = true;
    demoError = '';
    // Reflect the demo in the address at once: the snapshot import and workspace
    // load below can take seconds on a slow runner, and a reload mid-load must
    // still restart the demo.
    history.replaceState(null, '', `${location.pathname}?demo`);
    try {
      const { createDemoChannel } = await import('./lib/demo/index');
      resetWorkspace();
      session.channel = await createDemoChannel();
      connection.connected = true;
      connection.error = '';
      await loadWorkspace();
      await refreshDelivery();
      demo = true;
      hasWorkspace = true;
      phase = 'active';
    } catch (e) {
      resetWorkspace();
      history.replaceState(null, '', location.pathname);
      demoError = e instanceof Error ? e.message : String(e);
    } finally {
      demoStarting = false;
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
    if (demo) {
      demo = false;
      history.replaceState(null, '', location.pathname);
    }
    phase = 'login';
  }

  function warnBeforeUnload(event: BeforeUnloadEvent) {
    if (!hasUnsavedChanges()) return;
    event.preventDefault();
    event.returnValue = '';
  }

  // A capability URL connects at once, whatever the host; otherwise ask which
  // host served the page before choosing between the landing card and Login.
  const initialIntent = startIntent(location.search, location.hash);
  void detectHost().then((detected) => {
    host = detected;
    if (detected === 'cloud' && initialIntent.kind === 'demo') void startDemo();
  });

  // Auto-connect when opened straight from the capability URL: /?s=<code>#<key>.
  $effect(() => {
    if (phase !== 'login') {
      return;
    }
    const intent = startIntent(location.search, location.hash);
    if (intent.kind === 'session') {
      history.replaceState(null, '', location.pathname); // scrub code+key from the address bar
      void connect(intent.code, intent.key);
    }
  });

</script>

<svelte:window onbeforeunload={warnBeforeUnload} />

<main>
  {#if demo}
    <div class="demo-banner" role="status">
      <strong>Demo</strong> Sample content in this tab only: no server, nothing is saved anywhere.
      Reloading starts over.
      <button onclick={disconnect}>Exit demo</button>
    </div>
  {/if}
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
    <Workbench onDisconnect={disconnect} />
  {:else if host === 'cloud' && phase === 'login'}
    <Landing busy={demoStarting} error={demoError} onDemo={startDemo} />
  {:else if host !== null || phase !== 'login'}
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
    padding: var(--space-2) var(--space-4);
    font-size: var(--text-base);
  }
  .offline button:not(.btn) { margin-left: var(--space-3); }
  .demo-banner {
    background: var(--bg-raised);
    border-bottom: 1px solid var(--border);
    color: var(--muted);
    padding: var(--space-2) var(--space-4);
    font-size: var(--text-base);
  }
  .demo-banner strong {
    color: var(--accent);
    margin-right: var(--space-2);
  }
  .demo-banner button:not(.btn) { margin-left: var(--space-3); }
  .reconnect { max-height: 55vh; overflow: auto; flex: none; }
</style>
