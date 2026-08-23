package dev.materione.platform.items;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The container codec's degenerate paths (empty/null round-trips without
 * touching server-side ItemStack machinery — full stack round-trips run on
 * the live harness).
 */
class ItemListCodecTest {

    @Test
    void emptyInputEncodesToZeroBytes() {
        assertThat(ItemListCodec.encode(List.of())).isEmpty();
    }

    @Test
    void nullAndEmptyPayloadsDecodeToEmptyLists() {
        assertThat(ItemListCodec.decode(null)).isEmpty();
        assertThat(ItemListCodec.decode(new byte[0])).isEmpty();
    }

    @Test
    void tornPayloadDecodesWithoutThrowing() {
        // a length header claiming 3 stacks but no frames follows
        byte[] torn = new byte[] {0, 0, 0, 3};
        assertThat(ItemListCodec.decode(torn)).isEmpty();
    }
}
