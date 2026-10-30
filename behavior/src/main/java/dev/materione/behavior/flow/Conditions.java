package dev.materione.behavior.flow;

import java.util.List;
import java.util.stream.Collectors;

/** Small helpers shared by the condition records. */
final class Conditions {

    private Conditions() {}

    static String describe(List<Condition> children) {
        return children.stream().map(Condition::describe).collect(Collectors.joining(", "));
    }
}
