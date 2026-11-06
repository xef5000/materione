/**
 * The node inspector's context (logic.md section 31): the field components read the inspector's state, the vocabulary
 * and the connection from here instead of threading them through every level of the form.
 */
import { getContext, setContext } from 'svelte';
import type { InspectorState } from '../../../logic/inspector.svelte';

const KEY = Symbol('logic-inspector');

export function provideInspector(state: InspectorState): void {
  setContext(KEY, state);
}

export function useInspector(): InspectorState {
  return getContext<InspectorState>(KEY);
}
