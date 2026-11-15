import { MAX_BODY_BYTES, MAX_INPUT_TOKENS, MAX_MESSAGES, MAX_OUTPUT_TOKENS } from "./config";
import type { ModelSpec } from "./models";

export type AllowedContentBlock =
  | { type: "text"; text: string }
  | { type: "tool_use"; id: string; name: "run_program"; input: Record<string, unknown> }
  | { type: "tool_result"; tool_use_id: string; content: string; is_error?: boolean };

export interface NormalizedMessage {
  role: "user" | "assistant";
  content: string | AllowedContentBlock[];
}

export interface NormalizedTool {
  name: "run_program";
  description: string;
  input_schema: Record<string, unknown>;
}

export interface ValidatedChatRequest {
  system: string;
  tools: NormalizedTool[];
  messages: NormalizedMessage[];
  estimatedInputTokens: number;
}

export type ValidationError = {
  ok: false;
  status: 400 | 413;
  error: string;
  message: string;
};

export type ValidationResult = { ok: true; value: ValidatedChatRequest } | ValidationError;

const TOP_LEVEL_KEYS = new Set(["system", "messages", "tools"]);
const TOOL_KEYS = new Set(["name", "description", "input_schema", "inputSchema"]);
const MESSAGE_KEYS = new Set(["role", "content"]);
const TEXT_BLOCK_KEYS = new Set(["type", "text"]);
const TOOL_USE_KEYS = new Set(["type", "id", "name", "input"]);
const TOOL_RESULT_KEYS = new Set(["type", "tool_use_id", "content", "is_error"]);

const textEncoder = new TextEncoder();

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function fail(status: 400 | 413, error: string, message: string): ValidationError {
  return { ok: false, status, error, message };
}

async function readBoundedJsonBody(request: Request): Promise<{ ok: true; text: string } | ValidationError> {
  const contentType = request.headers.get("content-type")?.trim().toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) {
    return fail(400, "invalid_request", "Content-Type must be application/json.");
  }

  const contentLengthHeader = request.headers.get("content-length");
  if (contentLengthHeader !== null) {
    const contentLength = Number(contentLengthHeader);
    if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
      return fail(413, "too_large", "Request body exceeds 512 KiB.");
    }
  }

  if (!request.body) {
    return fail(400, "invalid_request", "Request body is required.");
  }

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value || value.byteLength === 0) continue;
      totalBytes += value.byteLength;
      if (totalBytes > MAX_BODY_BYTES) {
        await reader.cancel().catch(() => {});
        return fail(413, "too_large", "Request body exceeds 512 KiB.");
      }
      chunks.push(value);
    }
  } catch {
    return fail(400, "invalid_request", "Failed to read request body.");
  }

  const combined = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    combined.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    const text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(combined);
    return { ok: true, text };
  } catch {
    return fail(400, "invalid_request", "Request body must be valid UTF-8.");
  }
}

function validateTools(rawTools: unknown): { ok: true; tools: NormalizedTool[] } | ValidationError {
  if (!Array.isArray(rawTools)) {
    return fail(400, "invalid_request", "Field 'tools' must be an array.");
  }
  if (rawTools.length > 1) {
    return fail(400, "invalid_request", "At most 1 tool ('run_program') may be provided.");
  }

  const normalized: NormalizedTool[] = [];
  for (const item of rawTools) {
    if (!isPlainObject(item)) {
      return fail(400, "invalid_request", "Each tool must be a plain object.");
    }
    for (const key of Object.keys(item)) {
      if (!TOOL_KEYS.has(key)) {
        return fail(400, "invalid_request", `Disallowed tool field: ${key}`);
      }
    }
    if (item.name !== "run_program") {
      return fail(400, "invalid_request", "Only tool 'run_program' is permitted.");
    }
    if (typeof item.description !== "string" || item.description.length === 0) {
      return fail(400, "invalid_request", "Tool 'description' must be a non-empty string.");
    }
    const hasSnake = "input_schema" in item;
    const hasCamel = "inputSchema" in item;
    if (hasSnake && hasCamel) {
      return fail(400, "invalid_request", "Tool must specify either 'input_schema' or 'inputSchema', not both.");
    }
    const schema = hasSnake ? item.input_schema : item.inputSchema;
    if (!isPlainObject(schema) || schema.type !== "object") {
      return fail(400, "invalid_request", "Tool schema must be an object with type: 'object'.");
    }
    normalized.push({
      name: "run_program",
      description: item.description,
      input_schema: schema,
    });
  }

  return { ok: true, tools: normalized };
}

