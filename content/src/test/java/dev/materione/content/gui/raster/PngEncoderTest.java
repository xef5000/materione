package dev.materione.content.gui.raster;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.gui.TextureSource;
import dev.materione.core.texture.PngDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The in-repo PNG writer: a valid file with exactly the three chunks, byte-stable, and decodable. */
class PngEncoderTest {

    @Test
    void rgbaRoundTripsThroughTheDecoderExactly() {
        int[] argb = {0xFF112233, 0x80FF0000, 0x00000000, 0x01FFFFFF, 0xFF000000, 0x40808080};
        byte[] png = PngEncoder.encodeRgba(3, 2, argb);
        TextureSource.Pixels decoded = PngDecoder.decode(png).orElseThrow();
        assertThat(decoded.width()).isEqualTo(3);
        assertThat(decoded.height()).isEqualTo(2);
        assertThat(decoded.argb()).containsExactly(argb);
    }

    @Test
    void containsOnlyTheSignatureAndIhdrIdatIend() {
        byte[] png = PngEncoder.encodeRgba(4, 4, new int[16]);
        assertThat(java.util.Arrays.copyOf(png, 8)).containsExactly(137, 80, 78, 71, 13, 10, 26, 10);
        assertThat(chunkTypes(png)).containsExactly("IHDR", "IDAT", "IEND");
        ByteBuffer header = ByteBuffer.wrap(png, 16, 13);
        assertThat(header.getInt()).isEqualTo(4);
        assertThat(header.getInt()).isEqualTo(4);
        assertThat(header.get()).isEqualTo((byte) 8); // bit depth
        assertThat(header.get()).isEqualTo((byte) 6); // RGBA
    }

    @Test
    void sameInputGivesSameBytes() {
        int[] argb = new int[176 * 40];
        for (int index = 0; index < argb.length; index++) {
            argb[index] = (index * 2654435761L) > 0 ? (int) (index * 2654435761L) : index;
        }
        assertThat(PngEncoder.encodeRgba(176, 40, argb)).isEqualTo(PngEncoder.encodeRgba(176, 40, argb.clone()));
    }

    @Test
    void grayscaleDecodesToItsLuminance() {
        byte[] luminance = {0, (byte) 0xFF, (byte) 0x80, 0x10};
        byte[] png = PngEncoder.encodeGrayscale(2, 2, luminance);
        int[] decoded = PngDecoder.decode(png).orElseThrow().argb();
        assertThat(decoded[1] & 0xFF).isEqualTo(0xFF);
        assertThat(decoded[2] & 0xFF).isEqualTo(0x80);
        assertThat(decoded[3] & 0xFF).isEqualTo(0x10);
    }

    @Test
    void rejectsSizesThatDoNotMatchThePixels() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PngEncoder.encodeRgba(2, 2, new int[3]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> chunkTypes(byte[] png) {
        var types = new ArrayList<String>();
        int at = 8;
        while (at < png.length) {
            int length = ByteBuffer.wrap(png, at, 4).getInt();
            types.add(new String(png, at + 4, 4, StandardCharsets.US_ASCII));
            at += 12 + length;
        }
        return types;
    }
}
