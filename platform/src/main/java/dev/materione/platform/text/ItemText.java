package dev.materione.platform.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Item text styling shared by the stack factory and the GUI kit (runtime-
 * systems §3.3). Vanilla renders {@code custom_name} and lore lines
 * <em>italic</em> unless a component explicitly opts out — an authored
 * {@code "Scroll Ⅲ"} would show as *Scroll Ⅲ*. Everything materione sets
 * is wrapped so the default is non-italic; an author who wants italics
 * writes {@code &o} and keeps it, because the wrapper parent carries the
 * opt-out while deserialized children keep their own explicit styles.
 */
public final class ItemText {

    private ItemText() {}

    /** A legacy {@code &}-string as item text: colors kept, italics off by default. */
    public static Component legacy(String text) {
        return nonItalic(LegacyComponentSerializer.legacyAmpersand().deserialize(text));
    }

    /** Wraps a component so vanilla's italic-by-default name/lore styling is off. */
    public static Component nonItalic(Component component) {
        TextComponent wrapper = Component.text()
                .decoration(TextDecoration.ITALIC, false)
                .append(component)
                .build();
        return wrapper;
    }
}
