package dev.materione.content.gui;

import dev.materione.api.diagnostics.Diagnostic;
import java.util.List;
import java.util.Map;

/**
 * Everything one GUI compile produces (gui.md §4): the runtime model, the pack output and
 * the non-fatal diagnostics. The pack compiler emits {@link #assets()} and {@link #fonts()}
 * through the emitter; the runtime uses {@link #model()} only.
 *
 * @param model the runtime-facing compiled GUI
 * @param diagnostics layers skipped at compile time, each with its reason
 * @param assets generated files, keyed by pack-relative path under {@code assets/}, sorted
 * @param fonts the bitmap providers, in emission order, keyed by their font
 */
public record GuiCompilation(
        CompiledGui model,
        List<Diagnostic> diagnostics,
        Map<String, byte[]> assets,
        List<FontProvider> fonts) {

    public GuiCompilation {
        diagnostics = List.copyOf(diagnostics);
        assets = Map.copyOf(assets);
        fonts = List.copyOf(fonts);
    }

    /**
     * One bitmap provider for a font key.
     *
     * @param fontKey the font it belongs to ({@code ns:name})
     * @param provider the JSON-shaped provider
     */
    public record FontProvider(String fontKey, Map<String, Object> provider) {}
}
