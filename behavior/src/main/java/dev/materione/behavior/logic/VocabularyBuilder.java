package dev.materione.behavior.logic;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.api.registry.RegistryKey;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.RegistryView;
import dev.materione.behavior.rule.RuleParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles the version-2 {@link BehaviorVocabulary} from the registries (logic.md §5.4, D5): the
 * triggers, actions, conditions, functions and mechanics are read from where they are registered,
 * so the editor never keeps a second vocabulary. Pure and fail-soft: a type whose description or
 * availability check throws is listed from its registry entry alone (or as unavailable) rather
 * than failing the whole describe call.
 *
 * <p>Ordering is stable: triggers in registration order (the order the editor shows), everything
 * else sorted by authored id.
 */
public final class VocabularyBuilder {

    private VocabularyBuilder() {}

    /** Builds the vocabulary as the registries stand right now. */
    public static BehaviorVocabulary build(RegistryView registries) {
        var triggers = triggers(registries);
        var actions = actions(registries);
        var conditions = conditions(registries);
        return new BehaviorVocabulary(
                triggers.stream().map(VocabularyBuilder::eventOf).toList(),
                actions,
                RuleParser.RULE_OPTIONS,
                RuleParser.ACTION_OPTIONS,
                mechanics(registries),
                triggers,
                conditions,
                StepKinds.all(),
                categories(actions, conditions),
                functions(registries));
    }

    private static List<BehaviorVocabulary.Trigger> triggers(RegistryView registries) {
        var out = new ArrayList<BehaviorVocabulary.Trigger>();
        for (RegistryEntry<?> entry : entries(registries, RegistryKeys.TRIGGERS)) {
            if (!(entry.entry() instanceof TriggerType type)) {
                continue;
            }
            TriggerDescription description;
            try {
                description = type.describe();
            } catch (RuntimeException e) {
                description = new TriggerDescription(entry.description(), List.of(), List.of(), List.of(), List.of(), false);
            }
            out.add(new BehaviorVocabulary.Trigger(LogicIds.display(entry.id()), description, entry.registeredBy()));
        }
        return List.copyOf(out);
    }

    private static BehaviorVocabulary.Event eventOf(BehaviorVocabulary.Trigger trigger) {
        var self = trigger.description().self();
        return new BehaviorVocabulary.Event(trigger.id(), self.displayName(), self.description(),
                trigger.description().details(), trigger.description().firedBy());
    }

    private static List<BehaviorVocabulary.Action> actions(RegistryView registries) {
        var out = new ArrayList<BehaviorVocabulary.Action>();
        for (RegistryEntry<?> entry : entries(registries, RegistryKeys.ACTIONS)) {
            if (!(entry.entry() instanceof ActionType<?> type)) {
                continue;
            }
            String id = LogicIds.display(entry.id());
            Optional<ActionDescription> description = attempt(type::describe);
            Threading threading = attempt(type::threading).orElse(null);
            out.add(new BehaviorVocabulary.Action(
                    id,
                    description.map(ActionDescription::self).orElse(entry.description()),
                    description.map(ActionDescription::shorthand).orElse(null),
                    threading == Threading.GLOBAL,
                    description.map(ActionDescription::category).orElse(null),
                    threading,
                    availability(type::availability),
                    entry.registeredBy()));
        }
        out.sort(Comparator.comparing(BehaviorVocabulary.Action::id));
        return List.copyOf(out);
    }

    private static List<BehaviorVocabulary.Condition> conditions(RegistryView registries) {
        var out = new ArrayList<BehaviorVocabulary.Condition>();
        for (RegistryEntry<?> entry : entries(registries, RegistryKeys.CONDITIONS)) {
            if (!(entry.entry() instanceof ConditionType<?> type)) {
                continue;
            }
            ConditionDescription description = attempt(type::describe).orElseGet(
                    () -> ConditionDescription.of(entry.description(), LogicCategory.DATA));
            out.add(new BehaviorVocabulary.Condition(
                    LogicIds.display(entry.id()), description, availability(type::availability),
                    entry.registeredBy()));
        }
        out.sort(Comparator.comparing(BehaviorVocabulary.Condition::id));
        return List.copyOf(out);
    }

    private static List<BehaviorVocabulary.Function> functions(RegistryView registries) {
        var out = new ArrayList<BehaviorVocabulary.Function>();
        for (RegistryEntry<?> entry : entries(registries, RegistryKeys.EXPRESSION_FUNCTIONS)) {
            if (!(entry.entry() instanceof ExpressionFunction function)) {
                continue;
            }
            attempt(function::describe).ifPresent(description -> out.add(new BehaviorVocabulary.Function(
                    LogicIds.display(entry.id()), description, entry.registeredBy())));
        }
        out.sort(Comparator.comparing(BehaviorVocabulary.Function::id));
        return List.copyOf(out);
    }

    private static List<BehaviorVocabulary.Mechanic> mechanics(RegistryView registries) {
        var out = new ArrayList<BehaviorVocabulary.Mechanic>();
        for (RegistryEntry<?> entry : entries(registries, RegistryKeys.MECHANICS)) {
            boolean repeatable = entry.entry() instanceof Mechanic mechanic && mechanic.repeatable();
            out.add(new BehaviorVocabulary.Mechanic(
                    entry.id().toString(), entry.description(), repeatable, entry.registeredBy()));
        }
        out.sort(Comparator.comparing(BehaviorVocabulary.Mechanic::id));
        return List.copyOf(out);
    }

    /** The eight built-in categories, then any category an addon's action or condition introduced. */
    private static List<LogicCategory> categories(
            List<BehaviorVocabulary.Action> actions, List<BehaviorVocabulary.Condition> conditions) {
        Set<LogicCategory> addon = new LinkedHashSet<>();
        actions.stream().map(BehaviorVocabulary.Action::category).forEach(category -> {
            if (category != null && !category.isBuiltIn()) {
                addon.add(category);
            }
        });
        conditions.forEach(condition -> {
            LogicCategory category = condition.description().category();
            if (!category.isBuiltIn()) {
                addon.add(category);
            }
        });
        var out = new ArrayList<>(LogicCategory.builtIn());
        addon.stream().sorted(Comparator.comparing(LogicCategory::id)).forEach(out::add);
        return List.copyOf(out);
    }

    private static List<RegistryEntry<?>> entries(RegistryView registries, RegistryKey key) {
        var out = new ArrayList<RegistryEntry<?>>();
        Optional<Registry<?>> registry = registries.byKey(key);
        registry.ifPresent(found -> out.addAll(found.entries()));
        return out;
    }

    private static Availability availability(java.util.function.Supplier<Availability> check) {
        try {
            Availability result = check.get();
            return result == null ? Availability.AVAILABLE : result;
        } catch (RuntimeException e) {
            return Availability.unavailable("the availability check failed: " + e.getMessage());
        }
    }

    private static <T> Optional<T> attempt(java.util.function.Supplier<T> read) {
        try {
            return Optional.ofNullable(read.get());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
