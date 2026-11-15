import type { ChatMessage, ModelEvent, ModelProvider, ModelRequest } from '../src/lib/codemode/chat/provider';

export type RoutingStrategy = 'always-cheap' | 'always-mid' | 'escalate';

export interface RoutedProviderOptions {
  readonly strategy: RoutingStrategy;
  readonly cheap: ModelProvider;
  readonly mid: ModelProvider;
  readonly cheapId: string;
  readonly midId: string;
  readonly onTurnModel?: (info: { modelId: string; escalated: boolean }) => void;
}

/**
 * Inspects tool results within the current user request (after the last user message).
 * Escalates from cheap to mid once there are >= 2 type-error outcomes OR >= 1 refused dry-run outcome
 * (codemode.md section 12.1, issue 385).
 */
export function shouldEscalateToMid(messages: readonly ChatMessage[]): boolean {
  let lastUserIdx = -1;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]!.role === 'user') {
      lastUserIdx = i;
      break;
    }
  }
  let typeErrors = 0;
  let refusedDryRuns = 0;
  for (let i = lastUserIdx + 1; i < messages.length; i++) {
    const msg = messages[i]!;
    if (msg.role !== 'tool') continue;
    try {
      const parsed = JSON.parse(msg.content) as { status?: unknown };
      if (parsed.status === 'type-error') typeErrors++;
      else if (parsed.status === 'refused') refusedDryRuns++;
    } catch {
      /* ignore non-JSON tool content */
    }
    if (typeErrors >= 2 || refusedDryRuns >= 1) return true;
  }
  return false;
}

export function createRoutedProvider(options: RoutedProviderOptions): ModelProvider {
  const { strategy, cheap, mid, cheapId, midId, onTurnModel } = options;
  return {
    info: {
      id: `routed:${strategy}`,
      label: `Routed (${strategy}: ${cheapId} / ${midId})`,
      remote: cheap.info.remote || mid.info.remote,
    },
    async *stream(request: ModelRequest): AsyncIterable<ModelEvent> {
      let useMid = false;
      if (strategy === 'always-mid') {
        useMid = true;
      } else if (strategy === 'escalate') {
        useMid = shouldEscalateToMid(request.messages);
      }
      const chosen = useMid ? mid : cheap;
      const chosenId = useMid ? midId : cheapId;
      onTurnModel?.({ modelId: chosenId, escalated: strategy === 'escalate' && useMid });
      for await (const event of chosen.stream(request)) {
        yield event;
      }
    },
  };
}
