package dev.materione.plugin.gui.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.gui.TitleSink;
import dev.materione.content.gui.GuiVisualPlan;
import dev.materione.plugin.gui.dynamic.TitleProgram;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TitleRendererTest {
    @Test
    void titleAndClickCoverageFollowTheSameConditionAndFrameInLayerOrder() {
        var plan = new GuiVisualPlan("shop", 0xE200, 1, List.of(
                new GuiVisualPlan.Raster("base", 0, null,
                        List.of(new GuiVisualPlan.Glyph(0xE201, 30, Set.of(0))), 0),
                new GuiVisualPlan.Raster("tab", 2, "player.level > 5",
                        List.of(new GuiVisualPlan.Glyph(0xE202, 20, Set.of(0)),
                                new GuiVisualPlan.Glyph(0xE203, 24, Set.of(1))), 5),
                new GuiVisualPlan.Text("label", 1, "player.level > 5", 8, 19,
                        "Hi ${player.name}", TitleSink.Font.DEFAULT, 0xFFFFFFFF)));
        var program = TitleProgram.compile(RenderFixtures.GUI, plan, RenderFixtures.lookup());
        assertThat(program.problems()).isEmpty();
        var renderer = new TitleRenderer(RenderReporter.SILENT);
        var hidden = renderer.render(RenderFixtures.GUI, 1, program.program(), RenderFixtures.viewer(1), 0);
        assertThat(hidden.hidden()).containsExactlyInAnyOrder("label", "tab");
        assertThat(hidden.title()).contains("\uE201").doesNotContain("\uE202", "Hi Probe");
        var shown = renderer.render(RenderFixtures.GUI, 1, program.program(), RenderFixtures.viewer(9), 5);
        assertThat(shown.hidden()).isEmpty();
        assertThat(shown.title()).contains("Hi Probe", "\uE203").doesNotContain("\uE202");
        assertThat(shown.title().indexOf("\uE201")).isLessThan(shown.title().indexOf("Hi Probe"));
        assertThat(shown.title().indexOf("\uE203")).isLessThan(shown.title().indexOf("Hi Probe"));
        assertThat(shown.coverage().get("tab")).containsExactly(1);
        assertThat(shown.coverage().get("label")).contains(0);
        assertThat(renderer.render(RenderFixtures.GUI, 1, program.program(), RenderFixtures.viewer(9), 6).title())
                .isEqualTo(shown.title());
        assertThat(renderer.render(RenderFixtures.GUI, 1, program.program(), RenderFixtures.viewer(9), 10).title())
                .isNotEqualTo(shown.title());
    }

    @Test
    void invalidConditionIsHiddenAndMalformedTextIsReported() {
        var plan = new GuiVisualPlan("shop", 0xE200, 1, List.of(
                new GuiVisualPlan.Text("bad", 0, "player.level >", 8, 18,
                        "Hi ${player.name", TitleSink.Font.DEFAULT, 0xFFFFFFFF)));
        var result = TitleProgram.compile(RenderFixtures.GUI, plan, RenderFixtures.lookup());
        assertThat(result.problems()).isNotEmpty();
        var rendered = new TitleRenderer(RenderReporter.SILENT)
                .render(RenderFixtures.GUI, 1, result.program(), RenderFixtures.viewer(9), 0);
        assertThat(rendered.hidden()).containsExactly("bad");
        assertThat(rendered.title()).doesNotContain("Hi");
    }
}
