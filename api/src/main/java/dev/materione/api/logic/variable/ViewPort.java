package dev.materione.api.logic.variable;

import java.util.Optional;
import java.util.UUID;

/**
 * The seam the view runtime fills for the PlaceholderAPI expansion (logic.md §3.3, views.md §3.8):
 * {@code %materione_view_open%} is the view a player has open, {@code %materione_view_last%} the one before it,
 * each as {@code <kind>:<id>} ({@code gui:shop:menu}). The runtime registers its implementation with
 * {@code VariableService.views(...)} when it is built; before that, and in a host without views, the default
 * answers empty, so the placeholders expand to nothing.
 */
public interface ViewPort {

    /** Answers empty for every player: no views exist yet. */
    ViewPort NONE = new ViewPort() {
        @Override
        public Optional<String> openView(UUID player) {
            return Optional.empty();
        }

        @Override
        public Optional<String> lastView(UUID player) {
            return Optional.empty();
        }
    };

    /** The {@code <kind>:<id>} of the view {@code player} has open, or empty. */
    Optional<String> openView(UUID player);

    /** The {@code <kind>:<id>} of the view {@code player} had open before the current one, or empty. */
    Optional<String> lastView(UUID player);
}
