package dev.materione.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * The self-description of the one behavior system (D4) as the editor sees it:
 * which events fire for which content types, which actions and mechanics are
 * registered, and the rule options every rule may carry. Each piece is read
 * from where it is registered — the event taxonomy, the action executors, the
 * mechanics registry — so the editor never keeps a second vocabulary.
 *
 * @param events      the event taxonomy, including events nothing fires yet
 * @param actions     registered action executors; undescribed ones still appear
 * @param ruleOptions rule-level keys besides {@code on}/{@code if}/{@code do}
 * @param actionOptions keys any action map may carry besides its type key
 * @param mechanics   registered mechanics (built-in and addon)
 */
public record BehaviorVocabulary(
        List<Event> events,
        List<Action> actions,
        List<ParameterSchema> ruleOptions,
        List<ParameterSchema> actionOptions,
        List<Mechanic> mechanics) {

    public BehaviorVocabulary {
        events = events == null ? List.of() : List.copyOf(events);
        actions = actions == null ? List.of() : List.copyOf(actions);
        ruleOptions = ruleOptions == null ? List.of() : List.copyOf(ruleOptions);
        actionOptions = actionOptions == null ? List.of() : List.copyOf(actionOptions);
        mechanics = mechanics == null ? List.of() : List.copyOf(mechanics);
    }

    public static BehaviorVocabulary empty() {
        return new BehaviorVocabulary(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public BehaviorVocabulary withMechanics(List<Mechanic> registered) {
        return new BehaviorVocabulary(events, actions, ruleOptions, actionOptions, registered);
    }

    /**
     * One event type of the taxonomy.
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
     * One registered action executor.
     *
     * @param id          the action key ({@code message})
     * @param description its self-description, or {@code null} when the executor declares none
     * @param shorthand   the parameter a scalar value maps to ({@code message: "hi"} → {@code text}), or {@code null}
     * @param deferred    true when execution is routed through the scheduling gateway
     */
    public record Action(String id, SelfDescription description, String shorthand, boolean deferred) {
        public Action {
            Objects.requireNonNull(id, "id");
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
