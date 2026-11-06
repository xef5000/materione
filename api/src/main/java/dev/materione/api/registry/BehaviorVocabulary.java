package dev.materione.api.registry;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.InputMethodDescription;
import dev.materione.api.logic.ItemSourceDescription;
import dev.materione.api.logic.ListSourceDescription;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.RecipeDescription;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.TriggerDescription;
import java.util.List;
import java.util.Objects;

/**
 * The self-description of the one behavior system (D4) as the editor sees it: which events fire
 * for which content types, which actions, conditions and mechanics are registered, and the rule
 * options every rule may carry. Each piece is read from where it is registered (the trigger,
 * action, condition and function registries, the mechanics registry), so the editor never keeps a
 * second vocabulary.
 *
 * <p><b>Version 2 (logic.md §5.4).</b> The version-1 members ({@code events}, {@code actions},
 * {@code ruleOptions}, {@code actionOptions}, {@code mechanics}) keep their shape, so a consumer
 * written against version 1 reads a version-2 vocabulary unchanged. Version 2 adds, additively:
 * {@code triggers} (the richer form of {@code events}: filters, context schema, cancellability),
 * {@code conditions}, {@code steps}, {@code categories} and {@code functions}, and extends each
 * action with its category, threading, availability and owner. Provider availability is carried on
 * the entries it affects (an action or condition that needs an absent provider is listed with
 * {@code available = false} and the reason).
 *
 * @param events      the event taxonomy, including events nothing fires yet (version 1 view of {@code triggers})
 * @param actions     registered actions; an entry without a description still appears
 * @param ruleOptions rule-level keys besides {@code on}/{@code if}/{@code do}
 * @param actionOptions keys any action map may carry besides its type key
 * @param mechanics   registered mechanics (built-in and addon)
 * @param triggers    registered triggers, in registration order
 * @param conditions  registered conditions, sorted by id
 * @param steps       the engine-owned step kinds, supported or not
 * @param categories  every palette category in use: the eight built-ins first, then addon-added ones
 * @param functions   registered expression functions, sorted by id
 * @param recipes     registered recipes, sorted by id (logic.md §4.6, §30)
 * @param listSources registered list sources, sorted by id (logic.md §4.5, §28, §31)
 * @param itemSources registered item sources, sorted by id (logic.md §4.4, §31)
 * @param inputMethods registered input methods of the {@code prompt} step, sorted by id (logic.md §4.5, §29, §31)
 */
