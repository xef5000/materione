/**
 * Object identity for the approval model (codemode.md section 5.6): a grant is per *object*, and an object is one
 * stored document of one pack. The id is `<pack>:<path>`, the path exactly as the transaction names it.
 *
 * Matching is **exact**. There is no folding of case, no normalisation of `..` and no wildcard: a grant can only ever
 * cover the string it was given, so it cannot be read as covering anything wider. A name that is not canonical (an empty
 * segment, `.` or `..`, a backslash, a control character) is not an object id at all, and what names it cannot be covered
 * by any grant (classify.ts treats it as a blocker that only an explicit approval of that one proposal clears).
 */

/** `<pack>:<path>`, canonical by construction (see `objectId`). */
export type ObjectId = string & { readonly __objectId: never };

const MAX_LENGTH = 512;
const PACK = /^[A-Za-z0-9_.-]+$/;
// eslint-disable-next-line no-control-regex
const BAD_SEGMENT = /[\\\u0000-\u001f\u007f:]/;

function canonicalPath(path: string): boolean {
  if (path.length === 0 || path.length > MAX_LENGTH) return false;
  return path.split('/').every((segment) => segment.length > 0 && segment !== '.' && segment !== '..' && !BAD_SEGMENT.test(segment));
}

function canonicalPack(pack: string): boolean {
  return pack !== '.' && pack !== '..' && pack.length <= 128 && PACK.test(pack);
}

/** The object id of a document, or null when the pack or the path is not canonical. */
export function objectId(pack: unknown, path: unknown): ObjectId | null {
  if (typeof pack !== 'string' || typeof path !== 'string') return null;
  if (!canonicalPack(pack) || !canonicalPath(path)) return null;
  return `${pack}:${path}` as ObjectId;
}

/** Whether a string is a canonical object id (what `approve({ objects })` and a plan's declaration accept). */
export function isObjectId(value: unknown): value is ObjectId {
  if (typeof value !== 'string') return false;
  const colon = value.indexOf(':');
  if (colon <= 0) return false;
  return objectId(value.slice(0, colon), value.slice(colon + 1)) === value;
}
