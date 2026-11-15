import { MAX_MODEL_TURNS, REPAIR_BUDGET, type StopReason } from '../src/lib/codemode/chat/loop';
import { addUsage, type ChatMessage, type ModelProvider, type ModelRefusal, type ModelUsage, type ToolCall, type ToolSpec } from '../src/lib/codemode/chat/provider';
import type { SurfaceDocument } from '../src/lib/codemode/generate/surface';
import { parseReport, type TransactionReport } from '../src/lib/codemode/host/transport';
import type { BenchmarkOracle } from './oracle';

export const BASELINE_TOOLS: readonly ToolSpec[] = [
  {
    name: 'describe_surface',
    description:
      'Inspect the server registry self-description (contentTypes, actions, conditions, triggers, mechanics, functions, recipes, guiComponents, guiContainers, or search:<keyword>).',
    inputSchema: {
      type: 'object',
      properties: {
        section: {
          type: 'string',
          description:
            'Section name (contentTypes, actions, conditions, triggers, mechanics, functions, recipes, guiComponents, guiContainers) or "search:<keyword>".',
        },
      },
      required: ['section'],
      additionalProperties: false,
    },
  },
  {
    name: 'list_packs',
    description: 'List all content packs on the server.',
    inputSchema: {
      type: 'object',
      properties: {},
      required: [],
      additionalProperties: false,
    },
  },
  {
    name: 'list_resources',
    description: 'List resources inside a pack, optionally filtered by content type directory or id query.',
    inputSchema: {
      type: 'object',
      properties: {
        pack: { type: 'string', description: 'Pack id.' },
        type: { type: 'string', description: 'Optional content type directory (e.g. items, blocks, guis, variables).' },
        query: { type: 'string', description: 'Optional substring filter on resource id.' },
      },
      required: ['pack'],
      additionalProperties: false,
    },
  },
  {
    name: 'get_document',
    description: 'Read a stored YAML document from a pack, including its current revision and parsed fields.',
    inputSchema: {
      type: 'object',
      properties: {
        pack: { type: 'string', description: 'Pack id.' },
        path: { type: 'string', description: 'Document path relative to the pack (e.g. items/ruby_sword.yml).' },
      },
      required: ['pack', 'path'],
      additionalProperties: false,
    },
  },
  {
    name: 'propose_transaction',
    description:
      'Stage an authoring.transaction operation list (create, setFields, logicEdit, guiEdit, delete) for dry-run validation. New content applies automatically; changes to existing content wait for owner approval.',
    inputSchema: {
      type: 'object',
      properties: {
        pack: { type: 'string', description: 'Target pack id.' },
        summary: { type: 'string', description: 'One-sentence summary of the change.' },
        operations: {
          type: 'array',
          description: 'Array of authoring.transaction operation objects.',
        } as unknown as { type: string; description: string },
      },
      required: ['pack', 'operations'],
      additionalProperties: false,
    },
  },
];

export const BASELINE_SYSTEM_PROMPT = `You are the Materione assistant, inside the Materione web editor of a Minecraft server.
You have structured tools to inspect the server registry and packs and to propose authoring.transaction operations:
- describe_surface({ section }) to inspect content types, actions, conditions, triggers, mechanics, functions, recipes, guiComponents, guiContainers, or search:<keyword>.
- list_packs({}) to see available packs.
- list_resources({ pack, type?, query? }) to list resources in a pack.
- get_document({ pack, path }) to read an existing document and its rev.
- propose_transaction({ pack, summary?, operations }) to dry-run and stage authoring operations:
  * create: { kind: "create", type: "<dir>", id: "<id>", edits: [{ field: ["<id>", "<key>"], value: ... }] }
  * setFields: { kind: "setFields", path: "<dir>/<id>.yml", rev: <rev>, edits: [{ field: ["<id>", "<key>"], value: ... }] }
  * logicEdit: { kind: "logicEdit", path: "<dir>/<id>.yml", rev?: <rev>, edits: [{ op: "addRule", rule: { ... } }] }
  * guiEdit: { kind: "guiEdit", path: "guis/<id>.yml", rev?: <rev>, edits: [...] }
  * delete: { kind: "delete", path: "<dir>/<id>.yml", rev: <rev> }

If the server lacks a primitive, action, trigger, or content type needed for the request, report the missing capability clearly to the owner and do not fake it with an unrelated workaround.
If a request is ambiguous about which pack or item to target, ask one short clarifying question before writing.`;

