import { MAX_INPUT_TOKENS, MAX_OUTPUT_TOKENS } from "./config";
import { isValidContinuation } from "./validate";

const encoder = new TextEncoder();
const MAX_FRAME_CHARS = 2 * 1024 * 1024;

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function tokenCount(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= 1_000_000;
}

function event(type: string, value: Record<string, unknown>): Uint8Array {
  return encoder.encode(`event: ${type}\ndata: ${JSON.stringify({ type, ...value })}\n\n`);
}

/** Adapts Responses SSE to the editor's existing, provider-neutral proxy stream. */
export function adaptResponsesStream(
  body: ReadableStream<Uint8Array>,
  upperBoundInputTokens = MAX_INPUT_TOKENS,
): ReadableStream<Uint8Array> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let finished = false;
  let refused = false;
  const tools = new Map<number, { id: string; name: string; arguments: string }>();
  const completedTools = new Map<number, { id: string; name: string; arguments: string }>();
  let streamedText = "";

  function usageEvents(response: Record<string, unknown>): Uint8Array[] | null {
    if (!record(response.usage)) return null;
    const usage = response.usage;
    if (!record(usage.input_tokens_details)) return null;
    const details = usage.input_tokens_details;
    const cached = details.cached_tokens;
    const written = details.cache_write_tokens;
    if (!tokenCount(usage.input_tokens) || !tokenCount(usage.output_tokens) ||
        !tokenCount(cached) || !tokenCount(written) || cached + written > usage.input_tokens) return null;
    if (details.cache_write_tokens_details !== undefined) {
      const breakdown = details.cache_write_tokens_details;
      if (!record(breakdown) || !tokenCount(breakdown.ttl_30m_tokens) ||
          !tokenCount(breakdown.ttl_12h_tokens) || breakdown.ttl_12h_tokens !== 0 ||
          breakdown.ttl_30m_tokens !== written) return null;
    } else if (written !== 0) {
      return null;
    }
    return [
      event("message_start", { message: { usage: {
        input_tokens: usage.input_tokens - cached - written,
        cache_read_input_tokens: cached,
        cache_creation_input_tokens: written,
      } } }),
      event("message_delta", { usage: { output_tokens: usage.output_tokens } }),
    ];
  }

  function process(frame: string): Uint8Array[] {
    const lines = frame.split("\n");
    const data = lines.filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trimStart()).join("\n");
    if (!data || data === "[DONE]") return [];
    let parsed: unknown;
    try {
      parsed = JSON.parse(data);
    } catch {
      throw new Error("Invalid model stream frame.");
    }
    if (!record(parsed) || typeof parsed.type !== "string") throw new Error("Invalid model stream event.");
    const type = parsed.type;
    if (finished) return [];
    if (type === "response.output_text.delta" && typeof parsed.delta === "string") {
      streamedText += parsed.delta;
      if (streamedText.length > 240_000) throw new Error("Model text exceeds limit.");
      return [event("content_block_delta", { index: 0, delta: { type: "text_delta", text: parsed.delta } })];
    }
    if (type === "response.refusal.delta") {
      refused = true;
      return [];
    }
    if (type === "response.output_item.added" && record(parsed.item) && parsed.item.type === "function_call") {
      if (!Number.isSafeInteger(parsed.output_index) || Number(parsed.output_index) < 0 ||
          typeof parsed.item.call_id !== "string" || parsed.item.call_id.length === 0 ||
          parsed.item.name !== "run_program" || tools.has(Number(parsed.output_index)) ||
          completedTools.has(Number(parsed.output_index))) throw new Error("Invalid model tool call.");
      const index = Number(parsed.output_index);
      tools.set(index, { id: parsed.item.call_id, name: parsed.item.name, arguments: "" });
      return [event("content_block_start", { index, content_block: {
        type: "tool_use", id: parsed.item.call_id, name: parsed.item.name, input: {},
      } })];
    }
    if (type === "response.function_call_arguments.delta") {
      const index = Number(parsed.output_index);
      const tool = tools.get(index);
      if (!tool || typeof parsed.delta !== "string") throw new Error("Invalid model tool arguments.");
      tool.arguments += parsed.delta;
      if (tool.arguments.length > 240_000) throw new Error("Model tool arguments exceed limit.");
      return [event("content_block_delta", { index, delta: { type: "input_json_delta", partial_json: parsed.delta } })];
    }
    if (type === "response.output_item.done" && record(parsed.item) && parsed.item.type === "function_call") {
      const index = Number(parsed.output_index);
      const tool = tools.get(index);
      if (!tool || typeof parsed.item.arguments !== "string" || tool.id !== parsed.item.call_id ||
          tool.name !== parsed.item.name) throw new Error("Invalid completed model tool call.");
      let input: unknown;
      try {
        input = JSON.parse(parsed.item.arguments);
      } catch {
        throw new Error("Invalid completed model tool arguments.");
      }
      if (!record(input) || Object.keys(input).length !== 1 || typeof input.text !== "string" ||
          input.text.length === 0 || input.text.length > 60_000) {
        throw new Error("Invalid completed model tool arguments.");
      }
      const frames: Uint8Array[] = [];
      if (tool.arguments === "") {
        frames.push(event("content_block_delta", { index, delta: {
          type: "input_json_delta", partial_json: parsed.item.arguments,
        } }));
      } else if (tool.arguments !== parsed.item.arguments) {
        throw new Error("Inconsistent model tool arguments.");
      }
      tools.delete(index);
      completedTools.set(index, { ...tool, arguments: parsed.item.arguments });
      frames.push(event("content_block_stop", { index }));
      return frames;
    }
    if (type === "response.completed" || type === "response.incomplete" || type === "response.failed") {
      if (!record(parsed.response)) throw new Error("Invalid completed model response.");
      finished = true;
      const frames = usageEvents(parsed.response) ?? [];
      const usage = record(parsed.response.usage) ? parsed.response.usage : {};
      const exceededBounds = tokenCount(usage.input_tokens) && tokenCount(usage.output_tokens) &&
        (usage.input_tokens > upperBoundInputTokens || usage.output_tokens > MAX_OUTPUT_TOKENS);
      if (frames.length === 0 || refused || type !== "response.completed" ||
          exceededBounds || tools.size !== 0 || !isValidContinuation(parsed.response.output)) {
        frames.push(event("error", { error: {
          message: refused ? "The model refused this request." : exceededBounds
            ? "The model reported usage beyond the reserved request limits."
            : frames.length === 0
            ? "The model response did not complete; the reservation was retained because final usage was unavailable."
            : "The model response did not complete.",
        } }));
        return frames;
      }
      let finalText = "";
      let finalCalls = 0;
      let inconsistent = false;
      for (const [index, item] of parsed.response.output.entries()) {
        if (item.type === "message") {
          if (!Array.isArray(item.content)) {
            inconsistent = true;
            continue;
          }
          for (const part of item.content) {
            if (!record(part) || part.type !== "output_text" || typeof part.text !== "string") {
              inconsistent = true;
              continue;
            }
            finalText += part.text;
          }
        } else if (item.type === "function_call") {
          const call = completedTools.get(index);
          if (!call || call.id !== item.call_id || call.name !== item.name || call.arguments !== item.arguments) {
            inconsistent = true;
          }
          finalCalls++;
        }
      }
      if (inconsistent || finalText !== streamedText || finalCalls !== completedTools.size) {
        frames.push(event("error", { error: { message: "The model returned inconsistent output." } }));
        return frames;
      }
      frames.push(event("materione_continuation", { output: parsed.response.output }));
      frames.push(event("message_stop", {}));
      return frames;
    }
    if (type === "error") {
      finished = true;
      return [event("error", { error: {
        message: "The model stream failed; the reservation was retained because final usage was unavailable.",
      } })];
    }
    return [];
  }

  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            buffer += decoder.decode();
            if (buffer.trim()) {
              for (const chunk of process(buffer.replace(/\r\n/g, "\n").replace(/\r/g, "\n"))) controller.enqueue(chunk);
            }
            if (!finished) controller.enqueue(event("error", { error: {
              message: "The model stream ended unexpectedly; the reservation was retained because final usage was unavailable.",
            } }));
            controller.close();
            return;
          }
          buffer += decoder.decode(value, { stream: true });
          if (buffer.length > MAX_FRAME_CHARS) throw new Error("Model stream frame exceeds limit.");
          let heldCr = "";
          if (buffer.endsWith("\r")) {
            heldCr = "\r";
            buffer = buffer.slice(0, -1);
          }
          buffer = buffer.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
          let separator = buffer.indexOf("\n\n");
          let emitted = false;
          while (separator !== -1) {
            const frame = buffer.slice(0, separator);
            buffer = buffer.slice(separator + 2);
            for (const chunk of process(frame)) {
              controller.enqueue(chunk);
              emitted = true;
            }
            separator = buffer.indexOf("\n\n");
          }
          buffer += heldCr;
          if (emitted) return;
        }
      } catch (error) {
        await reader.cancel().catch(() => {});
        controller.error(error);
      }
    },
    async cancel(reason) {
      await reader.cancel(reason);
    },
  });
}
