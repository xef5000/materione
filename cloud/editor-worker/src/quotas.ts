import { Config } from "./config";
import { Env, todayUtc } from "./http";

export interface Usage {
  day: string;
  new_row_writes: number;
  store_posts: number;
  store_bytes: number;
}

/** Today's usage row as a statement, so callers can fold the read into a
 * {@code DB.batch} with their own reads (one D1 round trip). */
export function usageStatement(env: Env): D1PreparedStatement {
  return env.DB.prepare("SELECT * FROM usage WHERE day = ?").bind(todayUtc());
}

export function usageOrZero(row: Usage | null | undefined): Usage {
  return row ?? { day: todayUtc(), new_row_writes: 0, store_posts: 0, store_bytes: 0 };
}

export async function readUsage(env: Env): Promise<Usage> {
  return usageOrZero(await usageStatement(env).first<Usage>());
}

/** Applies a usage delta as one upsert; the upsert itself is one of the
 * new-row writes when the row is created, and one write when updated — both
 * are counted so the cap tracks D1's own accounting conservatively. */
export async function bumpUsage(
  env: Env,
  delta: { newRowWrites?: number; storePosts?: number; storeBytes?: number },
): Promise<void> {
  await bumpUsageStatement(env, delta).run();
}

/** {@link bumpUsage} as a statement, to ride in the caller's write batch. */
export function bumpUsageStatement(
  env: Env,
  delta: { newRowWrites?: number; storePosts?: number; storeBytes?: number },
): D1PreparedStatement {
  return env.DB.prepare(
    `INSERT INTO usage (day, new_row_writes, store_posts, store_bytes)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(day) DO UPDATE SET
       new_row_writes = new_row_writes + excluded.new_row_writes,
       store_posts = store_posts + excluded.store_posts,
       store_bytes = MAX(0, store_bytes + excluded.store_bytes)`,
  )
    .bind(
      todayUtc(),
      delta.newRowWrites ?? 0,
      delta.storePosts ?? 0,
      delta.storeBytes ?? 0,
    );
}

/** Rejects an operation that would push today's new-row writes past the
 * self-cap (kept below D1's hard 100k/day ceiling so sweep deletes fit). */
export async function withinWriteCap(
  env: Env,
  config: Config,
  needed: number,
): Promise<boolean> {
  const usage = await readUsage(env);
  return usage.new_row_writes + needed <= config.dailyNewRowWriteCap;
}
