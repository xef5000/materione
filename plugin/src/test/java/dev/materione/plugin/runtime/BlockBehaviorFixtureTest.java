package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.content.blocks.BlockDefinition;
import dev.materione.content.blocks.BlockType;
import dev.materione.core.document.LosslessYaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression: the amethyst_lamp fixture's bare {@code on:} key loads as a
 * YAML-1.1 boolean (SnakeYAML), which used to surface as a spurious
 * MTR-0021 "missing 'on:' event selector" at every boot. The full chain —
 * fixture file → data view → block parse → rule compile — must be clean.
 */
class BlockBehaviorFixtureTest {

    @Test
    void fixtureBehaviorParsesCleanEndToEnd() throws Exception {
        Path fixture = Path.of("..", "fixtures", "packs", "sample", "blocks", "amethyst_lamp.yml")
                .toAbsolutePath().normalize();
        Map<String, Object> document = LosslessYaml.parse(Files.readString(fixture)).data();
        @SuppressWarnings("unchecked")
        var definition = (Map<String, Object>) document.get("amethyst_lamp");
        assertThat(definition).isNotNull();

        BlockDefinition block = (BlockDefinition) new BlockType()
                .parse(NamespacedId.parse("sample:amethyst_lamp"), definition, Map.of())
                .orElseThrow();
        assertThat(block.behavior()).isNotEmpty();

        ParsedRules rules = FlowCompiler.compile(block.id(), block.behavior(),
                dev.materione.plugin.logic.LogicTestSupport
                        .withBuiltIns(new dev.materione.core.registry.RegistryService()).lookup());
        assertThat(rules.problems()).isEmpty();
        assertThat(rules.rules()).hasSize(1);
        assertThat(rules.rules().get(0).on().type()).isEqualTo("interact");
    }
}
