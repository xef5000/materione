package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Collision-free allocation identities and asset paths for GUI overlay frames. */
public final class GuiOverlayKeys {
    private GuiOverlayKeys() {}

    public static NamespacedId allocation(NamespacedId gui, String layer, int frame) {
        return NamespacedId.of("minecraft", "materione_gui_overlay/" + encode(gui.namespace()) + "/"
                + encode(gui.value()) + "/" + encode(layer) + "/" + frame);
    }

    public static String texture(NamespacedId gui, String layer, int frame) {
        return "font/materione/gui_overlay/" + encode(gui.value()) + "/" + encode(layer) + "/" + frame;
    }

    private static String encode(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }
}
