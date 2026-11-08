package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PackManifest;
import dev.materione.core.runtime.RuntimeContentIndex;
import dev.materione.core.runtime.RuntimeContentIndex.Type;
import dev.materione.core.runtime.RuntimeItem;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The content tree the pipeline publishes with each shadow (runtime-systems §8.3): which resources are rows, and which
 * folders they sit in. Pure: packs are built in memory and "live" is a map.
 */
class ContentIndexBuildTest {

    private static final Path ROOT = Path.of("packs").toAbsolutePath();

    private static ContentResource resource(String pack, String type, String directory, String id, String... folders) {
        Path file = ROOT.resolve(pack).resolve(directory);
        for (String folder : folders) {
            file = file.resolve(folder);
        }
        return new ContentResource(NamespacedId.of(pack, id), type, file.resolve(id + ".yml"), null);
    }

    private static ContentPack pack(String id, boolean valid, ContentResource... resources) {
        var manifest = valid ? new PackManifest(id, "1", 1, List.of(), null) : null;
        return new ContentPack(id, ROOT.resolve(id), manifest, List.of(resources));
    }

    private static RuntimeItem live(String id, String name, boolean synthetic) {
        return new RuntimeItem(NamespacedId.parse(id), "PAPER", null, name, List.of(), "hash", null, synthetic, null);
    }

    @Test
    void rowsAreTheLiveItemsAndTheBlocksWhoseItemWasGeneratedEachInTheFoldersOfItsFile() {
        Map<NamespacedId, RuntimeItem> live = new HashMap<>();
        for (RuntimeItem item : List.of(
                live("shop:ruby", "<red>Ruby", false),
                live("shop:coin", null, false),
                live("shop:lamp", "Lamp", true),          // a block whose placement item the runtime made
                live("shop:statue", "Statue", true))) {   // a furniture likewise
            live.put(item.id(), item);
        }
        var packs = List.of(
                pack("shop", true,
                        resource("shop", "item", "items", "ruby", "gems"),
                        resource("shop", "item", "items", "coin"),
                        resource("shop", "block", "blocks", "lamp", "gems", "lights"),
                        resource("shop", "furniture", "furnitures", "statue"),
                        // a block placed by an item that is its own row: the item is listed, the block is not
                        resource("shop", "block", "blocks", "ruby", "gems"),
                        // not live (quarantined), not browsable content, and a resource of another kind
                        resource("shop", "item", "items", "broken", "gems"),
                        resource("shop", "gui", "guis", "panel")),
                pack("bad", false, resource("bad", "item", "items", "ghost")));

        RuntimeContentIndex index = PipelineService.contentIndex(packs, id -> Optional.ofNullable(live.get(id)));

        assertThat(index.entries()).extracting(entry -> entry.id().toString())
                .containsExactly("shop:coin", "shop:lamp", "shop:ruby", "shop:statue");
        var byId = new HashMap<String, RuntimeContentIndex.Entry>();
        index.entries().forEach(entry -> byId.put(entry.id().toString(), entry));
        assertThat(byId.get("shop:ruby").folders()).containsExactly("gems");
        assertThat(byId.get("shop:ruby").type()).isEqualTo(Type.ITEM);
        assertThat(byId.get("shop:ruby").name()).isEqualTo("Ruby");
        assertThat(byId.get("shop:ruby").label()).isEqualTo("<red>Ruby");
        assertThat(byId.get("shop:coin").folders()).isEmpty();
        assertThat(byId.get("shop:coin").name()).isEqualTo("Coin");
        assertThat(byId.get("shop:lamp").folders()).containsExactly("gems", "lights");
        assertThat(byId.get("shop:lamp").type()).isEqualTo(Type.BLOCK);
        assertThat(byId.get("shop:statue").type()).isEqualTo(Type.FURNITURE);
        // the tree: items and blocks share the folder tree of their files
        assertThat(index.children("shop/gems")).extracting(RuntimeContentIndex.Node::name)
                .containsExactly("lights", "Ruby");
    }

    @Test
    void aPackWithNothingLiveHasNoRowsAndNoPackNode() {
        var packs = List.of(pack("shop", true, resource("shop", "gui", "guis", "panel")));

        RuntimeContentIndex index = PipelineService.contentIndex(packs, id -> Optional.empty());

        assertThat(index.entries()).isEmpty();
        assertThat(index.children("")).isEmpty();
    }
}
