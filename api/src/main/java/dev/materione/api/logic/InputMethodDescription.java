package dev.materione.api.logic;

import dev.materione.api.registry.SelfDescription;
import java.util.Objects;

/**
 * An input method's self-description (logic.md §4.5, §6.6).
 *
 * @param self the name, summary and parameter schema, the same shape every registry entry describes with
 * @param closesWindow true when asking the player takes their window away (chat, a sign editor, an anvil): the
 *     prompt then hides the player's GUI for the question and shows it again with the answer. A method that
 *     leaves the window in place (a future dialog) says false
 */
public record InputMethodDescription(SelfDescription self, boolean closesWindow) {

    public InputMethodDescription {
        Objects.requireNonNull(self, "self");
    }

    public static InputMethodDescription of(SelfDescription self, boolean closesWindow) {
        return new InputMethodDescription(self, closesWindow);
    }
}
