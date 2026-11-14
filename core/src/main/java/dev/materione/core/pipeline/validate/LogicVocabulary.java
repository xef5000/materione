package dev.materione.core.pipeline.validate;

import java.util.Map;
import java.util.Optional;

/**
 * What the reference walker needs to know about the actions and conditions a rule names: which of an authored key's
 * parameters name content, and what kind. Supplied by the host from the registries' self-description, so a new
 * action or an addon's is covered by declaring its parameter, not by editing a list here.
 */
@FunctionalInterface
public interface LogicVocabulary {

    /**
     * An action or condition.
     *
     * @param action true for an action (its parameters hold values), false for a condition (they may hold conditions)
     * @param references the parameters that name content, by parameter id, to the kind of content
     * @param shorthand the parameter a bare scalar stands for ({@code give_item: ruby}), or null
     */
    record Entry(boolean action, Map<String, String> references, String shorthand) {
        public Entry {
            references = Map.copyOf(references);
        }
    }

    /** The action or condition an authored key names, or empty when the key is neither. */
    Optional<Entry> entry(String authoredKey);
}
