package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.ViewFacet;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.ParamReader;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.gui.session.GuiViewerState;
import dev.materione.plugin.logic.action.view.BackViewAction;
import dev.materione.plugin.logic.action.view.CloseViewAction;
import dev.materione.plugin.logic.action.view.OpenViewAction;
import dev.materione.plugin.view.open.OpenRequest;
import dev.materione.plugin.view.open.OpenResult;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The generic view verbs against a second view kind (views.md section 3.6, row V0): {@code view.open},
 * {@code view.close} and {@code view.back} name no kind, so a kind registered beside the GUI is reachable from them, the
 * view history spans the two, and a kind whose viewers are not {@code opened} (a HUD) is not openable. The second kind
 * here is a fake that records its viewers in the same {@link ViewRegistry} a real dialog would.
 */
class ViewVerbsTest {

    private static final String MENU = "menu:\n  title: Menu\n  container: chest_3\n  layers: []\n";
    private static final String SHOP = "shop:\n  title: Shop\n  container: chest_3\n  layers: []\n";

    /** A second kind: opens a viewer record with an empty payload, the way a dialog opener would. */
    private static final class FakeKind implements ViewKind {

        private final String contentType;
        private final ViewFacet facet;
        private final ViewRegistry views;
        private final Set<NamespacedId> live = new HashSet<>();

        FakeKind(String contentType, ViewFacet.ViewerRule rule, ViewRegistry views, String... ids) {
            this.contentType = contentType;
            this.facet = new ViewFacet(rule, ViewFacet.RerenderPolicy.OFF, false, false);
            this.views = views;
            for (String id : ids) {
                live.add(NamespacedId.of(GuiFixture.PACK, id));
            }
        }

        @Override
        public String contentType() {
            return contentType;
        }

        @Override
        public ViewFacet facet() {
            return facet;
        }

        @Override
        public int minInterval() {
            return 20;
        }

        @Override
        public Optional<NamespacedId> resolve(String written, NamespacedId near) {
            String text = written.trim();
            NamespacedId id = text.contains(":")
                    ? NamespacedId.parse(text) : NamespacedId.of(near != null ? near.namespace() : GuiFixture.PACK, text);
            return live.contains(id) ? Optional.of(id) : Optional.empty();
        }

        @Override
        public OpenResult open(OpenRequest request) {
            var record = new ViewerRecord(request.viewer().id(), request.viewer().name(), contentType, request.view(),
                    request.args(), new ViewerPayload() { });
            if (request.restore() != null) {
                record.restore(request.restore());
            }
            ViewerRecord previous = views.begin(record).orElse(null);
            if (previous != null) {
                if (request.cause() != OpenRequest.Cause.BACK) {
                    views.history(request.viewer().id()).push(previous.snapshot());
                }
                views.end(previous, CloseReason.REPLACED);
            }
            return OpenResult.opened(record);
        }

        @Override
        public boolean close(Viewer viewer, ViewerRecord record) {
            return views.end(record, CloseReason.ACTION);
        }

        @Override
        public boolean render(Viewer viewer, ViewerRecord record) {
            return record.isOpen();
        }
    }

