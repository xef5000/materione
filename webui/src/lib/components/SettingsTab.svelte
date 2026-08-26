<script lang="ts">
  /**
   * The plugin settings editor: the raw settings.yml with hash-guarded
   * saves. YAML-only on purpose — nine flat keys don't need a generated
   * form, and honesty about restart-required keys beats pretending they
   * hot-apply.
   */
  import { ops, unwrap } from '../api';
  import { errorMessage, logActivity, session } from '../state.svelte';

  let content = $state('');
  let sha = $state('');
  let error = $state('');
  let dirty = $state(false);
  let saving = $state(false);

  $effect(() => {
    void load();
  });

  async function load() {
    try {
      const payload = unwrap(await ops.settingsGet(session.channel!));
      content = String(payload.raw ?? '');
      sha = String(payload.sha256 ?? '');
      dirty = false;
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
      await load();
    } catch (e) {
      const message = errorMessage(e);
      error = message.includes('MTR-0034')
        ? 'settings.yml changed elsewhere since you loaded it — reload and re-apply.'
        : message;
    } finally {
      saving = false;
    }
  }
</script>

<article>
  <header>
    <h2>plugin settings</h2>
    <span class="meta">settings/settings.yml{dirty ? ' · unsaved' : ''}</span>
    <button onclick={() => void load()}>reload</button>
    <button class="primary" disabled={saving || !dirty} onclick={() => void save()}>
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
    padding: 14px 18px;
    height: 100%;
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
  }
  header {
    display: flex;
    align-items: baseline;
    gap: 12px;
  }
  h2 {
    margin: 0 auto 0 0;
    font-size: 16px;
  }
  .meta {
    color: var(--muted);
    font-size: 12px;
    margin-right: auto;
  }
  button {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: 4px 12px;
    cursor: pointer;
    font-size: 13px;
  }
  button.primary {
    background: var(--primary);
    border-color: var(--primary);
    color: white;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .note {
    color: var(--faint);
    font-size: 12px;
    margin: 8px 0;
  }
  .error {
    background: var(--error-bg);
    color: var(--error);
    border-radius: 8px;
    padding: 8px 12px;
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
    border-radius: 8px;
    font: 13px/1.55 var(--mono);
    padding: 10px;
    resize: vertical;
  }
</style>
