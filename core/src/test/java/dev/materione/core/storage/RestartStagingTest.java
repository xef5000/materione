package dev.materione.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The staged-restart queue: stage → load → boot-apply drain round-trip.
 * M2 has no producers; this stub-level exercise is the proof the machinery
 * works for the M3+ classifiers (MTR-0028/0029 ride these entries).
 */
class RestartStagingTest {

    @TempDir
    Path dir;

    @Test
    void stagesAndLoadsRoundTrip() {
        RestartStaging staging = RestartStaging.open(dir.resolve("staged-restart"));
        assertThat(staging.stage(NamespacedId.parse("sample:beacon_base"), "beacon base changed"))
                .isTrue();
        assertThat(staging.stage(NamespacedId.parse("corpus:datapack_side"), "side output added"))
                .isTrue();
        staging.close();

        List<RestartStaging.Entry> loaded = RestartStaging.open(dir.resolve("staged-restart")).load();
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).resource()).isEqualTo(NamespacedId.parse("sample:beacon_base"));
        assertThat(loaded.get(0).reason()).isEqualTo("beacon base changed");
    }

    @Test
    void bootApplyDrainsTheQueue() {
        RestartStaging staging = RestartStaging.open(dir.resolve("staged-restart"));
        staging.stage(NamespacedId.parse("sample:x"), "reason");
        staging.close();

        // boot path: open, load, report, drain
        RestartStaging reboot = RestartStaging.open(dir.resolve("staged-restart"));
        List<RestartStaging.Entry> pending = reboot.load();
        assertThat(pending).hasSize(1);
        assertThat(reboot.clear()).isEqualTo(0);
        reboot.close();

        assertThat(RestartStaging.open(dir.resolve("staged-restart")).load()).isEmpty();
    }

    @Test
    void tornLinesAreSkippedNotFatal() throws Exception {
        var file = dir.resolve("staged-restart").resolve("staged.jsonl");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                file.getParent().toFile().mkdirs() || true);
        java.nio.file.Files.writeString(file,
                "{\"resource\":\"sample:x\",\"reason\":\"ok\"}\nnot json at all\n");

        assertThat(RestartStaging.open(dir.resolve("staged-restart")).load())
                .hasSize(1);
    }
}
