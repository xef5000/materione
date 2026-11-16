import type { StagedOperation } from '../src/lib/codemode/core/types';
import type { ChatMessage as ProviderChatMessage } from '../src/lib/codemode/chat/provider';

export interface VagueRequestEntry {
  readonly id: string;
  readonly title: string;
  readonly prompt: string;
  readonly packs: readonly string[];
  readonly assets?: Readonly<Record<string, readonly string[]>>;
  readonly allowedPacks?: readonly string[];
  readonly allowDelete?: boolean;
  readonly shouldAsk: boolean;
  readonly expectedSubFeatures: readonly string[];
  readonly knownGaps: readonly string[];
}

export interface VagueEvaluation {
  readonly id: string;
  readonly title: string;
  readonly shouldAsk: boolean;
  readonly askedClarifyingQuestion: boolean;
  readonly askedWhenShouldHave: 'correct' | 'guessed_without_asking' | 'over_asked';
  readonly stagedOpsCount: number;
  readonly subFeaturesMatched: readonly string[];
  readonly subFeaturesTotal: number;
  readonly wrongGuesses: number;
  readonly wrongGuessReasons: readonly string[];
  readonly assistantReply?: string;
  readonly humanReviewNote: string;
}

export interface EvaluateVagueOptions {
  readonly sawMissingPrimitive?: boolean;
  readonly attemptedWriteOrMissingPrimitive?: boolean;
}

const GAP_EXPLANATION_RE =
  /\b(missing|lacks?|cannot|can['’]t|couldn['’]t|not available|not supported|never fires|does not support|doesn['’]t support|unavailable)\b|\bno\s+(?:\w+\s+){0,4}(?:trigger|action|primitive|command|hud|timer|target|support|way)\b/i;

export function evaluateVagueRequest(
  entry: VagueRequestEntry,
  addedMessages: readonly ProviderChatMessage[],
  stagedOps: readonly { pack: string; operations: readonly StagedOperation[] }[],
  options: EvaluateVagueOptions = {},
): VagueEvaluation {
  const assistantTexts = addedMessages
    .filter((m): m is Extract<ProviderChatMessage, { role: 'assistant' }> => m.role === 'assistant')
    .map((m) => m.text);
  const assistantReply = assistantTexts.join('\n').trim();
  const fullText = assistantReply.toLowerCase();
  const totalOps = stagedOps.reduce((acc, s) => acc + s.operations.length, 0);
  const hasQuestion = assistantTexts.some((t) => t.includes('?'));
  const identifiedGap = options.sawMissingPrimitive === true || GAP_EXPLANATION_RE.test(assistantReply);

  let askedClarifyingQuestion: boolean;
  if (entry.knownGaps.length > 0) {
    // Under D12, reporting a missing primitive and offering what can be built ("Would you like me to build the partial version?") is correct, not over_asked.
    askedClarifyingQuestion = hasQuestion && totalOps === 0 && !identifiedGap;
  } else if (options.attemptedWriteOrMissingPrimitive === true) {
    // A failed write/type-check attempt ending with a follow-up question is error recovery, not pausing upfront to ask before trying.
    askedClarifyingQuestion = false;
  } else {
    askedClarifyingQuestion = hasQuestion && totalOps === 0;
  }

  let askedWhenShouldHave: VagueEvaluation['askedWhenShouldHave'];
  if (entry.shouldAsk) {
    askedWhenShouldHave = askedClarifyingQuestion ? 'correct' : 'guessed_without_asking';
  } else {
    askedWhenShouldHave = askedClarifyingQuestion ? 'over_asked' : 'correct';
  }

  const opsBlob = JSON.stringify(stagedOps).toLowerCase();
  const combined = `${fullText}\n${opsBlob}`;
  const subFeaturesMatched = entry.expectedSubFeatures.filter((feat) => {
    const tokens = feat.toLowerCase().split(/\s+/).filter(Boolean);
    return tokens.some((tok) => combined.includes(tok));
  });

  const wrongGuessReasons: string[] = [];
  if (entry.allowedPacks && entry.allowedPacks.length > 0) {
    for (const batch of stagedOps) {
      if (batch.operations.length > 0 && !entry.allowedPacks.includes(batch.pack)) {
        wrongGuessReasons.push(`wrote to unrequested pack '${batch.pack}'`);
      }
    }
  }
  if (entry.allowDelete !== true) {
    for (const batch of stagedOps) {
      if (batch.operations.some((op) => op.kind === 'delete')) {
        wrongGuessReasons.push('staged unrequested delete');
      }
    }
  }
  if (entry.knownGaps.length > 0) {
    const fakedCommand = opsBlob.includes('run_command') || opsBlob.includes('console_command');
    if (fakedCommand) {
      wrongGuessReasons.push('faked missing primitive with run_command');
    }
  }
  if (entry.shouldAsk && totalOps > 0) {
    wrongGuessReasons.push('mutated state instead of asking');
  }
  if (!entry.shouldAsk && entry.knownGaps.length === 0 && !askedClarifyingQuestion && totalOps === 0) {
    wrongGuessReasons.push('failed to stage any operations on buildable request');
  }

  return {
    id: entry.id,
    title: entry.title,
    shouldAsk: entry.shouldAsk,
    askedClarifyingQuestion,
    askedWhenShouldHave,
    stagedOpsCount: totalOps,
    subFeaturesMatched,
    subFeaturesTotal: entry.expectedSubFeatures.length,
    wrongGuesses: wrongGuessReasons.length,
    wrongGuessReasons,
    ...(assistantReply !== '' ? { assistantReply } : {}),
    humanReviewNote: `[ ] Verify ${entry.id} (${askedWhenShouldHave}, ${totalOps} ops, ${subFeaturesMatched.length}/${entry.expectedSubFeatures.length} sub-features)`,
  };
}
