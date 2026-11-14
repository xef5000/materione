// The wire shape of `authoring.transaction` operations, read from the plugin's own parser
// (TransactionOps.operationOf) instead of copied: a field the parser reads for a kind is allowed on it, a
// field it requires must be present. The SDK core's staged operations are validated against this, so the core
// cannot stage something the server does not read, and a server change to the shapes fails here.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { FIXTURES } from './codemode';

const TRANSACTION_OPS = join(FIXTURES, '..', 'web', 'src', 'main', 'java', 'dev', 'materione', 'web', 'rpc', 'ops', 'TransactionOps.java');

export interface KindShape {
  allowed: Set<string>;
  required: Set<string>;
}

export function wireShapes(): Map<string, KindShape> {
  const source = readFileSync(TRANSACTION_OPS, 'utf8');
  const start = source.indexOf('static TransactionOperation operationOf');
  const body = source.slice(start, source.indexOf('private static List<AuthoringApi.FieldEdit> editsOf'));
  const shapes = new Map<string, KindShape>();
  const parts = body.split(/\n\s*case "(\w+)" ->/);
  for (let i = 1; i < parts.length; i += 2) {
    const kind = parts[i] ?? '';
    const block = parts[i + 1] ?? '';
    const allowed = new Set<string>(['kind', 'rev']);
    const required = new Set<string>(['kind']);
    for (const match of block.matchAll(/requiredString\(op, "(\w+)"\)/g)) {
      allowed.add(match[1] ?? '');
      required.add(match[1] ?? '');
    }
    for (const match of block.matchAll(/(?:optionalString|optionalInteger|arrayOf)\(op,\s*"(\w+)"/g)) allowed.add(match[1] ?? '');
    for (const match of block.matchAll(/op\.has\("(\w+)"\)/g)) allowed.add(match[1] ?? '');
    if (/editsOf\(op\)/.test(block)) allowed.add('edits');
    shapes.set(kind, { allowed, required });
  }
  return shapes;
}

/** The problems of one staged operation against the wire's shape for its kind. */
export function wireProblems(operation: Record<string, unknown>, shapes = wireShapes()): string[] {
  const kind = String(operation.kind);
  const shape = shapes.get(kind);
  if (shape === undefined) return [`unknown operation kind "${kind}"`];
  const found: string[] = [];
  for (const key of Object.keys(operation)) if (!shape.allowed.has(key)) found.push(`${kind}: the wire reads no "${key}"`);
  for (const key of shape.required) if (!(key in operation)) found.push(`${kind}: the wire requires "${key}"`);
  const edits = operation.edits;
  if (edits !== undefined) {
    if (!Array.isArray(edits)) found.push(`${kind}: edits is not a list`);
    else if (edits.length > 256) found.push(`${kind}: more than 256 edits`);
  }
  return found;
}