function describeSection(surface: SurfaceDocument, section: string): unknown {
  const s = section.trim();
  if (s.startsWith('search:')) {
    const q = s.slice('search:'.length).trim().toLowerCase();
    const match = <T extends { id?: string; name?: string; description?: string }>(items: readonly T[]): T[] =>
      items.filter(
        (item) =>
          (item.id ?? item.name ?? '').toLowerCase().includes(q) ||
          (item.description ?? '').toLowerCase().includes(q),
      );
    return {
      contentTypes: match(surface.contentTypes).map((t) => ({ name: t.name, directory: t.directory, description: t.description })),
      actions: match(surface.behavior.actions).map((a) => ({ id: a.id, description: a.description, parameters: a.parameters.map((p) => p.id) })),
      conditions: match(surface.behavior.conditions).map((c) => ({ id: c.id, description: c.description })),
      triggers: match(surface.behavior.triggers).map((t) => ({ id: t.id, description: t.description, firedBy: t.firedBy })),
      mechanics: match(surface.behavior.mechanics).map((m) => ({ id: m.id, description: m.description })),
      guiComponents: match(surface.gui.components).map((g) => ({ id: g.id, description: g.description })),
    };
  }
  switch (s) {
    case 'contentTypes':
      return surface.contentTypes.map((t) => ({ name: t.name, directory: t.directory, description: t.description }));
    case 'actions':
      return surface.behavior.actions.map((a) => ({ id: a.id, available: a.available, description: a.description }));
    case 'conditions':
      return surface.behavior.conditions.map((c) => ({ id: c.id, available: c.available, description: c.description }));
    case 'triggers':
      return surface.behavior.triggers.map((t) => ({ id: t.id, firedBy: t.firedBy, description: t.description }));
    case 'mechanics':
      return surface.behavior.mechanics.map((m) => ({ id: m.id, description: m.description }));
    case 'functions':
      return surface.behavior.functions.map((f) => ({ id: f.id, returns: f.returns, description: f.description }));
    case 'recipes':
      return surface.behavior.recipes.map((r) => ({ id: r.id, description: r.description }));
    case 'guiComponents':
      return surface.gui.components.map((g) => ({ id: g.id, description: g.description }));
    case 'guiContainers':
      return surface.gui.containers.map((c) => ({ id: c.id, slotCount: c.slotCount, label: c.label }));
    default:
      return { error: `Unknown section '${s}'` };
  }
}

export interface BaselineStagedBatch {
  pack: string;
  operations: Record<string, unknown>[];
  accepted: boolean;
}

export interface BaselineStepOutcome {
  tool: string;
  status: 'read' | 'applied' | 'waiting' | 'refused' | 'invalid';
  failed: boolean;
  report?: TransactionReport;
}

export interface BaselineRunResult {
  stop: StopReason;
  added: ChatMessage[];
  turns: number;
  failedCalls: number;
  toolSteps: number;
  usage: ModelUsage;
  outcomes: BaselineStepOutcome[];
  stagedBatches: BaselineStagedBatch[];
  lastReport: TransactionReport | null;
  refusal?: ModelRefusal;
  error?: string;
}