function validateContentBlock(
  block: unknown,
  role: "user" | "assistant",
): { ok: true; block: AllowedContentBlock } | ValidationError {
  if (!isPlainObject(block)) {
    return fail(400, "invalid_request", "Content block must be a plain object.");
  }
  const type = block.type;
  if (type === "text") {
    for (const key of Object.keys(block)) {
      if (!TEXT_BLOCK_KEYS.has(key)) {
        return fail(400, "invalid_request", `Disallowed field on text block: ${key}`);
      }
    }
    if (typeof block.text !== "string") {
      return fail(400, "invalid_request", "Text block requires a string 'text' field.");
    }
    return { ok: true, block: { type: "text", text: block.text } };
  }

  if (type === "tool_use") {
    if (role !== "assistant") {
      return fail(400, "invalid_request", "Block type 'tool_use' is only allowed on assistant messages.");
    }
    for (const key of Object.keys(block)) {
      if (!TOOL_USE_KEYS.has(key)) {
        return fail(400, "invalid_request", `Disallowed field on tool_use block: ${key}`);
      }
    }
    if (typeof block.id !== "string" || block.id.length === 0) {
      return fail(400, "invalid_request", "Block 'tool_use' requires a non-empty string 'id'.");
    }
    if (block.name !== "run_program") {
      return fail(400, "invalid_request", "Only tool 'run_program' is permitted in tool_use blocks.");
    }
    if (!isPlainObject(block.input)) {
      return fail(400, "invalid_request", "Block 'tool_use' requires an object 'input'.");
    }
    return {
      ok: true,
      block: {
        type: "tool_use",
        id: block.id,
        name: "run_program",
        input: block.input,
      },
    };
  }

  if (type === "tool_result") {
    if (role !== "user") {
      return fail(400, "invalid_request", "Block type 'tool_result' is only allowed on user messages.");
    }
    for (const key of Object.keys(block)) {
      if (!TOOL_RESULT_KEYS.has(key)) {
        return fail(400, "invalid_request", `Disallowed field on tool_result block: ${key}`);
      }
    }
    if (typeof block.tool_use_id !== "string" || block.tool_use_id.length === 0) {
      return fail(400, "invalid_request", "Block 'tool_result' requires a non-empty string 'tool_use_id'.");
    }
    if (typeof block.content !== "string") {
      return fail(400, "invalid_request", "Block 'tool_result' requires a string 'content'.");
    }
    if ("is_error" in block && typeof block.is_error !== "boolean") {
      return fail(400, "invalid_request", "Block 'tool_result' field 'is_error' must be a boolean.");
    }
    return {
      ok: true,
      block: {
        type: "tool_result",
        tool_use_id: block.tool_use_id,
        content: block.content,
        ...(typeof block.is_error === "boolean" ? { is_error: block.is_error } : {}),
      },
    };
  }

  return fail(400, "invalid_request", `Disallowed content block type: ${String(type)}`);
}

