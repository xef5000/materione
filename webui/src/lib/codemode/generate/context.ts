/**
 * The context schema of a trigger as a tree (codemode.md section 3.8, trigger-typed rules). The generator
 * types a rule callback's `ctx` from this tree and the core builds the run-time `ctx` from the same tree, so
 * a path the types accept is a path the object has.
 */
import type { SurfaceContextVar } from './surface';

export interface ContextNode {
  children: Map<string, ContextNode>;
  /** The variable that sits at this very path, when there is one. */
  leaf?: SurfaceContextVar;
  /** A trailing `.*` family: any name under this node is a value of this variable. */
  wildcard?: SurfaceContextVar;
}

/** A dotted path becomes a node per segment; a trailing `*` marks a family. */
export function contextTree(vars: readonly SurfaceContextVar[]): ContextNode {
  const root: ContextNode = { children: new Map() };
  for (const variable of vars) {
    let node = root;
    const segments = variable.path.split('.').filter((segment) => segment !== '');
    segments.forEach((segment, index) => {
      if (segment === '*' && index === segments.length - 1) {
        node.wildcard = variable;
        return;
      }
      let child = node.children.get(segment);
      if (child === undefined) {
        child = { children: new Map() };
        node.children.set(segment, child);
      }
      node = child;
      if (index === segments.length - 1) node.leaf = variable;
    });
  }
  return root;
}

/** The variables a rule on `host` receives: the ones whose `hosts` list is empty or names it. */
export function contextFor(vars: readonly SurfaceContextVar[], host: string): SurfaceContextVar[] {
  return vars.filter((variable) => variable.hosts.length === 0 || variable.hosts.includes(host));
}
