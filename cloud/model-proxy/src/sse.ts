import { nowSeconds } from "./http";
import { settleReservation } from "./ledger";
import { computeSettledPicos, type ModelSpec } from "./models";

export interface SettlementStreamOptions {
  upstreamBody: ReadableStream<Uint8Array>;
  db: D1Database;
  ctx: ExecutionContext;
  reservationId: string;
  reservedPicos: number;
  model: ModelSpec;
}

const textDecoder = new TextDecoder();
const textEncoder = new TextEncoder();

interface UsageTracker {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  hasReportedUsage: boolean;
  sawMessageStop: boolean;
  streamErrored: boolean;
}

function applyUsageFields(tracker: UsageTracker, rawUsage: unknown): void {
  if (typeof rawUsage !== "object" || rawUsage === null) return;
  const usage = rawUsage as Record<string, unknown>;
  if (typeof usage.input_tokens === "number") tracker.inputTokens = usage.input_tokens;
  if (typeof usage.output_tokens === "number") tracker.outputTokens = usage.output_tokens;
  if (typeof usage.cache_read_input_tokens === "number") {
    tracker.cacheReadTokens = usage.cache_read_input_tokens;
  }
  if (typeof usage.cache_creation_input_tokens === "number") {
    tracker.cacheWriteTokens = usage.cache_creation_input_tokens;
  }
  tracker.hasReportedUsage = true;
}

function inspectSseFrame(frame: string, tracker: UsageTracker): void {
  if (!frame.trim()) return;
  let eventName = "";
  const dataLines: string[] = [];

  for (const line of frame.split("\n")) {
    if (line.startsWith(":")) continue;
    if (line.startsWith("event:")) {
      eventName = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trimStart());
    }
  }

  if (dataLines.length === 0) return;
  const rawData = dataLines.join("\n");
  if (rawData === "[DONE]") return;

  let parsed: Record<string, unknown>;
  try {
    const obj: unknown = JSON.parse(rawData);
    if (typeof obj !== "object" || obj === null) return;
    parsed = obj as Record<string, unknown>;
  } catch {
    return;
  }

  const effectiveType = eventName || (typeof parsed.type === "string" ? parsed.type : "");
  if (effectiveType === "message_start") {
    const msg = parsed.message;
    if (typeof msg === "object" && msg !== null) {
      applyUsageFields(tracker, (msg as Record<string, unknown>).usage);
    }
  } else if (effectiveType === "message_delta") {
    applyUsageFields(tracker, parsed.usage);
  } else if (effectiveType === "message_stop") {
    tracker.sawMessageStop = true;
  } else if (effectiveType === "error") {
    tracker.streamErrored = true;
  }
}

export function createSettlementStream(options: SettlementStreamOptions): ReadableStream<Uint8Array> {
  const { upstreamBody, db, ctx, reservationId, reservedPicos, model } = options;
  const reader = upstreamBody.getReader();

  const tracker: UsageTracker = {
    inputTokens: 0,
    outputTokens: 0,
    cacheReadTokens: 0,
    cacheWriteTokens: 0,
    hasReportedUsage: false,
    sawMessageStop: false,
    streamErrored: false,
  };

  let buffer = "";
  let settled = false;

  async function keepOnce(): Promise<void> {
    if (settled) return;
    settled = true;
    await settleReservation(db, {
      id: reservationId,
      status: "kept",
      settledPicos: reservedPicos,
      inputTokens: tracker.hasReportedUsage ? tracker.inputTokens : null,
      outputTokens: tracker.hasReportedUsage ? tracker.outputTokens : null,
      cacheReadTokens: tracker.hasReportedUsage ? tracker.cacheReadTokens : null,
      cacheWriteTokens: tracker.hasReportedUsage ? tracker.cacheWriteTokens : null,
      settledAt: nowSeconds(),
    });
  }

  async function settleCleanOnce(): Promise<number> {
    settled = true;
    const settledPicos = computeSettledPicos(model, tracker);
    await settleReservation(db, {
      id: reservationId,
      status: "settled",
      settledPicos,
      inputTokens: tracker.inputTokens,
      outputTokens: tracker.outputTokens,
      cacheReadTokens: tracker.cacheReadTokens,
      cacheWriteTokens: tracker.cacheWriteTokens,
      settledAt: nowSeconds(),
    });
    return settledPicos;
  }

  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        const { done, value } = await reader.read();
        if (done) {
          buffer += textDecoder.decode();
          if (buffer.length > 0) {
            inspectSseFrame(buffer.replace(/\r\n/g, "\n"), tracker);
            buffer = "";
          }

          if (tracker.sawMessageStop && tracker.hasReportedUsage && !tracker.streamErrored) {
            const settledPicos = await settleCleanOnce();
            const settlementEvent =
              `event: materione_settlement\n` +
              `data: ${JSON.stringify({
                settledPicos,
                usage: {
                  inputTokens: tracker.inputTokens,
                  outputTokens: tracker.outputTokens,
                  cachedInputTokens: tracker.cacheReadTokens,
                  cacheCreationInputTokens: tracker.cacheWriteTokens,
                },
              })}\n\n`;
            controller.enqueue(textEncoder.encode(settlementEvent));
          } else {
            await keepOnce();
          }

          controller.close();
          return;
        }

        if (value && value.byteLength > 0) {
          controller.enqueue(value);
          buffer += textDecoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
          let splitIdx = buffer.indexOf("\n\n");
          while (splitIdx !== -1) {
            const frame = buffer.slice(0, splitIdx);
            buffer = buffer.slice(splitIdx + 2);
            inspectSseFrame(frame, tracker);
            splitIdx = buffer.indexOf("\n\n");
          }
        }
      } catch (err) {
        ctx.waitUntil(keepOnce().catch(() => {}));
        controller.error(err);
      }
    },

    async cancel() {
      ctx.waitUntil(keepOnce().catch(() => {}));
      await reader.cancel().catch(() => {});
    },
  });
}
