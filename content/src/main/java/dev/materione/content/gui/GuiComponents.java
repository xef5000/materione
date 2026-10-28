package dev.materione.content.gui;

import dev.materione.api.gui.GuiComponent;
import java.util.List;

/**
 * The built-in layer kinds (gui.md §3), composed through the same public
 * {@link GuiComponent} contract addons implement. The plugin registers each one
 * into the {@code gui-components} registry during bootstrap, exactly as it
 * registers the content types; the GUI type never names a built-in directly.
 */
public final class GuiComponents {

    private GuiComponents() {}

    /** All built-in components, in registration (and therefore studio palette) order. */
    public static List<GuiComponent> builtins() {
        return List.of(
                new PanelComponent(), new SlotsComponent(), new ImageComponent(), new PaintComponent(),
                new ButtonComponent(), new TextComponent(), new ItemComponent());
    }
}
