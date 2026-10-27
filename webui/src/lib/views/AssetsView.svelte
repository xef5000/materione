<script lang="ts">
  /**
   * Assets: the pack's textures, models and sounds with their users. Selecting
   * one asks the server where it is used (bounded, and honest about what it did
   * not read), and the two ways to change a shared asset are explicit: replace
   * every use with another asset, or give chosen users their own copy. Both
   * are a previewed, revision-checked, all-or-none bulk change — never a loop
   * of independent writes.
   */
  import { assetOps, ops, unwrap } from '../api';
  import {
    assetCategory,
    coverageNote,
    impactSummary,
    parseBulkResult,
    parseReferenceResult,
    retargetOperations,
    bulkHeadline,
    formatValue,
    type BulkResult,
    type ReferenceHit,
    type ReferenceResult,
  } from '../bulk';
  import { readRevisions } from '../bulk-io';
  import {
    assetsUi,
    errorMessage,
    logActivity,
    openAsset,
    refreshPack,
    revealInStudio,
    session,
    ui,
    workspace,
  } from '../state.svelte';

  let { active = true }: { active?: boolean } = $props();

  type Category = 'all' | 'textures' | 'models' | 'sounds';
  const CATEGORIES: Category[] = ['all', 'textures', 'models', 'sounds'];

  let query = $state('');
  let category = $state<Category>('all');
  let thumb = $state('');
  let usage = $state<ReferenceResult | null>(null);
  let usageError = $state('');
  let usageLoading = $state(false);
  let error = $state('');
  let busy = $state(false);
  let list = $state<HTMLDivElement>();

  // Replacement and copy flows
  let flow = $state<null | 'replace' | 'copy'>(null);
  let replaceWith = $state('');
  let copyName = $state('');
  let chosen = $state<string[]>([]);
  let preview = $state<BulkResult | null>(null);

  const pack = $derived(workspace.activePack ?? '');
  const assets = $derived(
    workspace.assets
      .filter((asset) => category === 'all' || assetCategory(asset.path) === category)
      .filter((asset) => !query.trim() || asset.path.toLowerCase().includes(query.trim().toLowerCase())),
  );
  const selected = $derived(workspace.assets.find((asset) => asset.path === assetsUi.selected) ?? null);
  const hits = $derived(usage?.hits ?? []);
  const sameKind = $derived(
    selected
      ? workspace.assets.filter((asset) => asset.path !== selected.path
        && assetCategory(asset.path) === assetCategory(selected.path)
        && asset.kind === selected.kind)
      : [],
  );
  const hitKey = (hit: ReferenceHit): string => `${hit.path}#${hit.id}#${hit.field.join('.')}`;
  const chosenHits = $derived(hits.filter((hit) => chosen.includes(hitKey(hit))));
  const userWord = (count: number): string => `${count} ${count === 1 ? 'use' : 'uses'}`;
  const fileName = (path: string): string => path.slice(path.lastIndexOf('/') + 1);

  // Selecting an asset resets the flows and asks who uses it. A stale answer never replaces a newer one.
  let generation = 0;
  $effect(() => {
    const path = selected?.path;
    const channel = session.channel;
    flow = null;
    preview = null;
    error = '';
    chosen = [];
    usage = null;
    usageError = '';
    thumb = '';
    if (!path || !channel || !pack) return;
    const mine = ++generation;
    usageLoading = true;
    void (async () => {
      try {
        const result = parseReferenceResult(unwrap(await ops.references(channel, pack, 'asset', path)));
        if (mine === generation) usage = result;
      } catch (e) {
        if (mine === generation) usageError = errorMessage(e);
      } finally {
        if (mine === generation) usageLoading = false;
      }
    })();
    if (/\.(png|jpe?g)$/i.test(path)) {
      void (async () => {
        try {
          const data = unwrap(await assetOps.get(channel, pack, path)).data;
          if (mine === generation) {
            thumb = `data:image/${path.toLowerCase().endsWith('.png') ? 'png' : 'jpeg'};base64,${String(data)}`;
          }
        } catch {
          // the preview is optional; the usage answer above is the point of this view
        }
      })();
    }
  });

  function onKey(event: KeyboardEvent): void {
    if (!active || ui.view !== 'assets') return;
    const target = event.target as HTMLElement | null;
    if (target && ['INPUT', 'SELECT', 'TEXTAREA'].includes(target.tagName)) return;
    const index = assets.findIndex((asset) => asset.path === assetsUi.selected);
    const move = (next: number) => {
      const asset = assets[Math.max(0, Math.min(assets.length - 1, next))];
      if (!asset) return;
      assetsUi.selected = asset.path;
      list?.querySelector<HTMLElement>(`[data-path="${CSS.escape(asset.path)}"]`)?.scrollIntoView({ block: 'nearest' });
    };
    if (event.key === 'ArrowDown') { event.preventDefault(); move(index + 1); }
    else if (event.key === 'ArrowUp') { event.preventDefault(); move(index - 1); }
    else if (event.key === 'Home') { event.preventDefault(); move(0); }
    else if (event.key === 'End') { event.preventDefault(); move(assets.length - 1); }
  }

  /** Opens a user in its studio with the field focused; Back returns here. */
  async function openUser(hit: ReferenceHit): Promise<void> {
    if (!selected) return;
    await revealInStudio({ pack: hit.pack, type: hit.type, path: hit.path, id: hit.id }, hit.field.join('.') || undefined);
    if (ui.view === 'content') ui.returnTo = { view: 'assets', label: fileName(selected.path) };
  }

  function startFlow(next: 'replace' | 'copy'): void {
    flow = next;
    preview = null;
    error = '';
    if (next === 'replace') replaceWith = sameKind[0]?.path ?? '';
    if (next === 'copy' && selected) {
      const dot = selected.path.lastIndexOf('.');
      copyName = `${selected.path.slice(0, dot)}_copy${selected.path.slice(dot)}`;
      chosen = [];
    }
  }

  function toggle(hit: ReferenceHit): void {
    const key = hitKey(hit);
    chosen = chosen.includes(key) ? chosen.filter((entry) => entry !== key) : [...chosen, key];
    preview = null;
  }

  function targetPath(): string {
    return flow === 'replace' ? replaceWith : copyName.trim();
  }

  const flowHits = $derived(flow === 'replace' ? hits : chosenHits);
  const canPreview = $derived(
    Boolean(selected) && flowHits.length > 0 && (flow === 'replace'
      ? Boolean(replaceWith)
      : /^assets\/[A-Za-z0-9_./-]+\.[A-Za-z0-9]+$/.test(copyName.trim())
        && !workspace.assets.some((asset) => asset.path === copyName.trim())),
  );

  async function operations() {
    const paths = [...new Set(flowHits.map((hit) => hit.path))];
    const revs = await readRevisions(pack, paths);
    return retargetOperations(flowHits, { pack, path: selected!.path }, { pack, path: targetPath() }, revs);
  }

  async function runPreview(): Promise<void> {
    busy = true;
    error = '';
    try {
      preview = parseBulkResult(unwrap(await ops.bulk(session.channel!, pack, await operations(), true)));
    } catch (e) {
      error = errorMessage(e);
      preview = null;
    } finally {
      busy = false;
    }
  }

  async function apply(): Promise<void> {
    if (!selected || !preview) return;
    busy = true;
    error = '';
    const source = selected.path;
    const channel = session.channel!;
    let copied = false;
    try {
      const built = await operations();
      if (flow === 'copy') {
        // The copy has to exist before anything points at it; it is removed again if the retarget is refused.
        const bytes = String(unwrap(await assetOps.get(channel, pack, source)).data);
        unwrap(await assetOps.putBase64(channel, pack, copyName.trim(), bytes));
        copied = true;
      }
      unwrap(await ops.bulk(channel, pack, built, false));
      logActivity('save', flow === 'copy'
        ? `${userWord(chosenHits.length)} now use their own copy ${copyName.trim()}`
        : `${userWord(hits.length)} of ${fileName(source)} now use ${fileName(replaceWith)}`);
      const next = flow === 'copy' ? copyName.trim() : source;
      flow = null;
      preview = null;
      await refreshPack();
      assetsUi.selected = null;
      assetsUi.selected = next;
    } catch (e) {
      error = errorMessage(e);
      if (copied) {
        try {
          unwrap(await assetOps.remove(channel, pack, copyName.trim()));
        } catch {
          error += ` The copy ${copyName.trim()} was left in the pack; delete it from Files if you do not need it.`;
          await refreshPack();
        }
      }
      preview = null;
    } finally {
      busy = false;
    }
  }

  function editPixels(): void {
    if (!selected) return;
    openAsset(pack, selected.path, fileName(selected.path));
    ui.view = 'advanced';
  }

  function size(bytes: number): string {
    return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KiB`;
  }
</script>

<svelte:window onkeydown={onKey} />

<div class="assets">
  <section class="catalog" aria-label="Assets in this pack">
    <div class="filters">
      <input
        type="search"
        placeholder="search assets…"
        spellcheck="false"
        aria-label="Search assets"
        bind:value={query}
      />
      <div class="chips" role="group" aria-label="Asset kind">
        {#each CATEGORIES as entry (entry)}
          <button
            type="button"
            class:active={category === entry}
            aria-pressed={category === entry}
            onclick={() => (category = entry)}
          >{entry}</button>
        {/each}
      </div>
    </div>
    <div class="list" bind:this={list} role="listbox" aria-label="Assets" tabindex="0">
      {#each assets as asset (asset.path)}
        <div
          class="row"
          class:selected={asset.path === assetsUi.selected}
          data-path={asset.path}
          role="option"
          aria-selected={asset.path === assetsUi.selected}
          tabindex="-1"
          onclick={() => (assetsUi.selected = asset.path)}
          onkeydown={() => {}}
        >
          <span class="name" title={asset.path}>{asset.path.replace(/^assets\//, '')}</span>
          <span class="size">{size(asset.bytes)}</span>
        </div>
      {:else}
        <p class="empty">{workspace.assets.length ? 'No asset matches.' : 'This pack has no assets yet.'}</p>
      {/each}
    </div>
  </section>

  <section class="detail" aria-label="Selected asset">
    {#if !selected}
      <p class="empty">Select an asset to see what uses it.</p>
    {:else}
      <header>
        {#if thumb}<img src={thumb} alt="" class="thumb" />{/if}
        <div class="title">
          <h2 title={selected.path}>{fileName(selected.path)}</h2>
          <code>{selected.path}</code>
        </div>
      </header>

      <div class="used" aria-live="polite">
        {#if usageLoading}
          <p class="note">Looking for users…</p>
        {:else if usageError}
          <p class="error" role="alert">Could not search for users: {usageError}</p>
        {:else if usage}
          <h3>Used by — {impactSummary(hits)}</h3>
          <p class="note" class:partial={!usage.coverage.complete}>{coverageNote(usage.coverage)}</p>
          <ul class="hits">
            {#each hits as hit (hitKey(hit))}
              <li>
                {#if flow === 'copy'}
                  <input
                    type="checkbox"
                    aria-label="Give {hit.id} its own copy"
                    checked={chosen.includes(hitKey(hit))}
                    onchange={() => toggle(hit)}
                  />
                {/if}
                <button type="button" class="link" onclick={() => void openUser(hit)}>{hit.id}</button>
                <span class="where">{hit.type} · {hit.field.join('.')}</span>
                <code class="value" title={hit.value}>{hit.value}</code>
              </li>
            {:else}
              <li class="none">No document names this asset.</li>
            {/each}
          </ul>
        {/if}
      </div>

      {#if error}<p class="error" role="alert">{error}</p>{/if}

      {#if flow}
        <section class="flow" aria-label={flow === 'replace' ? 'Replace everywhere' : 'Give users a copy'}>
          {#if flow === 'replace'}
            <h3>Replace in all {userWord(hits.length)}</h3>
            <label>
              <span>Point them at</span>
              <select bind:value={replaceWith} onchange={() => (preview = null)}>
                {#each sameKind as asset (asset.path)}
                  <option value={asset.path}>{asset.path.replace(/^assets\//, '')}</option>
                {/each}
              </select>
            </label>
            <p class="note">{fileName(selected.path)} itself is left untouched.</p>
          {:else}
            <h3>Give the checked users their own copy</h3>
            <label>
              <span>Copy to</span>
              <input bind:value={copyName} spellcheck="false" oninput={() => (preview = null)} />
            </label>
            <p class="note">
              {chosenHits.length === 0 ? 'Check the users above that should stop sharing.' : `${userWord(chosenHits.length)} will point at the copy; everyone else keeps ${fileName(selected.path)}.`}
            </p>
          {/if}

          {#if preview}
            <div class="impact" aria-label="impact summary">
              <p><strong>{bulkHeadline(preview)}</strong>. Written together, or not at all.</p>
              <ul>
                {#each preview.documents as doc (doc.path)}
                  <li>
                    <span>{doc.path.slice(doc.path.lastIndexOf('/') + 1)}</span>
                    {#each doc.changes as change, index (index)}
                      <span class="diff"><del>{formatValue(change.before)}</del> → <ins>{formatValue(change.after)}</ins></span>
                    {/each}
                  </li>
                {/each}
              </ul>
            </div>
          {/if}

          <div class="actions">
            <button type="button" class="btn" onclick={() => { flow = null; preview = null; }}>Cancel</button>
            <button type="button" class="btn" disabled={busy || !canPreview} onclick={() => void runPreview()}>Preview changes</button>
            <button type="button" class="btn btn-primary" disabled={busy || !preview} onclick={() => void apply()}>
              {flow === 'replace' ? `Replace in ${userWord(hits.length)}` : `Retarget ${userWord(chosenHits.length)}`}
            </button>
          </div>
        </section>
      {:else}
        <div class="actions">
          <button
            type="button"
            class="btn"
            disabled={!usage || hits.length === 0 || sameKind.length === 0}
            title="Point every user at a different asset"
            onclick={() => startFlow('replace')}
          >Replace everywhere…</button>
          <button
            type="button"
            class="btn"
            disabled={!usage || hits.length < 2}
            title="Copy this file for some users and leave the rest sharing it"
            onclick={() => startFlow('copy')}
          >Give some users a copy…</button>
          {#if /\.png$/i.test(selected.path)}
            <button type="button" class="btn" onclick={editPixels}>
              Edit pixels{hits.length > 1 ? ` (changes all ${userWord(hits.length)})` : ''}
            </button>
          {/if}
        </div>
      {/if}
    {/if}
  </section>
</div>

<style>
  .assets {
    height: 100%;
    min-width: 0;
    min-height: 0;
    display: flex;
  }
  .catalog {
    flex: 0 1 340px;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    border-right: 1px solid var(--border);
  }
  .filters {
    flex: none;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  .filters input {
    min-width: 0;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: var(--space-2) var(--space-2);
    font-size: var(--text-base);
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1);
  }
  .chips button:not(.btn) {
    background: none;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-lg);
    color: var(--muted);
    padding: var(--space-half) var(--space-2);
    font-size: var(--text-xs);
    cursor: pointer;
  }
  .chips button:not(.btn).active {
    color: var(--accent);
    border-color: var(--accent);
  }
  .list {
    flex: 1 1 0;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-1);
  }
  .row {
    display: flex;
    gap: var(--space-2);
    align-items: center;
    padding: var(--space-2) var(--space-2);
    border-radius: var(--radius);
    font-size: var(--text-base);
    cursor: default;
    min-width: 0;
  }
  .row:hover { background: var(--bg-hover); }
  .row.selected { background: var(--bg-raised); outline: 1px solid var(--border-strong); }
  .name { flex: 1 1 0; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .size { flex: none; color: var(--faint); font-size: var(--text-xs); }
  .detail {
    flex: 1 1 0;
    min-width: 0;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-4) var(--space-5);
  }
  header { display: flex; gap: var(--space-3); align-items: center; margin-bottom: var(--space-3); min-width: 0; }
  .thumb { width: 64px; height: 64px; object-fit: contain; image-rendering: pixelated; background: var(--bg-input); border-radius: var(--radius); flex: none; }
  .title { min-width: 0; }
  h2 { margin: 0; font-size: var(--text-md); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .title code { color: var(--faint); font-size: var(--text-xs); overflow-wrap: anywhere; }
  h3 { margin: 0 0 var(--space-2); font-size: var(--text-base); }
  .note { margin: 0 0 var(--space-2); color: var(--muted); font-size: var(--text-sm); }
  .note.partial { color: var(--warn); }
  .error { color: var(--error); font-size: var(--text-sm); }
  .hits { list-style: none; margin: 0 0 var(--space-3); padding: 0; }
  .hits li { display: flex; gap: var(--space-2); align-items: baseline; padding: var(--space-1) 0; min-width: 0; font-size: var(--text-sm); }
  .hits li.none { color: var(--muted); }
  .link { background: none; border: none; color: var(--accent); cursor: pointer; padding: 0; font-size: var(--text-sm); text-align: left; }
  .where { color: var(--faint); font-size: var(--text-xs); white-space: nowrap; }
  .value { margin-left: auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); font-size: var(--text-xs); }
  .flow { border: 1px solid var(--border); border-radius: var(--radius-lg); padding: var(--space-3) var(--space-4); margin-bottom: var(--space-2); }
  .flow label { display: block; margin-bottom: var(--space-2); }
  .flow label > span { display: block; font-size: var(--text-sm); color: var(--muted); margin-bottom: var(--space-1); }
  .flow input, .flow select {
    width: 100%; box-sizing: border-box; background: var(--bg-input); color: inherit;
    border: 1px solid var(--border-strong); border-radius: var(--radius); padding: var(--space-2) var(--space-2); font: var(--text-sm) var(--mono);
  }
  .impact ul { list-style: none; margin: 0; padding: 0; max-height: 200px; overflow-y: auto; }
  .impact li { display: flex; flex-wrap: wrap; gap: var(--space-1) var(--space-3); font-size: var(--text-sm); padding: var(--space-half) 0; }
  .diff { font-family: var(--mono); overflow-wrap: anywhere; }
  del { color: var(--muted); }
  ins { text-decoration: none; color: var(--accent); }
  .actions { display: flex; flex-wrap: wrap; gap: var(--space-2); margin-top: var(--space-3); }
  .actions button:not(.btn) { border-radius: var(--radius); padding: var(--space-1) var(--space-4); cursor: pointer; font-size: var(--text-sm); }
  button:not(.btn):disabled { opacity: 0.5; cursor: default; }
  .empty { color: var(--muted); padding: var(--space-4); font-size: var(--text-base); }
</style>
