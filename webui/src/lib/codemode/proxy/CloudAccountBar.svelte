<script lang="ts">
  /**
   * Compact Materione AI account bar with daily free allowance, prepaid balance, Polar $10-$200 top-up form,
   * and billing portal link (ai-credits.md section 8.2).
   */
  import Button from '../../ui/Button.svelte';
  import { cloudAccount, openPolarPortal, signOut, startPolarCheckout } from './account.svelte';

  const QUICK_AMOUNTS = [10, 25, 50] as const;

  let buyOpen = $state(false);
  let amountDollars = $state<number>(10);

  const identity = $derived(cloudAccount.account?.displayName ?? cloudAccount.account?.emailHint ?? 'Signed in');
  const parsedAmount = $derived(typeof amountDollars === 'number' ? amountDollars : Number(amountDollars));
  const amountValid = $derived(Number.isInteger(parsedAmount) && parsedAmount >= 10 && parsedAmount <= 200);

  async function submitCheckout(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (!amountValid || cloudAccount.billingBusy) return;
    const opened = await startPolarCheckout(parsedAmount);
    if (opened) buyOpen = false;
  }
</script>

<section class="account-bar" aria-label="Materione AI account and credits">
  <div class="summary">
    <div class="meta">
      <strong class="who">{identity}</strong>
      <div class="balances">
        {#if cloudAccount.freeAllowance !== null}
          <span>
            {#if cloudAccount.freeAllowance.poolOpen}
              Free today: {cloudAccount.freeAllowance.remainingCredits.toFixed(2)} credits
            {:else}
              Pool closed today
            {/if}
          </span>
        {/if}
        {#if cloudAccount.credits !== null}
          <span>Balance: {cloudAccount.credits.balanceCredits.toLocaleString('en-US')} credits</span>
        {/if}
      </div>
    </div>
    <div class="actions">
      <Button
        size="sm"
        variant="secondary"
        data-testid="ai-buy-credits-toggle"
        aria-expanded={buyOpen}
        onclick={() => (buyOpen = !buyOpen)}
      >Buy credits</Button>
      <Button
        size="sm"
        variant="ghost"
        data-testid="ai-billing-portal"
        disabled={cloudAccount.billingBusy}
        onclick={() => void openPolarPortal()}
      >Invoices</Button>
      <Button
        size="sm"
        variant="ghost"
        data-testid="ai-signout"
        onclick={() => void signOut()}
      >Sign out</Button>
    </div>
  </div>

  {#if cloudAccount.notice !== null && !cloudAccount.account?.suspended}
    <p class="notice {cloudAccount.notice.tone}" role={cloudAccount.notice.tone === 'info' ? 'status' : 'alert'}>
      {cloudAccount.notice.text}
    </p>
  {/if}

  {#if buyOpen}
    <form class="topup" onsubmit={submitCheckout}>
      <div class="chips" role="group" aria-label="Quick-pick amounts">
        {#each QUICK_AMOUNTS as preset (preset)}
          <button
            type="button"
            class="chip"
            class:chip-accent={parsedAmount === preset}
            onclick={() => (amountDollars = preset)}
          >${preset}</button>
        {/each}
      </div>

      <label class="field" for="ai-topup-usd">
        <span class="field-label">Custom amount (USD)</span>
        <input
          id="ai-topup-usd"
          data-testid="ai-topup-usd"
          type="number"
          min="10"
          max="200"
          step="1"
          bind:value={amountDollars}
        />
      </label>

      {#if amountValid}
        <p class="preview">
          <strong>${parsedAmount} USD &rarr; {(parsedAmount * 100).toLocaleString('en-US')} credits</strong>
          <span class="hint">Tax calculated at checkout by Polar &middot; Credits never expire</span>
        </p>
      {:else}
        <p class="field-error" role="status">Enter a whole dollar amount between $10 and $200.</p>
      {/if}

      <div class="submit-row">
        <Button
          size="sm"
          variant="primary"
          type="submit"
          data-testid="ai-checkout-submit"
          disabled={!amountValid || cloudAccount.billingBusy}
        >Continue to checkout</Button>
      </div>
    </form>
  {/if}
</section>

<style>
  .account-bar {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--bg-raised);
    font-size: var(--text-sm);
  }
  .summary {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
  }
  .meta {
    display: grid;
    gap: var(--space-half);
    min-width: 0;
  }
  .who {
    color: var(--fg);
    font-size: var(--text-base);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .balances {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-3);
    color: var(--muted);
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-1);
  }
  .topup {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-2);
    padding-top: var(--space-2);
    border-top: 1px solid var(--border);
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1);
  }
  .preview {
    margin: 0;
    display: grid;
    gap: var(--space-half);
    color: var(--fg);
  }
  .hint {
    color: var(--muted);
    font-size: var(--text-xs);
  }
  .submit-row {
    display: flex;
    justify-content: flex-start;
  }
  .notice {
    margin: 0;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    font-size: var(--text-sm);
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