function boundToolContent(value: unknown, maxChars = 12_000): string {
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    const obj = value as Record<string, unknown>;
    const bounded: Record<string, unknown> = { ...obj };
    if (Array.isArray(obj.diagnostics)) {
      bounded.diagnostics = obj.diagnostics.slice(0, 20).map((d) => {
        if (typeof d !== 'object' || d === null) return d;
        const diag = d as Record<string, unknown>;
        return {
          ...diag,
          ...(typeof diag.message === 'string' ? { message: diag.message.slice(0, 300) } : {}),
          ...(typeof diag.hint === 'string' ? { hint: diag.hint.slice(0, 300) } : {}),
        };
      });
    }
    if (Array.isArray(obj.documents)) {
      bounded.documents = obj.documents.slice(0, 20).map((d) => {
        if (typeof d !== 'object' || d === null) return d;
        const doc = d as Record<string, unknown>;
        const next: Record<string, unknown> = { ...doc };
        if (typeof doc.text === 'string') next.text = doc.text.slice(0, 300);
        if (Array.isArray(doc.operations) && doc.operations.length > 10) {
          next.operations = doc.operations.slice(0, 10);
        }
        return next;
      });
    }
    if (Array.isArray(obj.resources)) {
      bounded.resources = obj.resources.slice(0, 50);
    }
    const serialized = JSON.stringify(bounded);
    if (serialized.length <= maxChars) return serialized;
    return JSON.stringify({
      status: typeof obj.status === 'string' ? obj.status : 'ok',
      accepted: typeof obj.accepted === 'boolean' ? obj.accepted : obj.status === 'applied' || obj.status === 'waiting',
      truncated: true,
      summary: `Tool response (${serialized.length} chars) exceeded ${maxChars} char limit.`,
    });
  }
  const serialized = JSON.stringify(value);
  if (serialized.length <= maxChars) return serialized;
  return JSON.stringify({
    status: 'ok',
    accepted: true,
    truncated: true,
    summary: `Tool response (${serialized.length} chars) exceeded ${maxChars} char limit.`,
  });
}

