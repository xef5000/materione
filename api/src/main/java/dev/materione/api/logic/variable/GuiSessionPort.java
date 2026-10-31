package dev.materione.api.logic.variable;

import java.util.Optional;
import java.util.UUID;

/**
 * The seam the GUI runtime fills for the PlaceholderAPI expansion (logic.md §3.3):
 * {@code %materione_gui_open%} is the id of the window a player has open, {@code %materione_gui_last%}
 * the one before it. The runtime registers its implementation with
 * {@code VariableService.guiSessions(...)} when it is built; before that, and in a host without GUI
 * sessions, the default answers empty, so the placeholders expand to nothing.
 */
public interface GuiSessionPort {

    /** Answers empty for every player: no GUI sessions exist yet. */
    GuiSessionPort NONE = new GuiSessionPort() {
        @Override
        public Optional<String> openGui(UUID player) {
            return Optional.empty();
        }

        @Override
        public Optional<String> lastGui(UUID player) {
            return Optional.empty();
        }
    };

    /** The id of the GUI {@code player} has open, or empty. */
    Optional<String> openGui(UUID player);

    /** The id of the GUI {@code player} had open before the current one, or empty. */
    Optional<String> lastGui(UUID player);
}
