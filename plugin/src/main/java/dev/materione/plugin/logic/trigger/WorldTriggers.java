package dev.materione.plugin.logic.trigger;

import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.rule.EventTypes;
import java.util.ArrayList;
import java.util.List;

/**
 * The world-event triggers (logic.md §37): an entity hit or killed by a player, a player hurt, joining,
 * leaving, dying and respawning. They are descriptions only, like every trigger: the filters a rule may set,
 * the variables it is guaranteed, who fires it and whether it can cancel. Registered through the public logic
 * API, so the compiler checks {@code ${root}} paths against these schemas, the editor autocompletes from them
 * and the code-mode SDK generator reads them (D5). The firing sites are {@code WorldEventListener}.
 *
 * <p>{@code entity.hit}, {@code entity.kill} and {@code player.hurt} are fired for an item's rules as well as
 * for rule documents, so they name both hosts; the rest belong to rule documents.
 */
public final class WorldTriggers {

    /** The host name of a rule document, the same name its content type registers under. */
    public static final String RULE = "rule";
    private static final List<String> ITEM_AND_RULE = List.of("item", RULE);
    private static final List<String> RULE_ONLY = List.of(RULE);

    private WorldTriggers() {}

    /**
     * The six triggers, in the order the editor lists them.
     *
     * @param entityTypes the entity types the server knows, for the {@code type} filter; empty accepts any name
     * @param damageCauses the damage causes, for the {@code cause} filter
     */
    public static List<TriggerType> all(List<String> entityTypes, List<String> damageCauses) {
        var type = filter("type", entityTypes, "Only this entity type (a type, or a list of types), e.g. zombie");
        var cause = filter("cause", damageCauses, "Only this damage cause (a cause, or a list of causes)");
        return List.of(
                trigger(EventTypes.ENTITY_HIT, "Entity hit",
                        "The player damages an entity (a melee hit, or a projectile the player shot). On an item, "
                                + "it fires for the item in the player's main hand; a rule can cancel the damage",
                        ITEM_AND_RULE, List.of(type, cause), true, entityHit()),
                trigger(EventTypes.ENTITY_KILL, "Entity killed",
                        "An entity dies and a player is its killer. On an item, it fires for the item in the "
                                + "player's main hand",
                        ITEM_AND_RULE, List.of(type), false, entityKill()),
                trigger(EventTypes.PLAYER_HURT, "Player hurt",
                        "The player takes damage. On an item, it fires for the item the player holds and for each "
                                + "piece of armor the player wears; a rule can cancel the damage",
                        ITEM_AND_RULE, List.of(cause), true, playerHurt()),
                trigger(EventTypes.PLAYER_JOIN, "Player join", "A player joins the server",
                        RULE_ONLY, List.of(), false, List.of(
                                ContextVariable.of("player.first_join", ParameterType.BOOLEAN,
                                        "Whether this is the first time the player has joined this server"))),
                trigger(EventTypes.PLAYER_QUIT, "Player quit",
                        "A player leaves the server. Steps after a wait do not run: the player is gone",
                        RULE_ONLY, List.of(), false, List.of()),
                trigger(EventTypes.PLAYER_RESPAWN, "Player respawn", "A player respawns after dying",
                        RULE_ONLY, List.of(), false, List.of()));
    }

    private static List<ContextVariable> entityHit() {
        var out = new ArrayList<ContextVariable>(target(true));
        out.addAll(damage());
        out.addAll(item());
        return out;
    }

    private static List<ContextVariable> entityKill() {
        var out = new ArrayList<ContextVariable>(target(false));
        out.addAll(item());
        return out;
    }

    private static List<ContextVariable> playerHurt() {
        var out = new ArrayList<ContextVariable>(damage());
        out.add(ContextVariable.of("source.type", ParameterType.STRING,
                "The type of the entity responsible for the damage (the shooter, for a projectile), "
                        + "blank when none was (a fall, fire)"));
        out.addAll(item());
        return out;
    }

    /** The struck or killed entity. A kill carries no health: the entity is dead. */
    private static List<ContextVariable> target(boolean withHealth) {
        var out = new ArrayList<ContextVariable>();
        out.add(ContextVariable.of("target.type", ParameterType.STRING, "The entity's type, e.g. zombie"));
        out.add(ContextVariable.of("target.uuid", ParameterType.STRING, "The entity's UUID"));
        out.add(ContextVariable.of("target.is_player", ParameterType.BOOLEAN, "Whether the entity is a player"));
        if (withHealth) {
            out.add(ContextVariable.of("target.health", ParameterType.DECIMAL,
                    "The entity's health before the damage lands"));
        }
        return out;
    }

