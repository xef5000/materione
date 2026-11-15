import { MAX_OUTPUT_TOKENS } from "./config";
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
  abortController?: AbortController;
  isClientAborted?: () => boolean;
  onFinalize?: () => void;
}

const textEncoder = new TextEncoder();

interface UsageTracker {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  sawInputUsage: boolean;
  sawFinalOutputUsage: boolean;
  streamedOutputUtf8Bytes: number;
  sawMessageStop: boolean;
  streamErrored: boolean;
  usageMalformed: boolean;
}

function isValidTokenCount(n: unknown): n is number {
  return typeof n === "number" && Number.isFinite(n) && Number.isInteger(n) && n >= 0 && n <= 1_000_000;
}

function applyUsageFields(
  tracker: UsageTracker,
  rawUsage: unknown,
  source: "message_start" | "message_delta",
): void {
  if (rawUsage === undefined) return;
  if (typeof rawUsage !== "object" || rawUsage === null || Array.isArray(rawUsage)) {
    tracker.usageMalformed = true;
    return;
  }
  const usage = rawUsage as Record<string, unknown>;

  const fields = [
    usage.input_tokens,
    usage.output_tokens,
    usage.cache_read_input_tokens,
    usage.cache_creation_input_tokens,
  ];
  for (const field of fields) {
    if (field !== undefined && !isValidTokenCount(field)) {
      tracker.usageMalformed = true;
      return;
    }
  }

  if (source === "message_start") {
    if (isValidTokenCount(usage.input_tokens)) {
      tracker.inputTokens = usage.input_tokens;
      tracker.sawInputUsage = true;
    }
    if (isValidTokenCount(usage.cache_read_input_tokens)) {
      tracker.cacheReadTokens = usage.cache_read_input_tokens;
    }
    if (isValidTokenCount(usage.cache_creation_input_tokens)) {
      tracker.cacheWriteTokens = usage.cache_creation_input_tokens;
    }
    if (isValidTokenCount(usage.output_tokens)) {
      tracker.outputTokens = Math.max(tracker.outputTokens, usage.output_tokens);
    }
    return;
  }

  if (isValidTokenCount(usage.output_tokens)) {
    tracker.outputTokens = usage.output_tokens;
    tracker.sawFinalOutputUsage = true;
  }
  if (isValidTokenCount(usage.input_tokens)) {
    tracker.inputTokens = usage.input_tokens;
    tracker.sawInputUsage = true;
  }
  if (isValidTokenCount(usage.cache_read_input_tokens)) {
    tracker.cacheReadTokens = usage.cache_read_input_tokens;
  }
  if (isValidTokenCount(usage.cache_creation_input_tokens)) {
    tracker.cacheWriteTokens = usage.cache_creation_input_tokens;
  }
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
      applyUsageFields(tracker, (msg as Record<string, unknown>).usage, "message_start");
    }
  } else if (effectiveType === "content_block_delta") {
    const delta = parsed.delta;
    if (typeof delta === "object" && delta !== null) {
      const d = delta as Record<string, unknown>;
      if (d.type === "text_delta" && typeof d.text === "string") {
        tracker.streamedOutputUtf8Bytes += textEncoder.encode(d.text).byteLength;
      } else if (d.type === "input_json_delta" && typeof d.partial_json === "string") {
        tracker.streamedOutputUtf8Bytes += textEncoder.encode(d.partial_json).byteLength;
      }
    }
  } else if (effectiveType === "message_delta") {
    applyUsageFields(tracker, parsed.usage, "message_delta");
  } else if (effectiveType === "message_stop") {
    tracker.sawMessageStop = true;
  } else if (effectiveType === "error") {
    tracker.streamErrored = true;
  }
}

