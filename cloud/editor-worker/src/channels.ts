import { configFrom, type Config } from "./config";
import { randomCode, randomToken, sha256Hex } from "./codes";
import {
  bearerToken,
  Env,
  errorResponse,
  jsonResponse,
  nowSeconds,
  rateLimited,
  secondsUntilUtcMidnight,
} from "./http";
import { bumpUsageStatement, bumpUsage, usageOrZero, usageStatement, withinWriteCap, type Usage } from "./quotas";

interface ChannelRow {
  id: string;
  plugin_token_hash: string;
  editor_token_hash: string;
  created_at: number;
  last_plugin_seen: number;
  last_editor_seen: number;
  closed: number;
  frames_bytes: number;
  expires_at: number;
  last_frame_at: number;
}

type Role = "plugin" | "editor";

interface FrameRow {
  id: number;
  body: string;
  size: number;
}

function quotaRetry(): Response {
  return jsonResponse(
    {
      error: "quota_exceeded",
      detail:
        "the shared relay instance is at its daily quota; retry after UTC midnight or self-host (docs/architecture/web.md §8)",
    },
    429,
    { "retry-after": String(secondsUntilUtcMidnight()) },
  );
}

async function authenticate(
  env: Env,
  channelId: string,
  request: Request,
): Promise<{ role: Role; channel: ChannelRow } | Response> {
  return authenticateWith(env, channelId, request, false);
}

/**
 * Resolves the caller's role from its bearer token. With {@code withUsage},
 * today's usage row is read in the same D1 round trip (the post path needs
 * both before it may write).
 */
async function authenticateWith(
  env: Env,
  channelId: string,
  request: Request,
  withUsage: boolean,
): Promise<{ role: Role; channel: ChannelRow; usage: Usage } | Response> {
  const token = bearerToken(request);
  if (!token) return errorResponse(401, "missing_token");
  const hashing = sha256Hex(token);
  const channelStmt = env.DB.prepare("SELECT * FROM channels WHERE id = ?").bind(channelId);
  let channel: ChannelRow | null;
  let usage: Usage;
  if (withUsage) {
    const [channels, usages] = await env.DB.batch<unknown>([channelStmt, usageStatement(env)]);
    channel = (channels!.results[0] as ChannelRow | undefined) ?? null;
    usage = usageOrZero(usages!.results[0] as Usage | undefined);
  } else {
    channel = await channelStmt.first<ChannelRow>();
    usage = usageOrZero(null);
  }
  if (!channel) return errorResponse(404, "no_such_channel");
  const hash = await hashing;
  if (hash === channel.plugin_token_hash) return { role: "plugin", channel, usage };
  if (hash === channel.editor_token_hash) return { role: "editor", channel, usage };
  return errorResponse(403, "invalid_token");
}

/** POST /v1/channels — plugin-only create. */
export async function createChannel(env: Env, request: Request): Promise<Response> {
  if (await rateLimited(env, request, "channels-create")) {
    return errorResponse(429, "rate_limited");
  }
  const config = configFrom(env as unknown as Record<string, unknown>);
  const now = nowSeconds();

  const live = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM channels WHERE closed = 0 AND expires_at > ?",
  )
    .bind(now)
    .first<{ n: number }>();
  if ((live?.n ?? 0) >= config.maxConcurrentChannels) {
    return errorResponse(429, "too_many_channels", "try again when another session ends");
  }
  if (!(await withinWriteCap(env, config, 3))) return quotaRetry();

  const id = randomCode(16);
  const pluginToken = randomToken(32);
  const editorToken = randomToken(32);
  const expires = now + config.channelTtlSeconds;
  await env.DB.prepare(
    `INSERT INTO channels (id, plugin_token_hash, editor_token_hash, created_at,
       last_plugin_seen, last_editor_seen, closed, frames_bytes, expires_at)
     VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?)`,
  )
    .bind(id, await sha256Hex(pluginToken), await sha256Hex(editorToken), now, now, now, expires)
    .run();
  await bumpUsage(env, { newRowWrites: 2 }); // channel row + usage upsert

  // The editor token rides only inside the plugin's E2E-encrypted session
  // blob (web.md §3.2); the relay persists nothing but its hash.
  return jsonResponse(
    { channelId: id, pluginToken, editorToken, expires, ttlSeconds: config.channelTtlSeconds },
    201,
  );
}

/** POST /v1/channels/{id}/frames — post sealed frames; the caller's token
 * selects the direction (plugin→editor or editor→plugin). */
