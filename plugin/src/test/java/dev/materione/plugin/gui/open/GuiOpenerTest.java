package dev.materione.plugin.gui.open;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.open.OpenGate.Admission;
import dev.materione.plugin.gui.session.CloseReason;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one open path (logic.md §6.1) and the session lifecycle around it: every stage, in order, with the
 * triggers it fires, and the replace and back-stack behaviour that {@code gui.open} and {@code gui.back}
 * rest on.
 */
class GuiOpenerTest {

    private static final String SHOP = """
            shop:
              title: Shop
              rows: 3
              layers: []
              behavior:
                - on: gui.opening
                  do:
                    - mark: "opening ${gui.id} category=${gui.args.category}"
                - on: gui.open
                  do:
                    - mark: "open ${gui.id} tab=${gui.session.tab}"
                - on: gui.close
                  do:
                    - mark: "close-any reason?"
                - on: gui.close
                  reason: replaced
                  do:
                    - mark: "closed-replaced"
                - on: gui.close
                  reason: [player, action]
                  do:
                    - mark: "closed-by-player-or-flow"
            """;

    private static final String MAP = """
            map:
              title: Map
              rows: 1
              layers: []
              behavior:
                - on: gui.open
                  do:
                    - mark: "open ${gui.id}"
            """;

    private GuiFixture f;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        f.gui(SHOP);
        f.gui(MAP);
    }

    private OpenResult open(String name, Map<String, String> args, OpenRequest.Cause cause) {
        return f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id(name), args, cause));
    }

    @Test
    void openingRunsEveryStageInOrder() {
        OpenResult result = open("shop", Map.of("category", "tools"), OpenRequest.Cause.API);

        assertThat(result.ok()).isTrue();
        GuiSession session = result.session();
        assertThat(f.sessions.current(f.viewer.id())).containsSame(session);
        assertThat(session.args()).containsEntry("category", "tools");
        assertThat(f.windows.log).containsExactly("open shop");
        // gui.opening fired before the window, gui.open after it; the session variable is not set yet
        assertThat(f.ran).containsExactly("mark:opening demo:shop category=tools", "mark:open demo:shop tab=");
    }

    @Test
    void aGuiThatIsNotLiveIsRefusedAndNothingChanges() {
        OpenResult result = open("ghost", Map.of(), OpenRequest.Cause.API);

        assertThat(result.outcome()).isEqualTo(OpenResult.Outcome.NOT_LIVE);
        assertThat(result.reason()).contains("demo:ghost").contains("not live");
        assertThat(f.sessions.openCount()).isZero();
        assertThat(f.windows.log).isEmpty();
    }

    @Test
    void theGateDecidesTheArgumentsAndCanRefuse() {
        f.opener.gate((viewer, gui, args) -> args.containsKey("category")
                ? Admission.allow(Map.of("category", args.get("category").toUpperCase(), "typed", "yes"))
                : Admission.deny("category is required"));

        assertThat(open("shop", Map.of(), OpenRequest.Cause.API).outcome()).isEqualTo(OpenResult.Outcome.DENIED);
        assertThat(f.sessions.openCount()).isZero();
        assertThat(f.ran).as("a refusal fires nothing").isEmpty();

        OpenResult allowed = open("shop", Map.of("category", "food"), OpenRequest.Cause.API);
        assertThat(allowed.session().args()).containsEntry("category", "FOOD").containsEntry("typed", "yes");
    }

    @Test
    void aCancellingOpeningRuleStopsTheOpen() {
        f.gui("""
                locked:
                  title: Locked
                  rows: 1
                  layers: []
                  behavior:
                    - on: gui.opening
                      do:
                        - mark: knocked
                        - cancel: true
                    - on: gui.open
                      do:
                        - mark: must-not-run
                """);

        OpenResult result = open("locked", Map.of(), OpenRequest.Cause.API);

        assertThat(result.outcome()).isEqualTo(OpenResult.Outcome.CANCELLED);
        assertThat(f.ran).containsExactly("mark:knocked");
        assertThat(f.sessions.openCount()).isZero();
        assertThat(f.windows.log).isEmpty();
    }

    @Test
    void openingAnotherGuiReplacesTheFirstFiresItsCloseAndRemembersIt() {
        GuiSession shop = open("shop", Map.of("category", "tools"), OpenRequest.Cause.API).session();
        shop.vars().put("tab", "weapons");
        f.ran.clear();

        GuiSession map = open("map", Map.of(), OpenRequest.Cause.ACTION).session();

        assertThat(shop.endedBecause()).isEqualTo(CloseReason.REPLACED);
        assertThat(f.sessions.current(f.viewer.id())).containsSame(map);
        // the replaced GUI's close rules (any reason, and reason: replaced) ran, then the new GUI opened
        assertThat(f.ran).containsExactly("mark:close-any reason?", "mark:closed-replaced", "mark:open demo:map");
        assertThat(f.sessions.history(f.viewer.id()).peek().orElseThrow().vars()).containsEntry("tab", "weapons");
    }

    @Test
    void goingBackRestoresTheArgumentsAndTheSessionVariables() {
        GuiSession shop = open("shop", Map.of("category", "tools"), OpenRequest.Cause.API).session();
        shop.vars().put("tab", "weapons");
        open("map", Map.of(), OpenRequest.Cause.ACTION);
        f.ran.clear();

        GuiSession.Snapshot previous = f.sessions.history(f.viewer.id()).pop().orElseThrow();
        GuiSession back = f.opener.open(OpenRequest.back(f.viewer, previous)).session();

        assertThat(back).isNotSameAs(shop);
        assertThat(back.gui()).isEqualTo(GuiFixture.id("shop"));
        assertThat(back.args()).containsEntry("category", "tools");
        assertThat(back.vars()).containsEntry("tab", "weapons");
        // the restored variable is visible to the rules that open it
        assertThat(f.ran).contains("mark:open demo:shop tab=weapons");
        // going back does not push the window it leaves, so the stack is empty now
        assertThat(f.sessions.history(f.viewer.id()).size()).isZero();
    }

    @Test
    void openingTheSameGuiAgainReplacesItToo() {
        GuiSession first = open("shop", Map.of("category", "a"), OpenRequest.Cause.API).session();
        GuiSession second = open("shop", Map.of("category", "b"), OpenRequest.Cause.API).session();

        assertThat(first.endedBecause()).isEqualTo(CloseReason.REPLACED);
        assertThat(second.args()).containsEntry("category", "b");
        assertThat(f.sessions.history(f.viewer.id()).size()).isEqualTo(1);
    }

    @Test
    void aWindowTheHostCouldNotOpenLeavesNoSession() {
        f.windows.failOpenWith = "the server said no";

        OpenResult result = open("shop", Map.of(), OpenRequest.Cause.API);

        assertThat(result.ok()).as("the session was created before the window").isTrue();
        assertThat(f.sessions.current(f.viewer.id())).as("and ended when the window failed").isEmpty();
        assertThat(f.ran).as("gui.open never fires for a window that never opened")
                .containsExactly("mark:opening demo:shop category=");
    }

    @Test
    void guiOpenWaitsForTheWindowWhenTheHostDefersIt() {
        f.windows.defer = true;

        GuiSession session = open("shop", Map.of(), OpenRequest.Cause.API).session();

        assertThat(f.sessions.current(f.viewer.id())).as("the session switches at once").containsSame(session);
        assertThat(f.ran).containsExactly("mark:opening demo:shop category=");

        f.windows.deferred.forEach(Runnable::run);
        assertThat(f.ran).containsExactly("mark:opening demo:shop category=", "mark:open demo:shop tab=");
    }

    @Test
    void aCloseRuleThatOpensSomethingElseWinsOverTheOpenInProgress() {
        f.gui("""
                bounce:
                  title: Bounce
                  rows: 1
                  layers: []
                  behavior:
                    - on: gui.close
                      reason: replaced
                      do:
                        - gui.open: map
                """);
        open("bounce", Map.of(), OpenRequest.Cause.API);

        OpenResult result = open("shop", Map.of(), OpenRequest.Cause.API);

        assertThat(result.outcome()).isEqualTo(OpenResult.Outcome.CANCELLED);
        assertThat(f.sessions.current(f.viewer.id()).orElseThrow().gui()).isEqualTo(GuiFixture.id("map"));
    }
}
