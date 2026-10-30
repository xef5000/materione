package dev.materione.api.logic.variable;

import dev.materione.api.NamespacedId;
import java.util.Set;

/**
 * What a {@code set_var} {@code var:} names (logic.md §3.3). The spelling is the expression path
 * without its {@code vars.} root, so a write and the read that follows it read the same:
 *
 * <pre>
 *   count            local (the M2 meaning, no declaration needed)
 *   local.count      local, explicitly
 *   session.page     the GUI session's variable (absent: the step fails)
 *   player.coins     declared player variable, pack = the resource's own pack
 *   player.shop.coins   the same, pack named
 *   server.shop.season  declared server variable
 *   shop:coins       declared variable, scope taken from its declaration
 * </pre>
 *
 * <p>A bare name is always local. Only a qualified spelling can reach a persistent variable, so a
 * typo in a bare name never creates a stored key. The four scope words are reserved and cannot be
 * local names.
 */
public sealed interface VariableRef permits VariableRef.Local, VariableRef.Session, VariableRef.Persistent {

    /** The words an expression or a reference reads as a scope, never as a local name. */
    Set<String> RESERVED = Set.of("local", "session", "player", "server");

    /** A run-local variable. */
    record Local(String name) implements VariableRef {}

    /** A variable of the open GUI session. */
    record Session(String name) implements VariableRef {}

    /**
     * A declared persistent variable.
     *
     * @param scope the scope the spelling named, or null for {@code pack:key} (the declaration decides)
     * @param id the pack-namespaced key
     */
    record Persistent(VariableScope scope, NamespacedId id) implements VariableRef {}

    /**
     * Parses a reference.
     *
     * @param text the authored spelling
     * @param ownPack the namespace of the resource the step belongs to; a bare scoped name
     *        ({@code player.coins}) resolves here, or null when there is none
     * @throws IllegalArgumentException with the reason when the spelling is not a reference
     */
    static VariableRef parse(String text, String ownPack) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("variable name is required");
        }
        String trimmed = text.trim();
        if (trimmed.indexOf(':') >= 0) {
            try {
                return new Persistent(null, NamespacedId.parse(trimmed));
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException("'" + trimmed + "' is not a pack:key variable name");
            }
        }
        String[] segments = trimmed.split("\\.", -1);
        String head = segments[0];
        if (segments.length == 1) {
            return local(head); // a bare name, or a scope word on its own (reserved)
        }
        if (VariableScope.of(head).isPresent()) {
            return scoped(VariableScope.of(head).orElseThrow(), segments, ownPack, trimmed);
        }
        if ("local".equals(head) && segments.length == 2) {
            return local(segments[1]);
        }
        if ("session".equals(head) && segments.length == 2) {
            return session(segments[1]);
        }
        throw new IllegalArgumentException("'" + trimmed + "' is not a variable name (a local name has no dots)");
    }

    private static Persistent scoped(VariableScope scope, String[] segments, String ownPack, String text) {
        try {
            if (segments.length == 2 && ownPack != null) {
                return new Persistent(scope, NamespacedId.of(ownPack, segments[1]));
            }
            if (segments.length == 3) {
                return new Persistent(scope, NamespacedId.of(segments[1], segments[2]));
            }
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("'" + text + "' is not a valid variable name");
        }
        throw new IllegalArgumentException("'" + text + "' names no variable (use "
                + scope.id() + ".<key> or " + scope.id() + ".<pack>.<key>)");
    }

    private static Local local(String name) {
        return new Local(checkedLocal(name));
    }

    private static Session session(String name) {
        return new Session(checkedLocal(name));
    }

    private static String checkedLocal(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("variable name is empty");
        }
        if (RESERVED.contains(name)) {
            throw new IllegalArgumentException("'" + name + "' is a reserved scope word and cannot name a variable");
        }
        return name;
    }
}