export async function postFrames(
  env: Env,
  request: Request,
  channelId: string,
): Promise<Response> {
  if (await rateLimited(env, request, "frames-post")) {
    return errorResponse(429, "rate_limited");
  }
  const config = configFrom(env as unknown as Record<string, unknown>);
  // Round trip 1: channel + today's usage.
  const auth = await authenticateWith(env, channelId, request, true);
  if (auth instanceof Response) return auth;
  if (auth.channel.closed) return errorResponse(410, "channel_closed");
  if (auth.channel.expires_at <= nowSeconds()) return errorResponse(410, "channel_expired");

  let payload: unknown;
  try {
    payload = await request.json();
  } catch {
    return errorResponse(400, "invalid_json");
  }
  const frames = (payload as { frames?: unknown }).frames;
  if (!Array.isArray(frames) || frames.length === 0 || frames.length > config.maxBatchFrames) {
    return errorResponse(400, "invalid_batch", `1..${config.maxBatchFrames} frames required`);
  }

  const bodies: string[] = [];
  let total = 0;
  for (const frame of frames) {
    // Opaque channel messages: the mailbox must not know the wire schema —
    // batches carry handshake messages (hello/hello-reply) as well as sealed
    // frames. Only object-ness and size are enforced here; authenticity and
    // ordering are end-to-end (seq numbers) between plugin and browser.
    if (typeof frame !== "object" || frame === null || Array.isArray(frame)) {
      return errorResponse(400, "invalid_message", "each message must be a JSON object");
    }
    const body = JSON.stringify(frame);
    if (body.length > config.maxFrameBytes) {
      return errorResponse(413, "frame_too_large", `messages are capped at ${config.maxFrameBytes} bytes`);
    }
    bodies.push(body);
    total += body.length;
  }

  // Channel-row update + n inserts + usage upsert.
  const writes = bodies.length + 2;
  if (auth.usage.new_row_writes + writes > config.dailyNewRowWriteCap) return quotaRetry();

  const direction = auth.role === "plugin" ? "p2e" : "e2p";
  const now = nowSeconds();
  const seenColumn = auth.role === "plugin" ? "last_plugin_seen" : "last_editor_seen";
  const stmts: D1PreparedStatement[] = [
    env.DB.prepare(
      `UPDATE channels SET ${seenColumn} = ?, last_frame_at = ?, frames_bytes = frames_bytes + ? WHERE id = ?`,
    ).bind(now, now, total, channelId),
  ];
  for (const body of bodies) {
    stmts.push(
      env.DB.prepare(
        "INSERT INTO frames (channel_id, direction, body, size, created_at) VALUES (?, ?, ?, ?, ?)",
      ).bind(channelId, direction, body, body.length, now),
    );
  }
  stmts.push(bumpUsageStatement(env, { newRowWrites: writes }));
  // Round trip 2: every write, atomically.
  await env.DB.batch(stmts);

  // The byte total is known without a re-read; prune only when over a cap.
  if (overCap(config, auth.channel.frames_bytes + total)) {
    await pruneOverCap(env, config, channelId);
  }
  return jsonResponse({ accepted: bodies.length }, 202);
}

/** GET /v1/channels/{id}/frames?after=&wait= — long-poll the frames
 * addressed to the caller. Non-destructive, ordered, cursor-paged. */
