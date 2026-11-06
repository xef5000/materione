<script lang="ts">
  /**
   * Asked when layers that have rules are deleted (logic.md section 7.4): delete the rules with them, or keep the rules and
   * take the layer filter off. Both are one undo step; the studio does the work, this only asks.
   */
  import Button from '../../../ui/Button.svelte';
  import Dialog from '../../../ui/Dialog.svelte';
  import type { LinkageState } from '../../../logic/linkage/linkage.svelte';

  let { links }: { links: LinkageState } = $props();

  const pending = $derived(links.pendingDelete);
  const subject = $derived(pending ? (pending.names.length === 1 ? `“${pending.names[0]}”` : `${pending.names.length} layers`) : '');
  const total = $derived(pending ? pending.exclusive + pending.shared : 0);
  /** What "Delete the rules" does, in a sentence: the rules only these layers name go, a rule shared with a layer that stays keeps it. */
  const removes = $derived.by(() => {
    if (!pending) return '';
    const only = pending.names.length === 1 ? 'this layer' : 'these layers';
    const gone = pending.exclusive === 0
      ? 'removes no rule'
      : `removes ${pending.exclusive === 1 ? 'the rule that names' : `the ${pending.exclusive} rules that name`} only ${only}`;
    const shared = pending.shared === 0
      ? ''
      : `; ${pending.shared === 1 ? 'a rule that also names' : `${pending.shared} rules that also name`} a layer that stays ${pending.shared === 1 ? 'keeps' : 'keep'} that one, without ${only}`;
    return gone + shared;
  });
</script>

{#if pending}
  <Dialog title="Delete {subject}?" onClose={() => links.resolveDelete('cancel')} width="480px">
    <div data-testid="delete-layer-dialog">
      <p class="lead">
        {total === 1 ? 'A rule' : `${total} rules`} in the Logic tab {total === 1 ? 'answers' : 'answer'} clicks on
        {pending.names.length === 1 ? 'this layer' : 'these layers'}.
      </p>
      <ul>
        <li><strong>Delete the rules</strong> {removes}.</li>
        <li><strong>Keep the rules</strong> takes the layer filter off them. A rule with no layer filter answers any click that no
          other rule claimed, so check them in the Logic tab.</li>
      </ul>
      <p class="note">Either choice is one undo step, with the layer.</p>
      <div class="dialog-actions">
        <!-- svelte-ignore a11y_autofocus -->
        <Button autofocus onclick={() => links.resolveDelete('cancel')}>Cancel</Button>
        <Button onclick={() => links.resolveDelete('detach')} data-testid="delete-layer-detach">Keep the rules</Button>
        <Button variant="danger" onclick={() => links.resolveDelete('delete')} data-testid="delete-layer-rules">Delete the rules</Button>
      </div>
    </div>
  </Dialog>
{/if}

<style>
  .lead {
    margin: 0 0 var(--space-3);
  }
  ul {
    display: grid;
    gap: var(--space-2);
    margin: 0 0 var(--space-3);
    padding-left: var(--space-5);
    color: var(--muted);
    font-size: var(--text-sm);
  }
  strong {
    color: var(--fg);
  }
  .note {
    margin: 0 0 var(--space-4);
    color: var(--faint);
    font-size: var(--text-xs);
  }
</style>
