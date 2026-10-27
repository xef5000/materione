<script lang="ts">
  /**
   * The plugin settings editor: the raw settings.yml with hash-guarded
   * saves. YAML-only on purpose — nine flat keys don't need a generated
   * form, and honesty about restart-required keys beats pretending they
   * hot-apply.
   */
  import { onMount } from 'svelte';
  import { ops, RpcError, unwrap } from '../api';
  import { connection, errorMessage, logActivity, session, setTabDirty } from '../state.svelte';

  let content = $state('');
  let sha = $state('');
  let error = $state('');
  let dirty = $state(false);
  let saving = $state(false);
  let uncertain = $state(false);
  $effect(() => { setTabDirty('settings', dirty || saving || uncertain); });

  onMount(() => {
    void load();
  });

  async function load(discard = false) {
    if ((dirty || uncertain) && !discard &&
        !confirm('Discard pending settings changes and reload? Cancel to return and save first.')) return;
    try {
      const payload = unwrap(await ops.settingsGet(session.channel!));
      content = String(payload.raw ?? '');
      sha = String(payload.sha256 ?? '');
      dirty = false;
      uncertain = false;
      error = '';
    } catch (e) {
      error = errorMessage(e);
    }
  }

  async function save() {
    saving = true;
    try {
      unwrap(await ops.settingsPut(session.channel!, content, sha));
      logActivity('info', 'settings.yml saved — most changes need a server restart to apply');
      dirty = false;
      await load(true);
    } catch (e) {
      const message = errorMessage(e);
      error = message.includes('MTR-0034')
        ? 'settings.yml changed elsewhere since you loaded it — reload and re-apply.'
        : message;
      if (!(e instanceof RpcError)) {
        uncertain = true;
        error = 'The settings save outcome is unknown. Pending text was kept; reload and reconcile before saving again.';
      }
    } finally {
      saving = false;
    }
  }
</script>

<article>
  <header>
    <h2>plugin settings</h2>
    <span class="meta">settings/settings.yml{dirty ? ' · unsaved' : ''}</span>
    <button disabled={saving || !connection.connected} onclick={() => void load()}>reload</button>
    <button class="btn btn-primary" disabled={saving || !dirty || uncertain || !connection.connected} onclick={() => void save()}>
      {saving ? 'saving…' : 'save'}
    </button>
  </header>
  <p class="note">
    Runtime-cached keys (delivery/editor host + port, …) take effect on the next restart; the
    write is validated before saving, so a typo can never leave the server unbootable.
  </p>
  {#if error}
    <p class="error">{error}</p>
  {/if}
  <textarea
    bind:value={content}
    disabled={saving || uncertain || !connection.connected}
    oninput={() => (dirty = true)}
    rows="22"
    spellcheck="false"
    aria-label="settings.yml"
  ></textarea>
</article>

<style>
  article {
    background: var(--bg-panel);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: var(--space-4) var(--space-5);
    height: 100%;
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
  }
  header {
    display: flex;
    align-items: baseline;
    gap: var(--space-3);
  }
  h2 {
    margin: 0 auto 0 0;
    font-size: var(--text-lg);
  }
  .meta {
    color: var(--muted);
    font-size: var(--text-sm);
    margin-right: auto;
  }
  button:not(.btn) {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-3);
    cursor: pointer;
    font-size: var(--text-base);
  }
  button:not(.btn):disabled {
    opacity: 0.5;
    cursor: default;
  }
  .note {
    color: var(--faint);
    font-size: var(--text-sm);
    margin: var(--space-2) 0;
  }
  .error {
    background: var(--error-bg);
    color: var(--error);
    border-radius: var(--radius);
    padding: var(--space-2) var(--space-3);
    white-space: pre-wrap;
  }
  textarea {
    width: 100%;
    box-sizing: border-box;
    flex: 1;
    min-height: 380px;
    background: var(--bg-input);
    color: var(--fg-code);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    font: var(--text-base)/1.55 var(--mono);
    padding: var(--space-3);
    resize: vertical;
  }
</style>
