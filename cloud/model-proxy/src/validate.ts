import { MAX_BODY_BYTES, MAX_INPUT_TOKENS, MAX_MESSAGES, MAX_OUTPUT_TOKENS } from "./config";
import type { ModelSpec } from "./models";
import {
  AGENT_INSTRUCTIONS_PREFIX,
  CANONICAL_RUN_PROGRAM_TOOL,
  MAX_SDK_INDEX_CHARS,
  SDK_INDEX_HEADER,
  SERVER_SYSTEM_CHARTER,
} from "./prompt";

export type AllowedContentBlock =
  | { type: "text"; text: string }
  | { type: "tool_use"; id: string; name: "run_program"; input: { text: string } }
  | { type: "tool_result"; tool_use_id: string; content: string; is_error?: boolean };

export interface NormalizedMessage {
  role: "user" | "assistant";
  content: string | AllowedContentBlock[];
  continuation?: Record<string, unknown>[];
}

export interface ValidatedChatRequest {
  sdkIndexSlice: string;
  messages: NormalizedMessage[];
  upperBoundInputTokens: number;
  upstreamBodyJson: string;
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
const MESSAGE_KEYS = new Set(["role", "content", "continuation"]);
const TEXT_BLOCK_KEYS = new Set(["type", "text"]);
const TOOL_USE_KEYS = new Set(["type", "id", "name", "input"]);
const TOOL_RESULT_KEYS = new Set(["type", "tool_use_id", "content", "is_error"]);
const DANGEROUS_KEYS = new Set(["__proto__", "constructor", "prototype"]);
const REASONING_ITEM_KEYS = new Set(["type", "id", "summary", "encrypted_content", "status", "content"]);
const MESSAGE_ITEM_KEYS = new Set(["type", "id", "role", "content", "status", "phase"]);
const OUTPUT_TEXT_KEYS = new Set(["type", "text", "annotations", "logprobs"]);
const FUNCTION_ITEM_KEYS = new Set(["type", "id", "call_id", "name", "arguments", "status"]);

const textEncoder = new TextEncoder();

function isSafePlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  for (const key of Object.getOwnPropertyNames(value)) {
    if (DANGEROUS_KEYS.has(key)) {
      return false;
    }
  }
  return true;
}

