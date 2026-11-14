package dev.materione.core.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.authoring.TransactionResult.OperationDiagnostic;
import dev.materione.core.authoring.StagedReferences.StagedDocument;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PackResources;
import dev.materione.core.pipeline.ParsedContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scope claim of {@link StagedReferences}, proved rather than argued: over a pack that exercises every reference
 * form {@code ReferenceValidator} resolves, and a second pack whose resources name the first one's in each of those
 * forms, random edit lists give the same findings from the scoped check (a name index decides which untouched
 * resources to look at) as from the oracle (every untouched resource of every pack, before and after, with no
 * index): the findings on written resources, plus the findings the list adds to the rest.
 *
 * <p>The reference forms, enumerated from {@code ReferenceValidator}: {@code template} (templates, and items as
 * templates), {@code model}, {@code texture}; a recipe's {@code result}, {@code input} and {@code ingredients} (a
 * map's values, or a list); a block's {@code item} and {@code drops}; a furniture's {@code item} and
 * {@code evolution[].target}; a glyph's {@code reference}; a painting's {@code texture}; an item's
 * {@code jukebox_song}; the block/furniture shared id; the asset source lists ({@code frames}, {@code sounds}),
 * which name assets and not resources. Each is a whole string, bare or {@code pack:}-qualified. A form that is a
 * map key, or part of a longer string, would show up here as a miss.
 */
class StagedReferencesDifferentialTest {

    private static final String PACK = "demo";
    private static final int ROUNDS = 400;

    /** Every id a reference may name: present, absent, qualified, another pack's, vanilla, asset-like. */
    private static final List<String> TARGETS = List.of(
            "alpha", "beta", "gamma", "base", "base2", "demo:alpha", "demo:beta", "demo:base", "other:thing",
            "other:stew", "minecraft:stone", "ghost", "gem", "rock", "shield_block", "chair", "twin", "g1", "s1",
            "demo:shield_block", "demo:chair", "painting/p1", "demo:gem");

    @TempDir
    Path temp;

    private Path packDirectory;
    private ParsedContent others;
    private final StagedReferences.DocumentCache cache = new StagedReferences.DocumentCache();

    /** A document generator: {@code (random, stored) -> text}; stored values are the canonical, mostly valid ones. */
    private final Map<String, BiFunction<Random, Boolean, String>> fixture = new LinkedHashMap<>();

    private static String pick(Random random, boolean stored, String canonical) {
        return stored ? canonical : TARGETS.get(random.nextInt(TARGETS.size()));
    }

    @BeforeEach
    void setUp() throws IOException {
        packDirectory = temp.resolve("packs").resolve(PACK);
        fixture.put("items/alpha.yml", (r, s) -> "%s:\n  material: STICK\n  texture: %s\n  model: %s\n  template: %s\n  jukebox_song: %s\n"
                .formatted(key(r, s, "alpha"), pick(r, s, "gem"), pick(r, s, "rock"), pick(r, s, "base"), pick(r, s, "s1")));
        fixture.put("items/beta.yml", (r, s) -> "%s:\n  material: STICK\n  template: %s\n"
                .formatted(key(r, s, "beta"), pick(r, s, "alpha")));
        fixture.put("templates/base.yml", (r, s) -> "%s:\n  material: PAPER\n".formatted(key(r, s, "base")));
        fixture.put("templates/base2.yml", (r, s) -> "%s:\n  template: %s\n".formatted(key(r, s, "base2"), pick(r, s, "base")));
        fixture.put("recipes/r1.yml", (r, s) -> "%s:\n  type: shaped\n  result: %s\n  input: %s\n  ingredients:\n    A: %s\n    B: %s\n"
                .formatted(key(r, s, "r1"), pick(r, s, "alpha"), pick(r, s, "beta"), pick(r, s, "demo:alpha"),
                        pick(r, s, "minecraft:stone")));
        fixture.put("recipes/r2.yml", (r, s) -> "%s:\n  type: shapeless\n  result: %s\n  ingredients:\n    - %s\n    - %s\n"
                .formatted(key(r, s, "r2"), pick(r, s, "demo:beta"), pick(r, s, "alpha"), pick(r, s, "beta")));
        fixture.put("blocks/shield_block.yml", (r, s) -> "%s:\n  material: STONE\n  item: %s\n  texture: %s\n  drops:\n    - %s\n    - %s\n"
                .formatted(key(r, s, "shield_block"), pick(r, s, "alpha"), pick(r, s, "gem"), pick(r, s, "beta"),
                        pick(r, s, "demo:shield_block")));
        fixture.put("blocks/twin.yml", (r, s) -> "%s:\n  material: STONE\n".formatted(key(r, s, "twin")));
        fixture.put("furnitures/chair.yml", (r, s) -> "%s:\n  item: %s\n  evolution:\n    - target: %s\n    - target: %s\n"
                .formatted(key(r, s, "chair"), pick(r, s, "alpha"), pick(r, s, "demo:chair"), pick(r, s, "chair")));
        fixture.put("furnitures/twin.yml", (r, s) -> "%s:\n  item: alpha\n".formatted(key(r, s, "twin")));
        fixture.put("glyphs/g1.yml", (r, s) -> "%s:\n  reference: %s\n".formatted(key(r, s, "g1"), pick(r, s, "g2")));
        fixture.put("glyphs/g2.yml", (r, s) -> "%s:\n  frames:\n    - %s\n".formatted(key(r, s, "g2"), pick(r, s, "gem")));
        fixture.put("sounds/s1.yml", (r, s) -> "%s:\n  sounds:\n    - %s\n  jukebox:\n    duration: 10\n"
                .formatted(key(r, s, "s1"), pick(r, s, "rock")));
        fixture.put("paintings/p1.yml", (r, s) -> "%s:\n  texture: %s\n  width: 1\n  height: 1\n"
                .formatted(key(r, s, "p1"), pick(r, s, "painting/p1")));

        for (var entry : fixture.entrySet()) {
            Path file = packDirectory.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue().apply(new Random(0), true));
        }
        Path textures = packDirectory.resolve("assets/textures");
        Files.createDirectories(textures.resolve("painting"));
        Files.write(textures.resolve("gem.png"), new byte[] {1});
        Files.write(textures.resolve("painting/p1.png"), new byte[] {1});

