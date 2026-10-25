package dev.materione.plugin.runtime.mechanics.builtin;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.mechanics.ItemModifier;
import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.mechanics.MechanicConfiguration;
import dev.materione.api.mechanics.MechanicContext;
import dev.materione.api.mechanics.MechanicInstance;
import dev.materione.api.mechanics.MechanicListener;
import dev.materione.api.mechanics.MechanicListener.ThreadClass;
import dev.materione.api.mechanics.MechanicLookup;
import dev.materione.api.mechanics.MechanicRuntime;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.platform.items.FoodItemModifier;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Food components are stack hooks; consume-side effects belong to this runtime. */
public final class FoodMechanic implements Mechanic {

    public record Effect(NamespacedId type, int amplifier, int durationTicks) {}

    public record Instance(
            int nutrition, float saturation, boolean canAlwaysEat, List<Effect> effects)
            implements MechanicInstance {
        public Instance {
            effects = List.copyOf(effects);
        }

        @Override
        public List<ItemModifier> itemModifiers() {
            return List.of(new FoodItemModifier(nutrition, saturation, canAlwaysEat));
        }
    }

    @Override
    public String name() {
        return "food";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Food",
                "Custom nutrition, saturation and consume effects",
                List.of(
                        new ParameterSchema("nutrition", ParameterType.INTEGER, true, null,
                                "Hunger points restored (0..20)", null, null),
                        new ParameterSchema("saturation", ParameterType.DECIMAL, true, null,
                                "Saturation restored (0..40)", null, null),
                        new ParameterSchema("can_always_eat", ParameterType.BOOLEAN, false, "false",
                                "Edible even at full hunger", null, null),
                        new ParameterSchema("effects", ParameterType.LIST, false, null,
                                "Potion effects on consume: {type, amplifier?, duration_ticks?}", null, null)),
                "2.0.0");
    }

    @Override
    public Instance parse(MechanicConfiguration configuration) {
        int nutrition = configuration.integer("nutrition", -1, 0, 20);
        float saturation = (float) configuration.decimal("saturation", -1, 0, 40);
        boolean canAlwaysEat = configuration.bool("can_always_eat", false);
        List<Effect> effects = configuration.sections("effects").stream()
                .map(effect -> new Effect(
                        effectType(effect.text("type", "")),
                        effect.integer("amplifier", 0, 0, Integer.MAX_VALUE),
                        effect.integer("duration_ticks", 200, 0, Integer.MAX_VALUE)))
                .toList();
        return new Instance(nutrition, saturation, canAlwaysEat, effects);
    }

    private static NamespacedId effectType(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return NamespacedId.parse(normalized.contains(":") ? normalized : "minecraft:" + normalized);
    }

    @Override
    public MechanicRuntime createRuntime(MechanicContext context) {
        return new Runtime(context);
    }

    private static final class Runtime implements MechanicRuntime {
        private final MechanicContext context;
        private final AtomicLong lastWarningNanos = new AtomicLong();

        private Runtime(MechanicContext context) {
            this.context = context;
        }

        @Override
        public List<MechanicListener<?>> listeners() {
            return List.of(new MechanicListener<>(
                    PlayerItemConsumeEvent.class, EventPriority.MONITOR, true, ThreadClass.REGION,
                    this::onConsume));
        }

        private void onConsume(PlayerItemConsumeEvent event, MechanicLookup lookup) {
            Instance instance = lookup.onItem(event.getItem(), Instance.class).orElse(null);
            if (instance == null || instance.effects().isEmpty()) {
                return;
            }
            Player player = event.getPlayer();
            context.scheduling().executeEntity(
                    context.plugin(), player, () -> applyEffects(player, instance.effects()));
        }

        private void applyEffects(Player player, List<Effect> effects) {
            for (Effect effect : effects) {
                NamespacedId id = effect.type();
                PotionEffectType type = org.bukkit.Registry.POTION_EFFECT_TYPE.get(
                        new NamespacedKey(id.namespace(), id.value()));
                if (type == null) {
                    warnLimited("food effect type '" + id + "' is not a potion type");
                    continue;
                }
                player.addPotionEffect(new PotionEffect(type, effect.durationTicks(), effect.amplifier()));
            }
        }

        private void warnLimited(String message) {
            long now = System.nanoTime();
            long last = lastWarningNanos.get();
            if (now - last < 1_000_000_000L || !lastWarningNanos.compareAndSet(last, now)) {
                return;
            }
            context.diagnostics().publish(new Diagnostic(
                    DiagnosticCodes.ACTION_FAILED, Severity.WARN, message, null, null,
                    "repeated occurrences are suppressed for one second"));
        }
    }
}
