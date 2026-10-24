import { closeChannel, createChannel, pollFrames, postFrames } from "./channels";
import { Env, errorResponse } from "./http";
import { blobMeta, deleteBlob, getBlob, putBlob } from "./store";
import { configFrom } from "./config";
import { bumpUsage } from "./quotas";
import { nowSeconds } from "./http";

const CHANNEL_PATH = /^\/v1\/channels\/([A-Za-z0-9_-]+)(\/frames|\/close)?$/;
const STORE_PATH = /^\/v1\/store\/([A-Za-z0-9_-]+)(\/meta)?$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/v1/channels") {
      if (request.method !== "POST") return errorResponse(405, "method_not_allowed");
      return createChannel(env, request);
    }
    const channelMatch = CHANNEL_PATH.exec(path);
    if (channelMatch) {
      const channelId = channelMatch[1];
      const action = channelMatch[2] ?? "";
      if (!channelId) return errorResponse(404, "not_found");
      if (request.method === "POST" && action === "/frames") {
        return postFrames(env, request, channelId);
      }
      if (request.method === "POST" && action === "/close") {
        return closeChannel(env, request, channelId);
      }
      if (request.method === "GET" && action === "/frames") {
        return pollFrames(env, request, channelId, url);
      }
      return errorResponse(405, "method_not_allowed");
    }

    if (path === "/v1/store") {
      if (request.method !== "POST") return errorResponse(405, "method_not_allowed");
      return putBlob(env, request, url);
    }
    const storeMatch = STORE_PATH.exec(path);
    if (storeMatch) {
      const code = storeMatch[1];
      const action = storeMatch[2] ?? "";
      if (!code) return errorResponse(404, "not_found");
      if (request.method === "GET" && action === "/meta") return blobMeta(env, code);
      if (request.method === "GET") return getBlob(env, code);
      if (request.method === "DELETE") return deleteBlob(env, request, code);
      return errorResponse(405, "method_not_allowed");
    }

    if (path === "/healthz") {
      return new Response("ok", { headers: { "cache-control": "no-store" } });
    }
    return errorResponse(404, "not_found");
  },

  /** Hourly TTL sweep (web.md §3.4): expired blobs, dead/idle channels and
   * their frames, and frames older than the retention horizon of live
   * channels. Deletes count as D1 rows written, so the sweep totals ride the
   * same daily budget the write cap leaves headroom for. */
  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    const config = configFrom(env as unknown as Record<string, unknown>);
    const now = nowSeconds();
    let deletes = 0;

    const expiredBytes = await env.DB.prepare(
      "SELECT COALESCE(SUM(size), 0) AS s FROM blobs WHERE expires_at <= ?",
    )
      .bind(now)
      .first<{ s: number }>();
    const blobSweep = await env.DB.prepare("DELETE FROM blobs WHERE expires_at <= ?").bind(now).run();
    deletes += blobSweep.meta.changes ?? 0;

    const dead = await env.DB.prepare(
      `SELECT id FROM channels WHERE
         expires_at <= ? OR
         closed = 1 AND MAX(last_plugin_seen, last_editor_seen) < ? OR
         MAX(last_plugin_seen, last_editor_seen) < ?`,
    )
      .bind(now, now - 3600, now - config.idleSweepSeconds)
      .all<{ id: string }>();
    const deadIds = (dead.results ?? []).map((row) => row.id);
    if (deadIds.length > 0) {
      const framesGone = await env.DB.prepare(
        "DELETE FROM frames WHERE channel_id IN (SELECT id FROM channels WHERE expires_at <= ? OR closed = 1 OR MAX(last_plugin_seen, last_editor_seen) < ?)",
      )
        .bind(now, now - config.idleSweepSeconds)
        .run();
      deletes += framesGone.meta.changes ?? 0;
      const channelsGone = await env.DB.prepare(
        "DELETE FROM channels WHERE id IN (SELECT id FROM channels WHERE expires_at <= ? OR closed = 1 OR MAX(last_plugin_seen, last_editor_seen) < ?)",
      )
        .bind(now, now - config.idleSweepSeconds)
        .run();
      deletes += channelsGone.meta.changes ?? 0;
    }

    const staleFrames = await env.DB.prepare(
      "DELETE FROM frames WHERE created_at < ? AND channel_id IN (SELECT id FROM channels WHERE closed = 0)",
    )
      .bind(now - config.frameRetentionSeconds)
      .run();
    deletes += staleFrames.meta.changes ?? 0;

    if (deletes > 0 || (expiredBytes?.s ?? 0) > 0) {
      await bumpUsage(env, {
        newRowWrites: deletes + 1,
        storeBytes: -(expiredBytes?.s ?? 0),
      });
    }
  },
} satisfies ExportedHandler<Env>;
