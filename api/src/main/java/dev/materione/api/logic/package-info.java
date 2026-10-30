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
 * <h2>Seams for later work</h2>
 *
 * <p>{@code LogicApi} deliberately has no {@code variables()} or {@code traces()} yet. The
 * variable stores arrive with the variables work (logic.md §3, §13 L5) and the run trace with the
 * live-trace work (§7.6, §13 W4); each adds its accessor to {@code LogicApi} then, so nothing here
 * pretends to behaviour it does not have.
 */
package dev.materione.api.logic;
