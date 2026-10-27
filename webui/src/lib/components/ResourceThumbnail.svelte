<script lang="ts">
  import type { ResourceSummary } from '../api';
  import { renderModelPreview } from '../model-preview';
  import { connection, server, session, workspace } from '../state.svelte';
  import { loadThumbnail, onThumbnailCapacity, ThumbnailCapacityError } from '../thumbnails';
  import { vanillaIconUrl } from '../vanilla-icons';

  interface Props {
    visual?: ResourceSummary['visual'];
    /** Live-render input; drawn in the browser and preferred over the static visual. */
    render?: ResourceSummary['render'];
    /** Base material; its vanilla icon stands in when there is no custom visual. */
    material?: string;
    label: string;
    size?: number;
    version?: number;
  }

  let { visual, render, material, label, size = 32, version = 0 }: Props = $props();
  let container = $state<HTMLDivElement>();
  let image = $state('');
  let thumbnailState = $state<'empty' | 'loading' | 'ready' | 'error' | 'unavailable'>('empty');
  /** The shown image is a vanilla icon, not this resource's own visual. */
  let vanilla = $state(false);
  /** The shown image is a live browser render of the resource's model. */
  let live = $state(false);
  let reason = $state('');

  const accessibleTitle = $derived(
    thumbnailState === 'ready'
      ? label
      : thumbnailState === 'loading'
        ? `${label}: loading thumbnail`
        : thumbnailState === 'error'
          ? `${label}: thumbnail unavailable — ${reason}`
          : thumbnailState === 'unavailable'
            ? `${label}: preview unavailable without an active editor connection`
            : `${label}: no preview available`,
  );
  const previewKind = $derived(
    live
      ? 'Live model preview (browser approximation)'
      : vanilla
        ? 'Vanilla item icon (no custom visual)'
        : visual?.kind === 'model-thumbnail' ? 'Cached model thumbnail' : 'Source texture preview',
  );

  $effect(() => {
    const target = visual;
    const spec = render;
    const revision = version;
    const connected = connection.connected;
    const channel = session.channel;
    const element = container;
    const baseMaterial = material;
    const minecraftVersion = server.minecraftVersion;
    const packs = [...workspace.packs];
    image = '';
    reason = '';
    vanilla = false;
    live = false;

    let cancelled = false;
    // Vanilla icons come from the CDN, so they need no editor connection;
    // any failure (offline, unknown item) keeps the honest placeholder.
    const showVanillaOrEmpty = () => {
      thumbnailState = 'empty';
      if (!baseMaterial) return;
      void vanillaIconUrl(baseMaterial, minecraftVersion).then(
        (url) => {
          if (cancelled || !url) return;
          vanilla = true;
          image = url;
          thumbnailState = 'ready';
        },
        () => {},
      );
    };

    if (!target && !spec) {
      showVanillaOrEmpty();
      return () => { cancelled = true; };
    }
    if (!connected || !channel) {
      if (target) thumbnailState = 'unavailable';
      else showVanillaOrEmpty();
      return () => { cancelled = true; };
    }
    if (target && target.kind !== 'texture' && target.kind !== 'model-thumbnail') {
      thumbnailState = 'error';
      reason = 'Unsupported preview type.';
      return;
    }
    thumbnailState = 'loading';

    let started = false;
    let visible = false;
    let observer: IntersectionObserver | undefined;
    let stopWaiting: (() => void) | undefined;
    // Cleared once the live render fails, so retries go straight to the static visual.
    let spec_ = spec;

    const loadStatic = () => {
      if (!target) {
        showVanillaOrEmpty();
        return;
      }
      void loadThumbnail(channel, target.pack, target.path, revision).then(
        (dataUrl) => {
          if (cancelled || session.channel !== channel) return;
          image = dataUrl;
          thumbnailState = 'ready';
        },
        (error: unknown) => {
          if (cancelled || session.channel !== channel) return;
          if (error instanceof ThumbnailCapacityError) {
            started = false;
            spec_ = undefined;
            stopWaiting = onThumbnailCapacity(start);
            return;
          }
          reason = error instanceof Error ? error.message : String(error);
          thumbnailState = 'error';
        },
      );
    };
    const start = () => {
      if (started || cancelled || !visible) return;
      stopWaiting?.();
      stopWaiting = undefined;
      started = true;
      thumbnailState = 'loading';
      if (!spec_) {
        loadStatic();
        return;
      }
      void renderModelPreview(channel, spec_, packs, revision).then(
        (dataUrl) => {
          if (cancelled || session.channel !== channel) return;
          live = true;
          image = dataUrl;
          thumbnailState = 'ready';
        },
        () => {
          if (cancelled || session.channel !== channel) return;
          // Undrawable model (unknown vanilla parent, unreadable file): use the static visual.
          spec_ = undefined;
          loadStatic();
        },
      );
    };

    if (typeof IntersectionObserver === 'undefined') {
      visible = true;
      start();
    } else if (element) {
      observer = new IntersectionObserver((entries) => {
        visible = entries.some((entry) => entry.isIntersecting);
        if (visible) start();
        else if (!started) {
          stopWaiting?.();
          stopWaiting = undefined;
        }
      });
      observer.observe(element);
    }

    return () => {
      cancelled = true;
      observer?.disconnect();
      stopWaiting?.();
    };
  });

  function imageFailed(): void {
    image = '';
    if (vanilla) {
      vanilla = false;
      thumbnailState = 'empty';
      return;
    }
    live = false;
    thumbnailState = 'error';
    reason = 'The asset data is not a displayable image.';
  }
</script>

<div
  class="thumbnail"
  class:model={visual?.kind === 'model-thumbnail' || live}
  class:vanilla
  class:live
  bind:this={container}
  role="img"
  aria-label={accessibleTitle}
  aria-busy={thumbnailState === 'loading'}
  title={thumbnailState === 'ready' ? `${accessibleTitle}: ${previewKind}` : accessibleTitle}
  style:width="{size}px"
  style:height="{size}px"
>
  {#if thumbnailState === 'ready' && image}
    <img src={image} alt="" draggable="false" loading="lazy" referrerpolicy="no-referrer"
      onerror={imageFailed} />
  {:else}
    <span aria-hidden="true">{thumbnailState === 'loading' ? '…' : thumbnailState === 'error' ? '!' : '◇'}</span>
  {/if}
</div>

<style>
  .thumbnail {
    flex: none;
    display: grid;
    place-items: center;
    max-width: 100%;
    overflow: hidden;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: var(--bg-input);
    color: var(--faint);
    font-size: var(--text-lg);
    line-height: 1;
  }

  img {
    width: 100%;
    height: 100%;
    object-fit: contain;
    image-rendering: pixelated;
  }
  .model img { image-rendering: auto; }
</style>
