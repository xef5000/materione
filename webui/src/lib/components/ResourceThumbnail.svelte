<script lang="ts">
  import type { ResourceSummary } from '../api';
  import { connection, session } from '../state.svelte';
  import { loadThumbnail, onThumbnailCapacity, ThumbnailCapacityError } from '../thumbnails';

  interface Props {
    visual?: ResourceSummary['visual'];
    label: string;
    size?: number;
    version?: number;
  }

  let { visual, label, size = 32, version = 0 }: Props = $props();
  let container = $state<HTMLDivElement>();
  let image = $state('');
  let thumbnailState = $state<'empty' | 'loading' | 'ready' | 'error' | 'unavailable'>('empty');
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
    visual?.kind === 'model-thumbnail' ? 'Cached model thumbnail' : 'Source texture preview',
  );

  $effect(() => {
    const target = visual;
    const revision = version;
    const connected = connection.connected;
    const channel = session.channel;
    const element = container;
    image = '';
    reason = '';

    if (!target) {
      thumbnailState = 'empty';
      return;
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
    thumbnailState = 'error';
    reason = 'The asset data is not a displayable image.';
  }
</script>

<div
  class="thumbnail"
  class:model={visual?.kind === 'model-thumbnail'}
  bind:this={container}
  role="img"
  aria-label={accessibleTitle}
  aria-busy={thumbnailState === 'loading'}
  title={thumbnailState === 'ready' ? `${accessibleTitle}: ${previewKind}` : accessibleTitle}
  style:width="{size}px"
  style:height="{size}px"
>
  {#if thumbnailState === 'ready' && image}
    <img src={image} alt="" draggable="false" onerror={imageFailed} />
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
