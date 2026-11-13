package dev.materione.plugin.logic.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.TriggerType;
import dev.materione.behavior.rule.EventTypes;
import dev.materione.platform.entity.EntityPalette;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The world triggers' self-description (logic.md §37): what the editor, the vocabulary and the code-mode SDK
 * generator read, with no schema written by hand anywhere else. The test is the pin on the table in the design.
 */
class WorldTriggersTest {

    private static final List<String> TYPES = List.of("zombie", "husk");
    private static final List<String> CAUSES = List.of("entity_attack", "fall");

    private final Map<String, TriggerType> byId = new java.util.LinkedHashMap<>();

    WorldTriggersTest() {
        WorldTriggers.all(TYPES, CAUSES).forEach(trigger -> byId.put(trigger.id(), trigger));
    }

    private List<String> paths(String trigger) {
        return byId.get(trigger).describe().context().stream().map(ContextVariable::path).toList();
    }

    @Test
    void theSevenTriggersAreNamedByTheConstantsTheFiringSitesUse() {
        assertThat(byId.keySet()).containsExactly(
                EventTypes.ENTITY_HIT, EventTypes.ENTITY_KILL, EventTypes.PLAYER_HURT, EventTypes.PLAYER_JOIN,
                EventTypes.PLAYER_QUIT, EventTypes.PLAYER_RESPAWN, EventTypes.PLAYER_DEATH);
    }

    @Test
    void entityHitKillAndPlayerHurtAreHostedByItemsAndRuleDocumentsTheRestByRuleDocumentsOnly() {
        for (String id : List.of(EventTypes.ENTITY_HIT, EventTypes.ENTITY_KILL, EventTypes.PLAYER_HURT)) {
            assertThat(byId.get(id).describe().firedBy()).containsExactly("item", "rule");
        }
        for (String id : List.of(EventTypes.PLAYER_JOIN, EventTypes.PLAYER_QUIT, EventTypes.PLAYER_RESPAWN,
                EventTypes.PLAYER_DEATH)) {
            assertThat(byId.get(id).describe().firedBy()).containsExactly("rule");
        }
    }

    @Test
    void onlyTheTriggersThatCanVetoAnEventAreCancellable() {
        assertThat(byId.values().stream().filter(t -> t.describe().cancellable()).map(TriggerType::id))
                .containsExactly(EventTypes.ENTITY_HIT, EventTypes.PLAYER_HURT);
    }

    @Test
    void theFiltersAreDeclaredWithTheServersOwnPalettes() {
        var hit = byId.get(EventTypes.ENTITY_HIT).describe().filters();
        assertThat(hit).extracting(filter -> filter.id()).containsExactly("type", "cause");
        assertThat(hit.get(0).allowedValues()).isEqualTo(TYPES);
        assertThat(hit.get(1).allowedValues()).isEqualTo(CAUSES);
        assertThat(byId.get(EventTypes.ENTITY_KILL).describe().filters()).extracting(filter -> filter.id())
                .containsExactly("type");
        assertThat(byId.get(EventTypes.PLAYER_HURT).describe().filters()).extracting(filter -> filter.id())
                .containsExactly("cause");
        assertThat(byId.get(EventTypes.PLAYER_JOIN).describe().filters()).isEmpty();
    }

    @Test
    void theContextSchemasMatchTheDesign() {
        assertThat(paths(EventTypes.ENTITY_HIT)).contains("target.type", "target.uuid", "target.health",
                "target.is_player", "damage.amount", "damage.final", "damage.cause", "item.id", "location.x");
        // a kill carries the dead entity (no health: it has none left) and where it died
        assertThat(paths(EventTypes.ENTITY_KILL)).contains("target.type", "target.uuid", "target.is_player",
                "location.world", "item.id").doesNotContain("target.health", "damage.final");
        assertThat(paths(EventTypes.PLAYER_HURT)).contains("damage.amount", "damage.final", "damage.cause",
                "source.type", "item.id");
        assertThat(paths(EventTypes.PLAYER_JOIN)).contains("player.first_join", "player.name", "vars.*");
        assertThat(paths(EventTypes.PLAYER_QUIT)).doesNotContain("player.first_join");
        assertThat(paths(EventTypes.PLAYER_DEATH)).contains("damage.cause");
        assertThat(paths(EventTypes.PLAYER_RESPAWN)).doesNotContain("damage.cause");
    }

    @Test
    void everyTriggerGuaranteesThePlayerThePlaceAndTheServer() {
        for (TriggerType trigger : byId.values()) {
            assertThat(paths(trigger.id())).as(trigger.id()).contains("player.name", "player.uuid", "location.x",
                    "world.name", "server.online", "vars.*");
            assertThat(paths(trigger.id())).as(trigger.id() + " has no duplicate path").doesNotHaveDuplicates();
        }
    }

    @Test
    void theCauseAndEntityPalettesComeFromTheServerAndDegradeToAnyNameWithoutOne() {
        assertThat(EntityPalette.damageCauses()).contains("entity_attack", "fall", "projectile").doesNotHaveDuplicates();
        // a unit test has no server registry: the type filter then accepts any name rather than none
        assertThat(EntityPalette.entityTypes()).isNotNull();
    }
}
