package dev.materione.plugin.view;

import dev.materione.api.logic.variable.ViewPort;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code %materione_view_open%} and {@code %materione_view_last%} (logic.md §3.3, views.md §3.8) answered from the
 * live registry: the view the player has open and the one they had before it, each as {@code <kind>:<id>}.
 */
public final class ViewRegistryPort implements ViewPort {

    private final ViewRegistry views;

    public ViewRegistryPort(ViewRegistry views) {
        this.views = Objects.requireNonNull(views, "views");
    }

    @Override
    public Optional<String> openView(UUID player) {
        return views.current(player).filter(ViewerRecord::isOpen).map(record -> record.ref().toString());
    }

    @Override
    public Optional<String> lastView(UUID player) {
        return views.last(player).map(ViewRef::toString);
    }
}
