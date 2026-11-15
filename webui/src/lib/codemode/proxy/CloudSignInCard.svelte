<script lang="ts">
  /**
   * Sign-in and suspension card for Materione AI on editor.materione.com (ai-credits.md section 8.2).
   */
  import Button from '../../ui/Button.svelte';
  import { cloudAccount, openPolarPortal, signInWith, signOut } from './account.svelte';
</script>

<section class="box" aria-label="Sign in to Materione AI">
  {#if cloudAccount.status === 'loading' || cloudAccount.status === 'idle'}
    <p class="lead">Checking your Materione AI session&hellip;</p>
  {:else if cloudAccount.status === 'signed-in' && cloudAccount.account?.suspended}
    <p class="lead">Your Materione AI account is suspended.</p>
    {#if cloudAccount.notice !== null}
      <p class="notice {cloudAccount.notice.tone}" role="alert">{cloudAccount.notice.text}</p>
    {/if}
    <div class="actions">
      <Button
        size="sm"
        variant="primary"
        disabled={cloudAccount.billingBusy}
        onclick={() => void openPolarPortal()}
      >Open billing portal</Button>
      <Button size="sm" variant="ghost" onclick={() => void signOut()}>Sign out</Button>
    </div>
  {:else if cloudAccount.status === 'signed-in' && (cloudAccount.credits?.balancePicos ?? 0) < 0}
    <p class="lead">Assistant paused until negative balance is repaid.</p>
    <p>Use <strong>Buy credits</strong> above to restore a positive credit balance and continue.</p>
  {:else}
    <p class="lead">Sign in to use the Materione AI assistant.</p>
    <p>
      $0.05/day free allowance on eligible accounts, plus optional prepaid credits ($10 minimum, 100 credits / $1 USD,
      never expire). Your server's session keys stay in this tab and are never sent to ai.materione.com.
    </p>
    <div class="actions">
      <Button
        size="sm"
        variant="primary"
        data-testid="ai-signin-google"
        onclick={() => signInWith('google')}
      >Sign in with Google</Button>
      <Button
        size="sm"
        variant="secondary"
        data-testid="ai-signin-discord"
        onclick={() => signInWith('discord')}
      >Sign in with Discord</Button>
    </div>
    {#if cloudAccount.notice !== null}
      <p class="notice {cloudAccount.notice.tone}" role={cloudAccount.notice.tone === 'info' ? 'status' : 'alert'}>
        {cloudAccount.notice.text}
      </p>
    {/if}
  {/if}
</section>

<style>
  .box {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    justify-items: start;
    gap: var(--space-2);
    padding: var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-raised);
    font-size: var(--text-base);
  }
  .box p {
    margin: 0;
  }
  .lead {
    font-weight: 600;
    color: var(--fg);
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .notice {
    width: 100%;
    box-sizing: border-box;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    font-size: var(--text-base);
  }
  .notice.warn {
    border-color: var(--warn);
    background: var(--warn-bg);
  }
  .notice.error {
    border-color: var(--error);
    background: var(--error-bg);
  }
</style>
