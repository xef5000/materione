package dev.materione.core.pipeline.validate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Severity;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.pipeline.ContentResource;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The item {@code components:} block warns instead of silently doing
 * nothing (#103): a non-empty block is a WARN naming the authored keys,
 * an empty or absent block is silent, and no other content type is
 * touched. The warning must never quarantine — the rest of the item
 * still ships.
 */
class ItemComponentsValidatorTest {

    private static ContentResource resource(String contentType, String yaml) {
        // null path: the validator never touches lineage, and the validate
        // package is ArchUnit-barred from java.nio.file
        return new ContentResource(
                NamespacedId.parse("sample:thing"),
                contentType,
                null,
                LosslessYaml.parse(yaml));
    }

    private static final ValidationEnvironment ENV = new ValidationEnvironment(List.of(), java.util.Set.of());

    @Test
    void authoredComponentsWarnWithTheKeys() {
        var problems = new ItemComponentsValidator().validate(resource("item", """
                thing:
                  material: NETHERITE_SWORD
                  components:
                    unbreakable: true
                    max_damage: 2500
                """), ENV);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).code().code()).isEqualTo("MTR-0048");
        assertThat(problems.get(0).severity()).isEqualTo(Severity.WARN);
        assertThat(problems.get(0).message()).contains("2 vanilla component(s)");
        assertThat(problems.get(0).message()).contains("max_damage").contains("unbreakable");
    }

    @Test
    void emptyOrAbsentBlocksStaySilent() {
        assertThat(new ItemComponentsValidator().validate(resource("item", """
                thing:
                  material: NETHERITE_SWORD
                  components: {}
                """), ENV)).isEmpty();
        assertThat(new ItemComponentsValidator().validate(resource("item", """
                thing:
                  material: NETHERITE_SWORD
                """), ENV)).isEmpty();
    }

    @Test
    void onlyItemsAreInspected() {
        assertThat(new ItemComponentsValidator().validate(resource("block", """
                thing:
                  material: STONE
                  components:
                    unbreakable: true
                """), ENV)).isEmpty();
    }
}
