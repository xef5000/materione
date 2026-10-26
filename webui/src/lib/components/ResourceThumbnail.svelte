<script lang="ts">
  import type { ResourceSummary } from '../api';
  import { connection, server, session } from '../state.svelte';
  import { loadThumbnail, onThumbnailCapacity, ThumbnailCapacityError } from '../thumbnails';
  import { vanillaIconUrl } from '../vanilla-icons';

  interface Props {
    visual?: ResourceSummary['visual'];
    /** Base material; its vanilla icon stands in when there is no custom visual. */
    material?: string;
    label: string;
    size?: number;
    version?: number;
  }

  let { visual, material, label, size = 32, version = 0 }: Props = $props();
  let container = $state<HTMLDivElement>();
  let image = $state('');
  let thumbnailState = $state<'empty' | 'loading' | 'ready' | 'error' | 'unavailable'>('empty');
  /** The shown image is a vanilla icon, not this resource's own visual. */
  let vanilla = $state(false);
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
    vanilla
      ? 'Vanilla item icon (no custom visual)'
      : visual?.kind === 'model-thumbnail' ? 'Cached model thumbnail' : 'Source texture preview',
  );

  $effect(() => {
    const target = visual;
    const revision = version;
    const connected = connection.connected;
    const channel = session.channel;
    const element = container;
    const baseMaterial = material;
    const minecraftVersion = server.minecraftVersion;
    image = '';
    reason = '';
    vanilla = false;

    if (!target) {
      if (!baseMaterial) {
        thumbnailState = 'empty';
        return;
      }
      // Vanilla icons come from the CDN, so they need no editor connection;
      // any failure (offline, unknown item) keeps the honest placeholder.
      let cancelled = false;
      void vanillaIconUrl(baseMaterial, minecraftVersion).then(
        (url) => {
          if (cancelled || !url) return;
          vanilla = true;
          image = url;
          thumbnailState = 'ready';
        },
        () => {},
      );
      thumbnailState = 'empty';
      return () => { cancelled = true; };
    }
    if (!connected || !channel) {
      thumbnailState = 'unavailable';
      return;
    }
    if (target.kind !== 'texture' && target.kind !== 'model-thumbnail') {
      thumbnailState = 'error';
      reason = 'Unsupported preview type.';
      return;
    }
    thumbnailState = 'loading';

    let cancelled = false;
    let started = false;
    let visible = false;
    let observer: IntersectionObserver | undefined;
    let stopWaiting: (() => void) | undefined;
    const start = () => {
      if (started || cancelled || !visible) return;
      stopWaiting?.();
      stopWaiting = undefined;
      started = true;
      thumbnailState = 'loading';
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
            stopWaiting = onThumbnailCapacity(start);
            return;
          }
          reason = error instanceof Error ? error.message : String(error);
          thumbnailState = 'error';
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
    thumbnailState = 'error';
    reason = 'The asset data is not a displayable image.';
  }
</script>

<div
  class="thumbnail"
  class:model={visual?.kind === 'model-thumbnail'}
  class:vanilla
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
    font-size: 17px;
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