function validateMessages(rawMessages: unknown): { ok: true; messages: NormalizedMessage[] } | ValidationError {
  if (!Array.isArray(rawMessages) || rawMessages.length === 0) {
    return fail(400, "invalid_request", "Field 'messages' must be a non-empty array.");
  }
  if (rawMessages.length > MAX_MESSAGES) {
    return fail(400, "invalid_request", `Field 'messages' exceeds maximum of ${MAX_MESSAGES} messages.`);
  }

  const normalized: NormalizedMessage[] = [];
  for (const item of rawMessages) {
    if (!isPlainObject(item)) {
      return fail(400, "invalid_request", "Each message must be a plain object.");
    }
    for (const key of Object.keys(item)) {
      if (!MESSAGE_KEYS.has(key)) {
        return fail(400, "invalid_request", `Disallowed message field: ${key}`);
      }
    }
    if (item.role !== "user" && item.role !== "assistant") {
      return fail(400, "invalid_request", "Message 'role' must be 'user' or 'assistant'.");
    }
    const role = item.role;
    if (typeof item.content === "string") {
      if (item.content.length === 0) {
        return fail(400, "invalid_request", "Message 'content' string must not be empty.");
      }
      normalized.push({ role, content: item.content });
      continue;
    }
    if (Array.isArray(item.content) && item.content.length > 0) {
      const blocks: AllowedContentBlock[] = [];
      for (const rawBlock of item.content) {
        const res = validateContentBlock(rawBlock, role);
        if (!res.ok) return res;
        blocks.push(res.block);
      }
      normalized.push({ role, content: blocks });
      continue;
    }
    return fail(400, "invalid_request", "Message 'content' must be a non-empty string or non-empty block array.");
  }

  return { ok: true, messages: normalized };
}

export async function validateChatRequest(request: Request): Promise<ValidationResult> {
  const bodyResult = await readBoundedJsonBody(request);
  if (!bodyResult.ok) return bodyResult;

  let parsed: unknown;
  try {
    parsed = JSON.parse(bodyResult.text);
  } catch {
    return fail(400, "invalid_request", "Request body must be valid JSON.");
  }

  if (!isPlainObject(parsed)) {
    return fail(400, "invalid_request", "Request body must be a JSON object.");
  }

  for (const key of Object.keys(parsed)) {
    if (!TOP_LEVEL_KEYS.has(key)) {
      return fail(400, "invalid_request", `Disallowed top-level field: ${key}`);
    }
  }
  for (const requiredKey of TOP_LEVEL_KEYS) {
    if (!(requiredKey in parsed)) {
      return fail(400, "invalid_request", `Missing required field: ${requiredKey}`);
    }
  }

  if (typeof parsed.system !== "string" || parsed.system.length === 0) {
    return fail(400, "invalid_request", "Field 'system' must be a non-empty string.");
  }

  const toolsResult = validateTools(parsed.tools);
  if (!toolsResult.ok) return toolsResult;

  const messagesResult = validateMessages(parsed.messages);
  if (!messagesResult.ok) return messagesResult;

  const utf8Bytes = textEncoder.encode(
    parsed.system + JSON.stringify(toolsResult.tools) + JSON.stringify(messagesResult.messages),
  ).byteLength;
  const estimatedInputTokens = Math.ceil(utf8Bytes / 3.5) + 256;

  if (estimatedInputTokens > MAX_INPUT_TOKENS) {
    return fail(413, "too_large", "Estimated input exceeds the 80,000-token short-context limit.");
  }

  return {
    ok: true,
    value: {
      system: parsed.system,
      tools: toolsResult.tools,
      messages: messagesResult.messages,
      estimatedInputTokens,
    },
  };
}

export function buildUpstreamPayload(model: ModelSpec, validated: ValidatedChatRequest): Record<string, unknown> {
  return {
    model: model.id,
    max_tokens: MAX_OUTPUT_TOKENS,
    stream: true,
    system: [{ type: "text", text: validated.system, cache_control: { type: "ephemeral" } }],
    ...(validated.tools.length > 0
      ? {
          tools: validated.tools.map((t, idx, arr) => ({
            name: "run_program",
            description: t.description,
            input_schema: t.input_schema,
            ...(idx === arr.length - 1 ? { cache_control: { type: "ephemeral" } } : {}),
          })),
        }
      : {}),
    messages: validated.messages,
  };
}
