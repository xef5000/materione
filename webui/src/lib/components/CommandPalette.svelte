<script lang="ts">
  /**
   * Ctrl/Cmd+K: go anywhere, open any resource or asset, start a new one, or
   * jump to a field of the open resource, by typing. Every entry runs an
   * action the UI already has; the palette adds no capability of its own.
   */
  import { onMount, tick } from 'svelte';
  import { rankCommands, type PaletteCommand } from '../bulk';
  import { typeTabs } from '../browser';
  import {
    content,
    openStudio,
    showAssetUsage,
    startCreate,
    ui,
    workspace,
  } from '../state.svelte';

  let { onClose }: { onClose: () => void } = $props();

  let query = $state('');
  let index = $state(0);
  let input = $state<HTMLInputElement>();

  const VIEWS: { view: typeof ui.view; label: string }[] = [
    { view: 'start', label: 'Start' },
    { view: 'content', label: 'Content' },
    { view: 'assets', label: 'Assets' },
    { view: 'advanced', label: 'Files' },
    { view: 'diagnostics', label: 'Problems' },
    { view: 'server', label: 'Server' },
  ];

  const commands = $derived.by((): PaletteCommand[] => {
    const out: PaletteCommand[] = [];
    // Fields come first when a resource is open: that is when "property search" is the intent.
    if (content.open) {
      const open = content.open;
      const type = workspace.types.find((entry) => (entry.directory ?? entry.name) === open.type
        || (entry.directory ?? entry.name) === open.path.split('/')[0]);
      for (const param of type?.parameters ?? []) {
        out.push({
          id: `field:${param.id}`,
          title: param.label ?? param.id,
          group: 'Field',
          keywords: `${param.id} ${param.section ?? ''} ${param.description ?? ''}`,
          run: () => openStudio(open, param.id),
        });
      }
    }
    for (const entry of VIEWS) {
      out.push({ id: `view:${entry.view}`, title: entry.label, group: 'Go to', run: () => { ui.view = entry.view; ui.returnTo = null; } });
    }
    for (const tab of typeTabs(workspace.types).filter((entry) => entry.enabled)) {
      out.push({
        id: `new:${tab.directory}`,
        title: `New ${tab.singular}`,
        group: 'Create',
        run: () => startCreate(tab.directory),
      });
    }
    for (const summary of workspace.summaries) {
      out.push({
        id: `resource:${summary.path}#${summary.id}`,
        title: summary.name,
        group: summary.type,
        keywords: `${summary.id} ${summary.material ?? ''}`,
        run: () => openStudio({ pack: summary.pack, type: summary.type, path: summary.path, id: summary.id }),
      });
    }
    for (const asset of workspace.assets) {
      out.push({
        id: `asset:${asset.path}`,
        title: asset.path.replace(/^assets\//, ''),
        group: 'Asset',
        run: () => showAssetUsage(asset.path),
      });
    }
    return out;
  });

  const results = $derived(rankCommands(query, commands, 12));
  $effect(() => {
    void query;
    index = 0;
  });

  onMount(() => input?.focus());

  function choose(command: PaletteCommand | undefined): void {
    if (!command) return;
    onClose();
    command.run();
  }

  async function onKey(event: KeyboardEvent): Promise<void> {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      index = Math.min(results.length - 1, index + 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      index = Math.max(0, index - 1);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      choose(results[index]);
    }
    await tick();
    document.querySelector('.palette li.active')?.scrollIntoView({ block: 'nearest' });
  }

</script>

<div class="scrim" role="presentation" onclick={onClose}>
  <div
    class="palette"
    role="dialog"
    aria-modal="true"
    aria-label="Command palette"
    tabindex="-1"
    onclick={(event) => event.stopPropagation()}
    onkeydown={onKey}
  >
    <input
      bind:this={input}
      bind:value={query}
      type="text"
      role="combobox"
      aria-expanded="true"
      aria-controls="palette-results"
      aria-activedescendant={results[index] ? `palette-${index}` : undefined}
      placeholder="Go to, open, create, or jump to a field…"
      spellcheck="false"
      autocomplete="off"
    />
    <ul id="palette-results" role="listbox">
      {#each results as command, position (command.id)}
        <li
          id="palette-{position}"
          role="option"
          aria-selected={position === index}
          class:active={position === index}
          onmouseenter={() => (index = position)}
          onclick={() => choose(command)}
          onkeydown={() => {}}
        >
          <span class="title">{command.title}</span>
          <span class="group">{command.group}</span>
        </li>
      {:else}
        <li class="none">Nothing matches “{query}”.</li>
      {/each}
    </ul>
  </div>
</div>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: var(--scrim);
    display: flex;
    justify-content: center;
    align-items: flex-start;
    padding-top: 12vh;
    z-index: 70;
  }
  .palette {
    width: min(560px, calc(100vw - 48px));
    background: var(--bg-panel);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-3);
    overflow: hidden;
  }
  input {
    width: 100%;
    box-sizing: border-box;
    background: var(--bg-input);
    color: inherit;
    border: none;
    border-bottom: 1px solid var(--border);
    padding: var(--space-3) var(--space-4);
    font-size: var(--text-md);
    outline: none;
  }
  ul { list-style: none; margin: 0; padding: var(--space-1); max-height: 50vh; overflow-y: auto; }
  li {
    display: flex;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius);
    font-size: var(--text-base);
    cursor: pointer;
    min-width: 0;
  }
  li.active { background: var(--bg-raised); outline: 1px solid var(--border-strong); }
  li.none { color: var(--muted); cursor: default; }
  .title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .group { flex: none; color: var(--faint); font-size: var(--text-xs); }
</style>
