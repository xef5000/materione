import { PICO_PER_USD } from "./config";

export interface ReserveParams {
  id: string;
  day: string;
  callerHash: string;
  model: string;
  reservedPicos: number;
  callerCapPicos: number;
  poolCapPicos: number;
  createdAt: number;
}

export type ReserveResult =
  | { ok: true }
  | { ok: false; reason: "allowance_exhausted" | "pool_exhausted" };

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

export interface AllowanceStatus {
  day: string;
  remainingPicos: number;
  dailyCapPicos: number;
  remainingUsd: number;
  dailyCapUsd: number;
  poolOpen: boolean;
}

export async function reserveAllowance(db: D1Database, params: ReserveParams): Promise<ReserveResult> {
  try {
    await db
      .prepare(
        `INSERT INTO reservations (id, day, caller_hash, model, reserved_picos, caller_cap_picos, pool_cap_picos, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)`,
      )
      .bind(
        params.id,
        params.day,
        params.callerHash,
        params.model,
        params.reservedPicos,
        params.callerCapPicos,
        params.poolCapPicos,
        params.createdAt,
      )
      .run();
    return { ok: true };
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    if (message.includes("ALLOWANCE_EXHAUSTED")) {
      return { ok: false, reason: "allowance_exhausted" };
    }
    if (message.includes("POOL_EXHAUSTED")) {
      return { ok: false, reason: "pool_exhausted" };
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

export async function getAllowanceStatus(
  db: D1Database,
  day: string,
  callerHash: string,
  callerCapPicos: number,
  poolCapPicos: number,
): Promise<AllowanceStatus> {
  const callerRow = await db
    .prepare("SELECT used_picos FROM daily_caller_usage WHERE day = ?1 AND caller_hash = ?2")
    .bind(day, callerHash)
    .first<{ used_picos: number }>();

  const poolRow = await db
    .prepare("SELECT used_picos FROM daily_pool_usage WHERE day = ?1")
    .bind(day)
    .first<{ used_picos: number }>();

  const callerUsed = callerRow?.used_picos ?? 0;
  const poolUsed = poolRow?.used_picos ?? 0;
  const remainingPicos = Math.max(0, callerCapPicos - callerUsed);

  return {
    day,
    remainingPicos,
    dailyCapPicos: callerCapPicos,
    remainingUsd: remainingPicos / PICO_PER_USD,
    dailyCapUsd: callerCapPicos / PICO_PER_USD,
    poolOpen: poolUsed < poolCapPicos,
  };
}

export async function sweepOldDays(db: D1Database, cutoffDay: string): Promise<void> {
  await db.batch([
    db.prepare("DELETE FROM reservations WHERE day < ?1").bind(cutoffDay),
    db.prepare("DELETE FROM daily_caller_usage WHERE day < ?1").bind(cutoffDay),
    db.prepare("DELETE FROM daily_pool_usage WHERE day < ?1").bind(cutoffDay),
  ]);
}