        // another pack whose resources name this pack's in every form, qualified
        List<ContentResource> other = new ArrayList<>();
        other.add(resource("other", "stew", "recipe", "recipes/stew.yml",
                "stew:\n  result: demo:alpha\n  input: demo:beta\n  ingredients:\n    A: demo:alpha\n"));
        other.add(resource("other", "thing", "item", "items/thing.yml",
                "thing:\n  material: STICK\n  template: demo:base2\n  texture: demo:gem\n  jukebox_song: demo:s1\n"));
        other.add(resource("other", "slab", "block", "blocks/slab.yml",
                "slab:\n  item: demo:alpha\n  drops:\n    - demo:beta\n    - demo:shield_block\n"));
        other.add(resource("other", "stool", "furniture", "furnitures/stool.yml",
                "stool:\n  item: demo:alpha\n  evolution:\n    - target: demo:chair\n"));
        other.add(resource("other", "gl", "glyph", "glyphs/gl.yml", "gl:\n  reference: demo:g2\n"));
        other.add(resource("other", "pt", "painting", "paintings/pt.yml", "pt:\n  texture: demo:painting/p1\n"));
        other.add(resource("other", "chain", "item", "items/chain.yml", "chain:\n  material: STICK\n  template: thing\n"));
        others = new ParsedContent(List.of(new ContentPack("other", temp, null, other)), Set.of(), Map.of());
    }

    private static String key(Random random, boolean stored, String canonical) {
        if (stored || random.nextInt(8) != 0) {
            return canonical;
        }
        return random.nextBoolean() ? canonical + "_renamed" : canonical.equals("alpha") ? "beta" : "alpha";
    }

    private static ContentResource resource(String pack, String id, String type, String path, String yaml) {
        return new ContentResource(NamespacedId.of(pack, id), type, Path.of(path), LosslessYaml.parse(yaml));
    }

    private static Set<String> keys(List<OperationDiagnostic> found) {
        Set<String> out = new TreeSet<>();
        for (OperationDiagnostic each : found) {
            var diagnostic = each.diagnostic();
            out.add(diagnostic.resource() + "|" + diagnostic.code().code() + "|" + diagnostic.message() + "|"
                    + diagnostic.target());
        }
        return out;
    }

    @Test
    void theScopedCheckEqualsTheFullBeforeAfterOracleOverRandomLists() throws Exception {
        Random random = new Random(357);
        List<String> paths = new ArrayList<>(fixture.keySet());
        int withImpact = 0;
        int withFindings = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Map<String, StagedDocument> staged = new LinkedHashMap<>();
            int count = 1 + random.nextInt(4);
            for (int i = 0; i < count; i++) {
                boolean fresh = random.nextInt(5) == 0;
                String path = fresh ? "items/new" + random.nextInt(3) + ".yml" : paths.get(random.nextInt(paths.size()));
                String original = fresh ? null : Files.readString(packDirectory.resolve(path));
                String text;
                int mode = random.nextInt(6);
                if (!fresh && mode == 0) {
                    text = null; // deleted
                } else if (fresh) {
                    text = "%s:\n  material: STICK\n  template: %s\n  texture: %s\n".formatted(
                            path.substring(6, path.length() - 4), TARGETS.get(random.nextInt(TARGETS.size())),
                            TARGETS.get(random.nextInt(TARGETS.size())));
                } else {
                    text = fixture.get(path).apply(random, false);
                }
                staged.put(path, new StagedDocument(path, original, text, true, i));
            }
            List<StagedDocument> list = List.copyOf(staged.values());

            var scoped = new StagedReferences(PACK, packDirectory, others, cache).check(list);
            var oracle = new StagedReferences(PACK, packDirectory, others, cache).checkEverything(list);

            assertThat(keys(scoped.diagnostics())).as("round " + round + " " + list).isEqualTo(keys(oracle.diagnostics()));
            if (scoped.impacted() > 0) {
                withImpact++;
            }
            if (!scoped.diagnostics().isEmpty()) {
                withFindings++;
            }
        }
        System.out.println("differential: " + ROUNDS + " rounds, " + withImpact + " with impacted resources, "
                + withFindings + " with findings");
        assertThat(withImpact).as("the test must exercise the impact path").isGreaterThan(ROUNDS / 4);
        assertThat(withFindings).isGreaterThan(ROUNDS / 4);
    }

    @Test
    void thePackResourcesRuleIsTheOneTheParseStageUses() {
        var document = LosslessYaml.parse("a:\n  material: STICK\nnot_a_mapping: 3\nBad Key:\n  x: 1\n");
        var declared = PackResources.declared(PACK, "currencies", Path.of("currencies/x.yml"), document);

        assertThat(declared.resources()).extracting(ContentResource::contentType).containsExactly("currency");
        assertThat(declared.resources()).extracting(r -> r.id().toString()).containsExactly("demo:a");
        assertThat(declared.malformedKeys()).containsExactly("Bad Key");
    }
}
