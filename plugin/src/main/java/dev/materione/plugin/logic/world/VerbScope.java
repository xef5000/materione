package dev.materione.plugin.logic.world;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.block.Block;

/**
 * What the world verbs tell the event bridges while they work (logic.md §41), kept per thread because the events a verb
 * raises are delivered synchronously on the thread that raised them.
 *
 * <ul>
 *   <li><b>The causing rule.</b> {@code damage} raises a real damage event and {@code break_blocks} a block-break event per
 *       block, so protection and combat plugins see them. Those events <em>are</em> answered by rules: every rule but the one that
 *       caused them (a "blocks mined" counter still counts every block of an excavator's 3 by 3; the excavator does not dig for
 *       ever). The verb enters a scope naming its rule, and the bridges drop that rule from the list they fire ({@link #allowed}).
 *   <li><b>The depth.</b> A rule that answers a verb's event may itself run a verb, and so on. The scopes nest; an event raised
 *       at depth {@link WorldLimits#MAX_CHAIN_DEPTH} is not answered by any rule ({@link #chainTooDeep}), which bounds a chain no
 *       matter how the rules are written. It is reported once per ten seconds ({@code MTR-0118}).
 *   <li><b>The event block.</b> While the item bridge runs the rules of a break, the block being broken is still in the world;
 *       {@code break_blocks} leaves it alone, because vanilla finishes breaking it (and drops it) once the rules return.
 * </ul>
 *
 * A scope is entered with try-with-resources and ends with the block; it never outlives the call that opened it.
 */
public final class VerbScope {

    /** The stand-in cause of a verb that runs with no rule (a test calling an action directly). */
    private static final Object NO_RULE = new Object();

    private static final ThreadLocal<ArrayDeque<Object>> CAUSES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Block> EVENT_BLOCK = new ThreadLocal<>();
    private static volatile Consumer<Diagnostic> deepSink = ignored -> { };
    private static final AtomicLong LAST_DEEP_REPORT = new AtomicLong(0);

    private VerbScope() {}

    /** A scope that ends without throwing. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Enters the scope of a verb that raises events, caused by {@code rule} (the opaque identity of the rule whose run it is, or
     * null when there is none).
     */
    public static Scope enter(Object rule) {
        ArrayDeque<Object> causes = CAUSES.get();
        causes.push(rule == null ? NO_RULE : rule);
        return () -> {
            causes.pop();
            if (causes.isEmpty()) {
                CAUSES.remove();
            }
        };
    }

    /** How many verb scopes are open on this thread: 0 for an event no verb raised. */
    public static int depth() {
        ArrayDeque<Object> causes = CAUSES.get();
        return causes.size();
    }

    /** Whether the events raised now are past the chain bound and must not be answered by any rule. */
    public static boolean chainTooDeep() {
        return depth() >= WorldLimits.MAX_CHAIN_DEPTH;
    }

    /**
     * Whether the event being answered now must be dropped because the chain of verb-raised events is too deep. The bridges call
     * this first; it reports the drop (once per ten seconds, {@code MTR-0118}) to the sink {@link #reportChainsTo} set.
     */
    public static boolean chainStopped() {
        if (!chainTooDeep()) {
            return false;
        }
        if (deepReportDue(System.currentTimeMillis())) {
            deepSink.accept(new Diagnostic(DiagnosticCodes.VERB_BUDGET_SPENT, Severity.WARN,
                    "a chain of events raised by world verbs went " + WorldLimits.MAX_CHAIN_DEPTH
                            + " levels deep; the rules stopped answering it", null, null,
                    "A rule whose verb raises an event that another rule's verb answers can chain; break the loop (logic.md section 41)."));
        }
        return true;
    }

    /** Forgets the last report, so a test sees the next one (not for production code). */
    public static void resetReportsForTest() {
        LAST_DEEP_REPORT.set(0);
    }

    /** Where {@link #chainStopped} reports; the plugin sets it once at boot. */
    public static void reportChainsTo(Consumer<Diagnostic> sink) {
        deepSink = sink == null ? ignored -> { } : sink;
    }

    /**
     * Whether a report of {@link #chainTooDeep} is due (at most one every ten seconds, for the whole server), so a bridge that
     * refuses an event reports it once and not on every event of a runaway chain.
     */
    public static boolean deepReportDue(long nowMillis) {
        long last = LAST_DEEP_REPORT.get();
        return nowMillis - last >= WorldVerbs.REPORT_INTERVAL_MS && LAST_DEEP_REPORT.compareAndSet(last, nowMillis);
    }

    /**
     * {@code rules} without the rules whose verbs are raising this event (by identity), or the list itself when no verb is: the
     * bridges fire this list and so never answer a verb's event with the run that caused it. An empty result means nothing fires.
     */
    public static <R> List<R> allowed(List<R> rules) {
        ArrayDeque<Object> causes = CAUSES.get();
        if (causes.isEmpty() || rules.isEmpty()) {
            return rules;
        }
        List<R> kept = new ArrayList<>(rules.size());
        for (R rule : rules) {
            if (!causedBy(causes, rule)) {
                kept.add(rule);
            }
        }
        return kept.size() == rules.size() ? rules : kept;
    }

    private static boolean causedBy(ArrayDeque<Object> causes, Object rule) {
        for (Object cause : causes) {
            if (cause == rule) {
                return true;
            }
        }
        return false;
    }

    /** Enters the scope in which {@code block} is the block of a break event whose rules are running. */
    public static Scope breakEvent(Block block) {
        Block previous = EVENT_BLOCK.get();
        EVENT_BLOCK.set(block);
        return () -> {
            if (previous == null) {
                EVENT_BLOCK.remove();
            } else {
                EVENT_BLOCK.set(previous);
            }
        };
    }

    /** Whether {@code block} is the block of the break event whose rules are running on this thread. */
    public static boolean isEventBlock(Block block) {
        Block event = EVENT_BLOCK.get();
        return event != null && event.equals(block);
    }
}
