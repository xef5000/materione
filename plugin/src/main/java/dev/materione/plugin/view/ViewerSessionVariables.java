package dev.materione.plugin.view;

import dev.materione.api.logic.variable.SessionVariables;
import java.util.Objects;
import java.util.Optional;

/**
 * A viewer record's session values as the logic engine sees them (logic.md §3.1, views.md §3.4):
 * {@code vars.session.*} and {@code set_var: {var: session.<name>}} read and write the record's map, the one
 * spelling of a window-local value, and the values follow the record through a refresh and {@code view.back}.
 */
public final class ViewerSessionVariables implements SessionVariables {

    private final ViewerRecord record;

    public ViewerSessionVariables(ViewerRecord record) {
        this.record = Objects.requireNonNull(record, "record");
    }

    @Override
    public Optional<Object> get(String name) {
        return Optional.ofNullable(record.sessionValues().get(name));
    }

    @Override
    public void put(String name, Object value) {
        record.sessionValue(name, value);
    }
}
