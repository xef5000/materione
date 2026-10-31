package dev.materione.plugin.gui.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The live answers behind {@code %materione_gui_open%}, {@code %materione_gui_last%} and {@code vars.session.*}. */
class GuiSessionsPortTest {

    private static final NamespacedId SHOP = NamespacedId.parse("demo:shop");
    private static final NamespacedId MAP = NamespacedId.parse("demo:map");

    private final GuiSessions sessions = new GuiSessions();
    private final GuiSessionsPort port = new GuiSessionsPort(sessions);
    private final UUID alex = UUID.randomUUID();

    private GuiSession session(NamespacedId gui) {
        return new GuiSession(alex, "Alex", gui, Map.of());
    }

    @Test
    void aPlayerWithNoGuiHasNeitherAnOpenNorALastOne() {
        assertThat(port.openGui(alex)).isEmpty();
        assertThat(port.lastGui(alex)).isEmpty();
    }

    @Test
    void openingAnotherGuiMakesTheFirstTheLastOne() {
        sessions.begin(session(SHOP));
        assertThat(port.openGui(alex)).contains("demo:shop");
        assertThat(port.lastGui(alex)).isEmpty();

        sessions.begin(session(MAP));
        assertThat(port.openGui(alex)).contains("demo:map");
        assertThat(port.lastGui(alex)).contains("demo:shop");
    }

    @Test
    void closingAGuiLeavesNothingOpenAndMakesItTheLastOne() {
        GuiSession shop = session(SHOP);
        sessions.begin(shop);
        sessions.end(shop, CloseReason.PLAYER);

        assertThat(port.openGui(alex)).isEmpty();
        assertThat(port.lastGui(alex)).contains("demo:shop");
    }

    @Test
    void endingASessionThatIsNoLongerCurrentDoesNotChangeTheLastOne() {
        GuiSession shop = session(SHOP);
        sessions.begin(shop);
        sessions.begin(session(MAP));
        sessions.end(shop, CloseReason.REPLACED);

        assertThat(port.lastGui(alex)).contains("demo:shop");
        assertThat(port.openGui(alex)).contains("demo:map");
    }

    @Test
    void sessionVariablesAreTheSessionsOwnMap() {
        GuiSession shop = session(SHOP);
        var variables = new GuiSessionVariables(shop);

        variables.put("page", 2);
        assertThat(shop.vars()).containsEntry("page", 2);
        shop.vars().put("tab", "tools");
        assertThat(variables.get("tab")).contains("tools");

        variables.put("page", null);
        assertThat(shop.vars()).doesNotContainKey("page");
        assertThat(variables.get("page")).isEmpty();
    }
}
