/**
 * Cloud Materione AI account session, daily free allowance, and Polar billing orchestration
 * (ai-credits.md section 8.2).
 *
 * Authentication stays strictly inside an HttpOnly session managed by `ai.materione.com`;
 * this module never persists tokens or credentials in the browser and never touches editor
 * session keys or review actions.
 */
import { untrack } from 'svelte';
import type { RpcChannel } from '../../api';
import type { EditorHost } from '../../host';
import { reviewAvailable } from '../review/state.svelte';
import { chat, installChatProvider } from '../chat/state.svelte';
import { resolveCloudProxyBaseUrl } from './config';
import { createProxyModelProvider } from './provider';

export interface CloudAccountIdentity {
  id: string;
  displayName: string | null;
  emailHint: string;
  suspended: boolean;
  freeEligible: boolean;
}

export interface CloudCreditsSummary {
  balancePicos: number;
  balanceCredits: number;
  picosPerCredit: number;
}

export interface CloudFreeAllowance {
  remainingPicos: number;
  dailyCapPicos: number;
  remainingCredits: number;
  dailyCapCredits: number;
  poolOpen: boolean;
  eligible?: boolean;
}

export interface CloudNotice {
  tone: 'info' | 'warn' | 'error';
  text: string;
}

class CloudAccountState {
  active = $state(false);
  status = $state<'idle' | 'loading' | 'signed-out' | 'signed-in' | 'error'>('idle');
  account = $state.raw<CloudAccountIdentity | null>(null);
  credits = $state.raw<CloudCreditsSummary | null>(null);
  freeAllowance = $state.raw<CloudFreeAllowance | null>(null);
  notice = $state<CloudNotice | null>(null);
  billingBusy = $state(false);
}

export const cloudAccount = new CloudAccountState();

let activeChannel: RpcChannel | null = null;
let activeBaseUrl: string | null = null;
let syncGeneration = 0;
let pendingBillingRefresh = false;
let removeListeners: (() => void) | null = null;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseAccount(raw: unknown): CloudAccountIdentity | null {
  if (!isRecord(raw) || typeof raw.id !== 'string') return null;
  return {
    id: raw.id,
    displayName: typeof raw.displayName === 'string' ? raw.displayName : null,
    emailHint: typeof raw.emailHint === 'string' ? raw.emailHint : '',
    suspended: Boolean(raw.suspended),
    freeEligible: raw.freeEligible !== false,
  };
}

function parseCredits(raw: unknown): CloudCreditsSummary | null {
  if (!isRecord(raw)) return null;
  if (
    typeof raw.balancePicos !== 'number' ||
    typeof raw.balanceCredits !== 'number' ||
    typeof raw.picosPerCredit !== 'number'
  ) {
    return null;
  }
  return {
    balancePicos: raw.balancePicos,
    balanceCredits: raw.balanceCredits,
    picosPerCredit: raw.picosPerCredit,
  };
}

function parseFreeAllowance(raw: unknown): CloudFreeAllowance | null {
  if (!isRecord(raw)) return null;
  if (
    typeof raw.remainingPicos !== 'number' ||
    typeof raw.dailyCapPicos !== 'number' ||
    typeof raw.remainingCredits !== 'number' ||
    typeof raw.dailyCapCredits !== 'number'
  ) {
    return null;
  }
  return {
    remainingPicos: raw.remainingPicos,
    dailyCapPicos: raw.dailyCapPicos,
    remainingCredits: raw.remainingCredits,
    dailyCapCredits: raw.dailyCapCredits,
    poolOpen: raw.poolOpen !== false,
    ...(typeof raw.eligible === 'boolean' ? { eligible: raw.eligible } : {}),
  };
}

function cleanupListeners(): void {
  removeListeners?.();
  removeListeners = null;
}

function handleSessionExpired(): void {
  cloudAccount.status = 'signed-out';
  cloudAccount.account = null;
  cloudAccount.credits = null;
  cloudAccount.freeAllowance = null;
  cloudAccount.notice = {
    tone: 'warn',
    text: 'Your Materione AI session expired. Sign in again to continue.',
  };
  if (chat.prompts.length === 0) {
    installChatProvider(null);
  }
}