    private static List<ContextVariable> damage() {
        return List.of(
                ContextVariable.of("damage.amount", ParameterType.DECIMAL,
                        "The damage dealt before armor and effects reduce it"),
                ContextVariable.of("damage.final", ParameterType.DECIMAL,
                        "The damage after armor, effects and other plugins have changed it, at the moment the rule runs"),
                ContextVariable.of("damage.cause", ParameterType.STRING,
                        "The damage cause, e.g. entity_attack or projectile"));
    }

    /** The item the rule runs for: the item hosting it, or the player's main-hand item in a rule document. */
    private static List<ContextVariable> item() {
        return List.of(
                ContextVariable.of("item.id", ParameterType.STRING,
                        "The Materione id of the item (the one hosting the rule, or the main-hand item in a rule "
                                + "document), blank for a vanilla item"),
                ContextVariable.of("item.material", ParameterType.STRING, "The item's vanilla material, lowercase"),
                ContextVariable.of("item.amount", ParameterType.INTEGER, "The stack size"),
                ContextVariable.of("item.durability_used", ParameterType.INTEGER,
                        "Durability used, once the durability mechanic has counted"),
                ContextVariable.of("item.durability_max", ParameterType.INTEGER,
                        "Maximum durability, once the durability mechanic has counted"));
    }

    /** What every world trigger guarantees: the player, the place, the world and the server. */
    private static List<ContextVariable> common() {
        return List.of(
                ContextVariable.of("player.name", ParameterType.STRING, "The player's name"),
                ContextVariable.of("player.uuid", ParameterType.STRING, "The player's UUID"),
                ContextVariable.of("player.gamemode", ParameterType.STRING,
                        "survival, creative, adventure or spectator"),
                ContextVariable.of("player.sneaking", ParameterType.BOOLEAN, "Whether the player is sneaking"),
                ContextVariable.of("player.op", ParameterType.BOOLEAN, "Whether the player is an operator"),
                ContextVariable.of("player.health", ParameterType.DECIMAL, "The player's health"),
                ContextVariable.of("player.level", ParameterType.INTEGER, "The player's experience level"),
                ContextVariable.of("player.x", ParameterType.DECIMAL, "The player's X coordinate"),
                ContextVariable.of("player.y", ParameterType.DECIMAL, "The player's Y coordinate"),
                ContextVariable.of("player.z", ParameterType.DECIMAL, "The player's Z coordinate"),
                ContextVariable.of("location.x", ParameterType.DECIMAL,
                        "X of the event: the entity that was hit or died, else the player"),
                ContextVariable.of("location.y", ParameterType.DECIMAL, "Y of the event location"),
                ContextVariable.of("location.z", ParameterType.DECIMAL, "Z of the event location"),
                ContextVariable.of("location.world", ParameterType.STRING, "World name of the event location"),
                ContextVariable.of("world.name", ParameterType.STRING, "World name"),
                ContextVariable.of("world.time", ParameterType.INTEGER, "World time in ticks"),
                ContextVariable.of("server.online", ParameterType.INTEGER, "Players online"),
                ContextVariable.of("server.max", ParameterType.INTEGER, "Player slots"),
                ContextVariable.of("vars.*", ParameterType.RAW, "Variables written earlier in this run by set_var"));
    }

    private static TriggerType trigger(String id, String name, String description, List<String> firedBy,
            List<ParameterSchema> filters, boolean cancellable, List<ContextVariable> own) {
        var context = new ArrayList<ContextVariable>(common());
        context.addAll(own);
        return new WorldTrigger(id, new TriggerDescription(
                SelfDescription.of(name, description), List.of(), firedBy, filters, context, cancellable));
    }

    private static ParameterSchema filter(String id, List<String> allowed, String description) {
        return new ParameterSchema(id, ParameterType.ENUM, false, null, description, allowed, null);
    }

    /** A trigger that is only its description. */
    private record WorldTrigger(String id, TriggerDescription description) implements TriggerType {
        @Override
        public TriggerDescription describe() {
            return description;
        }
    }
}
