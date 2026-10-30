package dev.materione.plugin.logic.condition;

import static dev.materione.plugin.logic.condition.ConditionFixtures.params;
import static dev.materione.plugin.logic.condition.ConditionFixtures.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.plugin.logic.action.PlayerFake;
import dev.materione.plugin.logic.support.ExperienceUnit;
import org.junit.jupiter.api.Test;

/** {@code has_exp} (logic.md §19): points count the total, levels count the level, as take_exp does. */
class HasExpConditionTest {

    private final HasExpCondition condition = new HasExpCondition();

    @Test
    void theShorthandIsAPointsAmount() {
        var parsed = condition.parse(reader(params("amount", 50)));
        assertThat(parsed.unit()).isEqualTo(ExperienceUnit.POINTS);
    }

    @Test
    void pointsAreTheTotalExperience() {
        var fake = new PlayerFake();
        fake.totalExperience = 120;
        var atLeast120 = condition.parse(reader(params("amount", 120)));
        var atLeast121 = condition.parse(reader(params("amount", 121)));
        assertThat(condition.test(atLeast120, fake.context())).isTrue();
        assertThat(condition.test(atLeast121, fake.context())).isFalse();
    }

    @Test
    void levelsAreTheLevelNotThePoints() {
        var fake = new PlayerFake();
        fake.totalExperience = 9_000;
        fake.level = 5;
        var five = condition.parse(reader(params("amount", 5, "unit", "levels")));
        var six = condition.parse(reader(params("amount", 6, "unit", "LEVELS")));
        assertThat(condition.test(five, fake.context())).isTrue();
        assertThat(condition.test(six, fake.context())).isFalse();
    }

    @Test
    void withoutAPlayerItIsFalse() {
        var parsed = condition.parse(reader(params("amount", 1)));
        assertThat(condition.test(parsed, PlayerFake.noPlayer())).isFalse();
    }

    @Test
    void rejectsZeroAMissingAmountAndAnUnknownUnit() {
        assertThatThrownBy(() -> condition.parse(reader(params("unit", "levels"))))
                .hasMessage("amount is required");
        assertThatThrownBy(() -> condition.parse(reader(params("amount", 0))))
                .hasMessageContaining("amount must be a whole number");
        assertThatThrownBy(() -> condition.parse(reader(params("amount", "5"))))
                .hasMessageContaining("amount must be");
        assertThatThrownBy(() -> condition.parse(reader(params("amount", 5, "unit", "bars"))))
                .hasMessage("unit must be one of points, levels");
    }
}
