/**
 * The parameters of the engine-owned steps (logic.md section 2.2, 31). The control steps are a closed set owned by the
 * engine, so their keys are part of the grammar, not of a registry; the vocabulary lists the kinds (`steps`) but not
 * their keys. They are described here as `ParamInfo`, the same shape every registry entry describes with, so the
 * inspector draws them with the same fields and writes them with the same payload rules (`params.ts`).
 *
 * Pure of the DOM and of Svelte.
 */
import type { ParamInfo } from './vocabulary';

/** `wait: 20` or `wait: {ticks: 20}`. */
export const WAIT_PARAMS: ParamInfo[] = [
  { id: 'ticks', label: 'Ticks', type: 'integer', required: true, min: 0, description: 'How long the run waits, in ticks (20 ticks are one second). It never blocks the server.' },
];

/** `stop` or `stop: {cancel: true}`. */
export const STOP_PARAMS: ParamInfo[] = [
  { id: 'cancel', label: 'Cancel the event', type: 'boolean', required: false, description: 'Also cancels the event that started this run, where it can be cancelled.' },
  { id: 'cancel_session_runs', label: 'Cancel this GUI\'s other runs', type: 'boolean', required: false, description: 'Also ends the other runs waiting in this GUI session.' },
];

/** `call: purchase` or `call: {flow: purchase, with: {price: 250}}`. `flows` are the named flows of this resource. */
export function callParams(flows: readonly string[]): ParamInfo[] {
  return [
    {
      id: 'flow',
      label: 'Flow',
      type: 'string',
      required: true,
      allowedValues: flows.length > 0 ? [...flows] : undefined,
      description: 'The named flow to run, from this resource.',
    },
    { id: 'with', label: 'Arguments', type: 'raw', uiHint: 'advanced', required: false, description: 'Values the flow receives as args.<name>, as a mapping.' },
  ];
}
