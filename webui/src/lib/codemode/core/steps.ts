/**
 * The structural steps of the flow model (`m.steps`), the one piece of the core that knows engine vocabulary
 * and not only registry description. They are engine-owned and closed (logic.md section 2.2, the engine's
 * `StepCompiler`), not registry entries, and self-description does not carry their grammar yet: issue 355 puts
 * it there, and this file then goes the way of `StepBuilders` in `core.d.ts`, which it mirrors member by member.
 * TEMPORARY, and the only file of the core that names a step: the ban test lets the step words appear as
 * property names here and nowhere else.
 */
import type { JsonValue } from './types';

/** What the builders need from the session: serialization with the program's own error reporting. */
export interface StepContext {
  /** Any value as a document tree. */
  node(value: unknown, where: string): JsonValue;
  /** A condition (a built condition or an expression) as the value of an `if`. */
  condition(value: unknown, where: string): JsonValue;
  /** A list of steps. */
  steps(value: unknown, where: string): JsonValue[];
  /** Records the diagnostic and ends the run. */
  invalid(message: string): never;
  /** Wraps a finished step tree as a step value. */
  step(node: JsonValue): unknown;
}

/**
 * The two keys of a rule that are flow vocabulary and not registered options: the condition that guards it and the
 * list of steps it runs (logic.md section 2.1). Everything else a rule carries is `on`, a trigger's filters, or a
 * registered rule option.
 */
export const FLOW = { condition: 'if', body: 'do' } as const;

type Dict = Record<string, JsonValue>;

function options(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

/** The members of `m.steps`, as `StepBuilders` types them. */
export function stepBuilders(c: StepContext): Record<string, (...args: unknown[]) => unknown> {
  return {
    if(condition: unknown, branches: unknown): unknown {
      const b = options(branches);
      if (b.then === undefined && b.else === undefined) c.invalid('m.steps.if needs a then branch, an else branch, or both');
      const node: Dict = { if: c.condition(condition, 'm.steps.if condition') };
      if (b.then !== undefined) node.then = c.steps(b.then, 'm.steps.if then');
      if (b.else !== undefined) node.else = c.steps(b.else, 'm.steps.if else');
      return c.step(node);
    },
    require(steps: unknown, rest: unknown): unknown {
      const o = options(rest);
      const node: Dict = { require: c.steps(steps, 'm.steps.require steps') };
      if (o.else !== undefined) node.else = c.steps(o.else, 'm.steps.require else');
      return c.step(node);
    },
    wait(ticks: unknown): unknown {
      if (typeof ticks !== 'number' || !Number.isInteger(ticks) || ticks < 0) c.invalid('m.steps.wait takes whole ticks, 0 or more');
      return c.step({ wait: ticks as number });
    },
    stop(rest: unknown): unknown {
      const o = options(rest);
      const keys = Object.keys(o).filter((key) => o[key] !== undefined);
      if (keys.length === 0) return c.step('stop');
      return c.step({ stop: c.node(o, 'm.steps.stop options') });
    },
    choose(branches: unknown, otherwise: unknown): unknown {
      if (!Array.isArray(branches) || branches.length === 0) c.invalid('m.steps.choose needs at least one branch');
      const cases: JsonValue[] = (branches as unknown[]).map((branch, index) => {
        const b = options(branch);
        return { if: c.condition(b.if, `m.steps.choose branch ${index}`), then: c.steps(b.then, `m.steps.choose branch ${index} then`) };
      });
      const node: Dict = { choose: cases };
      if (otherwise !== undefined) node.else = c.steps(otherwise, 'm.steps.choose otherwise');
      return c.step(node);
    },
    call(flow: unknown, args: unknown): unknown {
      if (typeof flow !== 'string' || flow === '') c.invalid('m.steps.call needs the name of a flow');
      const node: Dict = { call: flow as string };
      if (args !== undefined) node.with = c.node(args, 'm.steps.call arguments');
      return c.step(node);
    },
  };
}
