<script lang="ts">
  interface Props {
    phase: 'login' | 'connecting' | 'awaiting' | 'closed' | 'error';
    error: string;
    noncePrefix: string;
    onConnect: (code: string, fragmentKey: string) => void;
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
    if (code && key) {
      onConnect(code, key);
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
    background: #181c24;
    border: 1px solid #262c37;
    border-radius: 12px;
    padding: 22px 26px;
  }
  h2 {
    margin-top: 0;
  }
  textarea {
    width: 100%;
    box-sizing: border-box;
    background: #0e1116;
    color: inherit;
    border: 1px solid #3b4453;
    border-radius: 8px;
    font: 13px ui-monospace, monospace;
    padding: 10px;
    resize: vertical;
  }
  button {
    margin-top: 10px;
    background: #2f6fed;
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
    background: #0e1116;
    border-radius: 4px;
    padding: 1px 6px;
  }
  code.nonce {
    color: #7aa2f7;
    font-size: 15px;
  }
  .status {
    color: #e0af68;
  }
  .error {
    color: #f7768e;
  }
  details {
    margin-top: 16px;
    color: #8b93a5;
  }
</style>
