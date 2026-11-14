/**
 * The two session capabilities (web.md section 4.1) whose absence takes members out of the SDK (codemode.md
 * section 7): the generator leaves them out of the declarations and the core leaves them off the run-time
 * object, both through `capabilityOn`, so a jar that lacks one gets a smaller surface that is the same smaller
 * surface in types and at run time. These are protocol vocabulary, not registry content.
 */

/** The capability of `authoring.transaction`: the write side of the SDK needs it. */
export const CAPABILITY_TRANSACTION = 'codemode';

/** The capability of the logic edit and instantiate ops: a handle's `rules` needs it. */
export const CAPABILITY_LOGIC = 'logic';

/** Whether a member that needs `name` is part of the surface: a jar that predates the capability list (`null`) is assumed to have all. */
export function capabilityOn(capabilities: readonly string[] | null, name: string): boolean {
  return capabilities === null || capabilities.includes(name);
}
