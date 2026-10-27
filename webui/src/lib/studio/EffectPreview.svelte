<script lang="ts">
  /**
   * The effect applied to sample text, live: every character is moved or
   * recolored with the same formulas the generated text shader uses
   * (effects.ts), so changing the kind, speed, strength or palette changes the
   * picture at once. Type your own sample; pause it, or let the operating
   * system's reduced-motion setting hold it still.
   */
  import { untrack } from 'svelte';
  import Icon from '../components/Icon.svelte';
  import { effectSpecOf, frameOf, toHex } from '../effects';

  interface Props {
    /** The draft's values (kind, speed, amplitude, colors). */
    values: Record<string, unknown>;
  }

  let { values }: Props = $props();

  /** The text face draws at 2x the game's 8 px; one character is about 6 px wide. */
  const SCALE = 2;
  const ADVANCE = 6;

  let sample = $state('Materione rocks');
  let seconds = $state(0);
  const reduced = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
  let paused = $state(reduced);

  const spec = $derived(effectSpecOf(values));
  const characters = $derived([...sample].map((character, index) => {
    const frame = frameOf(spec, seconds, index * ADVANCE);
    const channels = frame.rgb ?? [1, 1, 1];
    const [red, green, blue] = channels.map((channel) => channel * frame.brightness * 255);
    return {
      character,
      style: `transform: translate(${(frame.dx * SCALE).toFixed(2)}px, ${(-frame.dy * SCALE).toFixed(2)}px); `
        + `color: ${toHex(red!, green!, blue!)};`,
    };
  }));

  $effect(() => {
    if (paused) return;
    let handle = 0;
    const start = performance.now() - untrack(() => seconds) * 1000;
    const tick = (now: number) => {
      seconds = (now - start) / 1000;
      handle = requestAnimationFrame(tick);
    };
    handle = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(handle);
  });
</script>

<section class="effect-preview" aria-label="Effect preview" data-effect-kind={spec.kind}>
  <div class="stage mc-text" data-stage>
    {#each characters as entry, index (index)}
      <span class="ch" data-ch={index} style={entry.style}>{entry.character === ' ' ? ' ' : entry.character}</span>
    {/each}
  </div>
  <div class="controls">
    <input type="text" aria-label="Sample text" maxlength="48" spellcheck="false" bind:value={sample} />
    <button type="button" class="btn btn-sm" aria-pressed={paused}
      aria-label={paused ? 'Play preview' : 'Pause preview'} onclick={() => (paused = !paused)}>
      <Icon name={paused ? 'play' : 'pause'} size={14} />
    </button>
  </div>
  <small>
    The text shader's own formulas, drawn in the browser at 2× text size.
    {#if spec.kind === 'rainbow' && spec.colors.length < 2}No palette: the full spectrum cycles.{/if}
    {#if paused}Paused.{/if}
  </small>
</section>

<style>
  .effect-preview {
    display: grid;
    gap: var(--space-2);
    min-width: 0;
  }
  .stage {
    min-height: 3.5em;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    padding: var(--space-3);
    background: color-mix(in srgb, var(--preview-shade) 55%, transparent);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    white-space: pre;
    overflow: hidden;
  }
  .ch {
    display: inline-block;
    will-change: transform;
  }
  .controls {
    display: flex;
    gap: var(--space-2);
  }
  .controls input {
    flex: 1;
    min-width: 0;
    background: var(--bg-input);
    color: inherit;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    padding: var(--space-1) var(--space-2);
    font-size: var(--text-base);
  }
  small {
    color: var(--faint);
    font-size: var(--text-xs);
  }
</style>
