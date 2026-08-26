<script lang="ts">
  import Login from './lib/components/Login.svelte';
  import Workbench from './lib/components/Workbench.svelte';
  import { EditorChannel } from './lib/protocol/session';
  import type { ChannelState } from './lib/protocol/session';
  import type { PushFrame } from './lib/events';
  import { bus, loadWorkspace, logActivity, resetWorkspace, session } from './lib/state.svelte';

  type Phase = 'login' | 'connecting' | 'awaiting' | 'active' | 'closed' | 'error';

  let phase = $state<Phase>('login');
  let error = $state('');
  let noncePrefix = $state('');
  let channel = $state<EditorChannel | null>(null);
  let browserLabel = navigator.userAgent.replace(/^.*\)\s*/, '').slice(0, 40) || 'browser';

  // Fires mid-handshake, before EditorChannel.open resolves.
  function handleHandshakeState(state: ChannelState): void {
    if (state.kind === 'awaiting-approval') {
      noncePrefix = state.noncePrefix;
      phase = 'awaiting';
    }
  }

  async function connect(code: string, fragmentKey: string) {
    phase = 'connecting';
    error = '';
    try {
      const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
      const wsUrl = `${wsProtocol}://${location.host}/editor/ws`;
      channel = await EditorChannel.open(location.origin, wsUrl, code, fragmentKey, browserLabel, {
        onState: handleHandshakeState,
      });
      session.channel = channel;
      // The channel allows a single listener per kind; everything below
      // subscribes through the fan-out signals instead.
      channel.onState((state: ChannelState) => {
        if (state.kind === 'closed') {
          resetWorkspace();
          phase = 'closed';
          error = state.reason;
        }
      });
      channel.onPush((push) => {
        bus.push.emit({ op: push.op, payload: push.payload as Record<string, unknown> });
      });
      noncePrefix = '';
      await loadWorkspace();
      phase = 'active';
    } catch (e) {
      resetWorkspace();
      phase = 'error';
      error = e instanceof Error ? e.message : String(e);
    }
  }

  function disconnect() {
    channel?.close();
    location.reload();
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

  // Keep the pipeline dock fed with server pushes even before any view cares.
  $effect(() => {
    return bus.push.add((frame: PushFrame) => {
      if (frame.op === 'push.reload') {
        const accepted = Boolean(frame.payload.accepted);
        const line =
          `reload ${accepted ? 'applied' : 'rejected'} — items live: ${String(frame.payload.itemsLive)}` +
          ` (${String(frame.payload.files)})`;
        connection.lastReload = line;
        logActivity('reload', line);
      }
    });
  });
</script>

<main>
  {#if phase === 'active' && channel}
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
</style>
