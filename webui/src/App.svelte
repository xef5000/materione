<script lang="ts">
  import Login from './lib/components/Login.svelte';
  import Dashboard from './lib/components/Dashboard.svelte';
  import { EditorChannel } from './lib/protocol/session';
  import type { ChannelState } from './lib/protocol/session';

  type Phase = 'login' | 'connecting' | 'awaiting' | 'active' | 'closed' | 'error';

  let phase = $state<Phase>('login');
  let error = $state('');
  let noncePrefix = $state('');
  let channel = $state<EditorChannel | null>(null);
  let browserLabel = navigator.userAgent.replace(/^.*\)\s*/, '').slice(0, 40) || 'browser';

  async function connect(code: string, fragmentKey: string) {
    phase = 'connecting';
    error = '';
    try {
      const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
      const wsUrl = `${wsProtocol}://${location.host}/editor/ws`;
      channel = await EditorChannel.open(location.origin, wsUrl, code, fragmentKey, browserLabel, {
        onState: (state: ChannelState) => {
          if (state.kind === 'awaiting-approval') {
            noncePrefix = state.noncePrefix;
            phase = 'awaiting';
          }
        },
      });
      channel.onState((state) => {
        if (state.kind === 'closed') {
          phase = 'closed';
          error = state.reason;
        }
      });
      noncePrefix = '';
      phase = 'active';
    } catch (e) {
      phase = 'error';
      error = e instanceof Error ? e.message : String(e);
    }
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

<header>
  <h1>Materione <span>editor</span></h1>
  {#if phase === 'active'}
    <span class="badge ok">connected · {browserLabel}</span>
    <button
      class="ghost"
      onclick={() => {
        channel?.close();
        location.reload();
      }}>disconnect</button
    >
  {/if}
</header>

<main>
  {#if phase === 'active' && channel}
    <Dashboard {channel} />
  {:else}
    <Login {phase} {error} {noncePrefix} onConnect={connect} />
  {/if}
</main>

<style>
  :global(html) {
    color-scheme: dark;
  }
  :global(body) {
    margin: 0;
    background: #12151b;
    color: #dfe4ec;
    font:
      15px/1.5 system-ui,
      sans-serif;
  }
  header {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 18px;
    border-bottom: 1px solid #262c37;
  }
  h1 {
    font-size: 17px;
    margin: 0 auto 0 0;
  }
  h1 span {
    color: #7aa2f7;
    font-weight: 400;
  }
  .badge.ok {
    color: #9ece6a;
    font-size: 12px;
  }
  button.ghost {
    background: none;
    border: 1px solid #3b4453;
    color: inherit;
    border-radius: 6px;
    padding: 4px 12px;
    cursor: pointer;
  }
  main {
    padding: 18px;
  }
</style>
