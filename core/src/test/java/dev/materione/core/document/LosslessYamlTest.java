package dev.materione.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void structuredValuesRenderAsBlockYamlAndLeaveTheRestAlone() {
        // Furniture hitboxes: a sequence of mappings, coordinates in flow
        // style because that is how a human writes a coordinate.
        String raw = """
                chair:
                  # authored by hand
                  model: chair
                  hitboxes:
                    - type: interaction
                      offset: [0, 0, 0]
                  storage: 27
                """;
        String edited = LosslessYaml.parse(raw)
                .withNode(
                        List.of(
                                mapping("type", "interaction", "offset", List.of(0, 0.5, 0),
                                        "width", 1),
                                mapping("type", "barrier", "offset", List.of(0, 1, 0))),
                        "chair", "hitboxes")
                .save();

        assertThat(edited).isEqualTo("""
                chair:
                  # authored by hand
                  model: chair
                  hitboxes:
                    - type: interaction
                      offset: [0, 0.5, 0]
                      width: 1
                    - type: barrier
                      offset: [0, 1, 0]
                  storage: 27
                """);
        // The result still parses, and re-saving it is byte-identical.
        var reparsed = LosslessYaml.parse(edited);
        assertThat(reparsed.parseError()).isEmpty();
        assertThat(reparsed.save()).isEqualTo(edited);
    }

    @Test
    void structuredMappingsInsertAndReplaceWithoutTouchingNeighbours() {
        String raw = "recipe:\n  type: shaped\n  # ingredients below\n  result: sword\n";
        var ingredients = mapping("R", "myserver:ruby", "S", "minecraft:stick");

        String inserted = LosslessYaml.parse(raw)
                .withNode(ingredients, "recipe", "ingredients")
                .save();
        // Namespaced ids carry a colon, so they are quoted — still the same value.
        assertThat(inserted).isEqualTo(
                "recipe:\n  type: shaped\n  # ingredients below\n  result: sword\n"
                        + "  ingredients:\n    R: \"myserver:ruby\"\n    S: \"minecraft:stick\"\n");

        // Replacing it drops the old keys entirely — no residue.
        var replacement = mapping("X", "minecraft:diamond");
        String replaced = LosslessYaml.parse(inserted)
                .withNode(replacement, "recipe", "ingredients")
                .save();
        assertThat(replaced).isEqualTo(
                "recipe:\n  type: shaped\n  # ingredients below\n  result: sword\n"
                        + "  ingredients:\n    X: \"minecraft:diamond\"\n");

        // Empty structures collapse onto the key line.
        assertThat(LosslessYaml.parse(inserted)
                .withNode(Map.of(), "recipe", "ingredients").save())
                .contains("ingredients: {}");
    }

    @Test
    void structuredWritesFallBackToTheOneLinePathForFlatValues() {
        String raw = "item:\n  material: PAPER\n";
        assertThat(LosslessYaml.parse(raw).withNode("STICK", "item", "material").save())
                .isEqualTo("item:\n  material: STICK\n");
        assertThat(LosslessYaml.parse(raw).withNode(List.of("a", "b"), "item", "lore").save())
                .isEqualTo("item:\n  material: PAPER\n  lore:\n    - a\n    - b\n");
    }

    @Test
    void removingAScalarKeyDeletesOnlyThatLine() {
        String raw = """
                # pack header
                item:
                  material: PAPER # keep this inline comment
                  display_name: Old Name

                  # the model is authored by hand
                  custom_model_data: 7
                """;

        var removed = LosslessYaml.parse(raw).withoutNode("item", "display_name");

        assertThat(removed.save()).isEqualTo("""
                # pack header
                item:
                  material: PAPER # keep this inline comment

                  # the model is authored by hand
                  custom_model_data: 7
                """);
        assertThat(removed.parseError()).isEmpty();
        assertThat(removed.scalar("item", "display_name")).isEmpty();
        assertThat(removed.scalar("item", "custom_model_data")).contains(7);
    }

    @Test
    void removingAListValueDropsItsItemsButKeepsTheTrailingComment() {
        String raw = """
                item:
                  material: PAPER
                  lore:
                    - first line
                    - second line
                  # keep this trailing comment
                  custom_model_data: 7
                """;

        String removed = LosslessYaml.parse(raw).withoutNode("item", "lore").save();

        assertThat(removed).isEqualTo("""
                item:
                  material: PAPER
                  # keep this trailing comment
                  custom_model_data: 7
                """);
    }

    @Test
    void removingANestedMappingDropsItsWholeBlockAndKeepsTheTrailingComment() {
        String raw = """
                item:
                  name: Blade
                  display:
                    color: red
                  # display note
                  storage: 27
                """;

        var removed = LosslessYaml.parse(raw).withoutNode("item", "display");

        assertThat(removed.save()).isEqualTo("""
                item:
                  name: Blade
                  # display note
                  storage: 27
                """);
        assertThat(removed.scalar("item", "display")).isEmpty();
        assertThat(removed.scalar("item", "storage")).contains(27);
    }

    @Test
    void removingAnAbsentKeyReturnsTheDocumentByteForByte() {
        String raw = "# header\nitem:\r\n  material: PAPER\r\n";

        assertThat(LosslessYaml.parse(raw).withoutNode("item", "missing").save()).isEqualTo(raw);
        assertThat(LosslessYaml.parse(raw).withoutNode("nothing_here").save()).isEqualTo(raw);
    }

    @Test
    void removingTheOnlyChildLeavesAnEmptyMappingNotNull() {
        String raw = """
                wand:
                  material: STICK
                  display:
                    color: red
                # tail comment
                """;

        var removed = LosslessYaml.parse(raw).withoutNode("wand", "display", "color");

        assertThat(removed.save()).isEqualTo("""
                wand:
                  material: STICK
                  display: {}
                # tail comment
                """);
        assertThat(removed.scalar("wand", "material")).contains("STICK");
        Map<?, ?> wand = (Map<?, ?>) removed.data().get("wand");
        Object display = wand.get("display");
        assertThat(display).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) display).isEmpty();
    }

    @Test
    void flowStyleKeysAreRefusedInsteadOfGuessed() {
        var document = LosslessYaml.parse("root: {a: 1, b: 2}\n");

        assertThatThrownBy(() -> document.withoutNode("root", "a"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void removalKeepsCrlfDocumentsCrlf() {
        String raw = "item:\r\n  material: PAPER\r\n  display_name: Old\r\n  # keep\r\n"
                + "  lore:\r\n    - one\r\n    - two\r\n";

        String scalarRemoved = LosslessYaml.parse(raw).withoutNode("item", "display_name").save();
        assertThat(scalarRemoved)
                .isEqualTo("item:\r\n  material: PAPER\r\n  # keep\r\n  lore:\r\n    - one\r\n    - two\r\n");

        String listRemoved = LosslessYaml.parse(raw).withoutNode("item", "lore").save();
        assertThat(listRemoved).isEqualTo("item:\r\n  material: PAPER\r\n  display_name: Old\r\n  # keep\r\n");
        assertThat(listRemoved.replace("\r\n", "")).doesNotContain("\n");
    }

    /** Ordered map literal — key order is part of what gets written out. */
    private static Map<String, Object> mapping(Object... pairs) {
        var map = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
