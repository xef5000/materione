package dev.materione.plugin.logic;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.LogicRegistration;
import dev.materione.api.logic.LogicRegistry;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.api.registry.RegistryKey;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.logic.LogicIds;
import dev.materione.core.registry.RegistryService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.bukkit.plugin.Plugin;

/**
 * One logic registry behind the public {@link LogicRegistry} facade: the same
 * {@code Registry} the introspection view exposes, written through its bounded path so a late
 * addon registration works after the registry froze. Mirrors {@code GuiComponentsService}: a
 * registration is owned by its plugin, closing it removes the type, and disabling the owner closes
 * every registration it made.
 *
 * @param <T> the registered entry type
 */
final class LogicRegistryImpl<T> implements LogicRegistry<T> {

    private final RegistryService registries;
    private final RegistryKey key;
    private final Class<T> entryType;
    private final String label;
    private final Function<T, String> localId;
    private final Function<T, SelfDescription> selfDescription;
    private final Map<NamespacedId, Registration> registrations = new HashMap<>();
    private boolean closed;

    /**
     * @param label what the registry holds, for messages ("action")
     * @param localId reads the type's own {@code id()}
     * @param selfDescription reads the {@link SelfDescription} the registry entry carries
     */
    LogicRegistryImpl(
            RegistryService registries,
            RegistryKey key,
            Class<T> entryType,
            String label,
            Function<T, String> localId,
            Function<T, SelfDescription> selfDescription) {
        this.registries = Objects.requireNonNull(registries, "registries");
        this.key = Objects.requireNonNull(key, "key");
        this.entryType = Objects.requireNonNull(entryType, "entryType");
        this.label = label;
        this.localId = localId;
        this.selfDescription = selfDescription;
        registries.registry(key, entryType); // exists from the start, so lookups never race creation
    }

    @Override
    public synchronized LogicRegistration register(Plugin owner, NamespacedId id, T type) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, label);
        if (closed) {
            throw new IllegalStateException("logic service is closed");
        }
        if (!owner.isEnabled()) {
            throw new IllegalStateException(label + " owner '" + owner.getName() + "' is disabled");
        }
        String own = Objects.requireNonNull(localId.apply(type), label + " id");
        if (!id.value().equals(own)) {
            throw new IllegalArgumentException(label + " '" + own + "' cannot be registered as '" + id
                    + "': the id's value must equal the " + label + "'s own id()");
        }
        SelfDescription description = Objects.requireNonNull(selfDescription.apply(type), label + " description");
        registries.registerBounded(key, entryType, id, type, description, owner.getName());
        var registration = new Registration(owner, id, type);
        registrations.put(id, registration);
        return registration;
    }

    @Override
    public Optional<T> get(NamespacedId id) {
        return registries.registry(key, entryType).get(id).map(RegistryEntry::entry);
    }

    @Override
    public Optional<T> resolve(String authoredId) {
        return LogicIds.parse(authoredId).flatMap(this::get);
    }

    @Override
    public List<RegistryEntry<T>> entries() {
        return List.copyOf(registries.registry(key, entryType).entries());
    }

    /** Owner disabled: every type it registered leaves the registry, as the owner's own code stops running. */
    synchronized void ownerDisabled(Plugin owner) {
        List<Registration> owned = new ArrayList<>();
        for (Registration registration : registrations.values()) {
            if (registration.owner == owner) {
                owned.add(registration);
            }
        }
        owned.forEach(Registration::close);
    }

    /** Removes every registration and refuses new ones. Idempotent. */
    synchronized void close() {
        closed = true;
        new ArrayList<>(registrations.values()).forEach(Registration::close);
    }

    private final class Registration implements LogicRegistration {

        private final Plugin owner;
        private final NamespacedId id;
        private final T type;
        private boolean active = true;

        private Registration(Plugin owner, NamespacedId id, T type) {
            this.owner = owner;
            this.id = id;
            this.type = type;
        }

        @Override
        public NamespacedId id() {
            return id;
        }

        @Override
        public void close() {
            synchronized (LogicRegistryImpl.this) {
                if (!active) {
                    return;
                }
                active = false;
                registrations.remove(id, this);
                registries.unregisterBounded(key, entryType, id, type);
            }
        }
    }
}
