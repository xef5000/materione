package dev.materione.plugin.logic.condition;

import static dev.materione.plugin.logic.condition.ConditionFixtures.params;
import static dev.materione.plugin.logic.condition.ConditionFixtures.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.plugin.logic.action.PlayerFake;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code has_permission}: the shorthand, the list and {@code min} forms, rejects, and the test against a player. */
class HasPermissionConditionTest {

    private final HasPermissionCondition condition = new HasPermissionCondition();

    @Test
    void theShorthandIsOneNodeAndTheCheckIsAllOfOne() {
        var parsed = condition.parse(reader(params("permission", "shop.vip")));
        assertThat(parsed.nodes()).containsExactly("shop.vip");
        assertThat(parsed.min()).isEqualTo(1);

        var fake = new PlayerFake();
        assertThat(condition.test(parsed, fake.context())).isFalse();
        fake.permissions.add("shop.vip");
        assertThat(condition.test(parsed, fake.context())).isTrue();
    }

    @Test
    void aBareListIsAcceptedAsTheShorthandAndNeedsEveryNode() {
        var parsed = condition.parse(reader(params("permission", List.of("a.one", "a.two"))));
        assertThat(parsed.nodes()).containsExactly("a.one", "a.two");
        assertThat(parsed.min()).isEqualTo(2);

        var fake = new PlayerFake();
        fake.permissions.add("a.one");
        assertThat(condition.test(parsed, fake.context())).isFalse();
        fake.permissions.add("a.two");
        assertThat(condition.test(parsed, fake.context())).isTrue();
    }

    @Test
    void minCountsHowManyOfTheListMustHold() {
        var parsed = condition.parse(reader(params("permissions", List.of("a.one", "a.two", "a.three"), "min", 2)));
        assertThat(parsed.min()).isEqualTo(2);

        var fake = new PlayerFake();
        fake.permissions.add("a.three");
        assertThat(condition.test(parsed, fake.context())).isFalse();
        fake.permissions.add("a.one");
        assertThat(condition.test(parsed, fake.context())).isTrue();
    }

    @Test
    void zeroMinIsAlwaysTrueAndIsWrittenOnPurpose() {
        var parsed = condition.parse(reader(params("permissions", List.of("a.one"), "min", 0)));
        assertThat(condition.test(parsed, new PlayerFake().context())).isTrue();
    }

    @Test
    void rejectsAMissingNodeBothSpellingsAndMalformedNodes() {
        assertThatThrownBy(() -> condition.parse(reader(params())))
                .hasMessage("permission is required (or permissions: [ ... ])");
        assertThatThrownBy(() -> condition.parse(reader(params("permission", "a.b", "permissions", List.of("c.d")))))
                .hasMessage("give permission or permissions, not both");
        assertThatThrownBy(() -> condition.parse(reader(params("permission", "bad node!"))))
                .hasMessageContaining("must be a node such as example.fly");
        assertThatThrownBy(() -> condition.parse(reader(params("permissions", List.of("ok.node", "no way")))))
                .hasMessageContaining("must be a node such as example.fly");
        assertThatThrownBy(() -> condition.parse(reader(params("permissions", List.of()))))
                .hasMessage("permissions needs at least one node");
        assertThatThrownBy(() -> condition.parse(reader(params("permissions", "a.b"))))
                .hasMessageContaining("must be a list of strings");
    }

    @Test
    void rejectsAMinOutsideTheListSize() {
        assertThatThrownBy(() -> condition.parse(reader(params("permissions", List.of("a.one"), "min", 2))))
                .hasMessageContaining("min must be an integer in 0..1");
        assertThatThrownBy(() -> condition.parse(reader(params("permission", "a.one", "min", -1))))
                .hasMessageContaining("min must be an integer in 0..1");
    }

    @Test
    void withoutAPlayerItIsFalse() {
        var parsed = condition.parse(reader(params("permission", "a.one")));
        assertThat(condition.test(parsed, PlayerFake.noPlayer())).isFalse();
    }

    @Test
    void describesItselfInThePlayerCategoryWithItsShorthand() {
        var description = condition.describe();
        assertThat(description.shorthand()).isEqualTo("permission");
        assertThat(description.category().id()).isEqualTo("player");
        assertThat(condition.id()).isEqualTo("has_permission");
    }
}
