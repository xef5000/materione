package dev.materione.plugin.logic.world;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * How {@code damage} learns whether its blow was vetoed (logic.md §41). Bukkit's {@code damage} returns nothing, so the verb
 * runs the call inside {@link #observe}: a listener at {@code MONITOR} (the last word, after every protection plugin has
 * decided) records whether the damage event for the watched entity ended cancelled. It says nothing to the player and
 * reports no diagnostic: the answer is the action's outcome, which a {@code require} can branch on and the logic trace shows.
 * Two plugins that both police the same blow are the server owner's to sort out.
 */
public interface DamageWatch {

    /** What became of one blow. */
    enum Result {
        /** The damage event was delivered and not cancelled (or there was no way to tell). */
        LANDED,
        /** A listener cancelled the damage event. */
        CANCELLED
    }

    /** Runs {@code damage} (which raises the event synchronously on this thread) and says what became of it. */
    Result observe(Entity victim, Runnable damage);

    /** A watch that cannot see (a unit test, a server where the listener is not registered): every blow landed. */
    DamageWatch NONE = (victim, damage) -> {
        damage.run();
        return Result.LANDED;
    };

    /**
     * The server's watch. It is a listener ({@code registerEvents} it once) with one per-thread slot, set only while a verb's own
     * call is on the stack, so it costs one thread-local read for every other damage event on the server.
     */
    final class Paper implements DamageWatch, Listener {
        private final ThreadLocal<Entity[]> watching = new ThreadLocal<>();
        private final ThreadLocal<boolean[]> cancelled = ThreadLocal.withInitial(() -> new boolean[1]);

        @Override
        public Result observe(Entity victim, Runnable damage) {
            Entity[] previous = watching.get();
            boolean[] flag = cancelled.get();
            boolean before = flag[0];
            flag[0] = false;
            watching.set(new Entity[] {victim});
            try {
                damage.run();
                return flag[0] ? Result.CANCELLED : Result.LANDED;
            } finally {
                flag[0] = before;
                if (previous == null) {
                    watching.remove();
                } else {
                    watching.set(previous);
                }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void seen(EntityDamageEvent event) {
            Entity[] slot = watching.get();
            if (slot != null && slot[0] == event.getEntity() && event.isCancelled()) {
                cancelled.get()[0] = true;
            }
        }
    }
}