export async function pollFrames(
  env: Env,
  request: Request,
  channelId: string,
  url: URL,
): Promise<Response> {
  const config = configFrom(env as unknown as Record<string, unknown>);
  const token = bearerToken(request);
  if (!token) return errorResponse(401, "missing_token");
  const after = Number(url.searchParams.get("after") ?? "0");
  if (!Number.isInteger(after) || after < 0) return errorResponse(400, "invalid_cursor");
  const waitParam = Number(url.searchParams.get("wait") ?? "0");
  const waitSeconds = Math.min(
    Math.max(Number.isFinite(waitParam) ? waitParam : 0, 0),
    config.longPollMaxWaitSeconds,
  );
  const hash = await sha256Hex(token);

  const deadline = Date.now() + waitSeconds * 1000;
  // One round trip per tick: the channel row (auth, closed, expiry) and the
  // caller's frames. The reader's direction is derived in SQL from its token
  // hash, so the first tick needs no separate authentication read; a token
  // matching neither capability selects no frames and is rejected below.
  const tick = [
    env.DB.prepare("SELECT * FROM channels WHERE id = ?").bind(channelId),
    env.DB.prepare(
      `SELECT id, body, size FROM frames
       WHERE channel_id = ?1
         AND direction = (SELECT CASE WHEN plugin_token_hash = ?2 THEN 'e2p'
                                      WHEN editor_token_hash = ?2 THEN 'p2e' END
                          FROM channels WHERE id = ?1)
         AND id > ?3
       ORDER BY id ASC LIMIT ?4`,
    ).bind(channelId, hash, after, config.maxPollFrames + 1),
  ];
  for (;;) {
    const [channels, frames] = await env.DB.batch<unknown>(tick);
    const channel = (channels!.results[0] as ChannelRow | undefined) ?? null;
    const rows = (frames!.results as FrameRow[]) ?? [];
    if (!channel) return errorResponse(404, "no_such_channel");
    const role: Role | null =
      hash === channel.plugin_token_hash ? "plugin" : hash === channel.editor_token_hash ? "editor" : null;
    if (!role) return errorResponse(403, "invalid_token");
    if (channel.closed || channel.expires_at <= nowSeconds()) {
      return jsonResponse({ closed: true, frames: [] });
    }

    const eligible: FrameRow[] = [];
    let bytes = 0;
    for (const row of rows) {
      if (eligible.length >= config.maxPollFrames || bytes + row.size > config.maxPollBytes) break;
      eligible.push(row);
      bytes += row.size;
    }
    if (eligible.length > 0 || Date.now() >= deadline) {
      const direction = role === "plugin" ? "e2p" : "p2e";
      const oldest = await finishPoll(env, config, channel, role, direction);
      return jsonResponse({
        closed: false,
        frames: eligible.map((row) => ({ id: row.id, frame: JSON.parse(row.body) })),
        oldest,
        more: rows.length > eligible.length,
      });
    }
    const active = nowSeconds() - channel.last_frame_at <= config.longPollActiveWindowSeconds;
    await scheduler.wait(active ? config.longPollActiveIntervalMs : config.longPollIntervalMs);
  }
}

/** POST /v1/channels/{id}/close — either capability. */
export async function closeChannel(
  env: Env,
  request: Request,
  channelId: string,
): Promise<Response> {
  const auth = await authenticate(env, channelId, request);
  if (auth instanceof Response) return auth;
  await env.DB.prepare("UPDATE channels SET closed = 1 WHERE id = ?").bind(channelId).run();
  await bumpUsage(env, { newRowWrites: 1 });
  return jsonResponse({ closed: true });
}

/**
 * The poll's closing round trip: the retention horizon (oldest retained
 * frame, for the reader's fell-behind check) and — throttled, since polls are
 * frequent and presence must not cost a write each time (§3.3 write
 * economics) — the reader's last-seen stamp, in one batch.
 */
async function finishPoll(
  env: Env,
  config: { seenWriteThrottleMs: number },
  channel: ChannelRow,
  role: Role,
  direction: string,
): Promise<number | null> {
  const stmts: D1PreparedStatement[] = [
    env.DB.prepare("SELECT MIN(id) AS m FROM frames WHERE channel_id = ? AND direction = ?")
      .bind(channel.id, direction),
  ];
  const lastSeen = role === "plugin" ? channel.last_plugin_seen : channel.last_editor_seen;
  if (Date.now() - lastSeen * 1000 >= config.seenWriteThrottleMs) {
    const column = role === "plugin" ? "last_plugin_seen" : "last_editor_seen";
    stmts.push(
      env.DB.prepare(`UPDATE channels SET ${column} = ? WHERE id = ?`).bind(nowSeconds(), channel.id),
      bumpUsageStatement(env, { newRowWrites: 1 }),
    );
  }
  const [oldest] = await env.DB.batch<{ m: number | null }>(stmts);
  return oldest!.results[0]?.m ?? null;
}

function overCap(config: Config, framesBytes: number): boolean {
  return (
    framesBytes >= config.maxChannelFramesBytes ||
    framesBytes >= config.maxFramesPerChannel * 256
  );
}

/** Enforces the per-channel retention cap (frame count / bytes) by deleting
 * the oldest frames of the channel and recounting the bytes — one batch. */
async function pruneOverCap(env: Env, config: Config, channelId: string): Promise<void> {
  await env.DB.batch([
    env.DB.prepare(
      `DELETE FROM frames WHERE channel_id = ? AND id NOT IN (
         SELECT id FROM frames WHERE channel_id = ? ORDER BY id DESC LIMIT ?
       )`,
    ).bind(channelId, channelId, config.maxFramesPerChannel),
    env.DB.prepare(
      `UPDATE channels SET frames_bytes =
         (SELECT COALESCE(SUM(size), 0) FROM frames WHERE channel_id = ?)
       WHERE id = ?`,
    ).bind(channelId, channelId),
  ]);
}
