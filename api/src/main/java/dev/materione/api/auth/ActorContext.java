package dev.materione.api.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who is performing an action and what they may do (api.md §3.1). Every
 * mutating or privileged {@code MaterioneAPI} call carries one: console
 * commands, in-game players, web editor sessions, addons and future MCP tool
 * calls are all actors. The permission checker enforces the master §9.2
 * taxonomy ({@code materione.editor}, {@code materione.edit.<pack>}, {@code
 * materione.pipeline}, {@code materione.admin}, {@code materione.give},
 * {@code materione.dev}) inside the API implementation, and the actor id
 * populates the audit log and document revision journals.
 *
 * <p>Fail-closed: an actor without a checker has no permissions.
 *
 * @param actorId           human-readable identity for audit entries
 *                          (player name, {@code console}, {@code editor:<owner>}, addon id…)
 * @param kind              which surface the action arrived through
 * @param playerUuid        the bound in-game identity when one exists
 * @param permissionChecker permission test; null means deny everything
 */
public record ActorContext(
        String actorId,
        Kind kind,
        Optional<UUID> playerUuid,
        Predicate<String> permissionChecker) {

    /** The surface an action arrives through (api.md §3.1). */
    public enum Kind { PLAYER, CONSOLE, EDITOR_SESSION, ADDON, MCP }

    public ActorContext {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(kind, "kind");
        playerUuid = playerUuid == null ? Optional.empty() : playerUuid;
        permissionChecker = permissionChecker == null ? node -> false : permissionChecker;
    }

    /** An in-game player acting through a command or GUI. */
    public static ActorContext player(UUID uuid, String name, Predicate<String> permissions) {
        return new ActorContext(name, Kind.PLAYER, Optional.of(uuid), permissions);
    }

    /** The server console: full permission, no in-game identity. */
    public static ActorContext console() {
        return new ActorContext("console", Kind.CONSOLE, Optional.empty(), node -> true);
    }

    /** A web editor session acting with its owner's permission nodes (master §9.2). */
    public static ActorContext editorSession(
            String ownerName, UUID ownerUuid, Predicate<String> permissions) {
        return new ActorContext(
                "editor:" + ownerName, Kind.EDITOR_SESSION, Optional.ofNullable(ownerUuid),
                permissions);
    }

    /** An in-process addon (trusted code, no in-game identity). */
    public static ActorContext addon(String addonId) {
        return new ActorContext(addonId, Kind.ADDON, Optional.empty(), node -> true);
    }

    /** An MCP/tooling agent call, permission-checked against its bound identity. */
    public static ActorContext mcp(String toolId, Predicate<String> permissions) {
        return new ActorContext("mcp:" + toolId, Kind.MCP, Optional.empty(), permissions);
    }

    /** Master §9.2 permission node check. */
    public boolean hasPermission(String node) {
        return permissionChecker.test(node);
    }
}
