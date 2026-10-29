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
}
