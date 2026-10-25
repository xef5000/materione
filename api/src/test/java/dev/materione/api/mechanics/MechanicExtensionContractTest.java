package dev.materione.api.mechanics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Map;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.junit.jupiter.api.Test;

/** Compiles a complete extension where only api and Paper are available. */
class MechanicExtensionContractTest {
    @Test
    void anApiOnlyFactoryCanDeclareBothNativeListenersAndSharedEca() {
        Mechanic extension = new Example();
        var binding = extension.parse(new MechanicConfiguration(
                NamespacedId.parse("pack:wand"), Map.of("message", "Hello")));
        MechanicRuntime runtime = extension.createRuntime(null);

        assertThat(binding).isEqualTo(new Greeting("Hello"));
        assertThat(binding.behaviorRules()).containsExactly(Map.of(
                "on", "click:right", "do", List.of(Map.of("message", "Hello"))));
        assertThat(runtime.listeners()).singleElement().satisfies(listener -> {
            assertThat(listener.eventType()).isEqualTo(PlayerItemDamageEvent.class);
            assertThat(listener.thread()).isEqualTo(MechanicListener.ThreadClass.REGION);
            assertThat(listener.ignoreCancelled()).isTrue();
        });
        runtime.close();
    }

    private record Greeting(String message) implements MechanicInstance {
        @Override
        public List<Map<String, Object>> behaviorRules() {
            return List.of(Map.of("on", "click:right", "do", List.of(Map.of("message", message))));
        }
    }

    private static final class Example implements Mechanic {
        @Override
        public String name() {
            return "greeting";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Greeting", "", List.of(
                    ParameterSchema.of("message", ParameterType.STRING, true)), "1");
        }

        @Override
        public MechanicInstance parse(MechanicConfiguration configuration) {
            return new Greeting(configuration.text("message", ""));
        }

        @Override
        public MechanicRuntime createRuntime(MechanicContext context) {
            return new MechanicRuntime() {
                @Override
                public List<MechanicListener<?>> listeners() {
                    return List.of(new MechanicListener<>(PlayerItemDamageEvent.class,
                            EventPriority.HIGH, true, MechanicListener.ThreadClass.REGION,
                            (event, lookup) -> lookup.onItem(event.getItem(), Greeting.class)
                                    .ifPresent(ignored -> event.setCancelled(true))));
                }
            };
        }
    }
}
