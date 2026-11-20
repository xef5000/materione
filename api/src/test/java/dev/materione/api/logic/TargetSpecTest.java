package dev.materione.api.logic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The five forms of {@code target:}, their bounds and their messages (logic.md §39). */
class TargetSpecTest {

    private static ParamReader step(Object target) {
        var map = new LinkedHashMap<String, Object>();
        map.put("text", "hi");
        if (target != null) {
            map.put("target", target);
        }
        return ParamReader.of(map);
    }

    private static TargetSpec read(Object target) {
        return TargetSpec.read(step(target)).orElseThrow();
    }

    @Test
    void anAbsentTargetIsNoTargetAndSelfIsExplicit() {
        assertThat(TargetSpec.read(step(null))).isEmpty();
        assertThat(read("self")).isEqualTo(new TargetSpec.Self());
        assertThat(read("self").describe()).isEqualTo("self");
    }

    @Test
    void theEventTargetIsABareWord() {
        assertThat(read("event.target")).isEqualTo(new TargetSpec.Event());
    }

    @Test
    void anArgumentNamesAPlayerArgument() {
        assertThat(read(Map.of("arg", "to"))).isEqualTo(new TargetSpec.Arg("to"));
        assertThat(read(Map.of("arg", "to")).describe()).isEqualTo("argument to");
        assertThatThrownBy(() -> read(Map.of("arg", "no spaces")))
                .hasMessage("target.arg must be an argument name (letters, digits, underscore)");
        assertThatThrownBy(() -> read(Map.of("arg", "")))
                .hasMessageContaining("arg must be a non-blank string");
    }

    @Test
    void aPlayerIsATemplate() {
        var spec = (TargetSpec.Player) read(Map.of("player", "Notch"));
        assertThat(spec.name().source()).isEqualTo("Notch");
        assertThat(spec.describe()).isEqualTo("player Notch");
        assertThatThrownBy(() -> read(Map.of("player", 3))).hasMessageContaining("player must be a non-blank string");
    }

    @Test
    void aNearbySelectorIsBounded() {
        var spec = (TargetSpec.Nearby) read(Map.of("nearby", Map.of("radius", 8, "type", "minecraft:Zombie", "limit", 3)));
        assertThat(spec).isEqualTo(new TargetSpec.Nearby(8, "zombie", 3));
        assertThat(spec.describe()).isEqualTo("nearby 8 zombie x3");

        var plain = (TargetSpec.Nearby) read(Map.of("nearby", Map.of("radius", 2.5)));
        assertThat(plain).isEqualTo(new TargetSpec.Nearby(2.5, null, 1));
        assertThat(plain.describe()).isEqualTo("nearby 2.5 x1");
    }

    @Test
    void aNearbySelectorRefusesWhatIsOutOfBounds() {
        assertThatThrownBy(() -> read(Map.of("nearby", Map.of("limit", 2))))
                .hasMessage("target.nearby.radius is required (0.5 to 64 blocks)");
        assertThatThrownBy(() -> read(Map.of("nearby", Map.of("radius", 65))))
                .hasMessageContaining("radius must be a finite number in 0.5..64");
        assertThatThrownBy(() -> read(Map.of("nearby", Map.of("radius", 0))))
                .hasMessageContaining("radius must be a finite number");
        assertThatThrownBy(() -> read(Map.of("nearby", Map.of("radius", 4, "limit", 17))))
                .hasMessageContaining("limit must be an integer in 1..16");
        assertThatThrownBy(() -> read(Map.of("nearby", Map.of("radius", 4, "limt", 2))))
                .hasMessage("target.nearby.limt is not a parameter; use radius, type, limit");
        assertThatThrownBy(() -> read(Map.of("nearby", Map.of("radius", 4, "type", "Not A Type!"))))
                .hasMessageContaining("is not an entity type key");
    }

