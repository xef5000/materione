package dev.materione.core.pipeline;

import dev.materione.api.NamespacedId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the last pipeline run parsed, as a validator sees it: the packs, the asset index and the painting-texture
 * sizes. {@link PipelineRunner#lastParsed()} keeps it current across targeted runs (a pack the run rode over keeps
 * its assets), which is what lets a transaction's dry run resolve references into packs it does not write.
 */
public record ParsedContent(
        List<ContentPack> packs, Set<NamespacedId> knownAssets, Map<NamespacedId, int[]> paintingSizes) {

    public static final ParsedContent EMPTY = new ParsedContent(List.of(), Set.of(), Map.of());

    public ParsedContent {
        packs = List.copyOf(packs);
        knownAssets = Set.copyOf(knownAssets);
        paintingSizes = Map.copyOf(paintingSizes);
    }
}
