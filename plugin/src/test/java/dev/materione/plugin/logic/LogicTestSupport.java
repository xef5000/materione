package dev.materione.plugin.logic;

import dev.materione.api.logic.TriggerType;
import dev.materione.behavior.logic.BuiltinTriggers;
import dev.materione.core.registry.RegistryService;
import java.lang.reflect.Proxy;
import java.util.function.BiFunction;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** Headless logic services for tests: a real {@link LogicService} over plugin stubs, no server. */
public final class LogicTestSupport {

    private LogicTestSupport() {}

    /** A logic service over {@code registries}, with no vocabulary registered yet. */
    public static LogicService newService(RegistryService registries) {
        return new LogicService(host(), registries);
    }

    /** A logic service with Materione's own triggers and actions registered, as the plugin does at boot. */
    public static LogicService withBuiltIns(RegistryService registries) {
        LogicService service = newService(registries);
        BuiltinLogic.register(service, owner("materione"), dev.materione.plugin.logic.support.ItemCatalog.none());
        return service;
    }

    /** Registers only the built-in triggers (for tests that bring their own actions). */
    public static void registerTriggers(LogicService service) {
        Plugin owner = owner("materione");
        for (TriggerType trigger : BuiltinTriggers.all()) {
            service.triggers().register(owner,
                    dev.materione.api.NamespacedId.of("materione", trigger.id()), trigger);
        }
    }

    /** An enabled plugin stub that answers only its name and enabled state. */
    public static Plugin owner(String name) {
        return plugin(name, true, null);
    }

    /** A plugin stub that is disabled (its registrations must be refused). */
    public static Plugin disabledOwner(String name) {
        return plugin(name, false, null);
    }

    private static Plugin host() {
        PluginManager manager = proxy(PluginManager.class, (method, args) -> switch (method.getName()) {
            case "registerEvents" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Server server = proxy(Server.class, (method, args) -> switch (method.getName()) {
            case "getPluginManager" -> manager;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return plugin("host", true, server);
    }

    private static Plugin plugin(String name, boolean enabled, Server server) {
        return proxy(Plugin.class, (method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "isEnabled" -> enabled;
            case "getServer" -> server;
            case "hashCode" -> name.hashCode();
            case "toString" -> "plugin " + name;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, BiFunction<java.lang.reflect.Method, Object[], Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> type.getSimpleName() + "@"
                                    + Integer.toHexString(System.identityHashCode(proxy));
                            default -> throw new AssertionError(method.getName());
                        };
                    }
                    return answers.apply(method, args);
                }));
    }
}
