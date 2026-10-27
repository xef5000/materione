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
    padding: var(--space-10) var(--space-8) var(--space-6);
    text-align: center;
  }
  .logo {
    display: block;
    margin: 0 auto var(--space-4);
    border-radius: var(--radius-lg);
  }
  h1 {
    margin: 0;
    font-size: var(--text-display);
    letter-spacing: 0.01em;
  }
  .subtitle {
    margin: var(--space-1) 0 var(--space-6);
    color: var(--muted);
    font-size: var(--text-md);
  }
  .demo {
    background: var(--accent);
    border: none;
    color: var(--accent-ink);
    border-radius: var(--radius);
    padding: var(--space-3) var(--space-8);
    font-size: var(--text-md);
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
    margin: var(--space-6) 0 0;
    color: var(--muted);
    text-wrap: balance;
  }
  code {
    white-space: nowrap;
    background: var(--bg-input);
    color: var(--fg-code);
    border-radius: var(--radius-sm);
    padding: var(--space-half) var(--space-2);
  }
  .error {
    color: var(--error);
    margin: var(--space-4) 0 0;
  }
  .links {
    display: flex;
    justify-content: center;
    gap: var(--space-5);
    margin-top: var(--space-6);
    padding-top: var(--space-4);
    border-top: 1px solid var(--border);
    font-size: var(--text-base);
  }
  .links a {
    color: var(--faint);
    text-decoration: none;
  }
  .links a:hover {
    color: var(--accent);
  }
</style>
