/**
 * The logic SPI (logic.md §4): the open vocabulary of the one behavior system (D4) — actions,
 * conditions, triggers and expression functions — as registry entries that describe themselves
 * (D5). Built-ins register through {@link dev.materione.api.logic.LogicApi} exactly the way an
 * addon does; the engine, the node palette and the docs read the same registries.
 *
 * <h2>Contracts at a glance</h2>
 *
 * <ul>
 *   <li><b>Parse once, run many.</b> A type turns its authored parameters into an immutable
 *       parameter object at load ({@link dev.materione.api.logic.ParamReader}); rejection is an
 *       {@link java.lang.IllegalArgumentException}, which the engine turns into a diagnostic on the
 *       one step that holds it. Nothing re-reads raw maps while running.
 *   <li><b>Context by facet.</b> A running step sees the world only through a
 *       {@link dev.materione.api.logic.LogicContext}: typed
 *       {@link dev.materione.api.logic.ContextFacet}s plus the expression-visible variable tree.
 *       That keeps the engine free of Bukkit and unit-testable with fakes.
 *   <li><b>Failure is a value.</b> An action reports {@link dev.materione.api.logic.ActionOutcome};
 *       an exception counts as a failure too and never escapes the engine.
 *   <li><b>Threads are declared.</b> Each action names the {@link dev.materione.api.logic.Threading}
 *       it must run under; the engine, not the action, dispatches through the thread gateway.
 * </ul>
 *
 * <h2>Live traces</h2>
 *
 * <p>{@code LogicApi.traces()} (logic.md §7.6, §32) subscribes to the step-boundary events of the runs a
 * filter selects. It is opt-in, rate-limited and capped, and costs the engine nothing while nobody listens.
 */
package dev.materione.api.logic;
