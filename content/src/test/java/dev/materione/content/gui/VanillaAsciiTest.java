package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The vanilla default-font advance table (gui.md section 9.4). The table is one file,
 * {@code fonts/vanilla-ascii/advances.txt}, read here and by the studio; the golden pins its digest
 * and histogram, so a change to the numbers is a visible change to the golden as well.
 */
class VanillaAsciiTest {

    @Test
    void theDigestAndHistogramMatchTheGolden() throws IOException, NoSuchAlgorithmException {
        Map<String, String> golden = golden();
        // the canonical form the golden describes: LF line endings, never the platform's
        var canonical = new StringBuilder("space " + VanillaAscii.SPACE_ADVANCE + "\n");
        var histogram = new TreeMap<Integer, Integer>();
        histogram.merge(VanillaAscii.SPACE_ADVANCE, 1, Integer::sum);
        for (int codepoint = VanillaAscii.FIRST + 1; codepoint <= VanillaAscii.LAST; codepoint++) {
            canonical.append(String.format("0x%02X %d\n", codepoint, VanillaAscii.advance(codepoint)));
            histogram.merge(VanillaAscii.advance(codepoint), 1, Integer::sum);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(HexFormat.of().formatHex(digest)).isEqualTo(golden.get("canonical-sha256"));

        var expected = new TreeMap<Integer, Integer>();
        for (String pair : golden.get("histogram").split(" ")) {
            String[] parts = pair.split(":");
            expected.put(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
        assertThat(histogram).isEqualTo(expected);
    }

    @Test
    void theSpaceIsTheClientsIncludeSpaceProvider() {
        // the space cell of ascii.png is empty; its advance comes from assets/minecraft/font/include/space.json
        assertThat(VanillaAscii.SPACE_ADVANCE).isEqualTo(4);
        assertThat(VanillaAscii.advance(' ')).isEqualTo(4);
    }

    @Test
    void measuresAStringAsTheSumOfItsCharacters() {
        assertThat(VanillaAscii.measure("Hi")).isEqualTo(VanillaAscii.advance('H') + VanillaAscii.advance('i'));
        assertThat(VanillaAscii.measure("")).isZero();
    }

    @Test
    void supportsPrintableAsciiOnly() {
        assertThat(VanillaAscii.supports(0x20)).isTrue();
        assertThat(VanillaAscii.supports(0x7E)).isTrue();
        assertThat(VanillaAscii.supports(0x1F)).isFalse();
        assertThat(VanillaAscii.supports(0x7F)).isFalse();
        assertThatThrownBy(() -> VanillaAscii.advance(0xE9)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTableMissingAGlyphIsRejected() {
        assertThatThrownBy(() -> VanillaAscii.parse("space 4\n0x21 2\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no advance for U+22");
    }

    private static Map<String, String> golden() throws IOException {
        var values = new HashMap<String, String>();
        for (String raw : Files.readAllLines(GoldenFiles.fixturesRoot().resolve("gui/vanilla-ascii/golden.txt"))) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith(";")) {
                continue;
            }
            int space = line.indexOf(' ');
            values.put(line.substring(0, space), line.substring(space + 1));
        }
        return values;
    }
}
