package dev.materione.plugin.logic.world;

import org.bukkit.block.Block;

/**
 * What the world verbs tell the event bridges while they work (logic.md §41), kept per thread because the events a verb
 * raises are delivered synchronously on the thread that raised them.
 *
 * <ul>
 *   <li><b>Damaging.</b> {@code damage} raises a real damage event so protection and combat plugins see it. That event
 *       is the rule's own effect, not a new blow: the world-event bridge does not fire {@code entity.hit} or
 *       {@code player.hurt} for it, or a rule that damages in answer to a hit would answer itself without end.
 *   <li><b>Breaking.</b> {@code break_blocks} raises a {@code BlockBreakEvent} per block. The item bridge does not fire
 *       the held item's {@code break} rules for those, for the same reason (an excavator would dig forever).
 *   <li><b>The event block.</b> While the item bridge runs the rules of a break, the block being broken is still in the
 *       world; {@code break_blocks} leaves it alone, because vanilla finishes breaking it (and drops it) once the rules
 *       return.
 * </ul>
 *
 * A scope is entered with try-with-resources and ends with the block; it never outlives the call that opened it.
 */
public final class VerbScope {

    private static final ThreadLocal<int[]> DAMAGING = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<int[]> BREAKING = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<Block> EVENT_BLOCK = new ThreadLocal<>();

    private VerbScope() {}

    /** A scope that ends without throwing. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** Enters the scope of a damage the verb deals. */
    public static Scope damaging() {
        DAMAGING.get()[0]++;
        return () -> DAMAGING.get()[0]--;
    }

    /** Whether a verb is dealing damage on this thread now. */
    public static boolean isDamaging() {
        return DAMAGING.get()[0] > 0;
    }

    /** Enters the scope of a block a verb breaks. */
    public static Scope breaking() {
        BREAKING.get()[0]++;
        return () -> BREAKING.get()[0]--;
    }

    /** Whether a verb is breaking a block on this thread now. */
    public static boolean isBreaking() {
        return BREAKING.get()[0] > 0;
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
