package dev.materione.plugin.runtime.mechanics;

import dev.materione.api.NamespacedId;
import dev.materione.api.mechanics.ItemModifier;
import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.mechanics.MechanicConfiguration;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryKey;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.SelfDescription;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Pure per-item compilation; no built-in names or gameplay dispatch live here. */
public final class MechanicsResolver {
    public static final RegistryKey REGISTRY_KEY = RegistryKeys.MECHANICS;

    private final Optional<Registry<Mechanic>> registry;

    public MechanicsResolver(Optional<Registry<Mechanic>> registry) {
        this.registry = registry;
    }

    public List<ActiveMechanic> resolve(
            NamespacedId itemId,
            Object declarations,
            BiConsumer<String, String> reject) {
        var active = new ArrayList<ActiveMechanic>();
        var seen = new HashSet<NamespacedId>();
        if (declarations == null) {
            return List.of();
        }
        if (!(declarations instanceof List<?> list)) {
            reject.accept("mechanics", "mechanics must be a list of mappings");
            return List.of();
        }
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> declaration)) {
                reject.accept("mechanics", "each mechanic declaration must be a mapping");
                continue;
            }
            for (var entry : declaration.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    reject.accept("mechanics", "mechanic names must be strings");
                    continue;
                }
                try {
                    NamespacedId id = name.contains(":")
                            ? NamespacedId.parse(name) : NamespacedId.of("materione", name);
                    var registered = registry.flatMap(entries -> entries.get(id))
                            .orElseThrow(() -> new IllegalArgumentException("unknown mechanic '" + id + "'"));
                    Mechanic factory = registered.entry();
                    if (seen.contains(id) && !factory.repeatable()) {
                        throw new IllegalArgumentException("duplicate mechanic declaration '" + id + "'");
                    }
                    Map<String, Object> params = validateSchema(
                            parameters(entry.getValue()), registered.description());
                    var configuration = new MechanicConfiguration(itemId, params);
                    BooleanSupplier available = () -> registry.flatMap(entries -> entries.get(id))
                            .map(current -> current.entry() == factory).orElse(false);
                    var instance = Objects.requireNonNull(factory.parse(configuration), "mechanic parser returned null");
                    var rules = new ArrayList<Map<String, Object>>();
                    for (Map<String, Object> rule : instance.behaviorRules()) {
                        rules.add(new MechanicConfiguration(itemId, rule).parameters());
                    }
                    var modifiers = new ArrayList<ItemModifier>();
                    for (ItemModifier modifier : instance.itemModifiers()) {
                        Objects.requireNonNull(modifier, "item modifier");
                        AtomicLong lastFailure = new AtomicLong();
                        modifiers.add(stack -> {
                            // Old stack specs cannot execute a disabled/replaced extension.
                            if (!available.getAsBoolean()) {
                                return;
                            }
                            try {
                                var candidate = stack.clone();
                                modifier.apply(candidate);
                                if (candidate.getType() != stack.getType()
                                        || candidate.getAmount() != stack.getAmount()) {
                                    throw new IllegalArgumentException("item modifiers must preserve material and amount");
                                }
                                var identity = dev.materione.platform.items.MaterioneStacks.identityOf(stack);
                                if (!identity.equals(dev.materione.platform.items.MaterioneStacks.identityOf(candidate))) {
                                    throw new IllegalArgumentException("item modifiers must preserve Materione identity");
                                }
                                stack.setItemMeta(candidate.getItemMeta());
                            } catch (RuntimeException failure) {
                                long now = System.nanoTime();
                                long previous = lastFailure.get();
                                if ((previous == 0 || now - previous >= 1_000_000_000L)
                                        && lastFailure.compareAndSet(previous, now)) {
                                    reject.accept(name, "item modifier failed: " + failure);
                                }
                            }
                        });
                    }
                    active.add(new ActiveMechanic(id, configuration.parameters(), instance,
                            rules, modifiers, available, registered.generation()));
                    seen.add(id);
                } catch (RuntimeException failure) {
                    // Extension parsing/contribution construction is a containment boundary.
                    reject.accept(name, failure.getMessage() == null ? failure.toString() : failure.getMessage());
                }
            }
        }
        return List.copyOf(active);
    }

    private static Map<String, Object> parameters(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> mapping)) {
            throw new IllegalArgumentException("parameters must be a mapping");
        }
        var params = new LinkedHashMap<String, Object>();
        for (var entry : mapping.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("parameter names must be strings");
            }
            params.put(key, entry.getValue());
        }
        return params;
    }

    private static Map<String, Object> validateSchema(Map<String, Object> raw, SelfDescription schema) {
        var params = new LinkedHashMap<>(raw);
        for (ParameterSchema field : schema.parameters()) {
            if (!params.containsKey(field.id())) {
                if (field.defaultValue() != null) {
                    params.put(field.id(), defaultValue(field));
                } else if (field.required()) {
                    throw new IllegalArgumentException("missing required parameter '" + field.id() + "'");
                } else {
                    continue;
                }
            }
            Object value = params.get(field.id());
            boolean valid = switch (field.type()) {
                case STRING, REFERENCE -> value instanceof String;
                case ENUM -> value instanceof String text && field.allowedValues().contains(text);
                case BOOLEAN -> value instanceof Boolean;
                case INTEGER -> value instanceof Number number && Double.isFinite(number.doubleValue())
                        && number.doubleValue() == Math.rint(number.doubleValue());
                case DECIMAL -> value instanceof Number number && Double.isFinite(number.doubleValue());
                case LIST -> value instanceof List<?>;
                case RAW -> true;
            };
            if (!valid) {
                throw new IllegalArgumentException(field.id() + " must match schema type " + field.type());
            }
            if (value instanceof List<?> children && !field.nested().isEmpty()) {
                var validated = new ArrayList<Map<String, Object>>();
                for (Object child : children) {
                    validated.add(validateSchema(parameters(child),
                            new SelfDescription(field.id(), "", field.nested(), "")));
                }
                params.put(field.id(), validated);
            }
        }
        return params;
    }

    private static Object defaultValue(ParameterSchema field) {
        String text = field.defaultValue();
        return switch (field.type()) {
            case INTEGER -> Long.valueOf(text);
            case DECIMAL -> Double.valueOf(text);
            case BOOLEAN -> {
                if (!"true".equals(text) && !"false".equals(text)) {
                    throw new IllegalArgumentException("invalid boolean schema default for " + field.id());
                }
                yield Boolean.valueOf(text);
            }
            case STRING, ENUM, REFERENCE, RAW -> text;
            case LIST -> throw new IllegalArgumentException("list schema defaults are not supported: " + field.id());
        };
    }
}
