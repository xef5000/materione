package dev.materione.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.document.LosslessYaml;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The duplicate-id rule (#194) is a pure function of the resources, never of their input order. */
class DuplicateResourcesTest {

    private static final Path PACK = Path.of("/packs/alpha");

    @Test
    void theFirstPathInOrderKeepsTheIdWhateverTheInputOrder() {
        ContentResource a = resource("items/a.yml", "item", "dup", "IRON_SWORD");
        ContentResource b = resource("items/b.yml", "item", "dup", "DIAMOND_SWORD");
        ContentResource z = resource("items/z/c.yml", "item", "dup", "STICK");

        for (List<ContentResource> order : permutations(List.of(a, b, z))) {
            DuplicateResources.Resolution resolution = DuplicateResources.resolve(PACK, order);
            assertThat(resolution.kept()).containsExactly(a);
            assertThat(resolution.diagnostics()).hasSize(2);
        }
    }

    @Test
    void everyLoserIsReportedAgainstTheWinnerAndNamesBothFiles() {
        ContentResource a = resource("items/a.yml", "item", "dup", "IRON_SWORD");
        ContentResource b = resource("items/b.yml", "item", "dup", "DIAMOND_SWORD");

        Diagnostic only = DuplicateResources.resolve(PACK, List.of(b, a)).diagnostics().get(0);

        assertThat(only.code()).isEqualTo(DiagnosticCodes.DUPLICATE_RESOURCE_ID);
        assertThat(only.resource()).isEqualTo(NamespacedId.parse("alpha:dup"));
        assertThat(only.message()).isEqualTo("resource alpha:dup (item) is declared in both items/a.yml and"
                + " items/b.yml; items/a.yml is used and items/b.yml's declaration is ignored");
        assertThat(only.location().file().replace('\\', '/')).endsWith("/items/b.yml");
    }

    @Test
    void theSameIdInTwoContentTypesIsNotADuplicate() {
        ContentResource item = resource("items/a.yml", "item", "gem", "EMERALD");
        ContentResource block = resource("blocks/a.yml", "block", "gem", "EMERALD_BLOCK");

        DuplicateResources.Resolution resolution = DuplicateResources.resolve(PACK, List.of(block, item));

        assertThat(resolution.kept()).containsExactlyInAnyOrder(item, block);
        assertThat(resolution.diagnostics()).isEmpty();
    }

    @Test
    void keptResourcesKeepTheirOriginalOrder() {
        ContentResource first = resource("items/a.yml", "item", "one", "STICK");
        ContentResource keeper = resource("items/c.yml", "item", "dup", "STICK");
        ContentResource other = resource("items/b.yml", "item", "two", "STICK");
        ContentResource loser = resource("items/d.yml", "item", "dup", "STICK");

        // items/c.yml precedes items/d.yml in path order, so it keeps "dup"; the kept list
        // still reads in the order the resources were given (a, c, b), not path order
        DuplicateResources.Resolution resolution =
                DuplicateResources.resolve(PACK, List.of(first, keeper, other, loser));

        assertThat(resolution.kept()).containsExactly(first, keeper, other);
    }

    /** All orders of a short list, so the test covers every discovery permutation. */
    private static List<List<ContentResource>> permutations(List<ContentResource> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        List<List<ContentResource>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<ContentResource> rest = new ArrayList<>(items);
            ContentResource head = rest.remove(i);
            for (List<ContentResource> tail : permutations(rest)) {
                List<ContentResource> order = new ArrayList<>();
                order.add(head);
                order.addAll(tail);
                out.add(Collections.unmodifiableList(order));
            }
        }
        return out;
    }

    private static ContentResource resource(String file, String type, String id, String material) {
        Path source = PACK.resolve(file);
        var document = LosslessYaml.parse(id + ":\n  material: " + material + "\n");
        return new ContentResource(NamespacedId.of("alpha", id), type, source, document);
    }
}
