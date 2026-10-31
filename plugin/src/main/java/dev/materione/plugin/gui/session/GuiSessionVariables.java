package dev.materione.plugin.gui.session;

import dev.materione.api.logic.variable.SessionVariables;
import java.util.Objects;
import java.util.Optional;

/**
 * A session's variables as the logic engine sees them (logic.md §3.1): {@code vars.session.*} and
 * {@code set_var: {var: session.<name>}} read and write the same map that {@code gui.session.*} shows, so
 * the two spellings never disagree, and the values follow the session through a refresh and
 * {@code gui.back}.
 */
public final class GuiSessionVariables implements SessionVariables {

    private final GuiSession session;

    public GuiSessionVariables(GuiSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public Optional<Object> get(String name) {
        return Optional.ofNullable(session.vars().get(name));
    }

    @Override
    public void put(String name, Object value) {
        if (value == null) {
            session.vars().remove(name);
        } else {
            session.vars().put(name, value);
        }
    }
}
