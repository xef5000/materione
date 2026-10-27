package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.content.blocks.BlockType;
import dev.materione.content.items.ItemType;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #154 against the real registry types and the shipped sample pack: the
 * flow the editor drives — pick {@code base_item} in the New dialog, inherit,
 * detach — resolves through the same {@code ItemType} the pipeline compiles
 * with, so the summary an author sees at each step is what the game would get.
 */
class SamplePackTemplatesTest {

    @TempDir
    Path temp;

    private AuthoringApi api;

    @BeforeEach
    void setUp() throws IOException {
        Path packsRoot = temp.resolve("packs");
        copy(Path.of("..", "fixtures", "packs", "sample"), packsRoot.resolve("sample"));
        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        api = new AuthoringApiImpl(
                documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit),
                null, () -> null, (actor, pack, file) -> { }, null,
                () -> List.of(new ItemType(), new BlockType()));
    }

    private static ActorContext editor() {
        return ActorContext.editorSession("session-1", null, node -> true);
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : files.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (source.toString().endsWith(".yml")) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
    }

    private String read(String path) throws IOException {
        return Files.readString(temp.resolve("packs/sample/" + path));
    }

    private String materialOf(String id) {
        return api.listResourceSummaries(
                        new AuthoringApi.ResourceFilter("sample", "items", id), 0, 10)
                .value().resources().stream()
                .filter(summary -> summary.resourceId().equals(id)).findFirst().orElseThrow().material();
    }

    @Test
    void theSamplePacksBaseItemServesItemsAndKnowsItsUsers() {
        AuthoringApi.TemplateInfo base = api.listTemplates().value().stream()
                .filter(template -> template.id().equals("base_item")).findFirst().orElseThrow();

        assertThat(base.serves()).containsExactly("items");
        assertThat(base.usedBy()).extracting(AuthoringApi.ResourceRef::resourceId)
                .containsExactlyInAnyOrder("ruby_sword", "templated_ruby");
        assertThat(base.definition()).containsKeys("material", "future_field", "custom_model");
    }

    @Test
    void rubyDaggerFromBaseItemInheritsThenDetachesWithTheSameMaterialAtEveryStep() throws Exception {
        ApiResult<AuthoringApi.DocumentView> created = api.createResource(editor(),
                new AuthoringApi.CreateResourceRequest(
                        "sample", "ruby_dagger", "Ruby Dagger", "items", null, false, "base_item"));

        assertThat(created.ok()).as(created.diagnostics().toString()).isTrue();
        assertThat(read("items/ruby_dagger.yml")).isEqualTo("""
                # ruby_dagger — created by the Materione editor
                ruby_dagger:
                  template: base_item
                  display_name: Ruby Dagger
                """);
        // the real ItemType resolves the link: the material is the template's
        assertThat(materialOf("ruby_dagger")).isEqualTo("PAPER");

        ApiResult<AuthoringApi.DocumentView> detached = api.detachTemplate(editor(),
                new AuthoringApi.DetachTemplateRequest("sample", "items/ruby_dagger.yml", "ruby_dagger", 1));

        assertThat(detached.ok()).as(detached.diagnostics().toString()).isTrue();
        String raw = read("items/ruby_dagger.yml");
        assertThat(raw).doesNotContain("template:").contains("display_name: Ruby Dagger")
                .contains("material: PAPER").contains("future_field: keep me")
                .contains("custom_model:");
        // nothing changed for the game: the detached item is what it was
        assertThat(materialOf("ruby_dagger")).isEqualTo("PAPER");
        // and the template no longer counts it as a user
        assertThat(api.listTemplates().value().stream()
                .filter(template -> template.id().equals("base_item")).findFirst().orElseThrow().usedBy())
                .extracting(AuthoringApi.ResourceRef::resourceId)
                .doesNotContain("ruby_dagger");
    }

    @Test
    void saveAsTemplateFromTheSampleSwordProducesATemplateTheTypeCanInherit() throws Exception {
        ApiResult<AuthoringApi.DocumentView> saved = api.saveAsTemplate(editor(),
                new AuthoringApi.SaveAsTemplateRequest(
                        "sample", "items/ruby_sword.yml", "ruby_sword", "sword_base",
                        List.of("material", "mechanics")));

        assertThat(saved.ok()).as(saved.diagnostics().toString()).isTrue();
        assertThat(read("templates/sword_base.yml")).contains("material: NETHERITE_SWORD")
                .contains("- durability:");
        api.createResource(editor(), new AuthoringApi.CreateResourceRequest(
                "sample", "silver_sword", "Silver Sword", "items", null, false, "sword_base"));
        assertThat(materialOf("silver_sword")).isEqualTo("NETHERITE_SWORD");
        // the sword itself is untouched
        assertThat(read("items/ruby_sword.yml")).contains("template: base_item");
    }
}
