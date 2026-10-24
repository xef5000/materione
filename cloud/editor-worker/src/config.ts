/**
 * Runtime config with code-level defaults (web.md §3.2/§3.3). Wrangler `vars`
 * override any of these from the dashboard; the defaults ARE the shared
 * free instance's quota posture.
 */
export interface Config {
  maxConcurrentChannels: number;
  channelTtlSeconds: number;
  idleSweepSeconds: number;
  frameRetentionSeconds: number;
  maxFramesPerChannel: number;
  maxChannelFramesBytes: number;
  maxFrameBytes: number;
  maxBatchFrames: number;
  maxPollFrames: number;
  maxPollBytes: number;
  maxBlobBytes: number;
  maxStoreBytesTotal: number;
  dailyNewRowWriteCap: number;
  dailyStorePosts: number;
  storeTtlClasses: number[];
  longPollMaxWaitSeconds: number;
  longPollIntervalMs: number;
  seenWriteThrottleMs: number;
}

const DEFAULTS: Config = {
  maxConcurrentChannels: 20,
  channelTtlSeconds: 12 * 3600,
  idleSweepSeconds: 8 * 3600,
  frameRetentionSeconds: 6 * 3600,
  maxFramesPerChannel: 2000,
  maxChannelFramesBytes: 16 * 1024 * 1024,
  maxFrameBytes: 1024 * 1024,
  maxBatchFrames: 32,
  maxPollFrames: 16,
  maxPollBytes: 4 * 1024 * 1024,
  maxBlobBytes: 768 * 1024,
  maxStoreBytesTotal: 2 * 1024 * 1024 * 1024,
  dailyNewRowWriteCap: 40_000,
  dailyStorePosts: 20_000,
  storeTtlClasses: [3600, 86400, 604800],
  longPollMaxWaitSeconds: 25,
  longPollIntervalMs: 1500,
  seenWriteThrottleMs: 60_000,
};

function num(env: Record<string, unknown>, key: keyof Config, fallback: number): number {
  const raw = env[key];
  if (typeof raw === "string" && raw !== "") {
    const parsed = Number(raw);
    if (Number.isFinite(parsed) && parsed > 0) return parsed;
  }
  if (typeof raw === "number" && Number.isFinite(raw)) return raw;
  return fallback;
}

export function configFrom(env: Record<string, unknown>): Config {
  return {
    maxConcurrentChannels: num(env, "maxConcurrentChannels", DEFAULTS.maxConcurrentChannels),
    channelTtlSeconds: num(env, "channelTtlSeconds", DEFAULTS.channelTtlSeconds),
    idleSweepSeconds: num(env, "idleSweepSeconds", DEFAULTS.idleSweepSeconds),
    frameRetentionSeconds: num(env, "frameRetentionSeconds", DEFAULTS.frameRetentionSeconds),
    maxFramesPerChannel: num(env, "maxFramesPerChannel", DEFAULTS.maxFramesPerChannel),
    maxChannelFramesBytes: num(env, "maxChannelFramesBytes", DEFAULTS.maxChannelFramesBytes),
    maxFrameBytes: num(env, "maxFrameBytes", DEFAULTS.maxFrameBytes),
    maxBatchFrames: num(env, "maxBatchFrames", DEFAULTS.maxBatchFrames),
    maxPollFrames: num(env, "maxPollFrames", DEFAULTS.maxPollFrames),
    maxPollBytes: num(env, "maxPollBytes", DEFAULTS.maxPollBytes),
    maxBlobBytes: num(env, "maxBlobBytes", DEFAULTS.maxBlobBytes),
    maxStoreBytesTotal: num(env, "maxStoreBytesTotal", DEFAULTS.maxStoreBytesTotal),
    dailyNewRowWriteCap: num(env, "dailyNewRowWriteCap", DEFAULTS.dailyNewRowWriteCap),
    dailyStorePosts: num(env, "dailyStorePosts", DEFAULTS.dailyStorePosts),
    storeTtlClasses: DEFAULTS.storeTtlClasses,
    longPollMaxWaitSeconds: num(env, "longPollMaxWaitSeconds", DEFAULTS.longPollMaxWaitSeconds),
    longPollIntervalMs: num(env, "longPollIntervalMs", DEFAULTS.longPollIntervalMs),
    seenWriteThrottleMs: num(env, "seenWriteThrottleMs", DEFAULTS.seenWriteThrottleMs),
  };
}
