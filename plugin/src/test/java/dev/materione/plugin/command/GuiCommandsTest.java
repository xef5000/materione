package dev.materione.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.runtime.RuntimeApi;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code /materione gui open <id>} resolves what the operator typed (gui.md §5): a qualified id
 * must be live, a bare value must be unambiguous, and nothing is guessed.
 */
class GuiCommandsTest {

    private static final List<RuntimeApi.GuiView> LIVE = List.of(
            new RuntimeApi.GuiView(NamespacedId.parse("demo:shop"), 3, "Shop", 4),
            new RuntimeApi.GuiView(NamespacedId.parse("demo:arena"), 1, "Arena", 0),
            new RuntimeApi.GuiView(NamespacedId.parse("market:shop"), 6, "Market", 9));

    @Test
    void aQualifiedLiveIdResolvesToItself() {
        GuiCommands.Resolution found = GuiCommands.resolve("market:shop", LIVE);

        assertThat(found.status()).isEqualTo(GuiCommands.Status.RESOLVED);
        assertThat(found.id()).isEqualTo(NamespacedId.parse("market:shop"));
    }

    @Test
    void aQualifiedIdThatIsNotLiveIsUnknownEvenIfItParses() {
        assertThat(GuiCommands.resolve("demo:missing", LIVE).status()).isEqualTo(GuiCommands.Status.UNKNOWN);
    }

    @Test
    void aBareValueMatchesUniquelyByValue() {
        GuiCommands.Resolution found = GuiCommands.resolve("arena", LIVE);

        assertThat(found.status()).isEqualTo(GuiCommands.Status.RESOLVED);
        assertThat(found.id()).isEqualTo(NamespacedId.parse("demo:arena"));
    }

    @Test
    void aBareValueThatTwoPacksShareIsAmbiguousAndListsBoth() {
        GuiCommands.Resolution found = GuiCommands.resolve("SHOP", LIVE);

        assertThat(found.status()).isEqualTo(GuiCommands.Status.AMBIGUOUS);
        assertThat(found.candidates()).containsExactly(
                NamespacedId.parse("demo:shop"), NamespacedId.parse("market:shop"));
    }

    @Test
    void blankOrMalformedInputIsUnknown() {
        assertThat(GuiCommands.resolve("   ", LIVE).status()).isEqualTo(GuiCommands.Status.UNKNOWN);
        assertThat(GuiCommands.resolve("a:b:c", LIVE).status()).isEqualTo(GuiCommands.Status.UNKNOWN);
        assertThat(GuiCommands.resolve("nothing", LIVE).status()).isEqualTo(GuiCommands.Status.UNKNOWN);
    }
}
