package dev.materione.platform.text;

import static org.assertj.core.api.Assertions.assertThat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

/**
 * Item text styling: vanilla's italic-by-default name/lore rendering must
 * be opted out of for everything materione sets, while an author's own
 * {@code &o} still survives (the wrapper carries the default; children
 * keep explicit styles).
 */
class ItemTextTest {

    @Test
    void legacyTextIsNonItalicByDefault() {
        Component component = ItemText.legacy("Scroll 03");
        assertThat(component.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        // the text itself survives as the child
        assertThat(component.children()).hasSize(1);
        assertThat(component.children().get(0)).isEqualTo(Component.text("Scroll 03"));
    }

    @Test
    void legacyColorsSurvive() {
        Component component = ItemText.legacy("&6Golden name");
        assertThat(component.children().get(0).color()).isEqualTo(NamedTextColor.GOLD);
        assertThat(component.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
    }

    @Test
    void legacyAmpersandDefaultSupportsBothRgbSpellings() {
        TextColor expected = TextColor.fromHexString("#12abef");

        Component direct = ItemText.legacy("&#12ABEF direct");
        Component expanded = ItemText.legacy("&x&1&2&A&B&E&F expanded");

        assertThat(direct.children().get(0).color()).isEqualTo(expected);
        assertThat(expanded.children().get(0).color()).isEqualTo(expected);
        assertThat(direct.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(expanded.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
    }

    @Test
    void authorItalicsOverrideTheDefault() {
        Component component = ItemText.legacy("&oHandwritten");
        assertThat(component.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(component.children().get(0).decoration(TextDecoration.ITALIC))
                .isEqualTo(TextDecoration.State.TRUE);
    }

    @Test
    void nonItalicWrapsWithoutMutating() {
        Component inner = Component.text("plain");
        Component wrapped = ItemText.nonItalic(inner);
        assertThat(wrapped.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(wrapped.children()).containsExactly(inner);
        assertThat(inner.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.NOT_SET);
    }
}
