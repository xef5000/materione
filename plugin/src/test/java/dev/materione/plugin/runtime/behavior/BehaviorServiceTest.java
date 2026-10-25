package dev.materione.plugin.runtime.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.behavior.expr.EvaluationContext;
import dev.materione.behavior.interp.Template;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

class BehaviorServiceTest {

    private static final NamespacedKey USED = new NamespacedKey("materione", "durability-used");
    private static final NamespacedKey MAX = new NamespacedKey("materione", "durability-max");
    private static final String FIXTURE_MESSAGE =
            "blade-wear ${item.durability_used}/${item.durability_max}";

    @Test
    void fixtureInterpolationGetsCountersAfterTheFirstDurabilityUse() {
        Map<NamespacedKey, Object> data = new HashMap<>();
        var pdc = proxy(PersistentDataContainer.class, (target, method, args) -> switch (method.getName()) {
            case "get" -> data.get(args[0]);
            default -> throw new AssertionError("unexpected PDC call: " + method.getName());
        });

        var freshVars = new HashMap<String, Object>();
        BehaviorService.addDurabilityContext(freshVars, pdc);
        var freshContext = new EvaluationContext(Map.of("item", freshVars));
        assertThat(Template.render(FIXTURE_MESSAGE, freshContext)).isEqualTo("blade-wear /");

        data.put(USED, 1);
        data.put(MAX, 3);
        var wornVars = new HashMap<String, Object>();
        BehaviorService.addDurabilityContext(wornVars, pdc);
        var wornContext = new EvaluationContext(Map.of("item", wornVars));
        assertThat(Template.render(FIXTURE_MESSAGE, wornContext)).isEqualTo("blade-wear 1/3");
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (target, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> target == args[0];
                            case "hashCode" -> System.identityHashCode(target);
                            case "toString" -> type.getSimpleName() + "@"
                                    + Integer.toHexString(System.identityHashCode(target));
                            default -> throw new AssertionError("unexpected Object method: " + method.getName());
                        };
                    }
                    return handler.invoke(target, method, args);
                }));
    }

}
