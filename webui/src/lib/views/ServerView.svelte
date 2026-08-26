<script lang="ts">
  /**
   * The control center: server identity, the editor listener state, manual
   * pipeline reloads, and the audit trail. Settings open as a tab so they
   * behave like every other document in the workbench.
   */
  import { ops, unwrap } from '../api';
  import {
    errorMessage,
    logActivity,
    openSettings,
    refreshPack,
    selectPack,
    server,
    session,
    workspace,
  } from '../state.svelte';

  interface AuditEntry {
    ts: string;
    actor: string;
    op: string;
    target?: string;
  }

  let audit = $state<AuditEntry[]>([]);
  let auditFilter = $state('');
  let reloading = $state(false);

  const shownAudit = $derived(
    auditFilter.trim()
      ? audit.filter((entry) =>
          (entry.op + ' ' + (entry.target ?? '') + ' ' + entry.actor)
            .toLowerCase()
            .includes(auditFilter.trim().toLowerCase()),
        )
      : audit,
  );

  async function reloadScope(scope: 'everything' | 'one-pack', pack?: string) {
    if (!session.channel) return;
    reloading = true;
    try {
      unwrap(await ops.reloadScope(session.channel, scope, pack));
      // Fire-and-forget server-side: the outcome lands in the pipeline dock
      // as push.reload (manual reloads always announce).
      logActivity('reload', `manual ${scope}${pack ? ':' + pack : ''} reload queued`);
      await refreshPack();
    } catch (e) {
      logActivity('error', `pipeline.reload: ${errorMessage(e)}`);
    } finally {
      reloading = false;
    }
  }

  async function loadAudit() {
    try {
      const payload = unwrap(await ops.audit(session.channel!, 100));
      audit = (payload.entries as AuditEntry[]) ?? [];
    } catch (e) {
      logActivity('error', `admin.audit.recent: ${errorMessage(e)}`);
    }
  }

  function switchAndReload(pack: string) {
    void (async () => {
      await selectPack(pack);
      await reloadScope('one-pack', pack);
    })();
  }

  $effect(() => {
    void loadAudit();
  });
</script>

<div class="block">
  <h3>server</h3>
  <dl>
    <dt>version</dt>
    <dd>{server.version || '—'}</dd>
    <dt>protocol</dt>
    <dd>v{server.protocolVersion || '—'}</dd>
    <dt>editor listener</dt>
    <dd>{server.editorRunning ? `running on :${server.editorPort}` : 'stopped'}</dd>
  </dl>
  <div class="actions">
    <button class="primary" disabled={reloading} onclick={() => void reloadScope('everything')}>
      {reloading ? 'reloading…' : 'full reload'}
    </button>
    <button onclick={openSettings}>edit settings.yml</button>
  </div>
</div>

{#if workspace.packs.length > 0}
  <div class="block">
    <h3>reload one pack</h3>
    <div class="packs">
      {#each workspace.packs as pack (pack)}
        <button
          disabled={reloading}
          title={`targeted reload of ${pack}`}
          onclick={() => (workspace.activePack === pack
            ? void reloadScope('one-pack', pack)
            : switchAndReload(pack))}
        >
          {pack}
        </button>
      {/each}
    </div>
  </div>
{/if}

<div class="block">
  <div class="head">
    <h3>audit trail</h3>
    <button class="small" onclick={() => void loadAudit()}>refresh</button>
  </div>
  <input class="filter" placeholder="filter op / target / actor…" bind:value={auditFilter} />
  <table>
    <tbody>
      {#each shownAudit as entry (entry.ts + entry.op + entry.target)}
        <tr>
          <td class="ts">{new Date(entry.ts).toLocaleTimeString()}</td>
          <td>{entry.op}</td>
          <td class="target">{entry.target ?? ''}</td>
        </tr>
      {/each}
      {#if shownAudit.length === 0}
        <tr><td class="empty" colspan="3">no entries match</td></tr>
      {/if}
    </tbody>
  </table>
</div>

<style>
  .block {
    border-top: 1px dotted var(--border);
    padding-top: 8px;
    margin-top: 10px;
  }
  h3 {
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--muted);
    margin: 0 0 6px;
  }
  .head {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
  }
  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 3px 14px;
    font-size: 12.5px;
    margin: 4px 0 10px;
  }
  dt {
    color: var(--muted);
  }
  dd {
    margin: 0;
    font-family: var(--mono);
  }
  .actions {
    display: flex;
    gap: 8px;
    margin-bottom: 6px;
  }
  button {
    border-radius: var(--radius);
    cursor: pointer;
    font-size: 12.5px;
    padding: 4px 12px;
  }
  .actions .primary {
    background: var(--primary);
    border: none;
    color: white;
  }
  .actions button:not(.primary),
  .packs button,
  .head .small {
    background: none;
    border: 1px solid var(--border-strong);
    color: inherit;
  }
  .head .small {
    font-size: 11px;
    padding: 2px 8px;
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .packs {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .filter {
    width: 100%;
    box-sizing: border-box;
    margin-bottom: 6px;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 4px 8px;
    font-size: 12px;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 12px;
  }
  td {
    padding: 2px 6px 2px 0;
    border-bottom: 1px dotted var(--border);
    vertical-align: top;
  }
  .ts {
    color: var(--faint);
    white-space: nowrap;
  }
  .target {
    font-family: var(--mono);
    word-break: break-all;
  }
  .empty {
    color: var(--faint);
    font-style: italic;
  }
</style>
