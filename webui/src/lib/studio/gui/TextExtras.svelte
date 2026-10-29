<script lang="ts">
  /**
   * What a `text` layer needs beyond its schema fields (text, font, colour): the honest word about
   * the preview and the compiler's verdict. Smallcaps is previewed pixel for pixel from the font the
   * pack ships; `default` is the client's own font, which the studio cannot draw, so its preview is
   * approximate and says so. A run the compiler would drop is stated here, with why, instead of
   * being discovered in game.
   */
  import Icon from '../../components/Icon.svelte';
  import type { GuiLayer } from '../../gui/model';
  import type { GuiStudioState } from '../../gui/studio.svelte';
  import { advanceOf, fontOf, textOf } from '../../gui/text';

  let { studio, layer }: { studio: GuiStudioState; layer: GuiLayer } = $props();

  const font = $derived(fontOf(layer));
  const issues = $derived(studio.textIssues.get(layer.id) ?? []);
  const width = $derived(font ? advanceOf(textOf(layer), font) : 0);
</script>

<section class="group" data-testid="text-extras">
  {#if font === 'default'}
    <p class="hint approx" data-testid="default-font-hint">
      <Icon name="info" size={14} />
      <span><strong>Approximate preview.</strong> The client's own font draws this in game; the studio shows a lookalike, so letter shapes and widths can differ by a pixel or two.</span>
    </p>
  {:else if font === 'smallcaps'}
    <p class="hint exact" data-testid="smallcaps-hint">
      <Icon name="check" size={14} />
      <span>Pixel-exact preview: drawn from the same font sheet the pack ships. {width} px wide.</span>
    </p>
  {/if}
  {#each issues as issue (issue.message)}
    <p class="hint {issue.severity}" role="note" data-testid="text-issue">
      <Icon name="triangle-alert" size={14} />
      <span>{#if issue.severity === 'skip'}<strong>Skipped in game (MTR-0052):</strong>{:else}<strong>Heads up:</strong>{/if} {issue.message}</span>
    </p>
  {/each}
</section>

<style>
  .group {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  .group:empty {
    display: none;
  }
  .hint {
    display: flex;
    align-items: flex-start;
    gap: var(--space-2);
    margin: 0;
    padding: var(--space-2);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    color: var(--muted);
    font-size: var(--text-sm);
    line-height: 1.4;
  }
  .hint :global(svg) {
    flex: none;
    margin-top: 2px;
  }
  .hint.exact {
    border-color: transparent;
    color: var(--ok);
  }
  .hint.skip,
  .hint.note {
    border-color: var(--warn);
    background: var(--warn-bg);
    color: var(--warn);
  }
</style>
