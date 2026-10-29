package dev.materione.plugin.runtime.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentRegistration;
import dev.materione.api.gui.GuiComponentsApi;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.registry.RegistryService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * The addon registration facade for GUI layer kinds (gui.md §10 #7, api.md §3.10): the same
 * {@code gui-components} registry the built-ins use, written through its bounded path so a
 * registration works after the registry froze. Mirrors {@code MechanicsService}: a registration
 * is owned by its plugin, closing it removes the component, and disabling the owner closes every
 * registration it made. A component is read on the next reload, when GUIs compile against it.
 */
public final class GuiComponentsService implements GuiComponentsApi, AutoCloseable, Listener {

    private final RegistryService registries;
    private final Map<NamespacedId, Registration> registrations = new HashMap<>();
    private boolean closed;

    /**
     * @param host the plugin that hosts the API; its plugin manager delivers owner-disable events
     * @param registries the service that owns the {@code gui-components} registry
     */
    public GuiComponentsService(Plugin host, RegistryService registries) {
        this.registries = Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(host, "host").getServer().getPluginManager().registerEvents(this, host);
    }

    @Override
    public synchronized GuiComponentRegistration register(Plugin owner, NamespacedId id, GuiComponent component) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(component, "component");
        if (closed) {
            throw new IllegalStateException("GUI components service is closed");
        }
        if (!owner.isEnabled()) {
            throw new IllegalStateException("GUI component owner '" + owner.getName() + "' is disabled");
        }
        SelfDescription description = Objects.requireNonNull(component.describe(), "component description");
        registries.registerBounded(RegistryKeys.GUI_COMPONENTS, GuiComponent.class, id, component,
                description, owner.getName());
        var registration = new Registration(owner, id, component);
        registrations.put(id, registration);
        return registration;
    }

    /** Owner disabled: every component it registered leaves the registry, as the owner's own code stops running. */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        ownerDisabled(event.getPlugin());
    }

    /** The owner-disable rule, on the plugin itself (the event's only use). */
    synchronized void ownerDisabled(Plugin owner) {
        List<Registration> owned = new ArrayList<>();
        for (Registration registration : registrations.values()) {
            if (registration.owner == owner) {
                owned.add(registration);
            }
        }
        owned.forEach(Registration::close);
    }

    /** Removes every registration and stops listening. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        new ArrayList<>(registrations.values()).forEach(Registration::close);
        HandlerList.unregisterAll(this);
    }

    private final class Registration implements GuiComponentRegistration {

        private final Plugin owner;
        private final NamespacedId id;
        private final GuiComponent component;
        private boolean active = true;

        private Registration(Plugin owner, NamespacedId id, GuiComponent component) {
            this.owner = owner;
            this.id = id;
            this.component = component;
        }

        @Override
        public NamespacedId id() {
            return id;
        }

        @Override
        public void close() {
            synchronized (GuiComponentsService.this) {
                if (!active) {
                    return;
                }
                active = false;
                registrations.remove(id, this);
                registries.unregisterBounded(RegistryKeys.GUI_COMPONENTS, GuiComponent.class, id, component);
            }
        }
    }
}
