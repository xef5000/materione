/**
 * Cloud Assistant live ModelProvider, Google/Discord sign-in, and Polar top-up E2E (issue #410, ai-credits.md §8.2).
 */
import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import '../contract';

const PROXY_ORIGIN = 'https://ai.materione.com';

const rail = (page: Page): Locator => page.getByRole('button', { name: /^Assistant/ });
const panel = (page: Page): Locator => page.getByRole('region', { name: 'Assistant', exact: true });
const composer = (page: Page): Locator => page.locator('#assistant-input');

test.beforeEach(async ({ page }) => {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
  await page.addInitScript(() => {
    (window as unknown as { __openedWindows: Array<{ url: string; target?: string; features?: string }> }).__openedWindows = [];
    window.open = ((url?: string | URL, target?: string, features?: string) => {
      (window as unknown as { __openedWindows: Array<{ url: string; target?: string; features?: string }> }).__openedWindows.push({
        url: String(url ?? ''),
        target,
        features,
      });
      return { closed: false, focus() {}, close() {} } as unknown as Window;
    }) as typeof window.open;
  });
});

test('cloud editor wires Google/Discord sign-in, SSE streaming + allowance refresh, and Polar $10-$200 checkout', async ({ page }) => {
  let signedIn = false;
  let freeRemainingCredits = 6.66;
  let balanceCredits = 1000;
  let allowanceCalls = 0;
  let checkoutBody: Record<string, unknown> | null = null;

  await page.route(`${PROXY_ORIGIN}/v1/**`, async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/v1/me') {
      if (!signedIn) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ authenticated: false }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          authenticated: true,
          account: {
            id: 'acc_1',
            displayName: 'Alex',
            emailHint: 'a***@example.com',
            suspended: false,
            freeEligible: true,
          },
          credits: {
            balancePicos: balanceCredits * 7_500_000_000,
            balanceCredits,
            picosPerCredit: 7_500_000_000,
          },
          freeAllowance: {
            remainingPicos: Math.round(freeRemainingCredits * 7_500_000_000),
            dailyCapPicos: 50_000_000_000,
            remainingCredits: freeRemainingCredits,
            dailyCapCredits: 6.66,
            poolOpen: true,
          },
        }),
      });
      return;
    }

    if (url.pathname === '/v1/allowance') {
      allowanceCalls++;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          day: '2026-11-15',
          account: {
            id: 'acc_1',
            displayName: 'Alex',
            emailHint: 'a***@example.com',
            suspended: false,
            freeEligible: true,
          },
          credits: {
            balancePicos: balanceCredits * 7_500_000_000,
            balanceCredits,
            picosPerCredit: 7_500_000_000,
          },
          freeAllowance: {
            remainingPicos: Math.round(freeRemainingCredits * 7_500_000_000),
            dailyCapPicos: 50_000_000_000,
            remainingCredits: freeRemainingCredits,
            dailyCapCredits: 6.66,
            poolOpen: true,
          },
        }),
      });
      return;
    }

    if (url.pathname === '/v1/chat') {
      freeRemainingCredits = 6.41;
      const sse = [
        'event: message_start\ndata: {"type":"message_start","message":{"usage":{"input_tokens":150,"cache_read_input_tokens":100,"output_tokens":0}}}\n\n',
        'event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}\n\n',
        'event: content_block_delta\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello from Materione AI."}}\n\n',
        'event: content_block_stop\ndata: {"type":"content_block_stop","index":0}\n\n',
        'event: message_delta\ndata: {"type":"message_delta","usage":{"output_tokens":12}}\n\n',
        'event: message_stop\ndata: {"type":"message_stop"}\n\n',
        'event: materione_settlement\ndata: {"settledCredits":0.25,"usage":{"inputTokens":150,"outputTokens":12,"cachedInputTokens":100}}\n\n',
      ].join('');
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream; charset=utf-8',
        body: sse,
      });
      return;
    }

    if (url.pathname === '/v1/checkout') {
      checkoutBody = route.request().postDataJSON() as Record<string, unknown>;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ checkoutUrl: 'https://checkout.polar.sh/test-session-15' }),
      });
      return;
    }

    await route.abort();
  });

  // 1. Unsigned cloud editor opens Assistant -> Sign in with Google / Discord, composer hidden
  await page.goto('/browser/fixture.html?codemode=cloud-proxy&host=cloud');
  await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');

  await rail(page).click();
  await expect(panel(page)).toBeVisible();
  await expect(panel(page).getByText('Sign in required', { exact: true })).toBeVisible();
  await expect(panel(page).getByTestId('ai-signin-google')).toBeVisible();
  await expect(panel(page).getByTestId('ai-signin-discord')).toBeVisible();
  await expect(composer(page)).toHaveCount(0);

  // 2. Sign in with Discord -> simulate popup postMessage -> AccountBar + disclosure + composer appear
  await panel(page).getByTestId('ai-signin-discord').click();
  const openedAfterAuth = await page.evaluate(() => (window as unknown as { __openedWindows: Array<{ url: string }> }).__openedWindows);
  expect(openedAfterAuth[0]?.url).toBe(`${PROXY_ORIGIN}/v1/auth/discord/start`);

  signedIn = true;
  await page.evaluate((origin) => {
    window.dispatchEvent(new MessageEvent('message', { origin, data: { type: 'materione-ai-auth-complete', ok: true } }));
  }, PROXY_ORIGIN);

  const accountBar = panel(page).getByRole('region', { name: 'Materione AI account and credits' });
  await expect(accountBar).toBeVisible();
  await expect(accountBar).toContainText('Alex');
  await expect(accountBar).toContainText('Free today: 6.66 credits');
  await expect(accountBar).toContainText('Balance: 1,000 credits');
  await expect(panel(page).locator('section[aria-label="What leaves this page"]')).toBeVisible();
  await expect(composer(page)).toBeVisible();

  // 3. Send a prompt -> SSE reply renders AND /v1/allowance refreshes balance
  await composer(page).fill('Say hello');
  await composer(page).press('Enter');
  const transcript = panel(page).getByRole('log', { name: 'Conversation' });
  await expect(transcript.getByText('Hello from Materione AI.')).toBeVisible();
  await expect.poll(() => allowanceCalls).toBeGreaterThanOrEqual(1);
  await expect(accountBar).toContainText('Free today: 6.41 credits');

  // 4. Buy credits -> validates $10-$200 range and posts { amountCents: 1500 } to /v1/checkout
  await accountBar.getByTestId('ai-buy-credits-toggle').click();
  const usdInput = accountBar.getByTestId('ai-topup-usd');
  const submitBtn = accountBar.getByTestId('ai-checkout-submit');
  await usdInput.fill('9');
  await expect(submitBtn).toBeDisabled();
  await usdInput.fill('15');
  await expect(accountBar).toContainText('1,500 credits');
  await expect(submitBtn).toBeEnabled();
  await submitBtn.click();

  await expect.poll(() => checkoutBody).toEqual({ amountCents: 1500 });
  await expect
    .poll(() => page.evaluate(() => (window as unknown as { __openedWindows: Array<{ url: string }> }).__openedWindows[1]?.url ?? null))
    .toBe('https://checkout.polar.sh/test-session-15');
});

test('local plugin host makes zero requests to ai.materione.com even with codemode=cloud-proxy', async ({ page }) => {
  const proxyRequests: string[] = [];
  page.on('request', (req) => {
    if (req.url().startsWith(PROXY_ORIGIN)) proxyRequests.push(req.url());
  });

  for (const query of ['?codemode', '?codemode=cloud-proxy']) {
    await page.goto(`/browser/fixture.html${query}`);
    await expect(page.locator('html')).toHaveAttribute('data-fixture-ready', 'true');
    await rail(page).click();
    await expect(panel(page).getByRole('note', { name: 'Available on editor.materione.com' })).toBeVisible();
  }
  expect(proxyRequests).toEqual([]);
});
