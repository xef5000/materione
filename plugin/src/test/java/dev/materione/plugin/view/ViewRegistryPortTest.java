package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import dev.materione.plugin.gui.session.GuiViewerState;

/** The live answers behind {@code %materione_view_open%}, {@code %materione_view_last%} and {@code vars.session.*}. */
class ViewRegistryPortTest {

    private static final NamespacedId SHOP = NamespacedId.parse("demo:shop");
    private static final NamespacedId MAP = NamespacedId.parse("demo:map");

    private final ViewRegistry sessions = new ViewRegistry();
    private final ViewRegistryPort port = new ViewRegistryPort(sessions);
    private final UUID alex = UUID.randomUUID();

    private ViewerRecord session(NamespacedId gui) {
        return GuiViewerState.record(alex, "Alex", gui, Map.of());
    }

    @Test
    void aPlayerWithNoGuiHasNeitherAnOpenNorALastOne() {
        assertThat(port.openView(alex)).isEmpty();
        assertThat(port.lastView(alex)).isEmpty();
    }

    @Test
    void openingAnotherGuiMakesTheFirstTheLastOne() {
        sessions.begin(session(SHOP));
        assertThat(port.openView(alex)).contains("gui:demo:shop");
        assertThat(port.lastView(alex)).isEmpty();

        sessions.begin(session(MAP));
        assertThat(port.openView(alex)).contains("gui:demo:map");
        assertThat(port.lastView(alex)).contains("gui:demo:shop");
    }

    @Test
    void closingAGuiLeavesNothingOpenAndMakesItTheLastOne() {
        ViewerRecord shop = session(SHOP);
        sessions.begin(shop);
        sessions.end(shop, CloseReason.PLAYER);

        assertThat(port.openView(alex)).isEmpty();
        assertThat(port.lastView(alex)).contains("gui:demo:shop");
    }

    @Test
    void endingASessionThatIsNoLongerCurrentDoesNotChangeTheLastOne() {
        ViewerRecord shop = session(SHOP);
        sessions.begin(shop);
        sessions.begin(session(MAP));
        sessions.end(shop, CloseReason.REPLACED);

        assertThat(port.lastView(alex)).contains("gui:demo:shop");
        assertThat(port.openView(alex)).contains("gui:demo:map");
    }

    @Test
    void sessionVariablesAreTheSessionsOwnMap() {
        ViewerRecord shop = session(SHOP);
        var variables = new ViewerSessionVariables(shop);

        variables.put("page", 2);
        assertThat(shop.sessionValues()).containsEntry("page", 2);
        shop.sessionValue("tab", "tools");
        assertThat(variables.get("tab")).contains("tools");

        variables.put("page", null);
        assertThat(shop.sessionValues()).doesNotContainKey("page");
        assertThat(variables.get("page")).isEmpty();
    }
}