    @Test
    void aValueThatIsNoFormIsRejectedWithAMessageToActOn() {
        assertThatThrownBy(() -> read("everyone")).hasMessageContaining("'everyone' is not a target")
                .hasMessageContaining("self, event.target");
        assertThatThrownBy(() -> read(List.of("self"))).hasMessageContaining("must be self, event.target, or a mapping");
        assertThatThrownBy(() -> read(5)).hasMessageContaining("must be self, event.target, or a mapping");
        assertThatThrownBy(() -> read(Map.of())).hasMessageContaining("exactly one of arg, player or nearby, not none");
        assertThatThrownBy(() -> read(Map.of("arg", "a", "player", "b")))
                .hasMessageContaining("exactly one of arg, player or nearby, not ");
        assertThatThrownBy(() -> read(Map.of("owner", "a"))).hasMessageContaining("target.owner is not a target form");
    }

    @Test
    void aTargetableDescriptionAddsTheSharedParameterOnce() {
        var self = new SelfDescription("Thing", "A thing",
                List.of(ParameterSchema.of("text", ParameterType.STRING, true)), "1.0.0");
        var plain = new ActionDescription(self, LogicCategory.PLAYER, "text");
        assertThat(plain.isTargetable()).isFalse();
        assertThat(plain.self().parameters()).extracting(ParameterSchema::id).containsExactly("text");

        var targetable = plain.withTargets(TargetKind.ENTITY, TargetKind.PLAYER, TargetKind.ENTITY);
        assertThat(targetable.targetable()).containsExactly(TargetKind.PLAYER, TargetKind.ENTITY);
        assertThat(targetable.self().parameters()).extracting(ParameterSchema::id).containsExactly("text", "target");
        var parameter = targetable.self().parameters().get(1);
        assertThat(parameter.type()).isEqualTo(ParameterType.RAW);
        assertThat(parameter.required()).isFalse();
        assertThat(parameter.defaultValue()).isEqualTo("self");
        assertThat(parameter.semantics().valueKind()).isEqualTo(ParameterSchema.KIND_TARGET);

        // declaring it again changes nothing (the description of a description is itself)
        assertThat(targetable.withTargets(TargetKind.PLAYER, TargetKind.ENTITY)).isEqualTo(targetable);
        assertThat(targetable.shorthand()).isEqualTo("text");
    }

    @Test
    void aTypeThatAlreadyDeclaredTargetKeepsItsOwn() {
        var own = ParameterSchema.of("target", ParameterType.STRING, false);
        var self = new SelfDescription("Thing", "A thing", List.of(own), "1.0.0");
        var description = new ActionDescription(self, LogicCategory.PLAYER, null).withTargets(TargetKind.PLAYER);
        assertThat(description.self().parameters()).containsExactly(own);
    }

    @Test
    void anEntityActionAdmitsPlayersAndAPlayerActionRefusesEntities() {
        var self = new SelfDescription("Thing", "A thing", List.of(), "1.0.0");
        var players = new ActionDescription(self, LogicCategory.PLAYER, null).withTargets(TargetKind.PLAYER);
        var entities = new ActionDescription(self, LogicCategory.PLAYER, null).withTargets(TargetKind.ENTITY);

        assertThat(players.admits(TargetKind.PLAYER)).isTrue();
        assertThat(players.admits(TargetKind.ENTITY)).isFalse();
        assertThat(entities.admits(TargetKind.ENTITY)).isTrue();
        assertThat(entities.admits(TargetKind.PLAYER)).isTrue();
        assertThat(new ActionDescription(self, LogicCategory.PLAYER, null).admits(TargetKind.PLAYER)).isFalse();
        assertThat(TargetKind.PLAYER.id()).isEqualTo("player");
    }

    @Test
    void aResolutionWithNoSubjectMustSayWhy() {
        assertThatThrownBy(() -> TargetResolution.none(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(TargetResolution.none("nobody").targets()).isEmpty();
        assertThat(TargetResolution.of(List.of(), "no zombie within 8 blocks").reason()).isEqualTo("no zombie within 8 blocks");
    }
}
