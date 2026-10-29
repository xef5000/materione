<script lang="ts">
  /**
   * "Add an image": the two ways an image layer gets its texture. Upload stores the file beside the
   * GUI's other textures (`textures/gui/<gui-path>/`) and saves it in the same transaction as the
   * layout; "From assets" picks a texture the pack already has, through the same explorer the item
   * studio uses. Either way the layer lands centered at the texture's natural size.
   */
  import Dialog from '../../ui/Dialog.svelte';
  import Icon from '../../components/Icon.svelte';
  import AssetExplorer from '../AssetExplorer.svelte';
  import type { GuiStudioState } from '../../gui/studio.svelte';
  import { session } from '../../state.svelte';

  let { studio, textures }: { studio: GuiStudioState; textures: string[] } = $props();

  let file = $state<HTMLInputElement>();
  let browsing = $state(false);
  let over = $state(false);

  function close(): void {
    studio.choosing = false;
  }

  async function take(files: FileList | File[] | null | undefined): Promise<void> {
    const picked = [...(files ?? [])].filter((entry) => entry.type.startsWith('image/'));
    if (!picked.length) return;
    for (const entry of picked) await studio.addImageFromFile(entry, textures);
    close();
  }
</script>

{#if browsing}
  <AssetExplorer
    pack={studio.pack}
    kind="texture"
    current=""
    onPick={(stored) => {
      browsing = false;
      close();
      if (stored) void studio.addImageFromAsset(session.channel, stored);
    }}
    onClose={() => (browsing = false)}
  />
{:else}
  <Dialog title="Add an image" onClose={close} width="440px">
    <div
      class="drop"
      class:over
      role="presentation"
      ondragover={(event) => {
        event.preventDefault();
        over = true;
      }}
      ondragleave={() => (over = false)}
      ondrop={(event) => {
        event.preventDefault();
        over = false;
        void take(event.dataTransfer?.files);
      }}
    >
      <Icon name="upload" size={28} />
      <p>Drop a PNG here, or</p>
      <button type="button" class="primary" data-testid="upload-image" onclick={() => file?.click()}>Upload an image…</button>
      <input bind:this={file} type="file" accept="image/*" multiple hidden aria-label="Upload an image" onchange={(event) => void take(event.currentTarget.files)} />
    </div>
    <p class="or">or use a texture the pack already has</p>
    <button type="button" class="secondary" data-testid="image-from-assets" onclick={() => (browsing = true)}><Icon name="layout-grid" size={14} /> Choose from assets…</button>
    <div class="dialog-actions"><button type="button" class="secondary" onclick={close}>Cancel</button></div>
  </Dialog>
{/if}

<style>
  .drop {
    display: grid;
    justify-items: center;
    gap: var(--space-2);
    padding: var(--space-5);
    border: 2px dashed var(--border-strong);
    border-radius: var(--radius-lg);
    color: var(--muted);
    transition: border-color var(--motion-fast) var(--ease-standard), background var(--motion-fast) var(--ease-standard);
  }
  .drop.over {
    border-color: var(--accent);
    background: var(--gui-marquee);
    color: var(--fg);
  }
  .drop p {
    margin: 0;
  }
  .or {
    margin: var(--space-3) 0 var(--space-2);
    color: var(--faint);
    font-size: var(--text-sm);
    text-align: center;
  }
  .primary,
  .secondary {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-4);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--bg-raised);
    color: var(--fg);
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .primary {
    background: var(--accent);
    border-color: transparent;
    color: var(--accent-ink);
    font-weight: 600;
  }
  .secondary:hover {
    background: var(--bg-hover);
  }
  .secondary {
    width: 100%;
  }
  .dialog-actions .secondary {
    width: auto;
  }
</style>
