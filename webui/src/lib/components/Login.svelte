<script lang="ts">
  interface Props {
    phase: 'login' | 'connecting' | 'awaiting' | 'closed' | 'error';
    error: string;
    noncePrefix: string;
    onConnect: (code: string, fragmentKey: string, origin?: string) => void;
  }

  let { phase, error, noncePrefix, onConnect }: Props = $props();

  let pasted = $state('');

  function submit(event: SubmitEvent) {
    event.preventDefault();
    const url = pasted.trim();
    const question = url.indexOf('?');
    const hash = url.indexOf('#');
    if (question < 0 || hash < 0 || hash < question) {
      return;
    }
    const params = new URLSearchParams(url.slice(question + 1, hash));
    const code = params.get('s');
    const key = url.slice(hash + 1);
    // A pasted direct-mode URL speaks to ITS origin, not this page's host —
    // the blob fetch honors it (relay URLs always match this page anyway).
    const origin = url.startsWith('http') ? new URL(url).origin : undefined;
    if (code && key) {
      onConnect(code, key, origin);
      pasted = '';
    }
  }
</script>

<section class="card">
  <h2>Open a session</h2>
  <p>
    Run <code>/materione editor</code> in the server console and paste the URL it prints here — or
    just open that URL directly; this page reads the same capability.
  </p>
  <form onsubmit={submit}>
    <textarea
      rows="3"
      placeholder="http://127.0.0.1:8089/?s=…#…"
      bind:value={pasted}
      spellcheck="false"
      aria-label="Editor session URL"
    ></textarea>
    <button type="submit" disabled={phase === 'connecting' || pasted.trim().length < 10}>
      Connect
    </button>
  </form>

  {#if phase === 'connecting'}
    <p class="status">decrypting session…</p>
  {:else if phase === 'awaiting'}
    <p class="status">
      Waiting for approval <strong>in game</strong> — look for nonce
      <code class="nonce">{noncePrefix}</code> and click Approve (or run
      <code>/materione editor approve {noncePrefix}</code>). The request expires after 120&nbsp;s.
    </p>
  {:else if phase === 'error' || phase === 'closed'}
    <p class="error">Connection failed: {error}</p>
  {/if}

  <details>
    <summary>Browsers we support</summary>
    <p>
      The handshake uses WebCrypto Ed25519 — Chrome 133+, Safari 17+ or Firefox 129+. Opening over
      plain HTTP only works on localhost; remote access needs an HTTPS reverse proxy.
    </p>
  </details>
</section>

<style>
  .card {
    max-width: 640px;
    margin: 8vh auto 0;
    background: var(--bg-panel);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: 22px 26px;
  }
  h2 {
    margin-top: 0;
  }
  textarea {
    width: 100%;
    box-sizing: border-box;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: 8px;
    font: 13px var(--mono);
    padding: 10px;
    resize: vertical;
  }
  button {
    margin-top: 10px;
    background: var(--primary);
    border: none;
    color: white;
    border-radius: 8px;
    padding: 8px 18px;
    cursor: pointer;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  code {
    background: var(--bg-input);
    border-radius: 4px;
    padding: 1px 6px;
  }
  code.nonce {
    color: var(--accent);
    font-size: 15px;
  }
  .status {
    color: var(--warn);
  }
  .error {
    color: var(--error);
  }
  details {
    margin-top: 16px;
    color: var(--muted);
  }
</style>
