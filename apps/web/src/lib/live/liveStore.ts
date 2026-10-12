import { INITIAL_STATE, reduce, type LiveAction, type LiveState } from "@/lib/live/liveState";

/**
 * Holds the live picture and tells interested parties when it changes.
 *
 * Deliberately not a React store. The map reads it from an animation loop, which has
 * no business going through React's render cycle, and the status chip reads it through
 * `useSyncExternalStore`. One plain object serves both, and a library for it would be
 * a dependency to hold about thirty lines.
 */
export class LiveStore {
  private state: LiveState = INITIAL_STATE;
  private readonly listeners = new Set<() => void>();

  /** Bound as properties so they can be handed straight to `useSyncExternalStore`. */
  readonly getState = (): LiveState => this.state;

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  dispatch(action: LiveAction): void {
    this.state = reduce(this.state, action);
    // Copied, so a listener that unsubscribes while being notified cannot skip another.
    for (const listener of [...this.listeners]) {
      listener();
    }
  }
}
