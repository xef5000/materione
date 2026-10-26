<script lang="ts">
  // The cloud editor's front door (web.md §6, cloud landing): shown on editor.materione.com
  // when the address carries no session. Plugin-served editors never render it.
  interface Props {
    busy: boolean;
    error: string;
    onDemo: () => void;
  }

  let { busy, error, onDemo }: Props = $props();
</script>

<section class="landing" aria-labelledby="landing-title">
  <img class="logo" src="/materione-logo.svg" alt="" width="72" height="72" />
  <h1 id="landing-title">Materione</h1>
  <p class="subtitle">Web content editor</p>
  <button type="button" class="demo" onclick={onDemo} disabled={busy}>
    {busy ? 'Opening demo…' : 'View Demo'}
  </button>
  {#if error}
    <p class="error" role="alert">The demo could not start: {error}</p>
  {/if}
  <p class="hint">To start a new session, run <code>/materione editor</code> in-game</p>
  <nav class="links" aria-label="Materione">
    <a href="https://materione.com">materione.com</a>
    <a href="https://materione.com/docs/">docs</a>
  </nav>
</section>

<style>
  .landing {
    width: min(460px, calc(100vw - 32px));
    box-sizing: border-box;
    margin: 14vh auto 0;
    background: var(--bg-panel);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: 36px 32px 24px;
    text-align: center;
  }
  .logo {
    display: block;
    margin: 0 auto 14px;
    border-radius: 16px;
  }
  h1 {
    margin: 0;
    font-size: 30px;
    letter-spacing: 0.01em;
  }
  .subtitle {
    margin: 4px 0 26px;
    color: var(--muted);
    font-size: 15px;
  }
  .demo {
    background: var(--primary);
    border: none;
    color: white;
    border-radius: 8px;
    padding: 10px 28px;
    font-size: 15px;
    font-weight: 600;
    cursor: pointer;
  }
  .demo:hover:not(:disabled) {
    filter: brightness(1.1);
  }
  .demo:disabled {
    opacity: 0.6;
    cursor: default;
  }
  .hint {
    margin: 26px 0 0;
    color: var(--muted);
    text-wrap: balance;
  }
  code {
    white-space: nowrap;
    background: var(--bg-input);
    color: var(--fg-code);
    border-radius: 4px;
    padding: 1px 6px;
  }
  .error {
    color: var(--error);
    margin: 14px 0 0;
  }
  .links {
    display: flex;
    justify-content: center;
    gap: 18px;
    margin-top: 22px;
    padding-top: 16px;
    border-top: 1px solid var(--border);
    font-size: 13px;
  }
  .links a {
    color: var(--faint);
    text-decoration: none;
  }
  .links a:hover {
    color: var(--accent);
  }
</style>
