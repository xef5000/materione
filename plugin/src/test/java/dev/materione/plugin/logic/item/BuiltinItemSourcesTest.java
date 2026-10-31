package dev.materione.plugin.logic.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ItemSource;
import dev.materione.api.logic.ParamReader;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.logic.LogicTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The built-in item sources' parameters, typed at load (logic.md §4.4): a head names exactly one skin and a
 * literal skin is checked now, a template is checked when it renders; a player slot is one of six. Resolution
 * needs a server, so it is covered by the probe; this covers what a server is not needed for.
 */
class BuiltinItemSourcesTest {

    private static final String HASH = "a".repeat(64);
    private static final String TEXTURE_URL = "https://textures.minecraft.net/texture/" + HASH;

    private static ParamReader params(Map<String, ?> values) {
        return ParamReader.of(values, EngineExpressions.INSTANCE);
    }

    private static String textureJson(String url) {
        return "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
    }

    @Test
    void theBuiltInSourcesAreRegisteredThroughThePublicRegistry() {
        var logic = LogicTestSupport.withBuiltIns(new RegistryService());

        assertThat(logic.lookup().itemSource("item")).isPresent();
        assertThat(logic.lookup().itemSource("head")).isPresent();
        assertThat(logic.lookup().itemSource("player_slot")).isPresent();
        assertThat(logic.lookup().itemSource("materione:head")).isPresent();
        assertThat(logic.lookup().itemSource("nope")).isEmpty();
    }

    @Test
    void aHeadNamesExactlyOneSkinOfWhichAPlayerNameIsChecked() {
        var head = new HeadItemSource();

        assertThat(head.parse(params(Map.of("player", "Notch")))).isNotNull();
        assertThat(head.parse(params(Map.of("player", "${player.name}")))).isNotNull();
        assertThatThrownBy(() -> head.parse(params(Map.of("player", "not a name!"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a player name");
        assertThatThrownBy(() -> head.parse(params(Map.of("player", "Notch", "texture", HASH))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> head.parse(params(Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
    }

    @Test
    void aTextureIsAHashOrItsUrlAndAnythingElseIsRejectedAtLoad() {
        assertThat(HeadItemSource.textureHash(HASH)).isEqualTo(HASH);
        assertThat(HeadItemSource.textureHash(TEXTURE_URL)).isEqualTo(HASH);
        assertThat(HeadItemSource.textureHash("https://example.com/skin.png")).isNull();

        var head = new HeadItemSource();
        assertThat(head.parse(params(Map.of("texture", TEXTURE_URL)))).isNotNull();
        assertThatThrownBy(() -> head.parse(params(Map.of("texture", "short"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("skin hash");
    }

    @Test
    void aBase64TexturesValueMustNameASkin() {
        String encoded = Base64.getEncoder().encodeToString(
                textureJson(TEXTURE_URL).getBytes(StandardCharsets.UTF_8));
        var head = new HeadItemSource();

        assertThat(HeadItemSource.textureHashOfBase64(encoded)).isEqualTo(HASH);
        assertThat(head.parse(params(Map.of("base64", encoded)))).isNotNull();
        assertThatThrownBy(() -> head.parse(params(Map.of("base64", "%%% not base64"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("base64");
    }

    @Test
    void aPlayerSlotIsOneOfSixAndAnythingElseIsRejected() {
        var slots = new PlayerSlotItemSource();

        assertThat(slots.parse(params(Map.of("slot", "main_hand")))).isEqualTo(PlayerSlotItemSource.Slot.MAIN_HAND);
        assertThat(slots.parse(params(Map.of("slot", "boots")))).isEqualTo(PlayerSlotItemSource.Slot.BOOTS);
        assertThatThrownBy(() -> slots.parse(params(Map.of("slot", "tail"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a player slot");
    }

    @Test
    void theShorthandsAreWhatAScalarPayloadIsReadUnder() {
        ItemSource<?> slot = new PlayerSlotItemSource();
        ItemSource<?> item = new MaterioneItemSource(dev.materione.plugin.logic.support.ItemCatalog.none());

        assertThat(slot.describe().shorthand()).isEqualTo("slot");
        assertThat(item.describe().shorthand()).isEqualTo("item");
        assertThat(new HeadItemSource().describe().shorthand()).isNull();
    }
}
