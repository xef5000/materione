package dev.materione.core.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Templates as a creation choice (issue 154): a resource born from a template
 * is the link plus its name; detaching materialises the inherited values; a
 * template can be extracted from a resource. All of it through the lossless
 * layer, journaled, and revision-guarded like every other write.
 */
class TemplateAuthoringTest {

    @TempDir
    Path temp;

    private AuthoringApi api;

    @BeforeEach
    void setUp() throws Exception {
        Path packsRoot = temp.resolve("packs");
        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        api = new AuthoringApiImpl(
                documents,
                new AuditLog(temp.resolve("audit.log")),
                packsRoot,
                new ModelTransactionService(documents, packsRoot, new AuditLog(temp.resolve("audit.log"))),
                null,
                () -> null,
                (actor, pack, file) -> { },
                null,
                () -> List.of(new FakeType("item", "items", "material", "lore"),
                        new FakeType("block", "blocks", "technique", "texture")));
        api.createPack(editor(), "demo").value();
        Files.createDirectories(temp.resolve("packs/demo/templates"));
        Files.writeString(temp.resolve("packs/demo/templates/base_item.yml"), """
                # shared by every blade
                base_item:
                  future_field: keep me
                  material: PAPER
                  lore:
                    - Forged
                    - Hard
                """);
    }

    private static ActorContext editor() {
        return ActorContext.editorSession("session-1", null, node -> true);
    }

    private String read(String path) throws Exception {
        return Files.readString(temp.resolve("packs/demo/" + path));
    }

    private AuthoringApi.CreateResourceRequest dagger(String template) {
        return new AuthoringApi.CreateResourceRequest(
                "demo", "ruby_dagger", "Ruby Dagger", "items", null, false, template);
    }

