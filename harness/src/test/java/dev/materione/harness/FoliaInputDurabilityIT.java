package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Row R2 of gui-input-durability.md (#306), probe P1 / F12 on a Folia 1.21.4 server: the player's save does not interleave
 * with a step on the player's entity scheduler, shown against a positive control. Opt-in
 * ({@code -Pharness.folia=true}: downloads the sha256-pinned Folia jar into the Gradle cache and needs
 * {@code npm ci --ignore-scripts} in {@code harness/protocol}); see {@link FoliaRunner}.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "harness.folia", matches = "true")
class FoliaInputDurabilityIT {

    @Test
    void theFoliaPlayerSaveRunsOnTheThreadThatRunsEntitySchedulerTasks() {
        HarnessResult result = new FoliaRunner().run(HarnessOptions.fromProperties());
        assertThat(result.ok()).as(result.summary()).isTrue();
    }
}
