package dev.materione.plugin.runtime.mechanics;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.mechanics.MechanicConfiguration;
import dev.materione.api.mechanics.MechanicContext;
import dev.materione.api.mechanics.MechanicInstance;
import dev.materione.api.mechanics.MechanicListener;
import dev.materione.api.mechanics.MechanicLookup;
import dev.materione.api.mechanics.MechanicRegistration;
import dev.materione.api.mechanics.MechanicRuntime;
import dev.materione.api.mechanics.MechanicsApi;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.runtime.ContentRuntime;
import dev.materione.platform.items.MaterioneStacks;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Registration-owned mechanic runtimes (D5): built-ins and addons use the same
 * factory, listener and resource lifecycle. Content reloads only swap bindings.
 */
public final class MechanicsService implements MechanicsApi, AutoCloseable, Listener {

    private final Plugin plugin;
    private final DiagnosticsBus diagnostics;
    private final SchedulingGateway scheduling;
    private final ContentRuntime runtime;
    private final PluginManager events;
    private final Map<NamespacedId, Registration> registrations = new LinkedHashMap<>();
    private RegistryService registryService;
    private boolean started;
    private boolean closed;

    /** Compatibility constructor; {@link #registerNatives} supplies the metadata registries. */
    public MechanicsService(
            Plugin plugin, DiagnosticsBus diagnostics, SchedulingGateway scheduling, ContentRuntime runtime) {
        this(plugin, diagnostics, scheduling, runtime, null);
    }

    public MechanicsService(
            Plugin plugin,
            DiagnosticsBus diagnostics,
            SchedulingGateway scheduling,
            ContentRuntime runtime,
            RegistryService registryService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.registryService = registryService;
        events = plugin.getServer().getPluginManager();
        events.registerEvent(
                PluginDisableEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> onOwnerDisable(PluginDisableEvent.class.cast(event)), plugin);
    }

    /** Compatibility bootstrap helper: native factories use the public registration path. */
    public synchronized void registerNatives(RegistryService registries) {
        Objects.requireNonNull(registries, "registries");
        if (registryService != null && registryService != registries) {
            throw new IllegalArgumentException("mechanics service already owns different registries");
        }
        registryService = registries;
        for (Mechanic mechanic : NativeMechanics.all()) {
            register(plugin, NamespacedId.of("materione", mechanic.name()), mechanic);
        }
    }

