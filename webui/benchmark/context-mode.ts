import { agentContext, INSTRUCTIONS } from '../src/lib/codemode/chat/context';
import { generateSdk } from '../src/lib/codemode/generate/generate';
import type { SurfaceDocument } from '../src/lib/codemode/generate/surface';

export type ContextMode = 'index' | 'full-dts';

export function assembleBenchmarkContext(surface: SurfaceDocument, mode: ContextMode): string {
  if (mode === 'index') {
    return agentContext(surface);
  }
  const fullDts = generateSdk(surface);
  return `${INSTRUCTIONS}\n\nSDK FULL DECLARATIONS (generated from the connected server's registries):\n\n\`\`\`typescript\n${fullDts}\n\`\`\``;
}
