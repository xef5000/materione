/**
 * Multi-listener signal — the fan-out layer between the single-slot
 * EditorChannel callbacks and every pane that cares about pushes.
 * The channel allows ONE onPush/onState listener; App.svelte forwards
 * through these signals so views subscribe and clean up independently.
 */
export type Listener<T> = (value: T) => void;

export class Signal<T> {
  readonly #listeners = new Set<Listener<T>>();

  /** Registers a listener; returns an unsubscribe function. */
  add(listener: Listener<T>): () => void {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }

  emit(value: T): void {
    for (const listener of [...this.#listeners]) {
      listener(value);
    }
  }

  clear(): void {
    this.#listeners.clear();
  }

  get size(): number {
    return this.#listeners.size;
  }
}

/** The shape of a server-initiated frame (push.reload today, more later). */
export interface PushFrame {
  op: string;
  payload: Record<string, unknown>;
}
