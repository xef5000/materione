package dev.materione.plugin.logic.world;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The caps of the world verbs and their settings (logic.md §41): defaults, overrides, and a bad value falling back with one warning. */
class WorldLimitsTest {

    @Test
    void anEmptySettingsFileIsTheDefaults() {
        var problems = new ArrayList<Diagnostic>();

        assertThat(WorldLimits.read(Map.of(), problems::add)).isEqualTo(WorldLimits.DEFAULTS);

        assertThat(problems).isEmpty();
        assertThat(WorldLimits.DEFAULTS.blocksPerStep()).isEqualTo(729);
    }

    @Test
    void eachKeyOverridesItsOwnCapAndNothingElse() {
        var limits = WorldLimits.read(Map.of("world_verbs_max_radius", 6, "world_verbs_max_blocks_per_run", 10_000,
                "world_verbs_max_particles_per_step", 100, "world_verbs_max_launches_per_run", 32.0), null);

        assertThat(limits.radius()).isEqualTo(6);
        assertThat(limits.blocksPerStep()).isEqualTo(13L * 13 * 13);
        assertThat(limits.blocksPerRun()).isEqualTo(10_000);
        assertThat(limits.particlesPerStep()).isEqualTo(100);
        assertThat(limits.launchesPerRun()).isEqualTo(32);
        assertThat(limits.particlesPerRun()).isEqualTo(WorldLimits.DEFAULTS.particlesPerRun());
        assertThat(limits.dropStacksPerRun()).isEqualTo(WorldLimits.DEFAULTS.dropStacksPerRun());
    }

    @Test
    void aBadValueFallsBackToItsDefaultWithOneWarningNamingTheKeyAndTheRange() {
        var problems = new ArrayList<Diagnostic>();
        List<Object> bad = List.of("lots", 0, -3, 2.5, 99, List.of(1));
        var settings = Map.<String, Object>of("world_verbs_max_radius", bad.get(4),
                "world_verbs_max_blocks_per_run", bad.get(0), "world_verbs_max_particles_per_step", bad.get(1),
                "world_verbs_max_launches_per_step", bad.get(2), "world_verbs_max_drop_stacks_per_run", bad.get(3),
                "world_verbs_max_particles_per_run", bad.get(5));

        var limits = WorldLimits.read(settings, problems::add);

        assertThat(limits).isEqualTo(WorldLimits.DEFAULTS);
        assertThat(problems).hasSize(6).allSatisfy(problem -> {
            assertThat(problem.code()).isEqualTo(DiagnosticCodes.VERB_SETTING_INVALID);
            assertThat(problem.message()).contains("world_verbs_max_").contains("the default");
        });
        assertThat(problems).extracting(Diagnostic::message).anySatisfy(message ->
                assertThat(message).contains("world_verbs_max_radius").contains("1 to 8"));
    }
}
