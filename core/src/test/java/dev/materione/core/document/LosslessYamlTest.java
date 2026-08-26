package dev.materione.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The frozen round-trip contract (master §5.6), locked by tests from day
 * one: load → untouched save is byte-identical; unknown fields survive;
 * an edit touches exactly one line.
 */
class LosslessYamlTest {

    @Test
    void roundTripIsByteIdenticalForEveryFixturesYaml() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(GoldenFiles.fixturesRoot().resolve("packs"))) {
            files = walk.filter(p -> p.toString().endsWith(".yml")).toList();
        }
        try (Stream<Path> walk = Files.walk(GoldenFiles.fixturesRoot().resolve("faults"))) {
            files = new java.util.ArrayList<>(files);
            files.addAll(walk.filter(p -> p.toString().endsWith(".yml")).toList());
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            String raw = Files.readString(file);
            assertThat(LosslessYaml.parse(raw).save())
                    .as("round-trip of %s", file)
                    .isEqualTo(raw);
        }
    }

    @Test
    void brokenSyntaxCarriesParseErrorInsteadOfThrowing() throws IOException {
        var raw = Files.readString(GoldenFiles.fixturesRoot().resolve("faults/broken-syntax.yml"));
        var document = LosslessYaml.parse(raw);
        assertThat(document.parseError()).isPresent();
        assertThat(document.data()).isEmpty();
        assertThat(document.save()).isEqualTo(raw);
    }

    @Test
    void unknownFieldsSurviveEditsAndRoundTrips() {
        String raw = """
                # header comment
                item:
                  future_field: keep me
                  material: PAPER
                  # inner comment
                  model: old_model
                """;
        var edited = LosslessYaml.parse(raw).withScalar("new_model", "item", "model");
        assertThat(edited.scalar("item", "future_field")).contains("keep me");
        assertThat(edited.scalar("item", "material")).contains("PAPER");
        assertThat(edited.save()).contains("future_field: keep me").contains("# inner comment");
    }

    @Test
    void editingOneScalarChangesExactlyOneLine() {
        String raw = """
                a:
                  one: 1
                  two: 2
                b:
                  three: 3
                """;
        var edited = LosslessYaml.parse(raw).withScalar(42, "a", "two");
        List<String> before = raw.lines().toList();
        List<String> after = edited.save().lines().toList();
        assertThat(after).hasSameSizeAs(before);
        int differences = 0;
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                differences++;
                assertThat(after.get(i)).isEqualTo("  two: 42");
            }
        }
        assertThat(differences).isOne();
    }

    @Test
    void insertingANewKeyPreservesEverythingElse(@TempDir Path temp) throws IOException {
        String raw = "item:\r\n  one: 1\r\n# tail comment\r\n";
        var edited = LosslessYaml.parse(raw).withScalar("inserted", "item", "new");
        assertThat(edited.save()).contains("\r\n").contains("  new: inserted\r\n").contains("# tail comment\r\n");
        assertThat(edited.scalar("item", "new")).contains("inserted");
        // untouched round-trip of the edited document stays stable too
        assertThat(LosslessYaml.parse(edited.save()).save()).isEqualTo(edited.save());
    }

    @Test
    void valuesNeedingQuotesAreQuotedAndSafeOnesPlain() {
        String raw = "item:\n  a: x\n";
        var doc = LosslessYaml.parse(raw)
                .withScalar("plain_value", "item", "a");
        assertThat(doc.save()).contains("a: plain_value");
        var quoted = LosslessYaml.parse(raw).withScalar("true story: part 1", "item", "a");
        assertThat(quoted.save()).contains("a: \"true story: part 1\"");
        var numeric = LosslessYaml.parse(raw).withScalar(7, "item", "a");
        assertThat(numeric.save()).contains("a: 7");
    }

    @Test
    void yaml11BooleanKeysNormalizeToStrings() {
        // SnakeYAML implements YAML 1.1: a bare `on:` mapping key loads as
        // boolean true — stringKeyed is the ECA selector's rescue path
        Map<String, Object> raw = LosslessYaml.parse("rule:\n  on: interact\n  do: x\n")
                .data()
                .get("rule") instanceof Map<?, ?> mapping ? LosslessYaml.stringKeyed(mapping) : Map.of();
        assertThat(raw).containsEntry("on", "interact");

        Map<String, Object> offKey = LosslessYaml.parse("rule:\n  \"off\": 1\n")
                .data()
                .get("rule") instanceof Map<?, ?> mapping ? LosslessYaml.stringKeyed(mapping) : Map.of();
        assertThat(offKey).containsEntry("off", 1);
    }

    @Test
    void listValuesRenderAsBlockSequencesAndReplaceTheirOwnRange() {
        // Insert where the key does not exist yet.
        String inserted = LosslessYaml.parse("item:\n  material: PAPER\n")
                .withScalar(List.of("one", "two"), "item", "lore")
                .save();
        assertThat(inserted).isEqualTo("item:\n  material: PAPER\n  lore:\n    - one\n    - two\n");

        // Overwrite an existing longer list — no residue from the old value,
        // and everything outside the field survives byte-for-byte.
        String raw = "item:\n  lore:\n    - a\n    - b\n    - c\n  # keep me\n  material: STICK\n";
        String overwritten = LosslessYaml.parse(raw)
                .withScalar(List.of("x"), "item", "lore")
                .save();
        assertThat(overwritten).isEqualTo("item:\n  lore:\n    - x\n  # keep me\n  material: STICK\n");

        // Empty lists collapse onto the key line.
        String emptied = LosslessYaml.parse(raw).withScalar(List.of(), "item", "lore").save();
        assertThat(emptied).contains("lore: []");
        assertThat(emptied).contains("# keep me");
    }
}
