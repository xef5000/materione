package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.content.ViewFacet;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.gui.GuiViewKind;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.plugin.runtime.PipelineService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The view facet is self-description (views.md section 9, D5): a content type says it is a view kind, and the editor,
 * the SDK generator and the {@code view.open} reference check learn which types are views from the registry alone. The
 * container GUI is the only registered view kind in row V0; its facet is the one its {@link ViewKind} declares, so the
 * two cannot say different things.
 */
class ViewFacetSelfDescriptionTest {

    private final RegistryService registries = new RegistryService();
    private final PipelineService pipeline = new PipelineService(null, new DiagnosticsBusService(64),
            new QuarantineTracker(), registries, null, new PlatformDescriptor("1.21.4", false, false, false, Set.of()));

    ViewFacetSelfDescriptionTest() {
        LogicTestSupport.withBuiltIns(registries, List.of("zombie", "player"));
        pipeline.registerBuiltins();
    }

    @Test
    void theGuiIsTheOnlyRegisteredViewKindAndItIsOpened() {
        var kinds = ViewFacet.byType(pipeline.contentTypes());

        assertThat(kinds).containsOnlyKeys("gui");
        ViewFacet gui = kinds.get("gui");
        assertThat(gui.viewerRule()).isEqualTo(ViewFacet.ViewerRule.OPENED);
        assertThat(gui.rerenderDefault()).isEqualTo(ViewFacet.RerenderPolicy.ON_CHANGE);
        assertThat(gui.supportsTarget()).as("target: arrives with row V4").isFalse();
        assertThat(gui.supportsKey()).as("key: arrives with row V5").isFalse();
        assertThat(gui.openable()).isTrue();
    }

    @Test
    void theKindsFacetIsTheSelfDescriptionsFacet() {
        assertThat(GuiViewKind.FACET).isEqualTo(ViewFacet.byType(pipeline.contentTypes()).get("gui"));
    }

    @Test
    void theFacetsValuesAreTheWordsTheEditorAndTheSdkRead() {
        assertThat(ViewFacet.ViewerRule.values()).extracting(ViewFacet.ViewerRule::id)
                .containsExactly("opened", "predicate", "spatial");
        assertThat(ViewFacet.RerenderPolicy.values()).extracting(ViewFacet.RerenderPolicy::id)
                .containsExactly("on_change", "off");
        assertThat(new ViewFacet(ViewFacet.ViewerRule.PREDICATE, ViewFacet.RerenderPolicy.ON_CHANGE, false, false)
                .openable()).as("a HUD shows itself: no verb opens it").isFalse();
    }
}
