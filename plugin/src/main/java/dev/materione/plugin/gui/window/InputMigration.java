package dev.materione.plugin.gui.window;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Places formerly owned items into still-active inputs and returns the remainder. */
final class InputMigration {
    private InputMigration() {}

    static <T> void move(Map<Integer, T> items, Set<Integer> active,
            BiConsumer<Integer, T> keep, Consumer<T> giveBack) {
        items.forEach((slot, item) -> {
            if (active.contains(slot)) {
                keep.accept(slot, item);
            } else {
                giveBack.accept(item);
            }
        });
    }
}
