package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The customized-item naming rules (gui.md §2, §10 #2), pinned on the same cases the studio's
 * former client-side copy covered, so the move to the API kept every rule.
 */
class GuiItemNamingTest {

    @Test
    void theItemLivesUnderTheGuiFolder() {
        assertThat(GuiItemNaming.guiStem("guis/shop/main.yml")).isEqualTo("shop/main");
        assertThat(GuiItemNaming.customFolder("guis/shop/main.yml")).isEqualTo("gui/shop/main");
        assertThat(GuiItemNaming.customPath("guis/shop/main.yml", "sword"))
                .isEqualTo("items/gui/shop/main/sword.yml");
        assertThat(GuiItemNaming.customPath("guis/hud.yml", "gem")).isEqualTo("items/gui/hud/gem.yml");
    }

    @Test
    void theLayerIdIsPreferredWhenFree() {
        assertThat(GuiItemNaming.chooseItemId("sword", "guis/shop/main.yml", Set.of())).isEqualTo("sword");
    }

    @Test
    void aTakenIdFallsBackToTheGuiPrefixThenACounter() {
        assertThat(GuiItemNaming.chooseItemId("sword", "guis/shop/main.yml", Set.of("sword")))
                .isEqualTo("shop_main_sword");
        assertThat(GuiItemNaming.chooseItemId("sword", "guis/shop/main.yml", Set.of("sword", "shop_main_sword")))
                .isEqualTo("shop_main_sword_2");
    }

    @Test
    void aOneCharacterLayerIdIsPrefixedBecauseAnItemIdNeedsTwo() {
        assertThat(GuiItemNaming.chooseItemId("a", "guis/hud.yml", Set.of())).isEqualTo("hud_a");
    }

    @Test
    void sanitizeFoldsCaseAndRunsOfOtherCharacters() {
        assertThat(GuiItemNaming.sanitize("Big  Gem!")).isEqualTo("big_gem");
        assertThat(GuiItemNaming.sanitize("__x__")).isEqualTo("x");
    }
}
