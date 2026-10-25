package dev.materione.plugin.runtime.mechanics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.mechanics.MechanicConfiguration;
import dev.materione.api.mechanics.MechanicContext;
import dev.materione.api.mechanics.MechanicInstance;
import dev.materione.api.mechanics.MechanicListener;
import dev.materione.api.mechanics.MechanicLookup;
import dev.materione.api.mechanics.MechanicRegistration;
import dev.materione.api.mechanics.MechanicRuntime;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryException;
import dev.materione.api.registry.RegistryKey;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.MaterioneApiImpl;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.runtime.ContentRuntime;
import dev.materione.core.runtime.RuntimeRegistries;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MechanicsServiceTest {

    private static final NamespacedId ITEM = NamespacedId.parse("demo:wand");
    private static final NamespacedId PULSE = NamespacedId.parse("addon:pulse");
    private static final NamespacedId OTHER = NamespacedId.parse("other:pulse");

    private final RegistryService registries = new RegistryService();
    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final ContentRuntime runtime = new ContentRuntime();
    private final SchedulingGateway scheduling = new DirectScheduling();
    private final EventHost events = new EventHost();
    private final Plugin host = events.plugin("Materione", new AtomicBoolean(true));
    private final AtomicBoolean addonEnabled = new AtomicBoolean(true);
    private final Plugin addon = events.plugin("Addon", addonEnabled);
    private final Plugin other = events.plugin("Other", new AtomicBoolean(true));
    private final MechanicsService service = startedService();

    @AfterEach
    void close() {
        service.close();
    }

    @Test
    void startupStagesDescriptorsAndLateRegistrationsAttachImmediately() throws EventException {
        service.close();
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        try (MechanicsService staged =
                new MechanicsService(host, diagnostics, scheduling, runtime, registries)) {
            staged.register(addon, PULSE, mechanic(context -> {
                creations.incrementAndGet();
                return listening((event, lookup) -> calls.incrementAndGet());
            }));
            assertThat(metadata().get(PULSE)).isPresent();
            assertThat(creations).hasValue(1);
            assertThat(events.subscriptions).isZero();
            events.fire(new TestEvent());
            assertThat(calls).hasValue(0);

            staged.start();
            staged.start();
            events.fire(new TestEvent());
            assertThat(events.subscriptions).isEqualTo(1);
            assertThat(creations).hasValue(1);
            assertThat(calls).hasValue(1);

            staged.register(other, OTHER, mechanic(context ->
                    listening((event, lookup) -> calls.incrementAndGet())));
            assertThat(events.subscriptions).isEqualTo(2);
            events.fire(new TestEvent());
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void stagedActivationFailureCleansItsRuntimeAndActivatesHealthyRegistrations() throws EventException {
        service.close();
        List<String> released = new ArrayList<>();
        AtomicInteger healthyCalls = new AtomicInteger();
        try (MechanicsService staged =
                new MechanicsService(host, diagnostics, scheduling, runtime, registries)) {
            staged.register(addon, PULSE, mechanic(context -> {
                context.track(() -> released.add("failed"));
                return listening((event, lookup) -> {});
            }));
            staged.register(other, OTHER, mechanic(context ->
                    listening((event, lookup) -> healthyCalls.incrementAndGet())));
            events.failOnSubscription = 1;

            staged.start();
            events.fire(new TestEvent());

            assertThat(released).containsExactly("failed");
            assertThat(metadata().get(PULSE)).isEmpty();
            assertThat(metadata().get(OTHER)).isPresent();
            assertThat(healthyCalls).hasValue(1);
            assertThat(TestEvent.getHandlerList().getRegisteredListeners()).hasSize(1);
            assertThat(diagnostics.recent(64)).singleElement()
                    .satisfies(d -> assertThat(d.message()).contains("listener activation failed"));
        }
    }

    @Test
    void deferredHighPriorityMechanicsRunAfterEarlierBehaviorBridge() throws EventException {
        service.close();
        List<String> order = new ArrayList<>();
        Listener bridge = new Listener() {};
        try (MechanicsService staged =
                new MechanicsService(host, diagnostics, scheduling, runtime, registries)) {
            staged.register(addon, PULSE, mechanic(context -> new MechanicRuntime() {
                @Override
                public List<MechanicListener<?>> listeners() {
                    return List.of(new MechanicListener<>(
                            TestEvent.class, EventPriority.HIGH, false, MechanicListener.ThreadClass.REGION,
                            (event, lookup) -> order.add("mechanic")));
                }
            }));
            events.manager.registerEvent(
                    TestEvent.class, bridge, EventPriority.HIGH,
                    (listener, event) -> order.add("behavior"), host);

            staged.start();
            events.fire(new TestEvent());

            assertThat(order).containsExactly("behavior", "mechanic");
        } finally {
            HandlerList.unregisterAll(bridge);
        }
    }

    @Test
    void boundedRegistrationLeavesEveryNormalWindowFrozenAndCreatesRuntimeOnce() throws EventException {
        Registry<Mechanic> metadata = metadata();
        Registry<String> unrelated = registries.registry(new RegistryKey("test-unrelated"), String.class);
        registries.freezeAll();
        AtomicInteger runtimeCreations = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        Mechanic mechanic = mechanic(context -> {
            runtimeCreations.incrementAndGet();
            assertThat(context.plugin()).isSameAs(addon);
            assertThat(context.scheduling()).isSameAs(scheduling);
            assertThat(context.diagnostics()).isSameAs(diagnostics);
            return listening((event, lookup) -> handled.incrementAndGet());
        });

        MechanicRegistration handle = service.register(addon, PULSE, mechanic);
        installBindings("first");
        events.fire(new TestEvent());
        installBindings("second");
        events.fire(new TestEvent());

        assertThat(handle.id()).isEqualTo(PULSE);
        assertThat(metadata.isFrozen()).isTrue();
        assertThat(unrelated.isFrozen()).isTrue();
        assertThat(metadata.get(PULSE)).isPresent();
        assertThat(runtimeCreations).hasValue(1);
        assertThat(handled).hasValue(2);
        assertThat(events.subscriptions).isEqualTo(1);
        assertThatThrownBy(() -> unrelated.register(
                NamespacedId.parse("addon:unrelated"), "value", SelfDescription.of("Value", "Test"), "Addon"))
                .isInstanceOf(RegistryException.class);
        assertThatThrownBy(() -> service.register(addon, PULSE, mechanic))
                .isInstanceOf(RegistryException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> service.register(addon, NamespacedId.parse("minecraft:pulse"), mechanic))
                .isInstanceOf(RegistryException.class).hasMessageContaining("reserved");
        assertThat(runtimeCreations).hasValue(1);
        assertThat(events.listenerOwners).containsExactly(addon);
    }

    @Test
    void partialListenerFailureRollsBackOnlyItsEntryAndClosesResourcesLifo() throws EventException {
        AtomicInteger healthyCalls = new AtomicInteger();
        service.register(other, OTHER, mechanic(context ->
                listening((event, lookup) -> healthyCalls.incrementAndGet())));
        List<String> closed = new ArrayList<>();
        events.failOnSubscription = events.subscriptions + 2;
        Mechanic failing = mechanic(context -> {
            context.track(() -> closed.add("first"));
            context.track(() -> {
                closed.add("second");
                throw new IllegalStateException("close failed");
            });
            return new MechanicRuntime() {
                @Override
                public List<MechanicListener<?>> listeners() {
                    return List.of(descriptor((event, lookup) -> {}),
                            descriptor((event, lookup) -> {}));
                }

                @Override
                public void close() {
                    closed.add("runtime");
                }
            };
        });

        assertThatThrownBy(() -> service.register(addon, PULSE, failing))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("event registration failed");
        events.fire(new TestEvent());

        assertThat(metadata().get(PULSE)).isEmpty();
        assertThat(metadata().get(OTHER)).isPresent();
        assertThat(TestEvent.getHandlerList().getRegisteredListeners()).hasSize(1);
        assertThat(healthyCalls).hasValue(1);
        assertThat(closed).containsExactly("runtime", "second", "first");
        assertThat(diagnostics.recent(64)).allMatch(d -> d.code().equals(DiagnosticCodes.ACTION_FAILED));
        assertThat(diagnostics.recent(64)).extracting("message")
                .anyMatch(message -> message.toString().contains("resource close failed"));
        service.close();
        assertThat(closed).containsExactly("runtime", "second", "first");
    }

    @Test
    void failedFactoryClosesResourcesTrackedBeforeItThrows() {
        List<String> closed = new ArrayList<>();
        assertThatThrownBy(() -> service.register(addon, PULSE, mechanic(context -> {
            context.track(() -> closed.add("owned"));
            throw new IllegalArgumentException("factory failed");
        }))).isInstanceOf(IllegalArgumentException.class).hasMessage("factory failed");

        assertThat(closed).containsExactly("owned");
        assertThat(metadata().get(PULSE)).isEmpty();
        assertThat(events.subscriptions).isZero();
        assertThat(diagnostics.recent(64)).singleElement()
                .satisfies(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.ACTION_FAILED));
    }

    @Test
    void shutdownDuringFactoryAlsoClosesTheRuntimeReturnedAfterShutdown() {
        List<String> closed = new ArrayList<>();
        assertThatThrownBy(() -> service.register(addon, PULSE, mechanic(context -> {
            context.track(() -> closed.add("owned"));
            service.close();
            return new MechanicRuntime() {
                @Override
                public void close() {
                    closed.add("late-runtime");
                }
            };
        }))).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");

        assertThat(closed).containsExactly("owned", "late-runtime");
        assertThat(metadata().entries()).isEmpty();
        assertThat(events.subscriptions).isZero();
    }

    @Test
    void eventEntrySnapshotIsTypedScopedByFullIdAndStableAcrossReload() throws EventException {
        List<String> observed = new ArrayList<>();
        List<MechanicContext> contexts = new ArrayList<>();
        service.register(other, OTHER, mechanic(context -> new MechanicRuntime() {}));
        service.register(addon, PULSE, mechanic(context -> {
            contexts.add(context);
            return listening((event, lookup) -> {
                observed.add(lookup.onItem(ITEM, Binding.class).orElseThrow().value());
                assertThat(lookup.onItem(ITEM, OtherBinding.class)).isEmpty();
                assertThat(lookup.onItem((org.bukkit.inventory.ItemStack) null, Binding.class)).isEmpty();
                installBindings("after");
                observed.add(lookup.onItem(ITEM, Binding.class).orElseThrow().value());
            });
        }));
        installBindings("before");
        MechanicLookup retained = contexts.getFirst().snapshot();

        events.fire(new TestEvent());
        events.fire(new TestEvent());

        assertThat(observed).containsExactly("before", "before", "after", "after");
        assertThat(retained.onItem(ITEM, Binding.class)).contains(new Binding("before"));
        assertThat(contexts.getFirst().snapshot().onItem(ITEM, Binding.class)).contains(new Binding("after"));
        assertThat(events.subscriptions).isEqualTo(1);
    }

    @Test
    void explicitCloseIsIdempotentStopsListenersAndInvalidatesCachedFactory() throws EventException {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        List<MechanicContext> contexts = new ArrayList<>();
        MechanicRegistration handle = service.register(addon, PULSE, mechanic(context -> {
            contexts.add(context);
            context.track(released::incrementAndGet);
            return listening((event, lookup) -> calls.incrementAndGet());
        }));
        Mechanic cached = metadata().get(PULSE).orElseThrow().entry();
        assertThat(cached.parse(new MechanicConfiguration(ITEM, Map.of("value", "typed"))))
                .isEqualTo(new Binding("typed"));

        handle.close();
        handle.close();
        events.fire(new TestEvent());

        assertThat(metadata().get(PULSE)).isEmpty();
        assertThat(calls).hasValue(0);
        assertThat(released).hasValue(1);
        assertThat(TestEvent.getHandlerList().getRegisteredListeners()).isEmpty();
        assertThatThrownBy(() -> cached.parse(new MechanicConfiguration(ITEM, Map.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> contexts.getFirst().track(released::incrementAndGet))
                .isInstanceOf(IllegalStateException.class);
        assertThat(released).hasValue(2);

        service.register(addon, PULSE, mechanic(context -> new MechanicRuntime() {}));
        handle.close();
        assertThat(metadata().get(PULSE)).isPresent();
    }

    @Test
    void boundedUnregisterCannotRemoveAReplacementEntry() {
        service.register(addon, PULSE, mechanic(context -> new MechanicRuntime() {}));
        Mechanic stale = metadata().get(PULSE).orElseThrow().entry();
        assertThat(registries.unregisterBounded(
                MechanicsResolver.REGISTRY_KEY, Mechanic.class, PULSE, stale)).isTrue();
        Mechanic replacement = mechanic(context -> new MechanicRuntime() {});
        registries.registerBounded(MechanicsResolver.REGISTRY_KEY, Mechanic.class, PULSE,
                replacement, replacement.describe(), "Other");

        assertThat(registries.unregisterBounded(
                MechanicsResolver.REGISTRY_KEY, Mechanic.class, PULSE, stale)).isFalse();
        service.close();
        assertThat(metadata().get(PULSE)).hasValueSatisfying(
                registered -> assertThat(registered.entry()).isSameAs(replacement));
    }

    @Test
    void closedContextCannotReadReplacementAndNewRuntimeCannotReadOldBindings() throws EventException {
        List<MechanicContext> contexts = new ArrayList<>();
        MechanicRegistration first = service.register(addon, PULSE, mechanic(context -> {
            contexts.add(context);
            return new MechanicRuntime() {};
        }));
        installBindings("first");
        MechanicLookup retained = contexts.getFirst().snapshot();
        assertThat(retained.onItem(ITEM, Binding.class)).contains(new Binding("first"));
        first.close();

        List<Optional<Binding>> observed = new ArrayList<>();
        service.register(addon, PULSE, mechanic(context -> {
            contexts.add(context);
            return listening((event, lookup) -> observed.add(lookup.onItem(ITEM, Binding.class)));
        }));
        events.fire(new TestEvent());
        installBindings("replacement");
        events.fire(new TestEvent());

        assertThat(observed).containsExactly(Optional.empty(), Optional.of(new Binding("replacement")));
        assertThat(retained.onItem(ITEM, Binding.class)).isEmpty();
        assertThat(contexts.getFirst().snapshot().onItem(ITEM, Binding.class)).isEmpty();
        assertThat(contexts.getLast().snapshot().onItem(ITEM, Binding.class))
                .contains(new Binding("replacement"));
    }

    @Test
    void ownerDisableCleansOnlyItsRegistrationsAndShutdownClosesTheRest()
            throws EventException, ReflectiveOperationException {
        AtomicInteger addonReleased = new AtomicInteger();
        AtomicInteger otherReleased = new AtomicInteger();
        AtomicInteger otherCalls = new AtomicInteger();
        service.register(addon, PULSE, mechanic(context -> {
            context.track(addonReleased::incrementAndGet);
            return listening((event, lookup) -> {});
        }));
        service.register(other, OTHER, mechanic(context -> {
            context.track(otherReleased::incrementAndGet);
            return listening((event, lookup) -> otherCalls.incrementAndGet());
        }));

        addonEnabled.set(false);
        events.fire(new TestEvent());
        events.fireDisable(addon);
        events.fire(new TestEvent());

        assertThat(metadata().get(PULSE)).isEmpty();
        assertThat(metadata().get(OTHER)).isPresent();
        assertThat(addonReleased).hasValue(1);
        assertThat(otherReleased).hasValue(0);
        assertThat(otherCalls).hasValue(2);
        assertThat(TestEvent.getHandlerList().getRegisteredListeners()).hasSize(1);
        assertThatThrownBy(() -> service.register(addon, PULSE, mechanic(context -> new MechanicRuntime() {})))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");

        service.close();
        service.close();
        assertThat(otherReleased).hasValue(1);
        assertThat(metadata().entries()).isEmpty();
        assertThat(TestEvent.getHandlerList().getRegisteredListeners()).isEmpty();
        assertThat(PluginDisableEvent.getHandlerList().getRegisteredListeners()).isEmpty();
        assertThatThrownBy(() -> service.register(other, OTHER, mechanic(context -> new MechanicRuntime() {})))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }

    @Test
    void handlerFailuresAreIsolatedAndRateLimitedPerRegistration() throws EventException {
        AtomicInteger healthyCalls = new AtomicInteger();
        BiConsumer<TestEvent, MechanicLookup> broken = (event, lookup) -> {
            throw new IllegalArgumentException("broken addon");
        };
        service.register(addon, PULSE, mechanic(context -> listening(broken)));
        service.register(other, OTHER, mechanic(context -> listening(broken)));
        service.register(host, NamespacedId.parse("materione:healthy"), mechanic(context ->
                listening((event, lookup) -> healthyCalls.incrementAndGet())));

        events.fire(new TestEvent());
        events.fire(new TestEvent());

        assertThat(healthyCalls).hasValue(2);
        assertThat(diagnostics.recent(64)).hasSize(2)
                .allMatch(d -> d.code().equals(DiagnosticCodes.ACTION_FAILED));
        assertThat(diagnostics.recent(64)).extracting("message")
                .anyMatch(message -> message.toString().contains(PULSE.toString()))
                .anyMatch(message -> message.toString().contains(OTHER.toString()));
    }

    @Test
    void descriptorsHonorCancellationAndRejectThreadClassMismatch() throws EventException {
        AtomicInteger regionCalls = new AtomicInteger();
        AtomicInteger asyncCalls = new AtomicInteger();
        service.register(addon, PULSE, mechanic(context ->
                listening((event, lookup) -> regionCalls.incrementAndGet())));
        service.register(other, OTHER, mechanic(context -> new MechanicRuntime() {
            @Override
            public List<MechanicListener<?>> listeners() {
                return List.of(new MechanicListener<>(
                        TestEvent.class, EventPriority.NORMAL, true, MechanicListener.ThreadClass.ASYNC,
                        (event, lookup) -> asyncCalls.incrementAndGet()));
            }
        }));
        TestEvent cancelled = new TestEvent();
        cancelled.setCancelled(true);
        events.fire(cancelled);
        assertThat(regionCalls).hasValue(0);
        assertThat(asyncCalls).hasValue(0);
        assertThat(diagnostics.recent(64)).isEmpty();

        events.fire(new TestEvent());
        events.fire(new TestEvent(true));
        assertThat(regionCalls).hasValue(1);
        assertThat(asyncCalls).hasValue(1);
        assertThat(diagnostics.recent(64)).hasSize(2)
                .allMatch(d -> d.message().contains("thread mismatch"));
    }

    @Test
    void facadeExposesHostAndPreservesOldConstructorArities() {
        var wired = new MaterioneApiImpl(
                "test", registries, diagnostics, () -> null, scheduling,
                null, null, null, null, null, null, service);
        assertThat(wired.mechanics()).isSameAs(service);
        var old = new MaterioneApiImpl(
                "test", registries, diagnostics, () -> null, scheduling, null, null, null, null);
        assertThatThrownBy(old::mechanics).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mechanics");
    }

    private Registry<Mechanic> metadata() {
        return registries.registry(MechanicsResolver.REGISTRY_KEY, Mechanic.class);
    }

    private MechanicsService startedService() {
        MechanicsService result = new MechanicsService(host, diagnostics, scheduling, runtime, registries);
        result.start();
        return result;
    }

    private void installBindings(String value) {
        List<Map<String, Object>> declarations = new ArrayList<>();
        declarations.add(Map.of(PULSE.toString(), Map.of("value", value)));
        if (metadata().get(OTHER).isPresent()) {
            declarations.add(Map.of(OTHER.toString(), Map.of("value", "wrong-namespace")));
        }
        List<ActiveMechanic> active = new MechanicsResolver(Optional.of(metadata())).resolve(
                ITEM, declarations, (name, reason) -> {
                    throw new AssertionError(name + ": " + reason);
                });
        runtime.apply(RuntimeRegistries.builder()
                .view(ItemMechanics.KEY, new ItemMechanics(Map.of(ITEM, active))).build(), "");
    }

    private static Mechanic mechanic(Function<MechanicContext, MechanicRuntime> factory) {
        return new Mechanic() {
            @Override
            public String name() {
                return "pulse";
            }

            @Override
            public SelfDescription describe() {
                return SelfDescription.of("Pulse", "Test extension");
            }

            @Override
            public MechanicInstance parse(MechanicConfiguration configuration) {
                return new Binding(configuration.text("value", "default"));
            }

            @Override
            public MechanicRuntime createRuntime(MechanicContext context) {
                return factory.apply(context);
            }
        };
    }

    private static MechanicRuntime listening(BiConsumer<TestEvent, MechanicLookup> handler) {
        return new MechanicRuntime() {
            @Override
            public List<MechanicListener<?>> listeners() {
                return List.of(descriptor(handler));
            }
        };
    }

    private static MechanicListener<TestEvent> descriptor(BiConsumer<TestEvent, MechanicLookup> handler) {
        return new MechanicListener<>(
                TestEvent.class, EventPriority.NORMAL, true, MechanicListener.ThreadClass.REGION, handler);
    }

    private record Binding(String value) implements MechanicInstance {}
    private record OtherBinding() implements MechanicInstance {}

    public static final class TestEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean cancelled;

        TestEvent() {
            this(false);
        }

        TestEvent(boolean asynchronous) {
            super(asynchronous);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** Real Bukkit handler lists and cancellation semantics, without starting a server. */
    private static final class EventHost {
        private int subscriptions;
        private int failOnSubscription = -1;
        private final List<Plugin> listenerOwners = new ArrayList<>();
        private final PluginManager manager = proxy(PluginManager.class, (instance, method, arguments) -> {
            if (method.getName().equals("registerEvent")) {
                Listener listener = Listener.class.cast(arguments[1]);
                EventPriority priority = EventPriority.class.cast(arguments[2]);
                EventExecutor executor = EventExecutor.class.cast(arguments[3]);
                Plugin owner = Plugin.class.cast(arguments[4]);
                boolean ignoreCancelled = arguments.length == 6 && Boolean.TRUE.equals(arguments[5]);
                HandlerList handlers;
                if (arguments[0] == PluginDisableEvent.class) {
                    handlers = PluginDisableEvent.getHandlerList();
                } else if (arguments[0] == TestEvent.class) {
                    handlers = TestEvent.getHandlerList();
                    subscriptions++;
                    listenerOwners.add(owner);
                } else {
                    throw new AssertionError("unexpected test event " + arguments[0]);
                }
                handlers.register(new RegisteredListener(listener, executor, priority, owner, ignoreCancelled));
                if (arguments[0] == TestEvent.class && subscriptions == failOnSubscription) {
                    throw new IllegalStateException("event registration failed");
                }
                return null;
            }
            return objectMethod(instance, method.getName(), arguments);
        });
        private final Server server = proxy(Server.class, (instance, method, arguments) -> {
            if (method.getName().equals("getPluginManager")) {
                return manager;
            }
            if (method.getName().equals("isPrimaryThread")) {
                return true;
            }
            return objectMethod(instance, method.getName(), arguments);
        });

        Plugin plugin(String name, AtomicBoolean enabled) {
            return proxy(Plugin.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getName" -> name;
                case "isEnabled" -> enabled.get();
                case "getServer" -> server;
                default -> objectMethod(instance, method.getName(), arguments);
            });
        }

        void fire(Event event) throws EventException {
            for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) {
                if (listener.getPlugin().isEnabled()) {
                    listener.callEvent(event);
                }
            }
        }

        void fireDisable(Plugin owner) throws EventException, ReflectiveOperationException {
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            Object previous = serverField.get(null);
            try {
                serverField.set(null, server);
                fire(new PluginDisableEvent(owner));
            } finally {
                serverField.set(null, previous);
            }
        }

        private static Object objectMethod(Object instance, String name, Object[] arguments) {
            return switch (name) {
                case "equals" -> instance == arguments[0];
                case "hashCode" -> System.identityHashCode(instance);
                case "toString" -> "test " + instance.getClass().getInterfaces()[0].getSimpleName();
                default -> throw new UnsupportedOperationException(name);
            };
        }

        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }
    }

    private static final class DirectScheduling implements SchedulingGateway {
        @Override
        public void executeSync(Plugin plugin, Location location, Runnable task) {
            task.run();
        }

        @Override
        public void executeEntity(Plugin plugin, Entity entity, Runnable task) {
            task.run();
        }

        @Override
        public void executeAsync(Plugin plugin, Runnable task) {
            task.run();
        }

        @Override
        public void executeGlobal(Plugin plugin, Runnable task) {
            task.run();
        }
    }
}