const OAUTH_POPUP_ERRORS: Record<string, string> = {
  oauth_denied: 'Sign-in was cancelled or denied.',
  invalid_oauth_state: 'Your sign-in session expired. Please try again.',
  oauth_exchange_failed: 'Could not complete sign-in with the identity provider.',
  email_not_verified: 'Your Google or Discord account must have a verified email address.',
  account_conflict: 'That identity is already linked to a different Materione account.',
  server_error: 'Sign-in is temporarily unavailable. Please try again.',
};

function applySignedInState(
  baseUrl: string,
  account: CloudAccountIdentity | null,
  credits: CloudCreditsSummary | null,
  freeAllowance: CloudFreeAllowance | null,
): void {
  cloudAccount.account = account;
  cloudAccount.credits = credits;
  cloudAccount.freeAllowance = freeAllowance;
  cloudAccount.status = 'signed-in';

  if (account?.suspended) {
    if (chat.prompts.length === 0) {
      installChatProvider(null);
    }
    cloudAccount.notice = {
      tone: 'warn',
      text: 'Your Materione AI account is suspended because of an unresolved billing dispute.',
    };
    return;
  }

  if ((credits?.balancePicos ?? 0) < 0) {
    if (chat.prompts.length === 0) {
      installChatProvider(null);
    }
    cloudAccount.notice = {
      tone: 'warn',
      text: 'Your credit balance is negative after a refund. Use Buy credits above to restore a positive balance and resume.',
    };
    return;
  }

  cloudAccount.notice = null;
  if (chat.prompts.length === 0 && chat.provider?.id !== 'materione-cloud') {
    installChatProvider(
      createProxyModelProvider({
        baseUrl,
        onSettled: () => void refreshAllowance(),
        onUnauthenticated: () => handleSessionExpired(),
      }),
    );
  }
}

function attachListeners(baseUrl: string): void {
  cleanupListeners();
  if (typeof window === 'undefined') return;
  const expectedOrigin = new URL(baseUrl).origin;
  const pollTimers = new Set<ReturnType<typeof setTimeout>>();

  const clearPolls = (): void => {
    for (const id of pollTimers) clearTimeout(id);
    pollTimers.clear();
  };

  const schedulePostCheckoutRefresh = (): void => {
    clearPolls();
    const baselinePicos = cloudAccount.credits?.balancePicos ?? null;
    void refreshAllowance();
    for (const delayMs of [2_500, 6_000]) {
      const timer = setTimeout(() => {
        pollTimers.delete(timer);
        if (cloudAccount.status !== 'signed-in') return;
        if (baselinePicos !== null && (cloudAccount.credits?.balancePicos ?? baselinePicos) > baselinePicos) {
          clearPolls();
          return;
        }
        void refreshAllowance();
      }, delayMs);
      pollTimers.add(timer);
    }
  };

  const onMessage = (event: MessageEvent): void => {
    if (event.origin !== expectedOrigin) return;
    const data: unknown = event.data;
    if (!isRecord(data)) return;
    if (data.type === 'materione-ai-auth-complete') {
      if (data.ok === true) {
        cloudAccount.notice = null;
        void refreshMe();
      } else {
        const rawCode = typeof data.error === 'string' ? data.error.trim() : '';
        const message =
          OAUTH_POPUP_ERRORS[rawCode] ??
          (rawCode !== '' ? rawCode : 'Sign-in could not be completed. Please try again.');
        cloudAccount.notice = { tone: 'error', text: message };
      }
    } else if (data.type === 'materione-ai-checkout-complete') {
      pendingBillingRefresh = false;
      schedulePostCheckoutRefresh();
    }
  };

  const onFocus = (): void => {
    if (pendingBillingRefresh && cloudAccount.status === 'signed-in') {
      pendingBillingRefresh = false;
      schedulePostCheckoutRefresh();
    }
  };

  window.addEventListener('message', onMessage);
  window.addEventListener('focus', onFocus);
  removeListeners = () => {
    clearPolls();
    window.removeEventListener('message', onMessage);
    window.removeEventListener('focus', onFocus);
  };
}

