package dev.materione.pack.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.AllocatedRange;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The preview view (gui.md §10 #8): the same answers as the table, and nothing bound or counted. */
class AllocationPreviewTest {

    private static final NamespacedId GUI = NamespacedId.of("shop", "main");

    @TempDir
    Path temp;

    @Test
    void aPreviewAnswersWhatTheTableWouldAndBindsNothing() {
        AllocationTable table = AllocationTable.load(temp.resolve("allocation.bin"));
        var preview = table.preview();

        AllocatedRange previewed = preview.range(GUI, "gui-background", 1);
        assertThat(table.liveCount()).as("a preview binds nothing").isZero();
        assertThat(table.allocationOf(GUI, "gui-background")).isEmpty();

        AllocatedRange real = table.range(GUI, "gui-background", 1);
        assertThat(previewed).as("the table gives the value the preview promised").isEqualTo(real);
        assertThat(table.liveCount()).isEqualTo(1);
    }

    @Test
    void aPreviewOfAnAlreadyBoundResourceReturnsItsBinding() {
        AllocationTable table = AllocationTable.load(temp.resolve("allocation.bin"));
        AllocatedRange bound = table.range(GUI, "gui-background", 1);
        var preview = table.preview();
        assertThat(preview.range(GUI, "gui-background", 1)).isEqualTo(bound);
        assertThat(preview.lookup(GUI, "gui-background")).contains(bound.toString());
        assertThat(preview.allocate(NamespacedId.of("shop", "other"), "glyph-codepoint")).isEqualTo("1");
        assertThat(table.liveCount()).isEqualTo(1);
    }

    @Test
    void multiplePreviewAllocationsMatchARealCompileWithoutMutatingTheTable() {
        AllocationTable table = AllocationTable.load(temp.resolve("allocation.bin"));
        NamespacedId first = NamespacedId.of("shop", "overlay/first");
        NamespacedId second = NamespacedId.of("shop", "overlay/second");
        var preview = table.preview();

        assertThat(preview.range(GUI, "gui-background", 1).start()).isEqualTo(1);
        assertThat(preview.range(first, "gui-background", 1).start()).isEqualTo(2);
        assertThat(preview.allocate(second, "gui-background")).isEqualTo("3");
        assertThat(preview.range(first, "gui-background", 1).start()).isEqualTo(2);
        assertThat(preview.lookup(second, "gui-background")).contains("3");
        assertThat(table.liveCount()).isZero();
        assertThat(table.range(GUI, "gui-background", 1).start()).isEqualTo(1);
        assertThat(table.range(first, "gui-background", 1).start()).isEqualTo(2);
        assertThat(table.allocate(second, "gui-background")).isEqualTo("3");
    }

    @Test
    void previewKeepsTombstonesAndSimulatesRangeGrowthWithoutChangingTheTable() {
        AllocationTable table = AllocationTable.load(temp.resolve("allocation.bin"));
        NamespacedId retired = NamespacedId.of("shop", "retired");
        table.range(retired, "gui-background", 1);
        table.tombstone(retired, "gui-background");
        var preview = table.preview();

        assertThat(preview.range(retired, "gui-background", 1).start()).isEqualTo(1);
        assertThat(preview.lookup(retired, "gui-background")).contains("1");
        assertThat(preview.range(retired, "gui-background", 2).toString()).isEqualTo("2,2");
        assertThat(preview.allocate(GUI, "gui-background")).isEqualTo("4");
        assertThat(table.allocationOf(retired, "gui-background")).isEmpty();
        assertThat(table.allocate(GUI, "gui-background")).isEqualTo("2");
    }

    @Test
    void previewsAreIndependentSnapshots() {
        AllocationTable table = AllocationTable.load(temp.resolve("allocation.bin"));
        var first = table.preview();
        assertThat(first.allocate(GUI, "gui-background")).isEqualTo("1");
        var second = table.preview();
        assertThat(second.allocate(NamespacedId.of("shop", "other"), "gui-background")).isEqualTo("1");
        assertThat(first.lookup(NamespacedId.of("shop", "other"), "gui-background")).isEmpty();
        assertThat(second.lookup(GUI, "gui-background")).isEmpty();
        assertThat(table.liveCount()).isZero();
    }
}
