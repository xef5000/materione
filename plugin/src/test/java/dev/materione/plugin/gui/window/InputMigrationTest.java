package dev.materione.plugin.gui.window;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InputMigrationTest {
    @Test
    void coveringAnInputReturnsItsStackAndKeepsOtherOwnedStacks() {
        var kept = new LinkedHashMap<Integer, String>();
        var returned = new ArrayList<String>();
        InputMigration.move(Map.of(0, "diamonds", 1, "iron"), Set.of(1, 2), kept::put, returned::add);
        assertThat(kept).containsExactly(Map.entry(1, "iron"));
        assertThat(returned).containsExactly("diamonds");
    }
}
