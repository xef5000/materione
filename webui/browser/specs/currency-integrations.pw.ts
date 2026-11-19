import { expect, test, type Page } from '@playwright/test';

/**
 * The currency form is drawn from the integration registry (logic.md section 18): `integration` is a choice among the
 * integrations the server registers, and the keys beneath it are the chosen integration's own, from its
 * self-description. Runs on the recorded sample server (the demo), whose registry holds the built-in `xp` and `custom`
 * integrations, so nothing about a particular integration is known to the editor.
 */
async function openCurrencies(page: Page): Promise<void> {
  await page.route('https://cdn.jsdelivr.net/**', (route) => route.abort());
  await page.route('**/host.json', (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    headers: { 'X-Materione-Host': 'cloud' },
    body: '{}',
  }));
  await page.goto('/?demo');
  await expect(page.getByRole('status').filter({ hasText: 'Sample content in this tab only' }))
    .toBeVisible({ timeout: 20_000 });
  await page.getByRole('button', { name: 'Content', exact: true }).click();
  await page.getByRole('button', { name: 'Currencies', exact: true }).click();
}

async function newCurrency(page: Page, name: string): Promise<void> {
  await page.getByRole('button', { name: 'New currency', exact: true }).first().click();
  await page.getByRole('textbox', { name: /currency name/i }).fill(name);
  await page.getByRole('dialog').getByRole('button', { name: /^create/i }).click();
  await expect(page.getByRole('button', { name: /open in files/i })).toBeVisible();
}

test('the studio offers the registered integrations and draws the chosen one\'s own keys', async ({ page }) => {
  await openCurrencies(page);
  await newCurrency(page, 'Gems');

  // the choices are the registry's, named as it names them, not a list kept in the editor
  const integration = page.getByRole('radiogroup', { name: 'Integration' });
  await expect(integration.getByRole('radio')).toHaveText(['Custom currency', 'Experience']);
  // the scaffold starts on xp, whose one key is the unit
  await expect(integration.getByRole('radio', { name: 'Experience' })).toBeChecked();
  const experience = page.getByRole('group', { name: 'Experience keys' });
  const unit = experience.getByRole('radiogroup', { name: /^Unit/ });
  await expect(unit.getByRole('radio')).toHaveText(['—', 'points', 'levels']);
  // the other integration's keys are not drawn
  await expect(page.getByRole('textbox', { name: /^Deposit command/ })).toHaveCount(0);

  await integration.getByRole('radio', { name: 'Custom currency' }).click();
  const custom = page.getByRole('group', { name: 'Custom currency keys' });
  for (const label of ['Deposit command', 'Withdraw command', 'Balance placeholder']) {
    await expect(custom.getByRole('textbox', { name: new RegExp(`^${label}`) })).toBeVisible();
  }
  await expect(page.getByRole('radiogroup', { name: /^Unit/ })).toHaveCount(0);
  await expect(page.getByRole('group', { name: 'Experience keys' })).toHaveCount(0);

  // a key typed into the form is part of the edit
  const deposit = custom.getByRole('textbox', { name: /^Deposit command/ });
  await deposit.fill('gems give {player} {amount}');
  await expect(deposit).toHaveValue('gems give {player} {amount}');
  await expect(page.getByRole('button', { name: 'Save & apply' })).toBeEnabled();
});

test('the new button names the currency, and the file editor offers the same choices', async ({ page }) => {
  await openCurrencies(page);
  // "currencies" minus its last letter is not a word, which the tab's fallback used to show
  await expect(page.getByRole('button', { name: 'New currencie' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'New currency', exact: true }).first()).toBeVisible();

  await newCurrency(page, 'Coins');
  await page.getByRole('button', { name: /open in files/i }).click();
  const integration = page.getByRole('combobox', { name: /^Integration/ });
  await expect(integration.locator('option')).toHaveText(['', 'custom', 'xp']);
  await integration.selectOption('custom');
  await expect(page.getByRole('textbox', { name: /^Deposit command/ })).toBeVisible();
});
