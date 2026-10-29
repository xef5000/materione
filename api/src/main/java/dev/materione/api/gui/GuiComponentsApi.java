package dev.materione.api.gui;

import dev.materione.api.NamespacedId;
import org.bukkit.plugin.Plugin;

/**
 * Addon GUI layer kinds (gui.md §3, §10 #7), registered through the same {@code gui-components}
 * registry the built-ins use. Mirrors {@code MechanicsApi}: registration never edits or reloads
 * content. A component registered now takes effect for GUIs on the next normal reload.
 */
public interface GuiComponentsApi {

    /**
     * Opens one bounded registration for {@code component} under {@code id}. Call on the
     * global/main thread during plugin enable, or via the scheduling gateway. Duplicate or
     * reserved ids fail explicitly; closing the handle, or disabling {@code owner}, removes the
     * component again.
     */
    GuiComponentRegistration register(Plugin owner, NamespacedId id, GuiComponent component);
}
