import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Page } from '@playwright/test';
import type { CheckResult } from '../../../src/lib/codemode/typecheck';
import type { TypecheckProbe } from '../../typecheck';

export type Probe = TypecheckProbe;

const FIXTURES = fileURLToPath(new URL('../../../../fixtures', import.meta.url));
const CORPUS_DIR = join(FIXTURES, 'codemode', 'corpus');

export const surfaces = {
  'plugin-current': readFileSync(join(FIXTURES, 'golden', 'codemode', 'plugin-current.d.ts'), 'utf8'),
  'with-addon': readFileSync(join(FIXTURES, 'golden', 'codemode', 'with-addon.d.ts'), 'utf8'),
};

export interface Expectation {
  task: string;
  registry: 'plugin-current' | 'with-addon';
  result: 'pass' | 'fail';
  diagnostics?: { code: number; messageContains?: string[]; missingPrimitive?: { member: string; catalog: string } }[];
}

export const corpus = readdirSync(CORPUS_DIR)
  .filter((name) => name.endsWith('.ts'))
  .sort()
  .map((name) => ({
    name,
    program: readFileSync(join(CORPUS_DIR, name), 'utf8'),
    expect: JSON.parse(readFileSync(join(CORPUS_DIR, name.replace(/\.ts$/, '.expect.json')), 'utf8')) as Expectation,
  }));

/** One check in the page, through the editor's shared client. */
export function check(page: Page, program: string, registry: keyof typeof surfaces = 'plugin-current'): Promise<CheckResult> {
  return page.evaluate(([text, sdk]) => window.__tc!.check(text!, sdk!), [program, surfaces[registry]] as const);
}
