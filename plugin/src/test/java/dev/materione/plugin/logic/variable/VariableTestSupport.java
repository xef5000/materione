package dev.materione.plugin.logic.variable;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.core.runtime.RuntimeVariables;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;

/** Shared fixtures for the variable tests: declarations, a fake data container, and a wired service. */
public final class VariableTestSupport {

    public static final NamespacedId COINS = NamespacedId.of("shop", "coins");
    public static final NamespacedId CLAIMED = NamespacedId.of("shop", "claimed");
    public static final NamespacedId SEASON = NamespacedId.of("shop", "season");

    public static final VariableDeclaration COINS_DECL =
            new VariableDeclaration(COINS, VariableScope.PLAYER, VariableType.NUMBER, 0, "Shop coins");
    public static final VariableDeclaration CLAIMED_DECL =
            new VariableDeclaration(CLAIMED, VariableScope.PLAYER, VariableType.BOOLEAN, false, null);
    public static final VariableDeclaration SEASON_DECL =
            new VariableDeclaration(SEASON, VariableScope.SERVER, VariableType.STRING, "spring", null);

    private VariableTestSupport() {}

    /** The declarations of the shop pack, as a shadow would hold them. */
    public static RuntimeVariables declarations() {
        var map = new LinkedHashMap<NamespacedId, VariableDeclaration>();
        for (VariableDeclaration declaration : List.of(COINS_DECL, CLAIMED_DECL, SEASON_DECL)) {
            map.put(declaration.id(), declaration);
        }
        return new RuntimeVariables(map);
    }

    /**
     * A data container that behaves like Bukkit's for the calls the store makes: values are kept per key,
     * whatever the type they were written with.
     */
    public static PersistentDataContainer fakeContainer() {
        Map<String, Object> values = new HashMap<>();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "get" -> values.get(((NamespacedKey) args[0]).toString());
            case "set" -> {
                values.put(((NamespacedKey) args[0]).toString(), args[2]);
                yield null;
            }
            case "remove" -> {
                values.remove(((NamespacedKey) args[0]).toString());
                yield null;
            }
            case "has" -> values.containsKey(((NamespacedKey) args[0]).toString());
            case "isEmpty" -> values.isEmpty();
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (PersistentDataContainer) Proxy.newProxyInstance(
                VariableTestSupport.class.getClassLoader(),
                new Class<?>[] {PersistentDataContainer.class},
                handler);
    }

    /** A service over a fake player container map and a server file under {@code dir}. */
    public static Wired wired(Path dir, Map<UUID, PersistentDataContainer> containers) {
        var diagnostics = new ArrayList<Diagnostic>();
        var server = new ServerVariableStore(dir.resolve("variables.json"), warning -> { });
        var service = new VariableService(
                new PdcVariableStore(uuid -> Optional.ofNullable(containers.get(uuid))),
                server,
                VariableTestSupport::declarations,
                diagnostics::add);
        return new Wired(service, server, diagnostics);
    }

    /** The parts of a wired service a test reads. */
    public record Wired(VariableService service, ServerVariableStore server, List<Diagnostic> diagnostics) {}

    /** The stored key of a declaration, for asserting on the container directly. */
    public static String keyOf(VariableDeclaration declaration) {
        return PdcVariableStore.keyOf(declaration).toString();
    }

    /** A declaration map for the compile environment of a test. */
    public static Map<NamespacedId, VariableDeclaration> declared() {
        return Map.of(COINS, COINS_DECL, CLAIMED, CLAIMED_DECL, SEASON, SEASON_DECL);
    }
}
