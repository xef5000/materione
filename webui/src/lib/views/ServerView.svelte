<script lang="ts">
  import { server } from '../state.svelte';
</script>

<dl>
  <dt>server version</dt>
  <dd>{server.version || '—'}</dd>
  <dt>protocol version</dt>
  <dd>{server.protocolVersion || '—'}</dd>
</dl>

<p class="note">
  Server-side actions (manual reload, settings editing, audit trail) arrive with the
  control-center update — this view is their home.
</p>

<style>
  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 4px 14px;
    font-size: 12.5px;
    margin: 4px 0 12px;
  }
  dt {
    color: var(--muted);
  }
  dd {
    margin: 0;
    font-family: var(--mono);
  }
  .note {
    color: var(--faint);
    font-size: 12px;
    border-top: 1px dotted var(--border);
    padding-top: 10px;
  }
</style>
