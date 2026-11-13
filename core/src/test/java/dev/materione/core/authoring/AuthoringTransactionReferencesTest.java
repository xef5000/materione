package dev.materione.core.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.FieldEdit;
import dev.materione.api.authoring.TransactionOperation;
import dev.materione.api.authoring.TransactionOperation.Create;
import dev.materione.api.authoring.TransactionOperation.Delete;
import dev.materione.api.authoring.TransactionOperation.Move;
import dev.materione.api.authoring.TransactionOperation.SetFields;
import dev.materione.api.authoring.TransactionRequest;
import dev.materione.api.authoring.TransactionResult;
import dev.materione.api.authoring.TransactionResult.OperationDiagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.Documents;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.ParsedContent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reference stage of a transaction's dry run (master section 5.4 level 3, issue 357): {@code ReferenceValidator}
 * over the pack as the list leaves it and the other packs as stored. A dry run and the check before an apply are the
 * same code, so each refused case is also run as an apply and must write nothing.
 */
class AuthoringTransactionReferencesTest {

    private static final String PACK = "demo";

    @TempDir
    Path temp;

    private AuthoringApiImpl api;
    private final List<String> reloads = new ArrayList<>();
    private ParsedContent others = ParsedContent.EMPTY;

    @BeforeEach
    void setUp() {
        Path packsRoot = temp.resolve("packs");
        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        api = new AuthoringApiImpl(documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit), null, () -> null,
                (actor, pack, file) -> reloads.add(pack + "|" + file.getFileName()),
                null, List::of);
        api.parsedContent(() -> others);
        api.createPack(editor(), PACK).value();
        seed("items/alpha.yml", "alpha:\n  material: STICK\n");
        seed("items/beta.yml", "beta:\n  material: STICK\n");
        seed("templates/base.yml", "base:\n  material: PAPER\n");
        seed("items/child.yml", "child:\n  template: base\n");
        seed("recipes/r.yml", "r:\n  type: shaped\n  result: alpha\n  ingredients:\n    A: beta\n");
        reloads.clear();
    }

    private static ActorContext editor() {
        return ActorContext.editorSession("session-1", null, node -> true);
    }

    private int seed(String path, String content) {
        return api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(PACK, path, 0, content))
                .value().revision();
    }

    private ApiResult<TransactionResult> run(boolean dryRun, TransactionOperation... operations) {
        return api.transaction(editor(), new TransactionRequest(PACK, List.of(operations), dryRun));
    }

    private static FieldEdit set(String root, String field, Object value) {
        return new FieldEdit(List.of(root, field), value);
    }

    private static List<OperationDiagnostic> unresolved(TransactionResult result) {
        return result.diagnostics().stream()
                .filter(found -> found.diagnostic().code() == DiagnosticCodes.UNRESOLVED_REFERENCE).toList();
    }

    private static boolean stage(TransactionResult result, String id) {
        return result.stages().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow().ran();
    }

    /** The case, as a dry run (reported) and as an apply (refused, nothing written, no reload). */
    private TransactionResult refusedBoth(TransactionOperation... operations) throws IOException {
        List<String> before = snapshot();
        TransactionResult dry = run(true, operations).value();
        assertThat(dry.accepted()).as(String.valueOf(dry.diagnostics())).isFalse();
        assertThat(snapshot()).isEqualTo(before);

        ApiResult<TransactionResult> applied = run(false, operations);
        assertThat(applied.value().accepted()).isFalse();
        assertThat(applied.value().applied()).isFalse();
        assertThat(applied.value().transactionId()).isNull();
        assertThat(snapshot()).as("a refused apply writes nothing").isEqualTo(before);
        assertThat(reloads).isEmpty();
        assertThat(applied.value().diagnostics()).isEqualTo(dry.diagnostics());
        return dry;
    }

    // ------------------------------------------------------------------

    @Test
    void deletingAReferencedItemIsRefusedWithTheOperationAndTheReference() throws Exception {
        TransactionResult dry = refusedBoth(new Delete("items/alpha.yml", 1));

        assertThat(stage(dry, "references")).isTrue();
        List<OperationDiagnostic> found = unresolved(dry);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).operation()).isEqualTo(0);
        assertThat(found.get(0).diagnostic().message()).contains("recipe demo:r", "result", "alpha");
        assertThat(found.get(0).diagnostic().resource()).isEqualTo(NamespacedId.of(PACK, "r"));
    }

    @Test
    void theOperationThatOrphanedItIsNamedNotTheFirst() throws Exception {
        TransactionResult dry = refusedBoth(
                new SetFields("items/beta.yml", 1, List.of(set("beta", "material", "BONE"))),
                new Delete("items/alpha.yml", 1));

        assertThat(unresolved(dry)).hasSize(1);
        assertThat(unresolved(dry).get(0).operation()).isEqualTo(1);
    }

    @Test
    void aMistypedIdInACreatedDocumentIsCaught() throws Exception {
        TransactionResult dry = refusedBoth(
                new Create("recipes", null, "r2", null, List.of(set("r2", "result", "alhpa"))));

        List<OperationDiagnostic> found = unresolved(dry);
        assertThat(found).isNotEmpty();
        assertThat(found.get(0).operation()).isEqualTo(0);
        assertThat(found.get(0).diagnostic().message()).contains("alhpa");
    }

    @Test
    void aMistypedTemplateOnAnEditedItemIsCaught() throws Exception {
        TransactionResult dry = refusedBoth(
                new SetFields("items/child.yml", 1, List.of(set("child", "template", "bsae"))));

        assertThat(unresolved(dry)).anyMatch(found -> found.diagnostic().message().contains("bsae")
                && found.operation() == 0);
    }

    @Test
    void aLaterOperationThatCreatesTheReferencedDocumentMakesItClean() throws Exception {
        TransactionResult dry = value(run(true,
                new Create("recipes", null, "r2", null, List.of(set("r2", "result", "gamma"))),
                new Create(null, null, "gamma", "Gamma", List.of())));
        assertThat(dry.accepted()).as(String.valueOf(dry.diagnostics())).isTrue();
        assertThat(unresolved(dry)).isEmpty();
        assertThat(stage(dry, "references")).isTrue();

        // and the other order, as an apply
        TransactionResult applied = value(run(false,
                new Create(null, null, "gamma", "Gamma", List.of()),
                new Create("recipes", null, "r2", null, List.of(set("r2", "result", "gamma")))));
        assertThat(applied.applied()).isTrue();
    }

    @Test
    void deletingTheItemAndRepairingItsUserInOneListIsClean() throws Exception {
        TransactionResult dry = value(run(true,
                new Delete("items/alpha.yml", 1),
                new SetFields("recipes/r.yml", 1, List.of(set("r", "result", "beta")))));

        assertThat(dry.accepted()).as(String.valueOf(dry.diagnostics())).isTrue();
    }

    @Test
    void aMoveKeepsTheIdsSoNothingIsOrphaned() throws Exception {
        TransactionResult dry = value(run(true, new Move("items/alpha.yml", 1, "items/moved/alpha.yml")));

        assertThat(dry.accepted()).as(String.valueOf(dry.diagnostics())).isTrue();
    }

    @Test
    void aDeletedTemplateBreaksItsUsers() throws Exception {
        TransactionResult dry = refusedBoth(new Delete("templates/base.yml", 1));

        assertThat(unresolved(dry)).anyMatch(found -> found.diagnostic().message().contains("base")
                && found.operation() == 0);
    }

    @Test
    void anOtherPacksResourceThatNamesADeletedOneIsReportedWithTheOperation() throws Exception {
        others = new ParsedContent(List.of(new ContentPack("other", temp, null, List.of(resource("other", "stew",
                "recipe", "recipes/stew.yml", "stew:\n  result: demo:alpha\n")))), Set.of(), Map.of());

        TransactionResult dry = refusedBoth(new Delete("items/alpha.yml", 1));

        assertThat(unresolved(dry)).extracting(found -> found.diagnostic().resource())
                .contains(NamespacedId.of("other", "stew"), NamespacedId.of(PACK, "r"));
        assertThat(unresolved(dry)).extracting(OperationDiagnostic::operation).containsOnly(0);
    }

    @Test
    void anOtherPacksResourceThatStillResolvesIsLeftAlone() throws Exception {
        others = new ParsedContent(List.of(new ContentPack("other", temp, null, List.of(resource("other", "stew",
                "recipe", "recipes/stew.yml", "stew:\n  result: demo:beta\n")))), Set.of(), Map.of());

        TransactionResult dry = value(run(true, new Delete("items/alpha.yml", 1),
                new SetFields("recipes/r.yml", 1, List.of(set("r", "result", "beta")))));

        assertThat(dry.accepted()).as(String.valueOf(dry.diagnostics())).isTrue();
    }

    @Test
    void aResourceThatWasAlreadyBrokenIsNotChargedToAListThatDidNotBreakIt() throws Exception {
        seed("recipes/old.yml", "old:\n  result: alpha\n  ingredients:\n    A: ghost\n");
        reloads.clear();

        TransactionResult dry = refusedBoth(new Delete("items/alpha.yml", 1));

        assertThat(unresolved(dry)).extracting(found -> found.diagnostic().message())
                .noneMatch(message -> message.contains("ghost"))
                .anyMatch(message -> message.contains("recipe demo:old") && message.contains("alpha"));
    }

    @Test
    void anUntouchedResourceThatIsAlreadyBrokenDoesNotBlockAnUnrelatedList() throws Exception {
        seed("recipes/old.yml", "old:\n  result: ghost\n");

        TransactionResult dry = value(run(true, new SetFields("items/beta.yml", 1,
                List.of(set("beta", "material", "BONE")))));

        assertThat(dry.accepted()).as(String.valueOf(dry.diagnostics())).isTrue();
    }

    @Test
    void anAssetTheStagedPackShipsResolvesATextureReference() throws Exception {
        Path textures = temp.resolve("packs").resolve(PACK).resolve("assets").resolve("textures");
        Files.createDirectories(textures);
        Files.write(textures.resolve("gem.png"), new byte[] {1});

        TransactionResult ok = value(run(true,
                new SetFields("items/beta.yml", 1, List.of(set("beta", "texture", "gem")))));
        assertThat(ok.accepted()).as(String.valueOf(ok.diagnostics())).isTrue();

        TransactionResult missing = value(run(true,
                new SetFields("items/beta.yml", 1, List.of(set("beta", "texture", "gme")))));
        assertThat(missing.accepted()).isFalse();
        assertThat(unresolved(missing)).anyMatch(found -> found.diagnostic().message().contains("gme"));
    }

    @Test
    void withoutAParsedSetTheStageSaysItDidNotRun() {
        api.parsedContent(null);

        TransactionResult dry = value(run(true, new Delete("items/alpha.yml", 1)));

        assertThat(stage(dry, "references")).isFalse();
        assertThat(dry.accepted()).isTrue();
    }

    /** Whatever the list, a dry run with the reference stage on leaves packs, journals and audit byte-identical. */
    @Test
    void theReferenceStageKeepsTheDryRunPropertyOfAStoreThatDoesNotMove() throws Exception {
        List<String> before = snapshot();
        Random random = new Random(357);
        String[] stored = {"items/alpha.yml", "items/beta.yml", "templates/base.yml", "items/child.yml", "recipes/r.yml"};
        for (int round = 0; round < 60; round++) {
            List<TransactionOperation> operations = new ArrayList<>();
            int count = 1 + random.nextInt(4);
            for (int i = 0; i < count; i++) {
                String path = stored[random.nextInt(stored.length)];
                String root = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
                switch (random.nextInt(4)) {
                    case 0 -> operations.add(new Delete(path, 1));
                    case 1 -> operations.add(new Move(path, 1, "items/m" + random.nextInt(3) + ".yml"));
                    case 2 -> operations.add(new SetFields(path, 1, List.of(set(root, "result",
                            random.nextBoolean() ? "alpha" : "nowhere"))));
                    default -> operations.add(new Create("recipes", null, "n" + random.nextInt(3), null,
                            List.of(set("n" + random.nextInt(3), "result", "beta"))));
                }
            }
            run(true, operations.toArray(TransactionOperation[]::new));
            assertThat(snapshot()).as("round " + round).isEqualTo(before);
            assertThat(reloads).isEmpty();
        }
    }

    /** The cost of the stage on a pack the size of the corpus: thousands of resources, one of them referenced. */
    @Test
    void theStageCostsMillisecondsOnAPackOfThousandsOfResources() throws Exception {
        Path items = temp.resolve("packs").resolve(PACK).resolve("items");
        Path recipes = temp.resolve("packs").resolve(PACK).resolve("recipes");
        for (int i = 0; i < 1999; i++) {
            Files.writeString(items.resolve("bulk" + i + ".yml"),
                    "bulk" + i + ":\n  material: STICK\n  texture: t" + i + "\n");
            Files.writeString(recipes.resolve("rb" + i + ".yml"),
                    "rb" + i + ":\n  type: shapeless\n  result: bulk" + i + "\n  ingredients:\n    - beta\n");
        }
        int revision = seed("items/bulk1999.yml", "bulk1999:\n  material: STICK\n  texture: t1999\n");
        Files.writeString(recipes.resolve("rb1999.yml"),
                "rb1999:\n  type: shapeless\n  result: bulk1999\n  ingredients:\n    - beta\n");
        run(true, new SetFields("items/beta.yml", 1, List.of(set("beta", "material", "BONE")))); // warm up
        long start = System.nanoTime();
        TransactionResult dry = value(run(true, new Delete("items/bulk1999.yml", revision)));
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("references stage: dry run of a delete over 4000 resources took " + millis + " ms");

        assertThat(unresolved(dry)).extracting(found -> found.diagnostic().message())
                .anyMatch(message -> message.contains("rb1999"));
        assertThat(millis).as("budget: well inside the 1 s targeted-reload budget").isLessThan(1000);
    }

    // ------------------------------------------------------------------

    private static TransactionResult value(ApiResult<TransactionResult> result) {
        assertThat(result.value()).as(String.valueOf(result.diagnostics())).isNotNull();
        return result.value();
    }

    private static ContentResource resource(String pack, String id, String type, String path, String yaml) {
        return new ContentResource(NamespacedId.of(pack, id), type, Path.of(path), LosslessYaml.parse(yaml));
    }

    private List<String> snapshot() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Path root : List.of(temp.resolve("packs"), temp.resolve("revisions"))) {
            if (!Files.exists(root)) {
                lines.add("absent " + root.getFileName());
                continue;
            }
            try (var walk = Files.walk(root)) {
                walk.sorted().forEach(path -> {
                    String relative = root.getFileName() + "/" + root.relativize(path).toString().replace('\\', '/');
                    try {
                        lines.add(Files.isDirectory(path) ? "D " + relative
                                : "F " + relative + " " + Documents.sha256(Files.readAllBytes(path)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
        Path audit = temp.resolve("audit.log");
        lines.add(Files.exists(audit) ? "audit " + Documents.sha256(Files.readAllBytes(audit)) : "audit absent");
        return lines;
    }
}
