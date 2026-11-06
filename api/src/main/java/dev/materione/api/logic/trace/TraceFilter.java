package dev.materione.api.logic.trace;

import java.util.Set;
import java.util.UUID;

/**
 * Which runs a subscription hears about (logic.md §7.6, §32). A trace is opt-in, per player and per resource,
 * so a filter must name at least one of them: there is no "everything" subscription.
 *
 * @param players the players whose runs are traced; empty means any player
 * @param resources the ids of the resources (GUIs) whose rules are traced, as {@code pack:id}; empty means any
 */
public record TraceFilter(Set<UUID> players, Set<String> resources) {

    public TraceFilter {
        players = Set.copyOf(players);
        resources = Set.copyOf(resources);
        if (players.isEmpty() && resources.isEmpty()) {
            throw new IllegalArgumentException("a trace filter must name a player or a resource");
        }
    }

    /** The runs of {@code resource}, whoever starts them. */
    public static TraceFilter resource(String resource) {
        return new TraceFilter(Set.of(), Set.of(resource));
    }

    /** The runs of {@code resource} that {@code player} starts. */
    public static TraceFilter resource(String resource, UUID player) {
        return new TraceFilter(Set.of(player), Set.of(resource));
    }

    /**
     * Whether a run belongs to the filter.
     *
     * @param resource the owning resource's id
     * @param player the run's player (a UUID text), or an empty string
     */
    public boolean matches(String resource, String player) {
        if (!resources.isEmpty() && !resources.contains(resource)) {
            return false;
        }
        if (players.isEmpty()) {
            return true;
        }
        try {
            return !player.isEmpty() && players.contains(UUID.fromString(player));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
