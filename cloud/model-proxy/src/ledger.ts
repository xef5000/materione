import { PICO_PER_USD, PICOS_PER_CREDIT } from "./config";
import type { AuthenticatedAccount } from "./auth";

export interface ReserveParams {
  id: string;
  day: string;
  accountId: string;
  subnetHash: string;
  model: string;
  reservedPicos: number;
  accountCapPicos: number;
  subnetCapPicos: number;
  poolCapPicos: number;
  createdAt: number;
}

export type ReserveFailureReason =
  | "account_suspended"
  | "account_not_found"
  | "insufficient_credits"
  | "allowance_exhausted"
  | "pool_exhausted"
  | "free_not_eligible";

export type ReserveResult =
  | { ok: true; freeReservedPicos: number; paidReservedPicos: number }
  | { ok: false; reason: ReserveFailureReason; creditBalancePicos?: number };

export interface SettleParams {
  id: string;
  status: "settled" | "kept";
  settledPicos: number;
  inputTokens: number | null;
  outputTokens: number | null;
  cacheReadTokens: number | null;
  cacheWriteTokens: number | null;
  settledAt: number;
}

export interface AccountAllowanceStatus {
  day: string;
  remainingPicos: number;
  dailyCapPicos: number;
  remainingUsd: number;
  dailyCapUsd: number;
  poolOpen: boolean;
  credits: {
    balancePicos: number;
    balanceCredits: number;
    picosPerCredit: number;
  };
  freeAllowance: {
    remainingPicos: number;
    dailyCapPicos: number;
    remainingCredits: number;
    dailyCapCredits: number;
    poolOpen: boolean;
    eligible: boolean;
  };
}

export async function reserveAllowance(db: D1Database, params: ReserveParams): Promise<ReserveResult> {
  try {
    const row = await db
      .prepare(
        `INSERT INTO reservations (
           id, day, account_id, subnet_hash, model, reserved_picos,
           free_reserved_picos, paid_reserved_picos,
           account_cap_picos, subnet_cap_picos, pool_cap_picos, created_at
         )
         SELECT
           ?1, ?2, a.id, ?3, ?4, ?5,
           MIN(?5, CASE WHEN a.free_eligible = 1 THEN MAX(0, MIN(
             ?6 - COALESCE((SELECT used_picos FROM daily_account_free_usage WHERE day = ?2 AND account_id = a.id), 0),
             ?7 - COALESCE((SELECT used_picos FROM daily_subnet_free_usage WHERE day = ?2 AND subnet_hash = ?3), 0),
             ?8 - COALESCE((SELECT used_picos FROM daily_pool_free_usage WHERE day = ?2), 0)
           )) ELSE 0 END) AS free_reserved_picos,
           ?5 - MIN(?5, CASE WHEN a.free_eligible = 1 THEN MAX(0, MIN(
             ?6 - COALESCE((SELECT used_picos FROM daily_account_free_usage WHERE day = ?2 AND account_id = a.id), 0),
             ?7 - COALESCE((SELECT used_picos FROM daily_subnet_free_usage WHERE day = ?2 AND subnet_hash = ?3), 0),
             ?8 - COALESCE((SELECT used_picos FROM daily_pool_free_usage WHERE day = ?2), 0)
           )) ELSE 0 END) AS paid_reserved_picos,
           ?6, ?7, ?8, ?9
         FROM accounts a
         WHERE a.id = ?10
         RETURNING free_reserved_picos, paid_reserved_picos`,
      )
      .bind(
        params.id,
        params.day,
        params.subnetHash,
        params.model,
        params.reservedPicos,
        params.accountCapPicos,
        params.subnetCapPicos,
        params.poolCapPicos,
        params.createdAt,
        params.accountId,
      )
      .first<{ free_reserved_picos: number; paid_reserved_picos: number }>();

    if (!row) {
      return { ok: false, reason: "account_not_found" };
    }
    return {
      ok: true,
      freeReservedPicos: row.free_reserved_picos,
      paidReservedPicos: row.paid_reserved_picos,
    };
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    if (message.includes("ACCOUNT_SUSPENDED")) {
      return { ok: false, reason: "account_suspended" };
    }
    if (message.includes("FREE_NOT_ELIGIBLE")) {
      return { ok: false, reason: "free_not_eligible" };
    }
    if (message.includes("ALLOWANCE_EXHAUSTED")) {
      return { ok: false, reason: "allowance_exhausted" };
    }
    if (message.includes("POOL_EXHAUSTED")) {
      return { ok: false, reason: "pool_exhausted" };
    }
    if (message.includes("INSUFFICIENT_CREDITS")) {
      const diag = await db
        .prepare(
          `SELECT
             a.credit_balance_picos,
             a.free_eligible,
             COALESCE((SELECT used_picos FROM daily_account_free_usage WHERE day = ?1 AND account_id = a.id), 0) AS account_used,
             COALESCE((SELECT used_picos FROM daily_subnet_free_usage WHERE day = ?1 AND subnet_hash = ?2), 0) AS subnet_used,
             COALESCE((SELECT used_picos FROM daily_pool_free_usage WHERE day = ?1), 0) AS pool_used
           FROM accounts a
           WHERE a.id = ?3`,
        )
        .bind(params.day, params.subnetHash, params.accountId)
        .first<{
          credit_balance_picos: number;
          free_eligible: number;
          account_used: number;
          subnet_used: number;
          pool_used: number;
        }>();

      if (!diag) {
        return { ok: false, reason: "account_not_found" };
      }
      if (diag.credit_balance_picos > 0) {
        return {
          ok: false,
          reason: "insufficient_credits",
          creditBalancePicos: diag.credit_balance_picos,
        };
      }
      if (diag.free_eligible !== 1) {
        return { ok: false, reason: "free_not_eligible", creditBalancePicos: 0 };
      }
      const accountRem = Math.max(0, params.accountCapPicos - diag.account_used);
      const subnetRem = Math.max(0, params.subnetCapPicos - diag.subnet_used);
      const poolRem = Math.max(0, params.poolCapPicos - diag.pool_used);
      if (poolRem < params.reservedPicos && accountRem >= params.reservedPicos && subnetRem >= params.reservedPicos) {
        return { ok: false, reason: "pool_exhausted", creditBalancePicos: 0 };
      }
      return { ok: false, reason: "allowance_exhausted", creditBalancePicos: 0 };
    }
    throw err;
  }
}