export async function refreshMe(): Promise<void> {
  const baseUrl = activeBaseUrl;
  if (baseUrl === null) return;
  const generation = ++syncGeneration;
  cloudAccount.status = 'loading';
  try {
    const response = await fetch(`${baseUrl}/v1/me`, { credentials: 'include' });
    if (generation !== syncGeneration || activeBaseUrl !== baseUrl) return;
    if (!response.ok) {
      cloudAccount.status = 'error';
      cloudAccount.notice = { tone: 'error', text: 'Could not reach Materione AI right now.' };
      if (chat.prompts.length === 0) installChatProvider(null);
      return;
    }
    const raw: unknown = await response.json();
    if (generation !== syncGeneration || activeBaseUrl !== baseUrl) return;
    if (!isRecord(raw) || raw.authenticated !== true) {
      cloudAccount.status = 'signed-out';
      cloudAccount.account = null;
      cloudAccount.credits = null;
      cloudAccount.freeAllowance = null;
      if (chat.prompts.length === 0) installChatProvider(null);
      return;
    }
    applySignedInState(
      baseUrl,
      parseAccount(raw.account),
      parseCredits(raw.credits),
      parseFreeAllowance(raw.freeAllowance),
    );
  } catch {
    if (generation !== syncGeneration || activeBaseUrl !== baseUrl) return;
    cloudAccount.status = 'error';
    cloudAccount.notice = { tone: 'error', text: 'Could not reach Materione AI right now.' };
    if (chat.prompts.length === 0) installChatProvider(null);
  }
}

export async function refreshAllowance(): Promise<void> {
  const baseUrl = activeBaseUrl;
  if (baseUrl === null) return;
  try {
    const response = await fetch(`${baseUrl}/v1/allowance`, { credentials: 'include' });
    if (activeBaseUrl !== baseUrl) return;
    if (response.status === 401) {
      handleSessionExpired();
      return;
    }
    if (!response.ok) return;
    const raw: unknown = await response.json();
    if (activeBaseUrl !== baseUrl || !isRecord(raw)) return;
    const nextAccount = parseAccount(raw.account) ?? cloudAccount.account;
    const nextCredits = parseCredits(raw.credits) ?? cloudAccount.credits;
    const nextFree = parseFreeAllowance(raw.freeAllowance) ?? cloudAccount.freeAllowance;
    applySignedInState(baseUrl, nextAccount, nextCredits, nextFree);
  } catch {
    // Non-fatal: keep existing balance visible until the next turn or focus refresh.
  }
}

export function syncCloudAssistant(
  channel: RpcChannel | null,
  host: EditorHost | null | undefined,
  options: { force?: boolean } = {},
): void {
  const baseUrl = channel !== null && reviewAvailable(channel) ? resolveCloudProxyBaseUrl(host) : null;
  untrack(() => {
    if (baseUrl === null) {
      cleanupListeners();
      syncGeneration++;
      activeChannel = null;
      activeBaseUrl = null;
      pendingBillingRefresh = false;
      cloudAccount.active = false;
      cloudAccount.status = 'idle';
      cloudAccount.account = null;
      cloudAccount.credits = null;
      cloudAccount.freeAllowance = null;
      cloudAccount.notice = null;
      cloudAccount.billingBusy = false;
      if (chat.prompts.length === 0 && chat.provider?.id === 'materione-cloud') {
        installChatProvider(null);
      }
      return;
    }

    if (!options.force && activeChannel === channel && activeBaseUrl === baseUrl) {
      return;
    }

    activeChannel = channel;
    activeBaseUrl = baseUrl;
    cloudAccount.active = true;
    cloudAccount.notice = null;
    attachListeners(baseUrl);
    void refreshMe();
  });
}

