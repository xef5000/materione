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

  interface Props {
    onDisconnect: () => void;
  }

  let { onDisconnect }: Props = $props();

  interface AuditEntry {
    ts: string;
    actor: string;
    op: string;
    target?: string;
  }

  type AuditRow = AuditEntry & { occurrence: number };

  let audit = $state<AuditRow[]>([]);
  let auditFilter = $state('');
  let reloading = $state(false);
  let nextAuditOccurrence = 0;

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
      audit = ((payload.entries as AuditEntry[]) ?? []).map((entry) => ({
        ...entry,
        occurrence: nextAuditOccurrence++,
      }));
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
    <button class="btn btn-primary" disabled={reloading} onclick={() => void reloadScope('everything')}>
      {reloading ? 'reloading…' : 'full reload'}
    </button>
    <button onclick={openSettings}>edit settings.yml</button>
    <button onclick={onDisconnect}>disconnect</button>
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
    <button class="btn btn-sm" onclick={() => void loadAudit()}>refresh</button>
  </div>
  <input class="filter" placeholder="filter op / target / actor…" bind:value={auditFilter} />
  <table>
    <tbody>
      {#each shownAudit as entry (entry.occurrence)}
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
    padding-top: var(--space-2);
    margin-top: var(--space-3);
  }
  h3 {
    font-size: var(--text-sm);
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--muted);
    margin: 0 0 var(--space-2);
  }
  .head {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
  }
  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: var(--space-1) var(--space-4);
    font-size: var(--text-sm);
    margin: var(--space-1) 0 var(--space-3);
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
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-bottom: var(--space-2);
  }
  button:not(.btn) {
    border-radius: var(--radius);
    cursor: pointer;
    font-size: var(--text-sm);
    padding: var(--space-1) var(--space-3);
  }
  .actions button:not(.btn-primary),
  .packs button {
    background: none;
    border: 1px solid var(--border-strong);
    color: inherit;
  }
  button:not(.btn):disabled {
    opacity: 0.5;
    cursor: default;
  }
  .packs {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .filter {
    width: 100%;
    box-sizing: border-box;
    margin-bottom: var(--space-2);
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-sm);
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: var(--text-sm);
  }
  td {
    padding: var(--space-half) var(--space-2) var(--space-half) 0;
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