export function createSettlementStream(options: SettlementStreamOptions): ReadableStream<Uint8Array> {
  const {
    upstreamBody,
    db,
    ctx,
    reservationId,
    reservedPicos,
    model,
    abortController,
    isClientAborted,
    onFinalize,
  } = options;
  const reader = upstreamBody.getReader();
  const decoder = new TextDecoder();

  const tracker: UsageTracker = {
    inputTokens: 0,
    outputTokens: 0,
    cacheReadTokens: 0,
    cacheWriteTokens: 0,
    sawInputUsage: false,
    sawFinalOutputUsage: false,
    streamedOutputUtf8Bytes: 0,
    sawMessageStop: false,
    streamErrored: false,
    usageMalformed: false,
  };

  let rawBuffer = "";
  let settled = false;
  let finalized = false;

  function finalizeOnce(): void {
    if (finalized) return;
    finalized = true;
    onFinalize?.();
  }

  function processNormalizedFrames(normalized: string): string {
    let remaining = normalized;
    let splitIdx = remaining.indexOf("\n\n");
    while (splitIdx !== -1) {
      const frame = remaining.slice(0, splitIdx);
      remaining = remaining.slice(splitIdx + 2);
      inspectSseFrame(frame, tracker);
      splitIdx = remaining.indexOf("\n\n");
    }
    return remaining;
  }

  async function settleOnEnd(
    reason: "eof" | "error" | "client_cancel",
  ): Promise<{ emitEvent: boolean; settledPicos: number; outputTokens: number } | null> {
    if (settled) return null;
    settled = true;

    if (tracker.sawInputUsage && tracker.sawFinalOutputUsage && !tracker.usageMalformed) {
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
      return {
        emitEvent: reason === "eof" && tracker.sawMessageStop && !tracker.streamErrored,
        settledPicos,
        outputTokens: tracker.outputTokens,
      };
    }

    if (tracker.sawInputUsage && !tracker.sawFinalOutputUsage && !tracker.usageMalformed) {
      const boundedOutputTokens =
        reason === "client_cancel"
          ? MAX_OUTPUT_TOKENS
          : Math.min(
              MAX_OUTPUT_TOKENS,
              Math.max(tracker.outputTokens, tracker.streamedOutputUtf8Bytes + 64),
            );
      const settledPicos = computeSettledPicos(model, {
        ...tracker,
        outputTokens: boundedOutputTokens,
      });
      await settleReservation(db, {
        id: reservationId,
        status: "settled",
        settledPicos,
        inputTokens: tracker.inputTokens,
        outputTokens: boundedOutputTokens,
        cacheReadTokens: tracker.cacheReadTokens,
        cacheWriteTokens: tracker.cacheWriteTokens,
        settledAt: nowSeconds(),
      });
      return {
        emitEvent: false,
        settledPicos,
        outputTokens: boundedOutputTokens,
      };
    }

    await settleReservation(db, {
      id: reservationId,
      status: "kept",
      settledPicos: reservedPicos,
      inputTokens: null,
      outputTokens: null,
      cacheReadTokens: null,
      cacheWriteTokens: null,
      settledAt: nowSeconds(),
    });
    return {
      emitEvent: false,
      settledPicos: reservedPicos,
      outputTokens: 0,
    };
  }

  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        const { done, value } = await reader.read();
        if (done) {
          rawBuffer += decoder.decode();
          if (rawBuffer.length > 0) {
            const normalized = rawBuffer.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
            const trailing = processNormalizedFrames(normalized);
            if (trailing.length > 0) {
              inspectSseFrame(trailing, tracker);
            }
            rawBuffer = "";
          }

          const outcome = await settleOnEnd(isClientAborted?.() ? "client_cancel" : "eof");
          if (outcome?.emitEvent) {
            const settlementEvent =
              `event: materione_settlement\n` +
              `data: ${JSON.stringify({
                settledPicos: outcome.settledPicos,
                usage: {
                  inputTokens: tracker.inputTokens,
                  outputTokens: outcome.outputTokens,
                  cachedInputTokens: tracker.cacheReadTokens,
                  cacheCreationInputTokens: tracker.cacheWriteTokens,
                },
              })}\n\n`;
            controller.enqueue(textEncoder.encode(settlementEvent));
          }

          finalizeOnce();
          controller.close();
          return;
        }

        if (value && value.byteLength > 0) {
          controller.enqueue(value);
          rawBuffer += decoder.decode(value, { stream: true });
          let heldCr = "";
          let processable = rawBuffer;
          if (processable.endsWith("\r")) {
            heldCr = "\r";
            processable = processable.slice(0, -1);
          }
          const normalized = processable.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
          const remaining = processNormalizedFrames(normalized);
          rawBuffer = remaining + heldCr;
        }
      } catch (err) {
        finalizeOnce();
        const reason = isClientAborted?.() ? "client_cancel" : "error";
        ctx.waitUntil(settleOnEnd(reason).catch(() => {}));
        controller.error(err);
      }
    },

    async cancel() {
      finalizeOnce();
      abortController?.abort();
      ctx.waitUntil(settleOnEnd("client_cancel").catch(() => {}));
      await reader.cancel().catch(() => {});
    },
  });
}