    @Override
    public synchronized MechanicRegistration register(Plugin owner, NamespacedId id, Mechanic mechanic) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mechanic, "mechanic");
        if (closed) {
            throw new IllegalStateException("mechanics service is closed");
        }
        if (!owner.isEnabled()) {
            throw new IllegalStateException("mechanic owner '" + owner.getName() + "' is disabled");
        }
        RegistryService metadata = Objects.requireNonNull(registryService, "mechanics registries not wired");
        Registration registration = new Registration(owner, id);
        final SelfDescription description;
        final String name;
        final boolean repeatable;
        try {
            description = Objects.requireNonNull(mechanic.describe(), "mechanic description");
            name = Objects.requireNonNull(mechanic.name(), "mechanic name");
            repeatable = mechanic.repeatable();
        } catch (RuntimeException failure) {
            registration.warn("metadata failed", failure);
            throw failure;
        }
        registration.entry = registration.managed(mechanic, name, description, repeatable);
        metadata.registerBounded(
                MechanicsResolver.REGISTRY_KEY, Mechanic.class, id,
                registration.entry, description, owner.getName());
        registrations.put(id, registration);
        try {
            MechanicRuntime wiring = registration.track(
                    Objects.requireNonNull(mechanic.createRuntime(registration), "mechanic runtime"));
            registration.descriptors = List.copyOf(wiring.listeners());
            registration.requireActive();
            if (started) {
                activate(registration);
            }
            return registration;
        } catch (RuntimeException failure) {
            registration.warn("initialization failed", failure);
            registration.close();
            throw failure;
        }
    }

    /** Attaches staged descriptors after the behavior bridge; later registrations attach immediately. */
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("mechanics service is closed");
        }
        if (started) {
            return;
        }
        started = true;
        for (Registration registration : List.copyOf(registrations.values())) {
            try {
                activate(registration);
            } catch (RuntimeException failure) {
                registration.warn("listener activation failed", failure);
                registration.close();
            }
        }
    }

    private void activate(Registration registration) {
        for (MechanicListener<?> descriptor : registration.descriptors) {
            registerListener(registration, descriptor);
        }
        registration.requireActive();
        registration.ready = true;
    }

    private <E extends Event> void registerListener(
            Registration registration, MechanicListener<E> descriptor) {
        registration.requireActive();
        events.registerEvent(
                descriptor.eventType(), registration.listener, descriptor.priority(),
                (listener, event) -> dispatch(registration, descriptor, event),
                registration.owner, descriptor.ignoreCancelled());
    }

    private <E extends Event> void dispatch(
            Registration registration, MechanicListener<E> descriptor, Event event) {
        if (!registration.ready || !registration.active.get() || !registration.owner.isEnabled()
                || !descriptor.eventType().isInstance(event)) {
            return;
        }
        MechanicLookup snapshot = registration.snapshot();
        if (event.isAsynchronous() != (descriptor.thread() == MechanicListener.ThreadClass.ASYNC)) {
            registration.warn("listener thread mismatch for " + descriptor.eventType().getSimpleName(), null);
            return;
        }
        try {
            descriptor.handler().accept(descriptor.eventType().cast(event), snapshot);
        } catch (RuntimeException failure) {
            registration.warn("handler failed for " + descriptor.eventType().getSimpleName(), failure);
        }
    }

    private synchronized void onOwnerDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            close();
            return;
        }
        for (Registration registration : List.copyOf(registrations.values())) {
            if (registration.owner == event.getPlugin()) {
                registration.close();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<Registration> owned = List.copyOf(registrations.values());
        for (int index = owned.size() - 1; index >= 0; index--) {
            owned.get(index).close();
        }
        HandlerList.unregisterAll(this);
    }

    private final class Registration implements MechanicRegistration, MechanicContext {
        private final Plugin owner;
        private final NamespacedId id;
        private final Listener listener = new Listener() {};
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicLong lastWarning = new AtomicLong();
        private final Deque<AutoCloseable> resources = new ArrayDeque<>();
        private List<MechanicListener<?>> descriptors = List.of();
        private volatile boolean ready;
        private Mechanic entry;

        private Registration(Plugin owner, NamespacedId id) {
            this.owner = owner;
            this.id = id;
        }

        @Override
        public NamespacedId id() {
            return id;
        }

        @Override
        public Plugin plugin() {
            return owner;
        }

        @Override
        public DiagnosticsBus diagnostics() {
            return diagnostics;
        }

        @Override
        public SchedulingGateway scheduling() {
            return scheduling;
        }

        @Override
        public MechanicLookup snapshot() {
            return new Lookup(runtime.registries().view(ItemMechanics.KEY), id,
                    () -> active.get() && owner.isEnabled());
        }

        @Override
        public <T extends AutoCloseable> T track(T resource) {
            Objects.requireNonNull(resource, "resource");
            synchronized (resources) {
                if (active.get()) {
                    resources.push(resource);
                    return resource;
                }
            }
            closeResource(resource);
            throw new IllegalStateException("mechanic '" + id + "' is closed");
        }

        private void requireActive() {
            if (!active.get() || !owner.isEnabled()) {
                throw new IllegalStateException("mechanic '" + id + "' is closed or its owner is disabled");
            }
        }

        private Mechanic managed(Mechanic delegate, String name, SelfDescription description, boolean repeatable) {
            return new Mechanic() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public SelfDescription describe() {
                    return description;
                }

                @Override
                public boolean repeatable() {
                    return repeatable;
                }

                @Override
                public MechanicInstance parse(MechanicConfiguration configuration) {
                    requireActive();
                    MechanicInstance instance = delegate.parse(configuration);
                    requireActive();
                    return Objects.requireNonNull(instance, "mechanic instance");
                }
            };
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            synchronized (MechanicsService.this) {
                registrations.remove(id, this);
                registryService.unregisterBounded(
                        MechanicsResolver.REGISTRY_KEY, Mechanic.class, id, entry);
            }
            HandlerList.unregisterAll(listener);
            while (true) {
                AutoCloseable resource;
                synchronized (resources) {
                    resource = resources.pollFirst();
                }
                if (resource == null) {
                    return;
                }
                closeResource(resource);
            }
        }

        private void closeResource(AutoCloseable resource) {
            try {
                resource.close();
            } catch (Exception failure) {
                diagnostics.publish(new Diagnostic(
                        DiagnosticCodes.ACTION_FAILED, Severity.WARN,
                        "mechanic '" + id + "' resource close failed: " + failure,
                        null, null, "other owned resources will still be closed"));
            }
        }

        private void warn(String operation, RuntimeException failure) {
            long now = System.nanoTime();
            long previous = lastWarning.get();
            if ((previous != 0 && now - previous < 1_000_000_000L)
                    || !lastWarning.compareAndSet(previous, now)) {
                return;
            }
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.ACTION_FAILED, Severity.WARN,
                    "mechanic '" + id + "' " + operation + (failure == null ? "" : ": " + failure),
                    null, null, "repeated failures for this registration are suppressed for one second"));
        }
    }

    private record Lookup(
            ItemMechanics bindings, NamespacedId mechanicId, BooleanSupplier active) implements MechanicLookup {
        @Override
        public <T extends MechanicInstance> Optional<T> onItem(NamespacedId item, Class<T> type) {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(type, "type");
            return bindings == null || !active.getAsBoolean()
                    ? Optional.empty() : bindings.onItem(item, mechanicId, type);
        }

        @Override
        public <T extends MechanicInstance> Optional<T> onItem(ItemStack stack, Class<T> type) {
            Objects.requireNonNull(type, "type");
            return MaterioneStacks.identityOf(stack).flatMap(item -> onItem(item, type));
        }

        @Override
        public <T extends MechanicInstance> List<T> allOnItem(NamespacedId item, Class<T> type) {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(type, "type");
            return bindings == null || !active.getAsBoolean()
                    ? List.of() : bindings.allOnItem(item, mechanicId, type);
        }

        @Override
        public <T extends MechanicInstance> List<T> allOnItem(ItemStack stack, Class<T> type) {
            Objects.requireNonNull(type, "type");
            return MaterioneStacks.identityOf(stack).map(item -> allOnItem(item, type)).orElse(List.of());
        }
    }
}
