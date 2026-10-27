<script lang="ts">
  /**
   * What a sound is called in the game: its namespaced id and the command that
   * plays it. Playing the sources themselves happens on each source row.
   */
  interface Props {
    pack: string;
    id: string;
    category: string;
    sources: number;
  }

  let { pack, id, category, sources }: Props = $props();
  const event = $derived(`${pack}:${id}`);
</script>

<section class="summary" aria-label="Sound summary">
  <dl>
    <dt>sound id</dt>
    <dd title={event}>{event}</dd>
    <dt>category</dt>
    <dd>{category || 'master'}</dd>
    <dt>sources</dt>
    <dd>{sources}</dd>
  </dl>
  <p>Test it in game: <code>/playsound {event} {category || 'master'} @s</code></p>
</section>

<style>
  .summary {
    display: grid;
    gap: var(--space-2);
    min-width: 0;
    font-size: var(--text-sm);
  }
  dl {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    gap: var(--space-half) var(--space-2);
    margin: 0;
    font-size: var(--text-xs);
  }
  dt {
    color: var(--faint);
  }
  dd {
    margin: 0;
    font-family: var(--mono);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  p {
    margin: 0;
    color: var(--muted);
    overflow-wrap: anywhere;
  }
  code {
    font: var(--text-xs) var(--mono);
    color: var(--fg-code);
  }
</style>
