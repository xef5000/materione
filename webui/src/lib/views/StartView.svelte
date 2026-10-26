<script lang="ts">
  /**
   * Start: what an owner can make, what needs fixing, and what behaviour this
   * server offers — all read from the registry (`authoring.describe`), the
   * diagnostics feed and the in-tab drafts. It owns no content state; every
   * starter hands off to Content/Studio, which write through the one pipeline.
   */
  import { typeTabs } from '../browser';
  import { diagnosticRepairTarget } from '../diagnostics';
  import { draftDirty, draftStore } from '../drafts.svelte';
  import { browseTo, diagnostics, openStudio, revealInStudio, startCreate, ui, workspace } from '../state.svelte';

  const creatable = $derived(typeTabs(workspace.types).filter((tab) => tab.enabled));
  const counts = $derived(
    Object.fromEntries(creatable.map((tab) => [
      tab.directory,
      workspace.resources.filter((resource) => resource.path.startsWith(`${tab.directory}/`)).length,
    ])),
  );
  const descriptions = $derived(
    Object.fromEntries(workspace.types.map((type) => [type.directory ?? type.name, type.description])),
  );
  const fixes = $derived(
    diagnostics.entries
      .filter((entry) => ['error', 'warn'].includes(entry.severity.toLowerCase()))
      .map((entry) => ({ entry, repair: diagnosticRepairTarget(entry, workspace.resources) }))
      .filter((row) => row.repair !== null)
      .slice(0, 6),
  );
  const pending = $derived(
    draftStore.documents
      .filter((draft) => draft.pack === workspace.activePack && draftDirty(draft))
      .map((draft) => ({
        draft,
        resource: workspace.resources.find((resource) =>
          resource.pack === draft.pack && resource.path === draft.path),
      })),
  );
  const mechanics = $derived(workspace.behavior?.mechanics ?? []);
  const builtIn = $derived(mechanics.filter((mechanic) => mechanic.id.startsWith('materione:')));
  const addons = $derived(mechanics.filter((mechanic) => !mechanic.id.startsWith('materione:')));
  const firing = $derived((workspace.behavior?.events ?? []).filter((event) => event.firedBy.length > 0));

  /** Abilities live on items: open the item catalogue to pick one. */
  function addAbility() {
    openStudio(null);
    browseTo('items');
    ui.view = 'content';
  }
</script>

<div class="start">
  <section aria-labelledby="start-create">
    <h2 id="start-create">Create</h2>
    {#if creatable.length === 0}
      <p class="note">Connect to a server to see what it can create.</p>
    {/if}
    <div class="starters">
      {#each creatable as tab (tab.directory)}
        <button type="button" class="starter" onclick={() => startCreate(tab.directory)}>
          <strong>New {tab.singular}</strong>
          <span>{descriptions[tab.directory] ?? ''}</span>
          <small>{counts[tab.directory] ?? 0} in this project</small>
        </button>
      {/each}
    </div>
  </section>

  {#if fixes.length > 0}
    <section aria-labelledby="start-fix">
      <h2 id="start-fix">Needs attention</h2>
      <ul>
        {#each fixes as row, index (index)}
          <li>
            <code>{row.entry.code}</code>
            <span class="message">{row.entry.message}</span>
            <button type="button" onclick={() => void revealInStudio(row.repair!.resource, row.repair!.field)}>
              {row.repair!.field ? `Fix ${row.repair!.field}` : 'Open'}
            </button>
          </li>
        {/each}
      </ul>
    </section>
  {/if}

  {#if pending.length > 0}
    <section aria-labelledby="start-pending">
      <h2 id="start-pending">Unsaved changes</h2>
      <ul>
        {#each pending as row (row.draft.path)}
          <li>
            <span class="message">{row.draft.path}</span>
            {#if row.resource}
              <button type="button" onclick={() => openStudio(row.resource!)}>Continue</button>
            {/if}
          </li>
        {/each}
      </ul>
    </section>
  {/if}

  <section aria-labelledby="start-behaviour">
    <h2 id="start-behaviour">Behaviour on this server</h2>
    {#if !workspace.behavior}
      <p class="note">This server does not describe its abilities or rule vocabulary; behaviour is edited in Files.</p>
    {:else}
      <p class="note">
        {builtIn.length} built-in {builtIn.length === 1 ? 'ability' : 'abilities'}{addons.length
          ? `, ${addons.length} from add-ons` : ''}; rules can react to {firing.map((event) => event.displayName.toLowerCase()).join(', ')}.
      </p>
      <ul class="abilities">
        {#each mechanics as mechanic (mechanic.id)}
          <li>
            <strong>{mechanic.displayName}</strong>
            <span class="message">{mechanic.activation ?? mechanic.description}</span>
            <small>{mechanic.id.startsWith('materione:') ? 'built in' : mechanic.registeredBy}</small>
          </li>
        {/each}
      </ul>
      <button type="button" class="secondary" onclick={addAbility}>Add an ability to an item</button>
    {/if}
  </section>
</div>

<style>
  .start {
    height: 100%;
    overflow-y: auto;
    padding: 20px 24px;
    box-sizing: border-box;
    min-width: 0;
    display: grid;
    align-content: start;
    gap: 22px;
  }
  section {
    min-width: 0;
    max-width: 900px;
  }
  h2 {
    margin: 0 0 10px;
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--faint);
    font-weight: normal;
  }
  .starters {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
    gap: 10px;
  }
  .starter {
    display: grid;
    gap: 4px;
    text-align: left;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: none;
    color: inherit;
    padding: 10px 12px;
    cursor: pointer;
    min-width: 0;
  }
  .starter:hover {
    background: var(--bg-hover);
  }
  .starter span,
  .starter small {
    color: var(--muted);
    font-size: 11px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  ul {
    list-style: none;
    margin: 0 0 10px;
    padding: 0;
    display: grid;
    gap: 6px;
  }
  li {
    display: flex;
    align-items: baseline;
    gap: 8px;
    min-width: 0;
    font-size: 12px;
  }
  .message {
    flex: 1;
    min-width: 0;
    color: var(--muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  li small {
    color: var(--faint);
  }
  li button,
  .secondary {
    border: 1px solid var(--border-strong);
    background: none;
    color: inherit;
    border-radius: var(--radius);
    padding: 2px 9px;
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
  }
  .note {
    margin: 0 0 10px;
    font-size: 12px;
    color: var(--muted);
  }
</style>