export function disposeCloudAssistant(): void {
  cleanupListeners();
  syncGeneration++;
  activeChannel = null;
  activeBaseUrl = null;
  pendingBillingRefresh = false;
  cloudAccount.active = false;
  cloudAccount.status = 'idle';
  cloudAccount.account = null;
  cloudAccount.credits = null;
  cloudAccount.freeAllowance = null;
  cloudAccount.notice = null;
  cloudAccount.billingBusy = false;
}

export function signInWith(provider: 'google' | 'discord'): void {
  if (activeBaseUrl === null) return;
  cloudAccount.notice = null;
  const popup = window.open(
    `${activeBaseUrl}/v1/auth/${provider}/start`,
    'materione-ai-auth',
    'popup=yes,width=520,height=680',
  );
  if (popup === null) {
    cloudAccount.notice = {
      tone: 'warn',
      text: 'Pop-up was blocked. Allow pop-ups for editor.materione.com to sign in.',
    };
  }
}

export async function signOut(): Promise<void> {
  const baseUrl = activeBaseUrl;
  if (baseUrl === null) return;
  syncGeneration++;
  await fetch(`${baseUrl}/v1/auth/logout`, {
    method: 'POST',
    credentials: 'include',
  }).catch(() => {});
  if (activeBaseUrl !== baseUrl) return;
  if (chat.prompts.length === 0) {
    installChatProvider(null);
  }
  cloudAccount.status = 'signed-out';
  cloudAccount.account = null;
  cloudAccount.credits = null;
  cloudAccount.freeAllowance = null;
  cloudAccount.notice = null;
}

export async function startPolarCheckout(amountDollars: number): Promise<boolean> {
  const baseUrl = activeBaseUrl;
  if (baseUrl === null) return false;
  if (!Number.isInteger(amountDollars) || amountDollars < 10 || amountDollars > 200) {
    cloudAccount.notice = { tone: 'error', text: 'Enter a whole dollar amount between $10 and $200.' };
    return false;
  }

  cloudAccount.billingBusy = true;
  cloudAccount.notice = null;
  try {
    const response = await fetch(`${baseUrl}/v1/checkout`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ amountCents: amountDollars * 100 }),
    });
    if (response.status === 401) {
      handleSessionExpired();
      return false;
    }
    const raw: unknown = await response.json().catch(() => null);
    const body = isRecord(raw) ? raw : null;
    const checkoutUrl = typeof body?.checkoutUrl === 'string' ? body.checkoutUrl : '';
    const message = typeof body?.message === 'string' ? body.message : '';
    if (!response.ok || !checkoutUrl.startsWith('https://')) {
      cloudAccount.notice = { tone: 'error', text: message || 'Could not start Polar checkout.' };
      return false;
    }
    pendingBillingRefresh = true;
    window.open(checkoutUrl, '_blank', 'noopener,noreferrer');
    return true;
  } catch {
    cloudAccount.notice = { tone: 'error', text: 'Could not reach the billing service.' };
    return false;
  } finally {
    cloudAccount.billingBusy = false;
  }
}

export async function openPolarPortal(): Promise<boolean> {
  const baseUrl = activeBaseUrl;
  if (baseUrl === null) return false;

  cloudAccount.billingBusy = true;
  cloudAccount.notice = null;
  try {
    const response = await fetch(`${baseUrl}/v1/portal`, {
      method: 'POST',
      credentials: 'include',
    });
    if (response.status === 401) {
      handleSessionExpired();
      return false;
    }
    const raw: unknown = await response.json().catch(() => null);
    const body = isRecord(raw) ? raw : null;
    const portalUrl = typeof body?.portalUrl === 'string' ? body.portalUrl : '';
    const message = typeof body?.message === 'string' ? body.message : '';
    if (!response.ok || !portalUrl.startsWith('https://')) {
      cloudAccount.notice = { tone: 'error', text: message || 'Could not open the billing portal.' };
      return false;
    }
    pendingBillingRefresh = true;
    window.open(portalUrl, '_blank', 'noopener,noreferrer');
    return true;
  } catch {
    cloudAccount.notice = { tone: 'error', text: 'Could not reach the billing service.' };
    return false;
  } finally {
    cloudAccount.billingBusy = false;
  }
}
