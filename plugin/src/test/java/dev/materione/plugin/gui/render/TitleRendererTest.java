package dev.materione.plugin.gui.render;

import dev.materione.content.gui.GuiContainers;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.gui.TitleSink;
import dev.materione.content.gui.GuiTitle;
import dev.materione.content.gui.GuiVisualPlan;
import dev.materione.plugin.gui.dynamic.TitleProgram;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TitleRendererTest {
    private static GuiVisualPlan.Glyph glyph(int codepoint, int x, int inkWidth, Set<Integer> slots) {
        return new GuiVisualPlan.Glyph(List.of(new GuiTitle.ArtGlyph(codepoint, x, inkWidth)), slots);
    }

    @Test
    void everyArtPieceReturnsThePenToTheOriginSoPiecesAssembleInAnyOrder() {
        var plan = new GuiVisualPlan("shop", List.of(
                new GuiVisualPlan.Raster("far", 0, null, List.of(new GuiVisualPlan.Glyph(
                        List.of(new GuiTitle.ArtGlyph(0xE201, -30, 20), new GuiTitle.ArtGlyph(0xE202, 100, 10)),
                        Set.of())), 0),
                new GuiVisualPlan.Raster("none", 1, null, List.of(new GuiVisualPlan.Glyph(List.of(), Set.of())), 0)));
        var program = TitleProgram.compile(RenderFixtures.GUI, plan, RenderFixtures.lookup());
        var title = new TitleRenderer(RenderReporter.SILENT)
                .render(RenderFixtures.GUI, GuiContainers.byId("chest_1").orElseThrow(), program.program(), RenderFixtures.viewer(1), 0).title();
        // pen 8 -> -30 (shift -38), 20 ink + 1 -> -9, -> 100 (shift 109), 10 ink + 1 -> 111, back to 8 (shift -103);
        // a frame that paints nothing adds nothing, not even a shift
        assertThat(title).isEqualTo("<shift:-38><font:shop:gui><#FFFFFF></font><shift:109><font:shop:gui><#FFFFFF>"
                + "</font><shift:-103>");
    }

    @Test
    void titleAndClickCoverageFollowTheSameConditionAndFrameInLayerOrder() {
        var plan = new GuiVisualPlan("shop", List.of(
                new GuiVisualPlan.Raster("base", 0, null,
                        List.of(glyph(0xE201, 0, 29, Set.of(0))), 0),
                new GuiVisualPlan.Raster("tab", 2, "player.level > 5",
                        List.of(glyph(0xE202, 0, 19, Set.of(0)),
                                glyph(0xE203, 0, 23, Set.of(1))), 5),
                new GuiVisualPlan.Text("label", 1, "player.level > 5", 8, 19,
                        "Hi ${player.name}", TitleSink.Font.DEFAULT, 0xFFFFFFFF)));
        var program = TitleProgram.compile(RenderFixtures.GUI, plan, RenderFixtures.lookup());
        assertThat(program.problems()).isEmpty();
        var renderer = new TitleRenderer(RenderReporter.SILENT);
        var hidden = renderer.render(RenderFixtures.GUI, GuiContainers.byId("chest_1").orElseThrow(), program.program(), RenderFixtures.viewer(1), 0);
        assertThat(hidden.hidden()).containsExactlyInAnyOrder("label", "tab");
        assertThat(hidden.title()).contains("\uE201").doesNotContain("\uE202", "Hi Probe");
        var shown = renderer.render(RenderFixtures.GUI, GuiContainers.byId("chest_1").orElseThrow(), program.program(), RenderFixtures.viewer(9), 5);
        assertThat(shown.hidden()).isEmpty();
        assertThat(shown.title()).contains("Hi Probe", "\uE203").doesNotContain("\uE202");
        assertThat(shown.title().indexOf("\uE201")).isLessThan(shown.title().indexOf("Hi Probe"));
        assertThat(shown.title().indexOf("\uE203")).isLessThan(shown.title().indexOf("Hi Probe"));
        assertThat(shown.coverage().get("tab")).containsExactly(1);
        assertThat(shown.coverage().get("label")).contains(0);
        assertThat(renderer.render(RenderFixtures.GUI, GuiContainers.byId("chest_1").orElseThrow(), program.program(), RenderFixtures.viewer(9), 6).title())
                .isEqualTo(shown.title());
        assertThat(renderer.render(RenderFixtures.GUI, GuiContainers.byId("chest_1").orElseThrow(), program.program(), RenderFixtures.viewer(9), 10).title())
                .isNotEqualTo(shown.title());
    }

    @Test
    void invalidConditionIsHiddenAndMalformedTextIsReported() {
        var plan = new GuiVisualPlan("shop", List.of(
                new GuiVisualPlan.Text("bad", 0, "player.level >", 8, 18,
                        "Hi ${player.name", TitleSink.Font.DEFAULT, 0xFFFFFFFF)));
        var result = TitleProgram.compile(RenderFixtures.GUI, plan, RenderFixtures.lookup());
        assertThat(result.problems()).isNotEmpty();
        var rendered = new TitleRenderer(RenderReporter.SILENT)
                .render(RenderFixtures.GUI, GuiContainers.byId("chest_1").orElseThrow(), result.program(), RenderFixtures.viewer(9), 0);
        assertThat(rendered.hidden()).containsExactly("bad");
        assertThat(rendered.title()).doesNotContain("Hi");
    }
}