export async function runBaselineAgent(
  history: readonly ChatMessage[],
  deps: {
    provider: ModelProvider;
    surface: SurfaceDocument;
    oracle: BenchmarkOracle;
    system?: string;
    signal?: AbortSignal;
  },
): Promise<BaselineRunResult> {
  const messages: ChatMessage[] = [...history];
  const added: ChatMessage[] = [];
  const outcomes: BaselineStepOutcome[] = [];
  const stagedBatches: BaselineStagedBatch[] = [];
  let usage: ModelUsage = {};
  let failedCalls = 0;
  let turns = 0;
  let toolSteps = 0;
  let lastReport: TransactionReport | null = null;
  const system = deps.system ?? BASELINE_SYSTEM_PROMPT;

  const finish = (stop: StopReason, extra: Partial<BaselineRunResult> = {}): BaselineRunResult => ({
    stop,
    added,
    turns,
    failedCalls,
    toolSteps,
    usage,
    outcomes,
    stagedBatches,
    lastReport,
    ...extra,
  });

  while (turns < MAX_MODEL_TURNS) {
    if (deps.signal?.aborted) return finish('aborted');
    turns++;
    let text = '';
    const calls: ToolCall[] = [];
    let refusal: ModelRefusal | undefined;
    try {
      for await (const ev of deps.provider.stream({
        system,
        messages,
        tools: BASELINE_TOOLS,
        signal: deps.signal,
      })) {
        if (ev.type === 'text') text += ev.delta;
        else if (ev.type === 'tool_call') calls.push(ev.call);
        else if (ev.type === 'usage') usage = addUsage(usage, ev.usage);
        else if (ev.type === 'refusal') refusal = ev.refusal;
      }
    } catch (err) {
      return finish('error', { error: err instanceof Error ? err.message : String(err) });
    }
    if (refusal !== undefined) {
      if (text !== '') {
        const msg: ChatMessage = { role: 'assistant', text, toolCalls: [] };
        messages.push(msg);
        added.push(msg);
      }
      return finish('refused', { refusal });
    }

    const assistantMsg: ChatMessage = { role: 'assistant', text, toolCalls: calls };
    messages.push(assistantMsg);
    added.push(assistantMsg);
    if (calls.length === 0) return finish('done');

    for (const call of calls) {
      toolSteps++;
      const input = (typeof call.input === 'object' && call.input !== null ? call.input : {}) as Record<string, unknown>;
      let outcome: BaselineStepOutcome;
      let contentObj: unknown;

      if (call.name === 'describe_surface') {
        const section = typeof input.section === 'string' ? input.section : '';
        contentObj = describeSection(deps.surface, section);
        outcome = { tool: call.name, status: 'read', failed: false };
      } else if (call.name === 'list_packs') {
        const reply = await deps.oracle.rpc('browse.packs', {});
        contentObj = reply.ok ? reply.data : reply.error;
        outcome = { tool: call.name, status: reply.ok ? 'read' : 'refused', failed: !reply.ok };
      } else if (call.name === 'list_resources') {
        const reply = await deps.oracle.rpc('authoring.summaries', {
          pack: String(input.pack ?? ''),
          ...(typeof input.type === 'string' ? { type: input.type } : {}),
          ...(typeof input.query === 'string' ? { query: input.query } : {}),
          offset: 0,
          limit: 200,
        });
        contentObj = reply.ok ? reply.data : reply.error;
        outcome = { tool: call.name, status: reply.ok ? 'read' : 'refused', failed: !reply.ok };
      } else if (call.name === 'get_document') {
        const reply = await deps.oracle.rpc('browse.get', {
          pack: String(input.pack ?? ''),
          path: String(input.path ?? ''),
        });
        contentObj = reply.ok ? reply.data : reply.error;
        outcome = { tool: call.name, status: reply.ok ? 'read' : 'refused', failed: !reply.ok };
      } else if (call.name === 'propose_transaction') {
        const pack = String(input.pack ?? '');
        const rawOps = Array.isArray(input.operations) ? input.operations : [];
        const operations = rawOps.filter((o): o is Record<string, unknown> => typeof o === 'object' && o !== null);
        const dryReply = await deps.oracle.rpc('authoring.transaction', { pack, operations, dryRun: true });
        if (!dryReply.ok) {
          stagedBatches.push({ pack, operations, accepted: false });
          contentObj = { status: 'refused', accepted: false, error: dryReply.error };
          outcome = { tool: call.name, status: 'refused', failed: true };
        } else {
          const dryReport = parseReport(dryReply.data);
          lastReport = dryReport;
          if (!dryReport.accepted) {
            stagedBatches.push({ pack, operations, accepted: false });
            contentObj = { status: 'refused', accepted: false, diagnostics: dryReport.diagnostics };
            outcome = { tool: call.name, status: 'refused', failed: true, report: dryReport };
          } else {
            const touchesExisting = dryReport.documents.some((d) => d.kind === 'changed' || d.kind === 'deleted');
            if (touchesExisting) {
              stagedBatches.push({ pack, operations, accepted: true });
              contentObj = {
                status: 'waiting',
                accepted: true,
                note: 'Dry run accepted; changes existing content so it waits for owner approval.',
                documents: dryReport.documents,
                diagnostics: dryReport.diagnostics,
              };
              outcome = { tool: call.name, status: 'waiting', failed: false, report: dryReport };
            } else {
              const applyReply = await deps.oracle.rpc('authoring.transaction', {
                pack,
                operations,
                dryRun: false,
                origin: 'code-mode',
                approval: 'not_needed',
              });
              const applyReport = applyReply.ok ? parseReport(applyReply.data) : dryReport;
              lastReport = applyReport;
              stagedBatches.push({ pack, operations, accepted: applyReport.applied });
              contentObj = {
                status: applyReport.applied ? 'applied' : 'refused',
                accepted: applyReport.applied,
                documents: applyReport.documents,
                diagnostics: applyReport.diagnostics,
              };
              outcome = {
                tool: call.name,
                status: applyReport.applied ? 'applied' : 'refused',
                failed: !applyReport.applied,
                report: applyReport,
              };
            }
          }
        }
      } else {
        contentObj = { status: 'invalid', accepted: false, error: `Unknown tool '${call.name}'` };
        outcome = { tool: call.name, status: 'invalid', failed: true };
      }

      if (outcome.failed) failedCalls++;
      outcomes.push(outcome);
      const toolMsg: ChatMessage = {
        role: 'tool',
        callId: call.id,
        name: call.name,
        content: boundToolContent(contentObj),
      };
      messages.push(toolMsg);
      added.push(toolMsg);
    }

    if (failedCalls >= REPAIR_BUDGET) return finish('repair-budget');
  }

  return finish('turn-limit');
}
