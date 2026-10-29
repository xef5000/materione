package dev.materione.content.gui;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.gui.RenderTarget;
import java.util.List;
import java.util.Map;

/**
 * Everything one GUI compile produces (gui.md §4): the runtime model, the pack output, the
 * non-fatal diagnostics and the per-layer outcome. The pack compiler emits {@link #assets()}
 * and {@link #fonts()} through the emitter; the runtime uses {@link #model()} only; the GUI API
 * reports {@link #layers()}.
 *
 * @param model the runtime-facing compiled GUI
 * @param diagnostics layers skipped at compile time, each with its reason
 * @param assets generated files, keyed by pack-relative path under {@code assets/}, sorted
 * @param fonts the bitmap providers, in emission order, keyed by their font
 * @param layers every layer of the GUI in list order, with where it landed and why it was skipped
 */
public record GuiCompilation(
        CompiledGui model,
        List<Diagnostic> diagnostics,
        Map<String, byte[]> assets,
        List<FontProvider> fonts,
        List<LayerReport> layers) {

    public GuiCompilation {
        diagnostics = List.copyOf(diagnostics);
        assets = Map.copyOf(assets);
        fonts = List.copyOf(fonts);
        layers = List.copyOf(layers);
    }

    /**
     * One bitmap provider for a font key.
     *
     * @param fontKey the font it belongs to ({@code ns:name})
     * @param provider the JSON-shaped provider
     */
    public record FontProvider(String fontKey, Map<String, Object> provider) {}

    /**
     * One layer's outcome.
     *
     * @param id the layer id
     * @param target the surface the layer targets
     * @param rank its 0-based position in the compiled order of the visible layers, or -1 when hidden
     * @param reason why the layer was skipped, or null when it was placed
     */
    public record LayerReport(String id, RenderTarget target, int rank, String reason) {

        /** Whether the compiler skipped this layer. */
        public boolean skipped() {
            return reason != null;
        }
    }
}
