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
import { bumpUsage, withinWriteCap } from "./quotas";

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
  const token = bearerToken(request);
  if (!token) return errorResponse(401, "missing_token");
  const channel = await env.DB.prepare("SELECT * FROM channels WHERE id = ?")
    .bind(channelId)
    .first<ChannelRow>();
  if (!channel) return errorResponse(404, "no_such_channel");
  const hash = await sha256Hex(token);
  if (hash === channel.plugin_token_hash) return { role: "plugin", channel };
  if (hash === channel.editor_token_hash) return { role: "editor", channel };
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
  const auth = await authenticate(env, channelId, request);
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
    if (typeof frame !== "object" || frame === null) {
      return errorResponse(400, "invalid_frame", "each frame must be an object");
    }
    const record = frame as Record<string, unknown>;
    if (
      typeof record.seq !== "number" ||
      typeof record.nonce !== "string" ||
      typeof record.ct !== "string" ||
      typeof record.sig !== "string"
    ) {
      return errorResponse(400, "invalid_frame", "frame must carry {seq, nonce, ct, sig}");
    }
    const body = JSON.stringify(record);
    if (body.length > config.maxFrameBytes) {
      return errorResponse(413, "frame_too_large", `frames are capped at ${config.maxFrameBytes} bytes`);
    }
    bodies.push(body);
    total += body.length;
  }

  // Channel-row update + n inserts + usage upsert.
  if (!(await withinWriteCap(env, config, bodies.length + 2))) return quotaRetry();

  const direction = auth.role === "plugin" ? "p2e" : "e2p";
  const now = nowSeconds();
  const seenColumn = auth.role === "plugin" ? "last_plugin_seen" : "last_editor_seen";
  const stmts: D1PreparedStatement[] = [
    env.DB.prepare(
      `UPDATE channels SET ${seenColumn} = ?, frames_bytes = frames_bytes + ? WHERE id = ?`,
    ).bind(now, total, channelId),
  ];
  for (const body of bodies) {
    stmts.push(
      env.DB.prepare(
        "INSERT INTO frames (channel_id, direction, body, size, created_at) VALUES (?, ?, ?, ?, ?)",
      ).bind(channelId, direction, body, body.length, now),
    );
  }
  await env.DB.batch(stmts);
  await bumpUsage(env, { newRowWrites: bodies.length + 2 });

  await pruneOverCap(env, config, channelId);
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
  const auth = await authenticate(env, channelId, request);
  if (auth instanceof Response) return auth;

  const direction = auth.role === "plugin" ? "e2p" : "p2e";
  const after = Number(url.searchParams.get("after") ?? "0");
  if (!Number.isInteger(after) || after < 0) return errorResponse(400, "invalid_cursor");
  const waitParam = Number(url.searchParams.get("wait") ?? "0");
  const waitSeconds = Math.min(
    Math.max(Number.isFinite(waitParam) ? waitParam : 0, 0),
    config.longPollMaxWaitSeconds,
  );

  const readRole = auth.role;
  const deadline = Date.now() + waitSeconds * 1000;
  for (;;) {
    const channel = await env.DB.prepare("SELECT * FROM channels WHERE id = ?")
      .bind(channelId)
      .first<ChannelRow>();
    if (!channel) return errorResponse(404, "no_such_channel");
    if (channel.closed || channel.expires_at <= nowSeconds()) {
      return jsonResponse({ closed: true, frames: [] });
    }

    const rows = await env.DB.prepare(
      "SELECT id, body, size FROM frames WHERE channel_id = ? AND direction = ? AND id > ? ORDER BY id ASC LIMIT ?",
    )
      .bind(channelId, direction, after, config.maxPollFrames + 1)
      .all<FrameRow>();

    const eligible: FrameRow[] = [];
    let bytes = 0;
    for (const row of rows.results ?? []) {
      if (eligible.length >= config.maxPollFrames || bytes + row.size > config.maxPollBytes) break;
      eligible.push(row);
      bytes += row.size;
    }
    if (eligible.length > 0) {
      const oldest = await env.DB.prepare(
        "SELECT MIN(id) AS m FROM frames WHERE channel_id = ? AND direction = ?",
      )
        .bind(channelId, direction)
        .first<{ m: number | null }>();
      await touchSeen(env, config, channel, readRole);
      return jsonResponse({
        closed: false,
        frames: eligible.map((row) => ({ id: row.id, frame: JSON.parse(row.body) })),
        oldest: oldest?.m ?? null,
        more: (rows.results?.length ?? 0) > eligible.length,
      });
    }

    if (Date.now() >= deadline) {
      const oldest = await env.DB.prepare(
        "SELECT MIN(id) AS m FROM frames WHERE channel_id = ? AND direction = ?",
      )
        .bind(channelId, direction)
        .first<{ m: number | null }>();
      await touchSeen(env, config, channel, readRole);
      return jsonResponse({ closed: false, frames: [], oldest: oldest?.m ?? null, more: false });
    }
    await scheduler.wait(config.longPollIntervalMs);
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

/** Updates last-seen for the polling role, throttled: polls are frequent and
 * presence must not cost a write each time (§3.3 write economics). */
async function touchSeen(
  env: Env,
  config: { seenWriteThrottleMs: number },
  channel: ChannelRow,
  role: Role,
): Promise<void> {
  const lastSeen = role === "plugin" ? channel.last_plugin_seen : channel.last_editor_seen;
  if (Date.now() - lastSeen * 1000 < config.seenWriteThrottleMs) return;
  const column = role === "plugin" ? "last_plugin_seen" : "last_editor_seen";
  await env.DB.prepare(`UPDATE channels SET ${column} = ? WHERE id = ?`)
    .bind(nowSeconds(), channel.id)
    .run();
  await bumpUsage(env, { newRowWrites: 1 });
}

/** Lazily enforces the per-channel retention cap (frame count / bytes) by
 * deleting the oldest frames of the channel. */
async function pruneOverCap(
  env: Env,
  config: Config & { seenWriteThrottleMs: number },
  channelId: string,
): Promise<void> {
  const channel = await env.DB.prepare("SELECT frames_bytes FROM channels WHERE id = ?")
    .bind(channelId)
    .first<{ frames_bytes: number }>();
  if (!channel) return;
  if (
    channel.frames_bytes < config.maxChannelFramesBytes &&
    channel.frames_bytes < config.maxFramesPerChannel * 256
  ) {
    return;
  }
  await env.DB.prepare(
    `DELETE FROM frames WHERE channel_id = ? AND id NOT IN (
       SELECT id FROM frames WHERE channel_id = ? ORDER BY id DESC LIMIT ?
     )`,
  )
    .bind(channelId, channelId, config.maxFramesPerChannel)
    .run();
  const remaining = await env.DB.prepare(
    "SELECT COALESCE(SUM(size), 0) AS s FROM frames WHERE channel_id = ?",
  )
    .bind(channelId)
    .first<{ s: number }>();
  await env.DB.prepare("UPDATE channels SET frames_bytes = ? WHERE id = ?")
    .bind(remaining?.s ?? 0, channelId)
    .run();
}
