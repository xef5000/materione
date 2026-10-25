import path from 'node:path';
import { redactEvidence } from '../../qa/evidence-redaction.mjs';

/** Keep diagnostic excerpts useful without retaining capability or nonce material. */
export function sanitizeBrowserDiagnosticText(value, limit = 360) {
  const max = Number.isInteger(limit) && limit > 0 ? Math.min(limit, 500) : 360;
  return redactEvidence(String(value ?? ''))
    .replace(/https?:\/\/[^\s"'<>\\]+/gi, '[URL]')
    .replace(/([?&]s=)[A-Za-z0-9_-]+(#[A-Za-z0-9_-]+)?/g, (_match, prefix, fragment) => `${prefix}[REDACTED]${fragment ? '#[REDACTED]' : ''}`)
    .replace(/\/s\/[A-Za-z0-9_-]+(?:#[A-Za-z0-9_-]+)?/g, '/s/[REDACTED]#[REDACTED]')
    .replace(/\b[A-F0-9]{8}\b/gi, '[REDACTED]')
    .replace(/\b[A-Za-z0-9_-]{16,}\b/g, '[REDACTED]')
    .replace(/\s+/g, ' ')
    .slice(0, max);
}

/** Page diagnostics deliberately omit all DOM text until the address is scrubbed. */
export function sanitizeBrowserDiagnostic(state = {}) {
  const addressCleared = Boolean(state.addressCleared);
  const readyState = ['loading', 'interactive', 'complete'].includes(state.readyState)
    ? state.readyState
    : 'unknown';
  const bodyChars = Number.isFinite(state.bodyChars)
    ? Math.max(0, Math.min(Math.floor(state.bodyChars), 1_000_000))
    : 0;
  const safe = {
    addressCleared,
    readyState,
    workspaceNavigation: Boolean(state.workspaceNavigation),
    approvalPromptVisible: Boolean(state.approvalPromptVisible),
    appErrorVisible: Boolean(state.appErrorVisible),
    bodyChars,
  };
  if (addressCleared && typeof state.bodyText === 'string') {
    safe.bodyText = sanitizeBrowserDiagnosticText(state.bodyText);
  }
  return safe;
}

async function pageDiagnostic(page) {
  if (!page) return { available: false };
  try {
    const state = await page.evaluate(() => {
      const addressCleared = location.search === '' && location.hash === ''
        && !/\/s\/[A-Za-z0-9_-]+(?:\/|$)/.test(location.pathname);
      let bodyText = '';
      let bodyChars = 0;
      if (addressCleared) {
        const clone = document.body?.cloneNode(true);
        clone?.querySelectorAll('code.nonce').forEach((element) => {
          element.textContent = '[APPROVAL_NONCE]';
        });
        clone?.querySelectorAll('input, textarea').forEach((element) => {
          element.removeAttribute('value');
          element.textContent = '';
        });
        const text = clone?.innerText ?? '';
        bodyChars = Math.min(text.length, 1_000_000);
        bodyText = text.slice(0, 1200);
      }
      return {
        addressCleared,
        readyState: document.readyState,
        workspaceNavigation: Boolean(document.querySelector('nav[aria-label="Workspace navigation"]')),
        approvalPromptVisible: Boolean(document.querySelector('code.nonce')),
        appErrorVisible: Boolean(document.querySelector('.error')),
        bodyChars,
        bodyText,
      };
    });
    return { available: true, ...sanitizeBrowserDiagnostic(state) };
  } catch {
    return { available: false };
  }
}

/**
 * Optional live-editor browser adapter for local QA catalog entries. Playwright
 * is resolved from webui (where the dev-only dependency belongs) and loaded only
 * when a browser test opens a session.
 */
export function createQaBrowserAdapter({ testDir, openSession, approveNonce, onScreenshot }) {
  let browser;
  let context;
  let page;
  let opening;

  return {
    get page() { return page; },

    async openEditor() {
      if (page) return page;
      if (opening) return opening;
      opening = (async () => {
        let chromium;
        try {
          ({ chromium } = await import('@playwright/test'));
        } catch {
          throw new Error('optional browser dependency is unavailable; install webui dev dependencies and Chromium');
        }

        let stage = 'starting the local editor session';
        try {
          const capabilityUrl = await openSession();
          browser = await chromium.launch({ headless: true });
          context = await browser.newContext({
            viewport: { width: 1440, height: 900 },
            reducedMotion: 'reduce',
          });
          page = await context.newPage();
          stage = 'opening the local editor';
          await page.goto(capabilityUrl, { waitUntil: 'domcontentloaded', timeout: 30_000 });
          stage = 'waiting for normal editor approval';
          const nonce = page.locator('code.nonce');
          await nonce.waitFor({ state: 'visible', timeout: 30_000 });
          const prefix = (await nonce.innerText()).trim();
          if (!/^[0-9a-f]{8}$/i.test(prefix)) {
            throw new Error('unexpected approval nonce shape');
          }
          await page.waitForFunction(
            () => location.search === '' && location.hash === '',
            undefined,
            { timeout: 5_000 },
          );
          await approveNonce(prefix);
          stage = 'waiting for the approved editor surface';
          await page.getByRole('navigation', { name: 'Workspace navigation' })
            .waitFor({ state: 'visible', timeout: 45_000 });
          return page;
        } catch (error) {
          const diagnostic = await pageDiagnostic(page);
          const errorName = /^[A-Za-z][A-Za-z0-9_.-]{0,39}$/.test(error?.name ?? '')
            ? error.name
            : 'Error';
          const errorMessage = sanitizeBrowserDiagnosticText(error?.message ?? 'unknown failure', 240);
          await this.close();
          throw new Error(
            `browser editor handshake failed while ${stage}; cause=${errorName}: ${errorMessage}; page=${JSON.stringify(diagnostic)}`,
          );
        }
      })();
      try {
        return await opening;
      } finally {
        opening = undefined;
      }
    },

    async browserShot(name) {
      if (!page) throw new Error('open the editor browser before capturing a browser screenshot');
      const safe = String(name).replace(/[^a-z0-9_-]+/gi, '-') || 'editor';
      const out = path.join(testDir, `${safe}.browser.png`);
      try {
        const app = page.locator('.workbench');
        await app.waitFor({ state: 'visible', timeout: 10_000 });
        await app.screenshot({ path: out, animations: 'disabled' });
      } catch {
        throw new Error('browser App screenshot failed');
      }
      onScreenshot(name, out);
      return out;
    },

    async close() {
      page = undefined;
      const currentContext = context;
      const currentBrowser = browser;
      context = undefined;
      browser = undefined;
      try { await currentContext?.close(); } catch { /* already closed */ }
      try { await currentBrowser?.close(); } catch { /* already closed */ }
    },
  };
}
