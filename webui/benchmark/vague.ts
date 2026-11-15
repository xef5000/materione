import type { StagedOperation } from '../src/lib/codemode/core/types';
import type { ChatMessage as ProviderChatMessage } from '../src/lib/codemode/chat/provider';

export interface VagueRequestEntry {
  readonly id: string;
  readonly title: string;
  readonly prompt: string;
  readonly packs: readonly string[];
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
  readonly subFeaturesMatched: readonly string[];
  readonly subFeaturesTotal: number;
  readonly wrongGuesses: number;
  readonly wrongGuessReasons: readonly string[];
  readonly humanReviewNote: string;
}

export function evaluateVagueRequest(
  entry: VagueRequestEntry,
  addedMessages: readonly ProviderChatMessage[],
  stagedOps: readonly { pack: string; operations: readonly StagedOperation[] }[],
): VagueEvaluation {
  const assistantTexts = addedMessages
    .filter((m): m is Extract<ProviderChatMessage, { role: 'assistant' }> => m.role === 'assistant')
    .map((m) => m.text);
  const fullText = assistantTexts.join('\n').toLowerCase();
  const totalOps = stagedOps.reduce((acc, s) => acc + s.operations.length, 0);
  const askedClarifyingQuestion = assistantTexts.some((t) => t.includes('?')) && totalOps === 0;

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

  return {
    id: entry.id,
    title: entry.title,
    shouldAsk: entry.shouldAsk,
    askedClarifyingQuestion,
    askedWhenShouldHave,
    subFeaturesMatched,
    subFeaturesTotal: entry.expectedSubFeatures.length,
    wrongGuesses: wrongGuessReasons.length,
    wrongGuessReasons,
    humanReviewNote: `[ ] Verify ${entry.id} (${askedWhenShouldHave}, ${subFeaturesMatched.length}/${entry.expectedSubFeatures.length} sub-features)`,
  };
}