    private GuiFixture f;
    private FakeKind dialog;
    private ViewerRecord menu;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        f.gui(MENU);
        f.gui(SHOP);
        dialog = new FakeKind("dialog", ViewFacet.ViewerRule.OPENED, f.sessions, "ask", "shop");
        f.kinds.register(dialog);
        f.kinds.register(new FakeKind("hud", ViewFacet.ViewerRule.PREDICATE, f.sessions, "mana"));
        menu = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("menu"), Map.of("page", "2"),
                OpenRequest.Cause.API)).record();
    }

    private MapLogicContext context(ViewerRecord record) {
        if (record != null && GuiViewerState.KIND.equals(record.kind())) {
            return GuiContexts.build(f.viewer, record, Map.of());
        }
        // a run of a kind with no context builder here: the player and the record are what a view verb reads
        var bare = new MapLogicContext(Map.of()).with(Facets.PLAYER, f.player);
        return record == null ? bare : bare.with(ViewerRecord.FACET, record);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ActionOutcome run(ActionType action, Map<String, Object> params, MapLogicContext context) {
        return action.execute(action.parse(ParamReader.of(params, EngineExpressions.INSTANCE)), context);
    }

    private ActionOutcome open(Map<String, Object> params, ViewerRecord from) {
        return run(new OpenViewAction(f.bridge.view()), params, context(from));
    }

    @Test
    void viewOpenReachesAKindItDoesNotNameAndTheGuiStaysOpenBesideIt() {
        assertThat(open(Map.of("view", "demo:ask", "args", Map.of("topic", "rules")), menu)).isEqualTo(ActionOutcome.OK);

        ViewerRecord opened = f.sessions.current(f.viewer.id(), "dialog").orElseThrow();
        assertThat(opened.args()).containsEntry("topic", "rules");
        assertThat(f.sessions.current(f.viewer.id(), "gui")).as("a dialog over a container").containsSame(menu);
        assertThat(f.sessions.current(f.viewer.id())).as("the latest is the player's current").containsSame(opened);
        assertThat(menu.isOpen()).isTrue();
    }

    @Test
    void aBareNameThatMeansTwoKindsIsRefusedAndAQualifiedOneIsNot() {
        // 'shop' is both a GUI and a dialog of this pack, and nothing is guessed
        ActionOutcome ambiguous = open(Map.of("view", "shop"), null);

        assertThat(ambiguous).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(((ActionOutcome.Failed) ambiguous).reason()).contains("more than one view");
    }

    @Test
    void aBareNameWrittenInAGuiPrefersTheGuiOfThatNameBesideIt() {
        assertThat(open(Map.of("view", "shop"), menu)).isEqualTo(ActionOutcome.OK);

        assertThat(f.sessions.current(f.viewer.id(), "gui").orElseThrow().definition())
                .isEqualTo(GuiFixture.id("shop"));
        assertThat(f.sessions.current(f.viewer.id(), "dialog")).isEmpty();
    }

    @Test
    void theKindSwitchNamesWhichViewAnIdMeansWhenTwoKindsShareIt() {
        // demo:shop is a GUI and a dialog; without kind: nothing can tell them apart, with it either opens
        ActionOutcome ambiguous = open(Map.of("view", "demo:shop"), null);
        assertThat(ambiguous).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(((ActionOutcome.Failed) ambiguous).reason()).contains("more than one view").contains("kind:");

        assertThat(open(Map.of("view", "demo:shop", "kind", "dialog"), null)).isEqualTo(ActionOutcome.OK);
        assertThat(f.sessions.current(f.viewer.id(), "dialog").orElseThrow().definition()).isEqualTo(GuiFixture.id("shop"));

        assertThat(open(Map.of("view", "demo:shop", "kind", "gui"), null)).isEqualTo(ActionOutcome.OK);
        assertThat(f.sessions.current(f.viewer.id(), "gui").orElseThrow().definition()).isEqualTo(GuiFixture.id("shop"));
    }

    @Test
    void anUnknownKindOrAKindThatLacksTheViewFailsWithWhatItLooked() {
        ActionOutcome unknown = open(Map.of("view", "demo:shop", "kind", "map"), menu);
        assertThat(((ActionOutcome.Failed) unknown).reason()).startsWith("no view kind 'map'").contains("dialog").contains("gui");

        ActionOutcome lacks = open(Map.of("view", "demo:ask", "kind", "gui"), menu);
        assertThat(lacks).isEqualTo(ActionOutcome.failed("no live gui matches 'demo:ask'"));
    }

    @Test
    void viewCloseTakesTheKindSwitchAndTreatsABlankViewAsTheFlowsOwn() {
        assertThat(open(Map.of("view", "demo:shop", "kind", "dialog"), menu)).isEqualTo(ActionOutcome.OK);
        ViewerRecord shopDialog = f.sessions.current(f.viewer.id(), "dialog").orElseThrow();

        ActionOutcome closed = run(new CloseViewAction(f.bridge.view()),
                Map.of("view", "demo:shop", "kind", "dialog"), context(menu));
        assertThat(closed).isEqualTo(ActionOutcome.OK);
        assertThat(shopDialog.endedBecause()).isEqualTo(CloseReason.ACTION);
        assertThat(menu.isOpen()).isTrue();

        // the editor's empty shorthand (view.close: "") is the bare step: the flow's own view
        assertThat(run(new CloseViewAction(f.bridge.view()), Map.of("view", ""), context(menu))).isEqualTo(ActionOutcome.OK);
        assertThat(menu.endedBecause()).isEqualTo(CloseReason.ACTION);
    }

    @Test
    void aKindWhoseViewersAreFormedByACondition_isNotOpenable() {
        ActionOutcome outcome = open(Map.of("view", "demo:mana"), menu);

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(((ActionOutcome.Failed) outcome).reason())
                .contains("hud").contains("shows itself").contains("no verb opens it");
        assertThat(f.sessions.current(f.viewer.id(), "hud")).isEmpty();
    }

    @Test
    void unknownViewsFailWithTheName() {
        ActionOutcome outcome = open(Map.of("view", "ghost"), menu);

        assertThat(outcome).isEqualTo(ActionOutcome.failed("no live view matches 'ghost'"));
    }

    @Test
    void backAcrossKindsReopensTheKindTheSnapshotCameFrom() {
        // a dialog is replaced by a GUI-kind open: put its snapshot on the shared history by hand, as an opener does
        ViewerRecord ask = new ViewerRecord(f.viewer.id(), "Probe", "dialog", NamespacedId.of(GuiFixture.PACK, "ask"),
                Map.of("topic", "rules"), new ViewerPayload() { });
        ask.sessionValue("answer", "yes");
        f.sessions.history(f.viewer.id()).push(ask.snapshot());

        assertThat(run(new BackViewAction(f.bridge.view()), Map.of(), context(menu))).isEqualTo(ActionOutcome.OK);

        ViewerRecord reopened = f.sessions.current(f.viewer.id(), "dialog").orElseThrow();
        assertThat(reopened.definition()).isEqualTo(NamespacedId.of(GuiFixture.PACK, "ask"));
        assertThat(reopened.args()).containsEntry("topic", "rules");
        assertThat(reopened.sessionValues()).containsEntry("answer", "yes");
        assertThat(f.sessions.history(f.viewer.id()).size()).as("back pops, it does not grow the history").isZero();
    }

    @Test
    void aBackThatFailsKeepsItsHistoryEntry() {
        ViewerRecord gone = new ViewerRecord(f.viewer.id(), "Probe", "board", NamespacedId.of(GuiFixture.PACK, "x"),
                Map.of(), new ViewerPayload() { });
        f.sessions.history(f.viewer.id()).push(gone.snapshot());

        ActionOutcome outcome = run(new BackViewAction(f.bridge.view()), Map.of(), context(menu));

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(f.sessions.history(f.viewer.id()).peek()).as("the next back tries the same view again")
                .hasValueSatisfying(snapshot -> assertThat(snapshot.kind()).isEqualTo("board"));
        assertThat(menu.isOpen()).isTrue();
    }

    @Test
    void viewCloseWithAViewClosesThatOneIfItIsShownAndFailsOtherwise() {
        assertThat(open(Map.of("view", "demo:ask"), menu)).isEqualTo(ActionOutcome.OK);
        ViewerRecord ask = f.sessions.current(f.viewer.id(), "dialog").orElseThrow();

        ActionOutcome notShown = run(new CloseViewAction(f.bridge.view()), Map.of("view", "demo:shop"), context(menu));
        assertThat(notShown).isEqualTo(ActionOutcome.failed("view demo:shop is not open"));
        assertThat(ask.isOpen()).isTrue();

        ActionOutcome closed = run(new CloseViewAction(f.bridge.view()), Map.of("view", "demo:ask"), context(menu));
        assertThat(closed).isEqualTo(ActionOutcome.OK);
        assertThat(ask.endedBecause()).isEqualTo(CloseReason.ACTION);
        assertThat(menu.isOpen()).as("the GUI beside it is left alone").isTrue();
    }

    @Test
    void viewCloseWithNoViewClosesTheFlowsOwnViewOfWhateverKind() {
        assertThat(open(Map.of("view", "demo:ask"), menu)).isEqualTo(ActionOutcome.OK);
        ViewerRecord ask = f.sessions.current(f.viewer.id(), "dialog").orElseThrow();

        assertThat(run(new CloseViewAction(f.bridge.view()), Map.of(), context(ask))).isEqualTo(ActionOutcome.OK);

        assertThat(ask.endedBecause()).isEqualTo(CloseReason.ACTION);
        assertThat(menu.isOpen()).isTrue();
    }

    @Test
    void theFlowsViewIsTheKindsOwnPayload() {
        assertThat(GuiViewerState.of(menu)).isNotNull();
        ViewerRecord ask = new ViewerRecord(f.viewer.id(), "Probe", "dialog", NamespacedId.of(GuiFixture.PACK, "ask"),
                Map.of(), new ViewerPayload() { });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> GuiViewerState.of(ask))
                .isInstanceOf(IllegalStateException.class);
    }
}
