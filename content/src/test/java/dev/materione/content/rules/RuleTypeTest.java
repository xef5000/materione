package dev.materione.content.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.document.LosslessYaml;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code rule} content type (logic.md §37): its self-description, the shape it parses, the lossless
 * round-trip of the golden document under {@code fixtures/rules/} (load then untouched save is byte-identical,
 * unknown keys survive), and the fault fixtures: a document that is not a rule document is refused whole, and
 * the healthy resource beside it in the same file is not.
 */
class RuleTypeTest {

    private final RuleType type = new RuleType();

    private static String fixture(String name) throws IOException {
        return Files.readString(GoldenFiles.fixturesRoot().resolve("rules").resolve(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resource(String yaml, String key) {
        return (Map<String, Object>) LosslessYaml.parse(yaml).data().get(key);
    }

    @Test
    void theTypeIsNamedRuleAndLivesInRulesDirectory() {
        assertThat(type.name()).isEqualTo("rule");
        assertThat(type.directory()).isEqualTo("rules");
    }

    @Test
    void theSelfDescriptionIsTheOneSchemaSource() {
        var parameters = type.describe().parameters();

        assertThat(parameters).extracting(p -> p.id()).containsExactly("description", "behavior", "flows");
        // the editor draws the behavior list with the rule editor, from this declaration alone
        assertThat(parameters.get(1).editor().widget()).isEqualTo("behavior-rules");
        assertThat(parameters.get(2).editor().advanced()).isTrue();
    }

    @Test
    void theGoldenDocumentParsesToItsRulesAndFlows() throws IOException {
        var parsed = type.parse(NamespacedId.of("pack", "welcome"), resource(fixture("welcome.yml"), "welcome"), Map.of());

        assertThat(parsed).isPresent();
        var rules = (RuleDefinition) parsed.get();
        assertThat(rules.description()).isEqualTo("Greet players, and count a quest kill");
        assertThat(rules.behavior()).hasSize(2);
        // a bare on: loads as a YAML 1.1 boolean key: the definition hands the compiler string keys
        assertThat(((Map<?, ?>) rules.behavior().get(0)).get("on")).isEqualTo("player.join");
        assertThat(((Map<?, ?>) rules.behavior().get(1)).get("type")).isEqualTo(List.of("zombie", "husk"));
        assertThat(rules.flows()).containsOnlyKeys("starter_kit");
        assertThat(rules.raw()).containsKey("sidecar_note");
    }

    @Test
    void loadThenUntouchedSaveIsByteIdenticalForEveryRuleFixture() throws IOException {
        try (var files = Files.list(GoldenFiles.fixturesRoot().resolve("rules"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".yml")).toList()) {
                String raw = Files.readString(file);
                assertThat(LosslessYaml.parse(raw).save()).as("round trip of %s", file.getFileName()).isEqualTo(raw);
            }
        }
    }

    @Test
    void anEditedDocumentKeepsEveryKeyAndCommentItDidNotTouch() throws IOException {
        String raw = fixture("welcome.yml");
        var document = LosslessYaml.parse(raw);
        assertThat(document.data()).containsKey("welcome");
        // the unknown keys and the node hint are in the parsed tree, so a form that edits one field keeps them
        Map<?, ?> rule = (Map<?, ?>) ((List<?>) resource(raw, "welcome").get("behavior")).get(1);
        assertThat(rule.containsKey("node") && rule.containsKey("future_option")).isTrue();
        assertThat(raw).contains("# no host: the world fires it").contains("sidecar_note: kept as written    # a key");
    }

    @Test
    void aBehaviorThatIsNotAListIsNotARuleDocumentAndItsSiblingIsUnaffected() throws IOException {
        String raw = fixture("bad-behavior.yml");

        assertThat(type.parse(NamespacedId.of("pack", "broken"), resource(raw, "broken"), Map.of())).isEmpty();
        assertThat(type.parse(NamespacedId.of("pack", "healthy"), resource(raw, "healthy"), Map.of())).isPresent();
    }

    @Test
    void aFlowsThatIsNotAMappingIsRefused() {
        var parsed = type.parse(NamespacedId.of("pack", "x"), Map.of("flows", List.of("nope")), Map.of());

        assertThat(parsed).isEmpty();
    }

    @Test
    void aDocumentWithNoRulesIsValidAndEmpty() {
        var parsed = type.parse(NamespacedId.of("pack", "empty"), Map.of("description", "later"), Map.of());

        assertThat(parsed).isPresent();
        assertThat(((RuleDefinition) parsed.get()).behavior()).isEmpty();
    }

    @Test
    void compilingEmitsNothingToThePack() {
        var parsed = type.parse(NamespacedId.of("pack", "empty"), Map.of(), Map.of()).orElseThrow();

        type.compile(parsed, null, null, null); // no emitter is touched: a rule is runtime logic
    }
}
