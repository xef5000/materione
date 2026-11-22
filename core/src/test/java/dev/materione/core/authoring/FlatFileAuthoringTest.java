package dev.materione.core.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.FieldEdit;
import dev.materione.api.authoring.TransactionOperation;
import dev.materione.api.authoring.TransactionOperation.Create;
import dev.materione.api.authoring.TransactionOperation.Duplicate;
import dev.materione.api.authoring.TransactionOperation.Move;
import dev.materione.api.authoring.TransactionOperation.SetFields;
import dev.materione.api.authoring.TransactionRequest;
import dev.materione.api.authoring.TransactionResult;
import dev.materione.api.content.AllocatedRange;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A flat file (#469, #475): {@code currencies/gems.yml} holding one currency as its top-level fields, named by the
 * file. The editor lists it as one resource, opens it keyed by that name, and an edit addressed to the resource
 * ({@code [gems, display_name]}, as for a keyed one) lands on the file's top level, through the one write path every
 * single-document call, bulk and the authoring transaction share. The file keeps its shape and every byte outside the
 * edit.
 */
class FlatFileAuthoringTest {

    private static final String PACK = "shop";

    private static final String GEMS = """
            # the gems currency
            integration: xp
            display_name: Gems # shown to players
            decimals: 0

            # read by the integration
            custom-key: kept
            """;

    @TempDir
    Path temp;

    private AuthoringApiImpl api;
    private Path gems;

    @BeforeEach
    void setUp() throws IOException {
        Path packsRoot = temp.resolve("packs");
        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        api = new AuthoringApiImpl(documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit), null, () -> null,
                (actor, pack, file) -> { }, null, () -> List.of(new StubCurrency()));
        api.previewAllocations(FreshAllocations::new);
        api.createPack(editor(), PACK).value();
        gems = temp.resolve("packs").resolve(PACK).resolve("currencies").resolve("gems.yml");
        Files.createDirectories(gems.getParent());
        Files.writeString(gems, GEMS);
    }

    // ------------------------------------------------------------------
    // listing and opening

    @Test
    void aFlatFileIsListedAsOneResourceNamedByTheFile() {
        List<AuthoringApi.ResourceRef> listed = api.listResources(
                new AuthoringApi.ResourceFilter(PACK, "currencies", null)).value();
        assertThat(listed).singleElement().satisfies(ref -> {
            assertThat(ref.resourceId()).isEqualTo("gems");
            assertThat(ref.path()).isEqualTo("currencies/gems.yml");
        });
        var page = api.listResourceSummaries(new AuthoringApi.ResourceFilter(PACK, "currencies", null), 0, 50).value();
        assertThat(page.resources()).singleElement().satisfies(row -> {
            assertThat(row.resourceId()).isEqualTo("gems");
            assertThat(row.path()).isEqualTo("currencies/gems.yml");
        });
    }

    @Test
    void aKeyedFileIsListedByItsKeysAsBefore() throws IOException {
        Files.writeString(gems.resolveSibling("bank.yml"), "coins:\n  integration: xp\nbucks:\n  integration: xp\n");
        List<String> ids = api.listResources(new AuthoringApi.ResourceFilter(PACK, "currencies", null)).value()
                .stream().map(AuthoringApi.ResourceRef::resourceId).toList();
        assertThat(ids).containsExactlyInAnyOrder("gems", "coins", "bucks");
    }

    @Test
    void openingAFlatFileShowsItsFieldsUnderTheFileName() {
        var view = api.getDocument(PACK, "currencies/gems.yml").value();
        assertThat(view.raw()).isEqualTo(GEMS);
        assertThat(view.parsed()).containsOnlyKeys("gems");
        assertThat(fieldsOf(view)).containsEntry("integration", "xp").containsEntry("display_name", "Gems")
                .containsEntry("custom-key", "kept");
    }

    // ------------------------------------------------------------------
    // editing keeps the shape

    @Test
    void aTransactionEditsAFlatFileAtItsTopLevelAndKeepsEveryOtherByte() throws IOException {
        int revision = api.getDocument(PACK, "currencies/gems.yml").value().revision();
        var result = run(new SetFields("currencies/gems.yml", revision, List.of(
                set("display_name", "Jewels"), set("decimals", 2), set("symbol", "G"))));
        assertThat(result.accepted()).as(String.valueOf(result)).isTrue();
        assertThat(result.applied()).isTrue();

        String after = Files.readString(gems);
        assertThat(after).doesNotContain("gems:");
        // only the touched lines differ: the same lines, in the same order, with those two rewritten and one added
        List<String> kept = new ArrayList<>(GEMS.lines().toList());
        kept.removeIf(line -> line.startsWith("display_name") || line.startsWith("decimals"));
        List<String> now = new ArrayList<>(after.lines().toList());
        now.removeIf(line -> line.startsWith("display_name") || line.startsWith("decimals") || line.startsWith("symbol"));
        assertThat(now).isEqualTo(kept);
        assertThat(after).contains("display_name: Jewels  # shown to players", "decimals: 2", "symbol: G");
        assertThat(after.lines().filter(line -> line.startsWith("symbol")).count()).isEqualTo(1);

        var view = api.getDocument(PACK, "currencies/gems.yml").value();
        assertThat(fieldsOf(view)).containsEntry("display_name", "Jewels").containsEntry("decimals", 2).containsEntry("symbol", "G");

        // and the pipeline reads the edited file as the same one currency, with nothing to report about its shape
        var declared = dev.materione.core.pipeline.PackResources.declared(
                PACK, "currencies", gems, dev.materione.core.document.LosslessYaml.parse(after));
        assertThat(declared.shape()).isEmpty();
        assertThat(declared.resources()).singleElement().satisfies(resource -> {
            assertThat(resource.id().toString()).isEqualTo("shop:gems");
            assertThat(resource.document().save()).isEqualTo(after);
        });
    }

    @Test
    void aTransactionRemovesAFieldOfAFlatFile() throws IOException {
        int revision = api.getDocument(PACK, "currencies/gems.yml").value().revision();
        var result = run(new SetFields("currencies/gems.yml", revision,
                List.of(new FieldEdit(List.of("gems", "custom-key"), null, true))));
        assertThat(result.applied()).as(String.valueOf(result)).isTrue();
        assertThat(Files.readString(gems)).isEqualTo(GEMS.replace("custom-key: kept\n", ""));
    }

    @Test
    void theSingleDocumentCallsEditAFlatFileTheSameWay() throws IOException {
        int revision = api.getDocument(PACK, "currencies/gems.yml").value().revision();
        var fields = api.setFields(editor(), new AuthoringApi.SetFieldsRequest(
                PACK, "currencies/gems.yml", revision, List.of(set("display_name", "Jewels")), null));
        assertThat(fields.ok()).as(String.valueOf(fields.diagnostics())).isTrue();
        var scalar = api.setScalar(editor(), new AuthoringApi.SetScalarRequest(
                PACK, "currencies/gems.yml", fields.value().revision(), List.of("gems", "decimals"), 3));
        assertThat(scalar.ok()).as(String.valueOf(scalar.diagnostics())).isTrue();
        assertThat(Files.readString(gems)).isEqualTo(GEMS
                .replace("display_name: Gems ", "display_name: Jewels  ").replace("decimals: 0", "decimals: 3"));
    }

    @Test
    void aRefusedEditLeavesTheFileUntouched() throws IOException {
        int revision = api.getDocument(PACK, "currencies/gems.yml").value().revision();
        // a mapping at the top level would turn the file into a keyed one, so its fields would stop being fields
        var nested = run(new SetFields("currencies/gems.yml", revision,
                List.of(new FieldEdit(List.of("gems", "options"), Map.of("a", 1), false))));
        assertThat(nested.accepted()).isFalse();
        assertThat(nested.diagnostics()).anySatisfy(d -> assertThat(d.diagnostic().message()).contains("keyed"));
        // an edit that names another resource, and one that names the whole file, are refused too
        assertThat(run(new SetFields("currencies/gems.yml", revision,
                List.of(new FieldEdit(List.of("other", "x"), "v", false)))).accepted()).isFalse();
        assertThat(run(new SetFields("currencies/gems.yml", revision,
                List.of(new FieldEdit(List.of("gems"), "y", false)))).accepted()).isFalse();
        assertThat(run(new SetFields("currencies/gems.yml", revision,
                List.of(new FieldEdit(List.of("gems"), null, true)))).accepted()).isFalse();
        assertThat(Files.readString(gems)).isEqualTo(GEMS);
    }

    @Test
    void theSchemaStageSeesAFlatResourceSoAnEditThatBreaksItIsRefused() throws IOException {
        int revision = api.getDocument(PACK, "currencies/gems.yml").value().revision();
        // the stub type needs an integration, as the real one does: removing it must be found, not skipped
        var broken = run(new SetFields("currencies/gems.yml", revision,
                List.of(new FieldEdit(List.of("gems", "integration"), null, true))));
        assertThat(broken.accepted()).as(String.valueOf(broken.diagnostics())).isFalse();
        assertThat(Files.readString(gems)).isEqualTo(GEMS);
    }

    // ------------------------------------------------------------------
    // copy, rename, create

    @Test
    void aCopyOfAFlatFileIsAFlatFileNamedByTheNewId() throws IOException {
        int revision = api.getDocument(PACK, "currencies/gems.yml").value().revision();
        var result = run(new Duplicate("currencies/gems.yml", revision, null, "coins",
                List.of(set("coins", "display_name", "Coins"))));
        assertThat(result.applied()).as(String.valueOf(result)).isTrue();
        Path copy = gems.resolveSibling("coins.yml");
        assertThat(Files.readString(copy)).isEqualTo(GEMS.replace("display_name: Gems ", "display_name: Coins  "));
        assertThat(api.listResources(new AuthoringApi.ResourceFilter(PACK, "currencies", null)).value())
                .extracting(AuthoringApi.ResourceRef::resourceId).containsExactlyInAnyOrder("gems", "coins");
        // the copy by the single-document call is the same file
        var single = api.duplicateResource(editor(),
                new AuthoringApi.DuplicateResourceRequest(PACK, "currencies/gems.yml", "pearls", null));
        assertThat(single.ok()).as(String.valueOf(single.diagnostics())).isTrue();
        assertThat(Files.readString(gems.resolveSibling("pearls.yml"))).isEqualTo(GEMS);
    }

    @Test
    void renamingTheFileRenamesTheResource() {
        int revision = api.getDocument(PACK, "currencies/gems.yml").value().revision();
        var result = run(new Move("currencies/gems.yml", revision, "currencies/jewels.yml"));
        assertThat(result.applied()).as(String.valueOf(result)).isTrue();
        assertThat(api.listResources(new AuthoringApi.ResourceFilter(PACK, "currencies", null)).value())
                .extracting(AuthoringApi.ResourceRef::resourceId).containsExactly("jewels");
        assertThat(api.getDocument(PACK, "currencies/jewels.yml").value().parsed()).containsOnlyKeys("jewels");
    }

    @Test
    void anIdAFlatFileHoldsCannotBeCreatedAgainKeyed() {
        var result = run(new Create("currencies", "other", "gems", "Gems", List.of()));
        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics()).anySatisfy(d ->
                assertThat(d.diagnostic().message()).contains("already declared in currencies/gems.yml"));
    }

    @Test
    void aNewCurrencyIsStillCreatedKeyed() throws IOException {
        var result = run(new Create("currencies", null, "coins", "Coins", List.of(set("coins", "decimals", 1))));
        assertThat(result.applied()).as(String.valueOf(result)).isTrue();
        String text = Files.readString(gems.resolveSibling("coins.yml"));
        assertThat(text).contains("coins:").contains("decimals: 1");
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(AuthoringApi.DocumentView view) {
        return (Map<String, Object>) view.parsed().get("gems");
    }

    private TransactionResult run(TransactionOperation... operations) {
        ApiResult<TransactionResult> result = api.transaction(
                editor(), new TransactionRequest(PACK, List.of(operations), false));
        assertThat(result.value()).as(String.valueOf(result.diagnostics())).isNotNull();
        return result.value();
    }

    private static FieldEdit set(String field, Object value) {
        return set("gems", field, value);
    }

    private static FieldEdit set(String root, String field, Object value) {
        return new FieldEdit(List.of(root, field), value);
    }

    private static ActorContext editor() {
        return ActorContext.editorSession("session-1", null, node -> true);
    }

    /** A currency type that, like the real one, needs an {@code integration} and reports that it takes a flat file. */
    private static final class StubCurrency implements ContentType {
        @Override
        public String name() {
            return "currency";
        }

        @Override
        public String directory() {
            return "currencies";
        }

        @Override
        public boolean flatFile() {
            return true;
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Currency", "", List.of(), "test");
        }

        @Override
        public Optional<Object> parse(
                NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
            return definition.get("integration") instanceof String text && !text.isBlank()
                    ? Optional.of(definition) : Optional.empty();
        }

        @Override
        public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter,
                Allocations allocations) {
            // money is runtime state: nothing is emitted
        }
    }

    private static final class FreshAllocations implements Allocations {
        @Override
        public String allocate(NamespacedId resource, String kind) {
            return "1";
        }

        @Override
        public AllocatedRange range(NamespacedId resource, String kind, int count) {
            return new AllocatedRange(1, count);
        }

        @Override
        public Optional<String> lookup(NamespacedId resource, String kind) {
            return Optional.empty();
        }
    }
}
