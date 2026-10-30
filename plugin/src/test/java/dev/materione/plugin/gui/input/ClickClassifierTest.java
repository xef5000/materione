package dev.materione.plugin.gui.input;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.gui.input.ClickClassifier.Click;
import java.util.List;
import java.util.Optional;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

/** The table from Bukkit's click types to the kinds rules filter on (logic.md §6.2). */
class ClickClassifierTest {

    private static Optional<ClickKind> kind(ClickType type) {
        return ClickClassifier.classify(type, -1).map(Click::kind);
    }

    @Test
    void everyClickTypeAMenuHandlesMapsToItsKind() {
        assertThat(kind(ClickType.LEFT)).contains(ClickKind.LEFT);
        assertThat(kind(ClickType.RIGHT)).contains(ClickKind.RIGHT);
        assertThat(kind(ClickType.SHIFT_LEFT)).contains(ClickKind.SHIFT_LEFT);
        assertThat(kind(ClickType.SHIFT_RIGHT)).contains(ClickKind.SHIFT_RIGHT);
        assertThat(kind(ClickType.MIDDLE)).contains(ClickKind.MIDDLE);
        assertThat(kind(ClickType.DOUBLE_CLICK)).contains(ClickKind.DOUBLE);
        assertThat(kind(ClickType.DROP)).contains(ClickKind.DROP);
        assertThat(kind(ClickType.CONTROL_DROP)).contains(ClickKind.CTRL_DROP);
        assertThat(kind(ClickType.SWAP_OFFHAND)).contains(ClickKind.SWAP_OFFHAND);
    }

    @Test
    void aNumberKeyClickCarriesTheKeyOneToNine() {
        assertThat(ClickClassifier.classify(ClickType.NUMBER_KEY, 0))
                .contains(new Click(ClickKind.NUMBER_KEY, 1));
        assertThat(ClickClassifier.classify(ClickType.NUMBER_KEY, 8))
                .contains(new Click(ClickKind.NUMBER_KEY, 9));
        // a key that is not on the hotbar is not a click a menu can name
        assertThat(ClickClassifier.classify(ClickType.NUMBER_KEY, -1)).isEmpty();
        assertThat(ClickClassifier.classify(ClickType.NUMBER_KEY, 9)).isEmpty();
    }

    @Test
    void otherKindsCarryNoKey() {
        assertThat(ClickClassifier.classify(ClickType.LEFT, 4)).contains(new Click(ClickKind.LEFT, 0));
    }

    @Test
    void clicksAMenuDoesNotHandleClassifyToNothing() {
        for (ClickType type : List.of(ClickType.CREATIVE, ClickType.UNKNOWN,
                ClickType.WINDOW_BORDER_LEFT, ClickType.WINDOW_BORDER_RIGHT)) {
            assertThat(ClickClassifier.classify(type, -1)).as(type.name()).isEmpty();
        }
    }

    @Test
    void everyBukkitClickTypeIsAccountedFor() {
        // a new Bukkit value would have to be placed in the table above; classify must not throw for any
        for (ClickType type : ClickType.values()) {
            ClickClassifier.classify(type, 0);
            ClickClassifier.outside(type);
        }
    }

    @Test
    void anOutsideClickIsOnlyLeftOrRight() {
        assertThat(ClickClassifier.outside(ClickType.LEFT)).contains(ClickKind.LEFT);
        assertThat(ClickClassifier.outside(ClickType.WINDOW_BORDER_LEFT)).contains(ClickKind.LEFT);
        assertThat(ClickClassifier.outside(ClickType.RIGHT)).contains(ClickKind.RIGHT);
        assertThat(ClickClassifier.outside(ClickType.WINDOW_BORDER_RIGHT)).contains(ClickKind.RIGHT);
        assertThat(ClickClassifier.outside(ClickType.SHIFT_LEFT)).isEmpty();
        assertThat(ClickClassifier.outside(ClickType.DROP)).isEmpty();
    }

    @Test
    void theKindIdsAreWhatAuthorsWrite() {
        assertThat(ClickKind.ids()).containsExactly("left", "right", "shift_left", "shift_right", "middle",
                "double", "drop", "ctrl_drop", "number_key", "swap_offhand");
        assertThat(ClickKind.of("ctrl_drop")).contains(ClickKind.CTRL_DROP);
        assertThat(ClickKind.of("nope")).isEmpty();
    }
}
