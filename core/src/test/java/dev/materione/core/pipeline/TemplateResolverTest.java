package dev.materione.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.core.pipeline.TemplateResolver.Resolution;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Template inheritance as both the pipeline and the editor read it
 * (runtime-systems §3.2): parent-first merge where the nearer template and
 * the definition win, provenance per inherited top-level key, and refusal
 * (a null merge, never a guess) for unknown, cyclic or diamond chains.
 */
class TemplateResolverTest {

    private static final String NAMESPACE = "demo";

    /** Ordered map literal — key order is part of what the resolver merges. */
    private static Map<String, Object> map(Object... pairs) {
        var out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }

    /** demo:root <- demo:mid. Parent references are written as full ids. */
    private static Map<String, Map<String, Object>> chain() {
        return Map.of(
                "demo:root", map("material", "PAPER", "display_name", "Root Name",
                        "lore", List.of("root line")),
                "demo:mid", map("template", "demo:root", "display_name", "Mid Name", "storage", 9));
    }

    @Test
    void parentsMergeFirstAndTheNearerTemplateAndTheDefinitionWin() {
        Resolution resolution = TemplateResolver.resolve(
                map("template", "demo:mid", "material", "DIAMOND_SWORD"), NAMESPACE, chain());

        assertThat(resolution.failure()).isNull();
        assertThat(resolution.merged())
                .containsEntry("material", "DIAMOND_SWORD")   // the definition beats both templates
                .containsEntry("display_name", "Mid Name")    // the nearer template beats its parent
                .containsEntry("storage", 9)                  // only the child sets it
                .containsEntry("lore", List.of("root line")); // only the parent sets it
    }

    @Test
    void sourcesNameTheTemplateThatSuppliedEachInheritedKey() {
        Resolution resolution = TemplateResolver.resolve(
                map("template", "demo:mid", "material", "DIAMOND_SWORD"), NAMESPACE, chain());

        assertThat(resolution.sources())
                .containsEntry("display_name", "demo:mid")
                .containsEntry("storage", "demo:mid")
                .containsEntry("lore", "demo:root")
                .containsOnlyKeys("display_name", "storage", "lore");
    }

    @Test
    void chainListsTemplateIdsNearestFirst() {
        Resolution resolution = TemplateResolver.resolve(
                map("template", "demo:mid", "material", "DIAMOND_SWORD"), NAMESPACE, chain());

        assertThat(resolution.chain()).containsExactly("demo:mid", "demo:root");
    }

    @Test
    void aDefinitionWithoutTemplateIsItsOwnResolution() {
        Map<String, Object> definition = map("material", "PAPER");

        Resolution resolution = TemplateResolver.resolve(definition, NAMESPACE, chain());

        assertThat(resolution.failure()).isNull();
        assertThat(resolution.chain()).isEmpty();
        assertThat(resolution.sources()).isEmpty();
        assertThat(resolution.merged()).isEqualTo(definition);
    }

    @Test
    void aBlankTemplateReferenceIsTreatedAsNoReference() {
        Resolution resolution = TemplateResolver.resolve(
                map("template", "   ", "material", "PAPER"), NAMESPACE, chain());

        assertThat(resolution.chain()).isEmpty();
        assertThat(resolution.merged()).containsOnlyKeys("template", "material");
    }

    @Test
    void unknownTemplateFailsWithNoMergeAndNamesTheMissingId() {
        Resolution resolution = TemplateResolver.resolve(map("template", "ghost"), NAMESPACE, chain());

        assertThat(resolution.merged()).isNull();
        assertThat(resolution.failure()).contains("unknown template").contains("demo:ghost");
        assertThat(resolution.chain()).containsExactly("demo:ghost");
        assertThat(resolution.sources()).isEmpty();
    }

    @Test
    void aShortParentReferenceResolvesInTheNamespaceOfTheTemplateThatNamesIt() {
        Map<String, Map<String, Object>> chain = Map.of(
                "demo:root", map("material", "PAPER"),
                "other:mid", map("template", "other:far", "x", 1),
                "other:far", map("template", "far_root", "y", 2),
                "demo:mid", map("template", "root", "z", 3));

        Resolution local = TemplateResolver.resolve(map("template", "mid"), NAMESPACE, chain);
        Resolution foreign = TemplateResolver.resolve(map("template", "other:mid"), NAMESPACE, chain);

        assertThat(local.failure()).isNull();
        assertThat(local.chain()).containsExactly("demo:mid", "demo:root");
        assertThat(local.merged()).containsEntry("material", "PAPER").containsEntry("z", 3);
        // other:far names far_root, which is looked up in other: (and is absent), never in demo:
        assertThat(foreign.failure()).contains("unknown template other:far_root");
    }

    @Test
    void cyclicChainFailsWithNoMergeAndReportsTheRevisit() {
        Map<String, Map<String, Object>> cyclic = Map.of(
                "demo:a", map("template", "demo:b", "x", 1),
                "demo:b", map("template", "demo:a", "y", 2));

        Resolution resolution = TemplateResolver.resolve(map("template", "demo:a"), NAMESPACE, cyclic);

        assertThat(resolution.merged()).isNull();
        assertThat(resolution.failure()).contains("revisits").contains("demo:a");
        assertThat(resolution.chain()).containsExactly("demo:a", "demo:b");
    }

    @Test
    void mergeIsNullExactlyWhenResolutionFailsAndOtherwiseEqualsItsMerged() {
        var definition = map("template", "demo:mid", "material", "DIAMOND_SWORD");
        Map<String, Map<String, Object>> cyclic = Map.of(
                "demo:a", map("template", "demo:a"));

        assertThat(TemplateResolver.merge(map("template", "ghost"), NAMESPACE, chain())).isNull();
        assertThat(TemplateResolver.merge(map("template", "demo:a"), NAMESPACE, cyclic)).isNull();
        assertThat(TemplateResolver.merge(definition, NAMESPACE, chain()))
                .isEqualTo(TemplateResolver.resolve(definition, NAMESPACE, chain()).merged());
    }

    @Test
    void shortReferencesResolveAgainstTheDefaultNamespaceAndQualifiedOnesDoNot() {
        Map<String, Map<String, Object>> pool = Map.of(
                "demo:base", map("material", "PAPER"),
                "other:shared", map("material", "STONE"));

        Resolution shortRef = TemplateResolver.resolve(map("template", "base"), "demo", pool);
        assertThat(shortRef.chain()).containsExactly("demo:base");
        assertThat(shortRef.merged()).containsEntry("material", "PAPER");

        Resolution qualified = TemplateResolver.resolve(map("template", "other:shared"), "demo", pool);
        assertThat(qualified.chain()).containsExactly("other:shared");
        assertThat(qualified.merged()).containsEntry("material", "STONE");

        assertThat(TemplateResolver.shortRef("demo", "base")).isEqualTo("demo:base");
        assertThat(TemplateResolver.shortRef("demo", "other:base")).isEqualTo("other:base");
    }
}
