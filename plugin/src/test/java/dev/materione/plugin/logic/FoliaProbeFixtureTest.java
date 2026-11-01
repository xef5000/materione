package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.content.items.ItemDefinition;
import dev.materione.content.items.ItemType;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.registry.RegistryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FoliaProbeFixtureTest {
    @Test
    void localFoliaItemsCompileThroughRealBuiltinVocabulary() throws Exception {
        var fixture = Path.of("..", "scripts", "probe", "fixtures", "folia250", "items", "folia250.yml");
        var definitions = LosslessYaml.parse(Files.readString(fixture)).data();
        assertThat(definitions).hasSize(6);
        var registries = new RegistryService();
        LogicTestSupport.withBuiltIns(registries);
        var variable = new dev.materione.api.logic.variable.VariableDeclaration(
                NamespacedId.of("probe", "folia250_total"),
                dev.materione.api.logic.variable.VariableScope.SERVER,
                dev.materione.api.logic.variable.VariableType.NUMBER, 0, "");
        var lookup = new dev.materione.behavior.logic.RegistryLogicLookup(registries,
                id -> id.equals(variable.id()) ? java.util.Optional.of(variable) : java.util.Optional.empty());
        for (var entry : definitions.entrySet()) {
            var id = NamespacedId.of("probe", entry.getKey());
            @SuppressWarnings("unchecked")
            var raw = (Map<String, Object>) entry.getValue();
            var item = (ItemDefinition) new ItemType().parse(id, raw, Map.of()).orElseThrow();
            var compiled = FlowCompiler.compile(id, item.behavior(), lookup);
            assertThat(compiled.problems()).as(id.toString()).isEmpty();
            assertThat(compiled.rules()).as(id.toString()).hasSize(1);
        }
    }
}