public record BehaviorVocabulary(
        List<Event> events,
        List<Action> actions,
        List<ParameterSchema> ruleOptions,
        List<ParameterSchema> actionOptions,
        List<Mechanic> mechanics,
        List<Trigger> triggers,
        List<Condition> conditions,
        List<StepKind> steps,
        List<LogicCategory> categories,
        List<Function> functions,
        List<Recipe> recipes,
        List<ListSourceEntry> listSources,
        List<ItemSourceEntry> itemSources,
        List<InputMethodEntry> inputMethods) {

    /** The vocabulary schema version this record models. */
    public static final int VERSION = 2;

    public BehaviorVocabulary {
        events = events == null ? List.of() : List.copyOf(events);
        actions = actions == null ? List.of() : List.copyOf(actions);
        ruleOptions = ruleOptions == null ? List.of() : List.copyOf(ruleOptions);
        actionOptions = actionOptions == null ? List.of() : List.copyOf(actionOptions);
        mechanics = mechanics == null ? List.of() : List.copyOf(mechanics);
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        steps = steps == null ? List.of() : List.copyOf(steps);
        categories = categories == null ? List.of() : List.copyOf(categories);
        functions = functions == null ? List.of() : List.copyOf(functions);
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
        listSources = listSources == null ? List.of() : List.copyOf(listSources);
        itemSources = itemSources == null ? List.of() : List.copyOf(itemSources);
        inputMethods = inputMethods == null ? List.of() : List.copyOf(inputMethods);
    }

    /** The shape before the source and method registries (logic.md §31): the same members, without them. */
    public BehaviorVocabulary(
            List<Event> events,
            List<Action> actions,
            List<ParameterSchema> ruleOptions,
            List<ParameterSchema> actionOptions,
            List<Mechanic> mechanics,
            List<Trigger> triggers,
            List<Condition> conditions,
            List<StepKind> steps,
            List<LogicCategory> categories,
            List<Function> functions,
            List<Recipe> recipes) {
        this(events, actions, ruleOptions, actionOptions, mechanics, triggers, conditions, steps, categories,
                functions, recipes, List.of(), List.of(), List.of());
    }

    /** The shape before recipes (logic.md §30): the same members, without {@code recipes}. */
    public BehaviorVocabulary(
            List<Event> events,
            List<Action> actions,
            List<ParameterSchema> ruleOptions,
            List<ParameterSchema> actionOptions,
            List<Mechanic> mechanics,
            List<Trigger> triggers,
            List<Condition> conditions,
            List<StepKind> steps,
            List<LogicCategory> categories,
            List<Function> functions) {
        this(events, actions, ruleOptions, actionOptions, mechanics, triggers, conditions, steps, categories,
                functions, List.of(), List.of(), List.of(), List.of());
    }

    /** The version-1 shape: no triggers, conditions, steps, categories, functions or recipes. */
    public BehaviorVocabulary(
            List<Event> events,
            List<Action> actions,
            List<ParameterSchema> ruleOptions,
            List<ParameterSchema> actionOptions,
            List<Mechanic> mechanics) {
        this(events, actions, ruleOptions, actionOptions, mechanics,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public static BehaviorVocabulary empty() {
        return new BehaviorVocabulary(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public BehaviorVocabulary withMechanics(List<Mechanic> registered) {
        return new BehaviorVocabulary(events, actions, ruleOptions, actionOptions, registered,
                triggers, conditions, steps, categories, functions, recipes, listSources, itemSources, inputMethods);
    }

    /**
     * One event type of the taxonomy (the version-1 view of a {@link Trigger}).
     *
     * @param id          the selector type ({@code click})
     * @param displayName short human name
     * @param description what happens in game when it fires
     * @param details     selector details the runtime fires ({@code right}, {@code left}); empty when none
     * @param firedBy     content type names whose runtime fires this event; empty when nothing fires it yet
     */
    public record Event(String id, String displayName, String description, List<String> details,
            List<String> firedBy) {
        public Event {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            description = description == null ? "" : description;
            details = details == null ? List.of() : List.copyOf(details);
            firedBy = firedBy == null ? List.of() : List.copyOf(firedBy);
        }
    }

    /**
     * One registered action.
     *
     * @param id           the action key as authors write it ({@code message}; addon actions are qualified)
     * @param description  its self-description, or {@code null} when the action declares none
     * @param shorthand    the parameter a scalar value maps to ({@code message: "hi"} → {@code text}), or {@code null}
     * @param deferred     true when execution is routed through the scheduling gateway rather than run inline
     * @param category     the palette category, or {@code null} for an undescribed action
     * @param threading    the declared thread, or {@code null} for an undescribed action
     * @param availability whether the server can run it now, with the reason when it cannot
     * @param registeredBy owning plugin name, or blank when unknown
     * @param takesElse    true when the step may carry an {@code else:} that runs when the action goes
     *                     unanswered (logic.md §29, {@code ActionType.takesElse()}); the editor draws it as a branch
     */
    public record Action(String id, SelfDescription description, String shorthand, boolean deferred,
            LogicCategory category, Threading threading, Availability availability, String registeredBy,
            boolean takesElse) {
        public Action {
            Objects.requireNonNull(id, "id");
            availability = availability == null ? Availability.AVAILABLE : availability;
            registeredBy = registeredBy == null ? "" : registeredBy;
        }

        /** The shape before {@code takesElse} (logic.md §31): an action with no {@code else:} branch. */
        public Action(String id, SelfDescription description, String shorthand, boolean deferred,
                LogicCategory category, Threading threading, Availability availability, String registeredBy) {
            this(id, description, shorthand, deferred, category, threading, availability, registeredBy, false);
        }

        /** Version-1 shape: no category, threading, availability or owner. */
        public Action(String id, SelfDescription description, String shorthand, boolean deferred) {
            this(id, description, shorthand, deferred, null, null, Availability.AVAILABLE, "", false);
        }
    }

    /**
     * One registered condition.
     *
     * @param id           the condition key as authors write it ({@code has_money})
     * @param description  its description (self-description, category, shorthand)
     * @param availability whether the server can evaluate it now
     * @param registeredBy owning plugin name
     */
    public record Condition(String id, ConditionDescription description, Availability availability,
            String registeredBy) {
        public Condition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(description, "description");
            availability = availability == null ? Availability.AVAILABLE : availability;
            registeredBy = registeredBy == null ? "" : registeredBy;
        }
    }

    /**
     * One registered trigger: the full description a rule's {@code on:} is checked against.
     *
     * @param id           the selector type as authors write it ({@code click}; addon triggers are qualified)
     * @param description  filters, context schema, cancellability, details and who fires it
     * @param registeredBy owning plugin name
     */
    public record Trigger(String id, TriggerDescription description, String registeredBy) {
        public Trigger {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(description, "description");
            registeredBy = registeredBy == null ? "" : registeredBy;
        }
    }

    /**
     * One registered expression function.
     *
     * @param id           the function name as written in expressions
     * @param description  display metadata, argument schema and return kind
     * @param registeredBy owning plugin name
     */
    public record Function(String id, FunctionDescription description, String registeredBy) {
        public Function {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(description, "description");
            registeredBy = registeredBy == null ? "" : registeredBy;
        }
    }

    /**
     * One registered recipe (logic.md §4.6, §30): the parameterized fragment the editor offers, with its typed parameters.
     *
     * @param id           the recipe key as authors write it ({@code pay_to_receive}; addon recipes are qualified)
     * @param description  its name, help text and parameters
     * @param registeredBy owning plugin name
     */
    public record Recipe(String id, RecipeDescription description, String registeredBy) {
        public Recipe {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(description, "description");
            registeredBy = registeredBy == null ? "" : registeredBy;
        }
    }

    /**
     * One registered list source (logic.md §4.5, §28): where a GUI {@code list} component's entries come from.
     *
     * @param id           the source key as authors write it ({@code online_players})
     * @param description  its name, parameters, shorthand, entry schema and whether it is cached
     * @param registeredBy owning plugin name
     */
    public record ListSourceEntry(String id, ListSourceDescription description, String registeredBy) {
        public ListSourceEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(description, "description");
            registeredBy = registeredBy == null ? "" : registeredBy;
        }
    }

    /**
     * One registered item source (logic.md §4.4): where a GUI item layer's item comes from.
     *
     * @param id           the source key as authors write it ({@code head})
     * @param description  its name, parameters and shorthand
     * @param registeredBy owning plugin name
     */
    public record ItemSourceEntry(String id, ItemSourceDescription description, String registeredBy) {
        public ItemSourceEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(description, "description");
            registeredBy = registeredBy == null ? "" : registeredBy;
        }
    }

    /**
     * One registered input method (logic.md §4.5, §29): how a {@code prompt} step asks a player for text.
     *
     * @param id           the method key as authors write it ({@code chat})
     * @param description  its name, parameters and whether it closes the player's window
     * @param availability whether the server can use it now (the sign editor is unavailable on this build)
     * @param registeredBy owning plugin name
     */
    public record InputMethodEntry(String id, InputMethodDescription description, Availability availability,
            String registeredBy) {
        public InputMethodEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(description, "description");
            availability = availability == null ? Availability.AVAILABLE : availability;
            registeredBy = registeredBy == null ? "" : registeredBy;
        }
    }

    /**
     * One engine-owned step kind (logic.md §2.2). The set is closed: control flow belongs to the
     * engine, and only actions, conditions and triggers are open registries.
     *
     * @param id          the step key ({@code action}, {@code if}, {@code require}…)
     * @param displayName short human name
     * @param description what the step does
     * @param parameters  the keys the step takes besides its own, as a schema
     * @param supported   true when this server's engine runs the step today; an unsupported kind is
     *                    part of the grammar but the editor must not offer it yet
     */
    public record StepKind(String id, String displayName, String description,
            List<ParameterSchema> parameters, boolean supported) {
        public StepKind {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            description = description == null ? "" : description;
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    /**
     * One registered mechanic.
     *
     * @param id           full namespaced id ({@code materione:food})
     * @param description  its self-description
     * @param repeatable   whether an item may declare it more than once
     * @param registeredBy owning plugin name
     */
    public record Mechanic(String id, SelfDescription description, boolean repeatable, String registeredBy) {
        public Mechanic {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(description, "description");
            registeredBy = registeredBy == null ? "unknown" : registeredBy;
        }
    }
}
