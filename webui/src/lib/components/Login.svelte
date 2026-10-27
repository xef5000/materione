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
    padding: var(--space-6) var(--space-6);
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
    border-radius: var(--radius);
    font: var(--text-base) var(--mono);
    padding: var(--space-3);
    resize: vertical;
  }
  button:not(.btn) {
    margin-top: var(--space-3);
    background: var(--accent);
    border: none;
    color: var(--accent-ink);
    border-radius: var(--radius);
    padding: var(--space-2) var(--space-5);
    cursor: pointer;
  }
  button:not(.btn):disabled {
    opacity: 0.5;
    cursor: default;
  }
  code {
    background: var(--bg-input);
    border-radius: var(--radius-sm);
    padding: var(--space-half) var(--space-2);
  }
  code.nonce {
    color: var(--accent);
    font-size: var(--text-md);
  }
  .status {
    color: var(--warn);
  }
  .error {
    color: var(--error);
  }
  details {
    margin-top: var(--space-4);
    color: var(--muted);
  }
</style>