function hasOnlyKeys(value: Record<string, unknown>, allowed: Set<string>): boolean {
  return Object.keys(value).every((key) => allowed.has(key));
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

const SDK_INDEX_SUBHEADER =
  'For the fields and parameters of an entry, run a read-only program: return m.docs.type("item") or return m.docs.search("word").';
const MAX_SDK_INDEX_LINE_CHARS = 360;

function isValidSdkIndexGrammar(sdkIndexSlice: string): boolean {
  const lines = sdkIndexSlice.replace(/\r\n/g, "\n").split("\n");
  if (lines[0] !== SDK_INDEX_HEADER) return false;
  for (let i = 1; i < lines.length; i++) {
    const line = lines[i]!;
    if (line.length === 0) continue;
    if (line.length > MAX_SDK_INDEX_LINE_CHARS) return false;
    if (i === 1 && line === SDK_INDEX_SUBHEADER) continue;
    if (line.startsWith("## ") || line.startsWith("- ")) continue;
    return false;
  }
  return true;
}

function extractSdkIndexSlice(system: string): string | null {
  let slice: string | null = null;
  if (system.startsWith(SDK_INDEX_HEADER)) {
    slice = system;
  } else if (system.startsWith(AGENT_INSTRUCTIONS_PREFIX)) {
    const marker = `\n\n${SDK_INDEX_HEADER}`;
    const idx = system.indexOf(marker);
    if (idx !== -1) {
      slice = system.slice(idx + 2);
    }
  }
  if (slice === null) return null;
  if (slice.length > MAX_SDK_INDEX_CHARS) return null;
  if (!isValidSdkIndexGrammar(slice)) return null;
  return slice;
}

function validateTools(rawTools: unknown): { ok: true } | ValidationError {
  if (!Array.isArray(rawTools)) {
    return fail(400, "invalid_request", "Field 'tools' must be an array.");
  }
  if (rawTools.length !== 1) {
    return fail(400, "invalid_request", "Exactly 1 tool ('run_program') must be provided.");
  }

  const item = rawTools[0];
  if (!isSafePlainObject(item)) {
    return fail(400, "invalid_request", "Tool entry must be a plain object.");
  }
  for (const key of Object.keys(item)) {
    if (!TOOL_KEYS.has(key)) {
      return fail(400, "invalid_request", "Disallowed tool field.");
    }
  }
  if (item.name !== "run_program") {
    return fail(400, "invalid_request", "Only tool 'run_program' is permitted.");
  }
  if (typeof item.description !== "string" || item.description.length < 1 || item.description.length > 1_000) {
    return fail(400, "invalid_request", "Tool 'description' must be a string between 1 and 1,000 characters.");
  }

  const hasSnake = "input_schema" in item;
  const hasCamel = "inputSchema" in item;
  if (hasSnake === hasCamel) {
    return fail(400, "invalid_request", "Tool must specify either 'input_schema' or 'inputSchema'.");
  }
  const schema = hasSnake ? item.input_schema : item.inputSchema;
  if (!isSafePlainObject(schema) || schema.type !== "object") {
    return fail(400, "invalid_request", "Tool schema must be an object with type: 'object'.");
  }
  if (!isSafePlainObject(schema.properties)) {
    return fail(400, "invalid_request", "Tool schema 'properties' must be a plain object.");
  }
  const propKeys = Object.keys(schema.properties);
  if (propKeys.length !== 1 || propKeys[0] !== "text") {
    return fail(400, "invalid_request", "Tool schema 'properties' must contain only 'text'.");
  }
  const textProp = schema.properties.text;
  if (!isSafePlainObject(textProp) || textProp.type !== "string") {
    return fail(400, "invalid_request", "Tool schema 'properties.text' must have type: 'string'.");
  }

  return { ok: true };
}

function validateContentBlock(
  block: unknown,
  role: "user" | "assistant",
): { ok: true; block: AllowedContentBlock } | ValidationError {
  if (!isSafePlainObject(block)) {
    return fail(400, "invalid_request", "Content block must be a plain object.");
  }
  const type = block.type;
  if (type === "text") {
    for (const key of Object.keys(block)) {
      if (!TEXT_BLOCK_KEYS.has(key)) {
        return fail(400, "invalid_request", "Disallowed field on text block.");
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
        return fail(400, "invalid_request", "Disallowed field on tool_use block.");
      }
    }
    if (typeof block.id !== "string" || block.id.length === 0) {
      return fail(400, "invalid_request", "Block 'tool_use' requires a non-empty string 'id'.");
    }
    if (block.name !== "run_program") {
      return fail(400, "invalid_request", "Only tool 'run_program' is permitted in tool_use blocks.");
    }
    if (!isSafePlainObject(block.input)) {
      return fail(400, "invalid_request", "Block 'tool_use' requires a plain object 'input'.");
    }
    const inputKeys = Object.keys(block.input);
    if (inputKeys.length !== 1 || inputKeys[0] !== "text") {
      return fail(400, "invalid_request", "Block 'tool_use' input must contain only 'text'.");
    }
    const programText = block.input.text;
    if (typeof programText !== "string" || programText.length < 1 || programText.length > 60_000) {
      return fail(400, "invalid_request", "Block 'tool_use' input.text must be a string between 1 and 60,000 characters.");
    }
    return {
      ok: true,
      block: {
        type: "tool_use",
        id: block.id,
        name: "run_program",
        input: { text: programText },
      },
    };
  }

  if (type === "tool_result") {
    if (role !== "user") {
      return fail(400, "invalid_request", "Block type 'tool_result' is only allowed on user messages.");
    }
    for (const key of Object.keys(block)) {
      if (!TOOL_RESULT_KEYS.has(key)) {
        return fail(400, "invalid_request", "Disallowed field on tool_result block.");
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

  return fail(400, "invalid_request", "Disallowed content block type.");
}

export function isValidContinuation(value: unknown): value is Record<string, unknown>[] {
  if (!Array.isArray(value) || value.length > 128) return false;
  for (const raw of value) {
    if (!isSafePlainObject(raw)) return false;
    if (raw.type === "reasoning") {
      if (!hasOnlyKeys(raw, REASONING_ITEM_KEYS) || typeof raw.id !== "string" ||
          typeof raw.encrypted_content !== "string" || !Array.isArray(raw.summary) ||
          raw.summary.some((part: unknown) => !isSafePlainObject(part) || part.type !== "summary_text" ||
            typeof part.text !== "string" || !hasOnlyKeys(part, TEXT_BLOCK_KEYS)) ||
          (raw.content !== undefined && (!Array.isArray(raw.content) ||
            raw.content.some((part: unknown) => !isSafePlainObject(part) || part.type !== "reasoning_text" ||
              typeof part.text !== "string" || !hasOnlyKeys(part, TEXT_BLOCK_KEYS))))) return false;
    } else if (raw.type === "message") {
      if (!hasOnlyKeys(raw, MESSAGE_ITEM_KEYS) || raw.role !== "assistant" || !Array.isArray(raw.content) ||
          raw.content.some((part: unknown) => !isSafePlainObject(part) || !hasOnlyKeys(part, OUTPUT_TEXT_KEYS) ||
            part.type !== "output_text" || typeof part.text !== "string")) return false;
    } else if (raw.type === "function_call") {
      if (!hasOnlyKeys(raw, FUNCTION_ITEM_KEYS) || raw.name !== "run_program" ||
          typeof raw.call_id !== "string" || typeof raw.arguments !== "string") return false;
    } else {
      return false;
    }
  }
  return true;
}

function validateMessages(
  rawMessages: unknown,
): { ok: true; messages: NormalizedMessage[]; totalContentBlocks: number } | ValidationError {
  if (!Array.isArray(rawMessages) || rawMessages.length === 0) {
    return fail(400, "invalid_request", "Field 'messages' must be a non-empty array.");
  }
  if (rawMessages.length > MAX_MESSAGES) {
    return fail(400, "invalid_request", `Field 'messages' exceeds maximum of ${MAX_MESSAGES} messages.`);
  }

  const normalized: NormalizedMessage[] = [];
  let totalContentBlocks = 0;

  for (const item of rawMessages) {
    if (!isSafePlainObject(item)) {
      return fail(400, "invalid_request", "Each message must be a plain object.");
    }
    for (const key of Object.keys(item)) {
      if (!MESSAGE_KEYS.has(key)) {
        return fail(400, "invalid_request", "Disallowed message field.");
      }
    }
    if (item.role !== "user" && item.role !== "assistant") {
      return fail(400, "invalid_request", "Message 'role' must be 'user' or 'assistant'.");
    }
    const role = item.role;
    let continuation: Record<string, unknown>[] | undefined;
    if ("continuation" in item) {
      if (role !== "assistant" || !isValidContinuation(item.continuation)) {
        return fail(400, "invalid_request", "Invalid assistant continuation.");
      }
      continuation = item.continuation;
    }
    if (typeof item.content === "string") {
      if (item.content.length === 0) {
        return fail(400, "invalid_request", "Message 'content' string must not be empty.");
      }
      normalized.push({ role, content: item.content, ...(continuation ? { continuation } : {}) });
      totalContentBlocks += 1;
      continue;
    }
    if (Array.isArray(item.content) && item.content.length > 0) {
      const blocks: AllowedContentBlock[] = [];
      for (const rawBlock of item.content) {
        const res = validateContentBlock(rawBlock, role);
        if (!res.ok) return res;
        blocks.push(res.block);
      }
      normalized.push({ role, content: blocks, ...(continuation ? { continuation } : {}) });
      totalContentBlocks += blocks.length;
      continue;
    }
    return fail(400, "invalid_request", "Message 'content' must be a non-empty string or non-empty block array.");
  }

  for (const message of normalized) {
    if (!message.continuation) continue;
    const blocks: AllowedContentBlock[] = typeof message.content === "string"
      ? [{ type: "text", text: message.content }]
      : message.content;
    const expectedText = blocks.filter((block) => block.type === "text").map((block) => block.text).join("");
    const expectedCalls = blocks.filter((block) => block.type === "tool_use");
    let actualText = "";
    const actualCalls: { id: string; input: unknown }[] = [];
    for (const item of message.continuation) {
      if (item.type === "message" && Array.isArray(item.content)) {
        actualText += item.content.map((part: { text: string }) => part.text).join("");
      } else if (item.type === "function_call") {
        try {
          actualCalls.push({ id: String(item.call_id), input: JSON.parse(String(item.arguments)) });
        } catch {
          return fail(400, "invalid_request", "Invalid continuation tool arguments.");
        }
      }
    }
    if (actualText !== expectedText || actualCalls.length !== expectedCalls.length ||
        actualCalls.some((call, i) => call.id !== expectedCalls[i]?.id ||
          JSON.stringify(call.input) !== JSON.stringify(expectedCalls[i]?.input))) {
      return fail(400, "invalid_request", "Assistant continuation does not match the chat history.");
    }
  }
  return { ok: true, messages: normalized, totalContentBlocks };
}

export function buildUpstreamPayload(
  model: ModelSpec,
  validated: Pick<ValidatedChatRequest, "sdkIndexSlice" | "messages">,
): Record<string, unknown> {
  const input: unknown[] = [];
  for (const message of validated.messages) {
    if (message.role === "assistant" && message.continuation) {
      input.push(...message.continuation);
      continue;
    }
    if (typeof message.content === "string") {
      input.push({ role: message.role, content: message.content });
      continue;
    }
    for (const block of message.content) {
      if (block.type === "text") {
        input.push({ role: message.role, content: block.text });
      } else if (block.type === "tool_use") {
        input.push({
          type: "function_call",
          call_id: block.id,
          name: block.name,
          arguments: JSON.stringify(block.input),
        });
      } else {
        input.push({ type: "function_call_output", call_id: block.tool_use_id, output: block.content });
      }
    }
  }
  return {
    model: model.catalogId.replace(/^openai\//, ""),
    max_output_tokens: MAX_OUTPUT_TOKENS,
    stream: true,
    store: false,
    reasoning: { effort: "medium" },
    prompt_cache_options: { ttl: "30m" },
    include: ["reasoning.encrypted_content"],
    instructions: `${SERVER_SYSTEM_CHARTER}\n\n<untrusted_server_sdk_index>\n${validated.sdkIndexSlice}\n</untrusted_server_sdk_index>`,
    tools: [{
      type: "function",
      name: CANONICAL_RUN_PROGRAM_TOOL.name,
      description: CANONICAL_RUN_PROGRAM_TOOL.description,
      parameters: CANONICAL_RUN_PROGRAM_TOOL.input_schema,
      strict: true,
    }],
    input,
  };
}

export async function validateChatRequest(request: Request, model: ModelSpec): Promise<ValidationResult> {
  const bodyResult = await readBoundedJsonBody(request);
  if (!bodyResult.ok) return bodyResult;

  let parsed: unknown;
  try {
    parsed = JSON.parse(bodyResult.text);
  } catch {
    return fail(400, "invalid_request", "Request body must be valid JSON.");
  }

  if (!isSafePlainObject(parsed)) {
    return fail(400, "invalid_request", "Request body must be a JSON object.");
  }

  for (const key of Object.keys(parsed)) {
    if (!TOP_LEVEL_KEYS.has(key)) {
      return fail(400, "invalid_request", "Disallowed top-level field.");
    }
  }
  for (const requiredKey of TOP_LEVEL_KEYS) {
    if (!(requiredKey in parsed)) {
      return fail(400, "invalid_request", "Missing required top-level field.");
    }
  }

  if (typeof parsed.system !== "string" || parsed.system.length === 0) {
    return fail(400, "invalid_request", "Field 'system' must be a non-empty string.");
  }

  const sdkIndexSlice = extractSdkIndexSlice(parsed.system);
  if (sdkIndexSlice === null) {
    return fail(400, "invalid_request", "Field 'system' must include the Materione SDK index.");
  }
  if (sdkIndexSlice.length > MAX_SDK_INDEX_CHARS) {
    return fail(400, "invalid_request", "SDK index exceeds maximum length.");
  }
  if (sdkIndexSlice.toLowerCase().includes("</untrusted_server_sdk_index>")) {
    return fail(400, "invalid_request", "SDK index contains disallowed boundary tag.");
  }

  const toolsResult = validateTools(parsed.tools);
  if (!toolsResult.ok) return toolsResult;

  const messagesResult = validateMessages(parsed.messages);
  if (!messagesResult.ok) return messagesResult;

  const upstreamPayload = buildUpstreamPayload(model, {
    sdkIndexSlice,
    messages: messagesResult.messages,
  });
  const upstreamBodyJson = JSON.stringify(upstreamPayload);
  const upstreamUtf8Bytes = textEncoder.encode(upstreamBodyJson).byteLength;
  const framingUnits = messagesResult.messages.length + messagesResult.totalContentBlocks;
  const upperBoundInputTokens = upstreamUtf8Bytes + 1_024 + framingUnits * 16;

  if (upperBoundInputTokens > MAX_INPUT_TOKENS) {
    return fail(413, "too_large", "Request exceeds the 80,000-token short-context upper bound.");
  }

  return {
    ok: true,
    value: {
      sdkIndexSlice,
      messages: messagesResult.messages,
      upperBoundInputTokens,
      upstreamBodyJson,
    },
  };
}