export async function settleReservation(db: D1Database, params: SettleParams): Promise<void> {
  await db
    .prepare(
      `UPDATE reservations
       SET status = ?2,
           settled_picos = ?3,
           free_settled_picos = MIN(?3, free_reserved_picos),
           paid_settled_picos = MAX(0, ?3 - MIN(?3, free_reserved_picos)),
           input_tokens = ?4,
           output_tokens = ?5,
           cache_read_tokens = ?6,
           cache_write_tokens = ?7,
           settled_at = ?8
       WHERE id = ?1 AND status = 'reserved'`,
    )
    .bind(
      params.id,
      params.status,
      params.settledPicos,
      params.inputTokens,
      params.outputTokens,
      params.cacheReadTokens,
      params.cacheWriteTokens,
      params.settledAt,
    )
    .run();
}

export async function getAccountAllowanceStatus(
  db: D1Database,
  day: string,
  account: Pick<AuthenticatedAccount, "id" | "creditBalancePicos" | "freeEligible" | "suspended">,
  subnetHash: string,
  accountCapPicos: number,
  subnetCapPicos: number,
  poolCapPicos: number,
): Promise<AccountAllowanceStatus> {
  const row = await db
    .prepare(
      `SELECT
         a.credit_balance_picos,
         a.free_eligible,
         a.suspended,
         COALESCE((SELECT used_picos FROM daily_account_free_usage WHERE day = ?1 AND account_id = a.id), 0) AS account_used,
         COALESCE((SELECT used_picos FROM daily_subnet_free_usage WHERE day = ?1 AND subnet_hash = ?2), 0) AS subnet_used,
         COALESCE((SELECT used_picos FROM daily_pool_free_usage WHERE day = ?1), 0) AS pool_used
       FROM accounts a
       WHERE a.id = ?3`,
    )
    .bind(day, subnetHash, account.id)
    .first<{
      credit_balance_picos: number;
      free_eligible: number;
      suspended: number;
      account_used: number;
      subnet_used: number;
      pool_used: number;
    }>();

  const balancePicos = row?.credit_balance_picos ?? account.creditBalancePicos;
  const eligible =
    (row?.free_eligible ?? account.freeEligible) === 1 &&
    (row?.suspended ?? account.suspended) === 0 &&
    balancePicos >= 0;
  const accountRemaining = Math.max(0, accountCapPicos - (row?.account_used ?? 0));
  const subnetRemaining = Math.max(0, subnetCapPicos - (row?.subnet_used ?? 0));
  const poolRemaining = Math.max(0, poolCapPicos - (row?.pool_used ?? 0));
  const poolOpen = (row?.pool_used ?? 0) < poolCapPicos;

  const freeRemainingPicos =
    eligible && poolOpen ? Math.min(accountRemaining, subnetRemaining, poolRemaining) : 0;

  return {
    day,
    remainingPicos: freeRemainingPicos,
    dailyCapPicos: accountCapPicos,
    remainingUsd: freeRemainingPicos / PICO_PER_USD,
    dailyCapUsd: accountCapPicos / PICO_PER_USD,
    poolOpen,
    credits: {
      balancePicos,
      balanceCredits: Math.floor(balancePicos / PICOS_PER_CREDIT),
      picosPerCredit: PICOS_PER_CREDIT,
    },
    freeAllowance: {
      remainingPicos: freeRemainingPicos,
      dailyCapPicos: accountCapPicos,
      remainingCredits: Number((freeRemainingPicos / PICOS_PER_CREDIT).toFixed(2)),
      dailyCapCredits: Number((accountCapPicos / PICOS_PER_CREDIT).toFixed(2)),
      poolOpen,
      eligible,
    },
  };
}

export async function sweepExpiredState(
  db: D1Database,
  freeCutoffDay: string,
  staleReservedDay: string,
  reservationCutoffDay: string,
  nowSec: number,
): Promise<void> {
  await db.batch([
    db.prepare("DELETE FROM sessions WHERE expires_at < ?1").bind(nowSec),
    db
      .prepare(
        `UPDATE reservations
         SET status = 'kept',
             settled_picos = reserved_picos,
             free_settled_picos = free_reserved_picos,
             paid_settled_picos = paid_reserved_picos,
             settled_at = ?2
         WHERE status = 'reserved' AND day < ?1`,
      )
      .bind(staleReservedDay, nowSec),
    db
      .prepare("DELETE FROM reservations WHERE status IN ('settled', 'kept') AND day < ?1")
      .bind(reservationCutoffDay),
    db.prepare("DELETE FROM daily_account_free_usage WHERE day < ?1").bind(freeCutoffDay),
    db.prepare("DELETE FROM daily_subnet_free_usage WHERE day < ?1").bind(freeCutoffDay),
    db.prepare("DELETE FROM daily_pool_free_usage WHERE day < ?1").bind(freeCutoffDay),
  ]);
}