    @Test
    @SuppressWarnings("unchecked")
    void creatingFromATemplateWritesTheLinkAndTheNameOnly() throws Exception {
        ApiResult<AuthoringApi.DocumentView> created = api.createResource(editor(), dagger("base_item"));

        assertThat(created.ok()).as(created.diagnostics().toString()).isTrue();
        assertThat(read("items/ruby_dagger.yml")).isEqualTo("""
                # ruby_dagger — created by the Materione editor
                ruby_dagger:
                  template: base_item
                  display_name: Ruby Dagger
                """);
        // the studio's inheritance view sees every value the template supplies
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) ((Map<String, Object>) created.value().computed()
                .get("inheritance")).get("ruby_dagger");
        assertThat(view).containsEntry("chain", List.of("demo:base_item"));
        assertThat((Map<String, Object>) view.get("inherited")).containsKeys("material", "lore", "future_field");
        assertThat(api.getHistory("demo", "items/ruby_dagger.yml", 5).value()).hasSize(1);
    }

    @Test
    void aNewTemplateStartsWithOneFieldOfTheTypeItServes() throws Exception {
        api.createResource(editor(), new AuthoringApi.CreateResourceRequest(
                "demo", "lamp_base", "Lamp Base", "templates", null, false, null, "blocks"));
        api.createResource(editor(), new AuthoringApi.CreateResourceRequest(
                "demo", "blade_base", "Blade Base", "templates", null, false, null, "items"));

        assertThat(read("templates/lamp_base.yml")).contains("lamp_base:\n  technique: note_block");
        assertThat(read("templates/blade_base.yml")).contains("blade_base:\n  material: PAPER");
        // and the studio can extend it: the new root is an ordinary block mapping
        ApiResult<AuthoringApi.DocumentView> edited = api.setFields(editor(), new AuthoringApi.SetFieldsRequest(
                "demo", "templates/blade_base.yml", 1,
                List.of(new AuthoringApi.FieldEdit(List.of("blade_base", "lore"), List.of("Sharp")))));
        assertThat(edited.ok()).as(edited.diagnostics().toString()).isTrue();
    }

    @Test
    void anUnknownTemplateWritesNothing() {
        ApiResult<AuthoringApi.DocumentView> refused = api.createResource(editor(), dagger("nope"));

        assertThat(refused.ok()).isFalse();
        assertThat(refused.diagnostics().get(0).message()).contains("unknown template demo:nope");
        assertThat(temp.resolve("packs/demo/items/ruby_dagger.yml")).doesNotExist();
    }

    @Test
    void detachingMaterialisesTheInheritedValuesAndKeepsTheOverrides() throws Exception {
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest("demo", "items/ruby_dagger.yml", 0, """
                # my dagger
                ruby_dagger:
                  template: base_item # linked
                  display_name: "Ruby Dagger"
                  material: IRON_SWORD
                """));

        ApiResult<AuthoringApi.DocumentView> detached = api.detachTemplate(editor(),
                new AuthoringApi.DetachTemplateRequest("demo", "items/ruby_dagger.yml", "ruby_dagger", 1));

        assertThat(detached.ok()).as(detached.diagnostics().toString()).isTrue();
        // the override wins and stays put; only the values it did not set arrive
        assertThat(read("items/ruby_dagger.yml")).isEqualTo("""
                # my dagger
                ruby_dagger:
                  display_name: "Ruby Dagger"
                  material: IRON_SWORD
                  future_field: keep me
                  lore:
                    - Forged
                    - Hard
                """);
        assertThat(detached.value().computed()).doesNotContainKey("inheritance");
        assertThat(api.getHistory("demo", "items/ruby_dagger.yml", 5).value()).hasSize(2);
        assertThat(Files.readString(temp.resolve("audit.log"))).contains("authoring.detachTemplate");
    }

    @Test
    void detachingFollowsTheWholeChainParentFirst() throws Exception {
        Files.writeString(temp.resolve("packs/demo/templates/blade.yml"), """
                blade:
                  template: base_item
                  material: DIAMOND_SWORD
                """);
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest("demo", "items/ruby_dagger.yml", 0, """
                ruby_dagger:
                  template: blade
                  display_name: "Ruby Dagger"
                """));

        ApiResult<AuthoringApi.DocumentView> detached = api.detachTemplate(editor(),
                new AuthoringApi.DetachTemplateRequest("demo", "items/ruby_dagger.yml", null, 1));
        assertThat(detached.ok()).as(detached.diagnostics().toString()).isTrue();

        String raw = read("items/ruby_dagger.yml");
        assertThat(raw).doesNotContain("template:").contains("material: DIAMOND_SWORD")
                .contains("future_field: keep me").contains("- Forged");
    }

    @Test
    void detachingARootThatIsNotBasedOnATemplateIsRefused() {
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/plain.yml", 0, "plain:\n  material: STICK\n"));

        ApiResult<AuthoringApi.DocumentView> refused = api.detachTemplate(editor(),
                new AuthoringApi.DetachTemplateRequest("demo", "items/plain.yml", "plain", 1));

        assertThat(refused.ok()).isFalse();
        assertThat(refused.diagnostics().get(0).message()).contains("not based on a template");
    }

    @Test
    void aStaleDetachIsAConflictNotAnOverwrite() {
        api.createResource(editor(), dagger("base_item"));

        ApiResult<AuthoringApi.DocumentView> stale = api.detachTemplate(editor(),
                new AuthoringApi.DetachTemplateRequest("demo", "items/ruby_dagger.yml", "ruby_dagger", 7));

        assertThat(stale.ok()).isFalse();
        assertThat(stale.diagnostics().get(0).code().code()).isEqualTo("MTR-0034");
    }

    @Test
    void savingAsATemplateExtractsTheChosenFieldsLosslessly() throws Exception {
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest("demo", "items/ruby_dagger.yml", 0, """
                ruby_dagger:
                  display_name: "Ruby Dagger"
                  material: IRON_SWORD
                  lore:
                    - Sharp
                  mechanics:
                    - durability:
                        max: 250
                """));

        ApiResult<AuthoringApi.DocumentView> saved = api.saveAsTemplate(editor(),
                new AuthoringApi.SaveAsTemplateRequest(
                        "demo", "items/ruby_dagger.yml", "ruby_dagger", "dagger_base",
                        List.of("material", "mechanics", "lore")));

        assertThat(saved.ok()).as(saved.diagnostics().toString()).isTrue();
        assertThat(saved.value().path()).isEqualTo("templates/dagger_base.yml");
        assertThat(read("templates/dagger_base.yml")).isEqualTo("""
                # dagger_base — saved from items/ruby_dagger.yml by the Materione editor
                dagger_base:
                  material: IRON_SWORD
                  mechanics:
                    - durability:
                        max: 250
                  lore:
                    - Sharp
                """);
        // the source is untouched, and the new template is immediately usable
        assertThat(read("items/ruby_dagger.yml")).contains("display_name: \"Ruby Dagger\"");
        assertThat(api.createResource(editor(), new AuthoringApi.CreateResourceRequest(
                "demo", "silver_dagger", "Silver Dagger", "items", null, false, "dagger_base")).ok())
                .isTrue();
    }

    @Test
    void savingAsATemplateRefusesClashesAndFieldsTheResourceDoesNotSet() {
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/plain.yml", 0, "plain:\n  material: STICK\n"));

        ApiResult<AuthoringApi.DocumentView> clash = api.saveAsTemplate(editor(),
                new AuthoringApi.SaveAsTemplateRequest(
                        "demo", "items/plain.yml", "plain", "base_item", List.of("material")));
        ApiResult<AuthoringApi.DocumentView> absent = api.saveAsTemplate(editor(),
                new AuthoringApi.SaveAsTemplateRequest(
                        "demo", "items/plain.yml", "plain", "other", List.of("lore")));
        ApiResult<AuthoringApi.DocumentView> empty = api.saveAsTemplate(editor(),
                new AuthoringApi.SaveAsTemplateRequest(
                        "demo", "items/plain.yml", "plain", "other", List.of()));

        assertThat(clash.ok()).isFalse();
        assertThat(clash.diagnostics().get(0).message()).contains("already exists");
        assertThat(absent.ok()).isFalse();
        assertThat(absent.diagnostics().get(0).message()).contains("does not set 'lore'");
        assertThat(empty.ok()).isFalse();
        assertThat(temp.resolve("packs/demo/templates/other.yml")).doesNotExist();
    }

    @Test
    void theCatalogDerivesWhatATemplateServesAndWhoUsesIt() throws Exception {
        Files.writeString(temp.resolve("packs/demo/templates/slab_base.yml"), """
                slab_base:
                  technique: slab
                """);
        Files.writeString(temp.resolve("packs/demo/templates/mystery.yml"), "mystery:\n  zzz: 1\n");
        api.createResource(editor(), dagger("base_item"));

        List<AuthoringApi.TemplateInfo> templates = api.listTemplates().value();

        assertThat(templates).extracting(AuthoringApi.TemplateInfo::id)
                .containsExactly("base_item", "mystery", "slab_base");
        AuthoringApi.TemplateInfo base = templates.get(0);
        assertThat(base.serves()).containsExactly("items");
        assertThat(base.usedBy()).extracting(AuthoringApi.ResourceRef::resourceId).containsExactly("ruby_dagger");
        assertThat(base.definition()).containsKeys("material", "lore", "future_field");
        assertThat(templates.get(2).serves()).containsExactly("blocks");
        // nothing to go on: any template-capable type may use it
        assertThat(templates.get(1).serves()).containsExactly("items", "blocks");
        assertThat(templates.get(1).usedBy()).isEmpty();
    }

    /** A type with a {@code template} reference field plus the fields given. */
    private static final class FakeType implements ContentType {
        private final String name;
        private final String directory;
        private final List<String> fields;

        FakeType(String name, String directory, String... fields) {
            this.name = name;
            this.directory = directory;
            this.fields = List.of(fields);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String directory() {
            return directory;
        }

        @Override
        public SelfDescription describe() {
            List<ParameterSchema> parameters = new java.util.ArrayList<>();
            parameters.add(new ParameterSchema("display_name", ParameterType.STRING, false, null, null, null, null));
            parameters.add(new ParameterSchema("template", ParameterType.REFERENCE, false, null, null, null, null)
                    .withEditor(new ParameterSchema.EditorMetadata(
                            "Template", "identity", 20, true, "reference", "template", "")));
            fields.forEach(field -> parameters.add(
                    new ParameterSchema(field, ParameterType.STRING, false, null, null, null, null)));
            return new SelfDescription(name, "", parameters, "test");
        }

        @Override
        public Optional<Object> parse(
                NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
            return Optional.of(definition);
        }

        @Override
        public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter,
                Allocations allocations) { }
    }
}
