package dev.materione.core.authoring;

import dev.materione.api.authoring.AuthoringApi.ReferenceCoverage;
import dev.materione.api.authoring.AuthoringApi.ReferenceHit;
import dev.materione.api.authoring.AuthoringApi.ReferenceKind;
import dev.materione.api.authoring.AuthoringApi.ReferenceQuery;
import dev.materione.api.authoring.AuthoringApi.ReferenceResult;
import dev.materione.api.authoring.AuthoringApi.ResourceRef;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.LosslessYaml;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Where is this used?" by exact authored text. A document names an asset or
 * resource with a scalar (a texture {@code corpus/black}, a template id, a
 * qualified {@code pack:id}); this walks every resource root of every pack and
 * reports scalars that equal one of the target's accepted spellings. It does
 * not understand fields, so a coincidental identical string is a hit too —
 * hits carry the field path so the author can judge — and it never reads
 * asset files (a model's texture list is invisible here). The result says
 * both things in its coverage block rather than implying a full graph.
 */
final class ReferenceScanner {

    static final int MAX_HITS = 200;
    static final long READ_BUDGET_BYTES = 32L * 1024 * 1024;
    private static final int MAX_DEPTH = 24;

    private static final List<String> NOT_SCANNED = List.of(
            "Asset files are not read: textures named inside a model or sound definition are not found.",
            "Matching is by authored text: a value built by a template is counted where the template writes it.",
            "Documents that do not parse are skipped.");

    private final DocumentStore documents;

    ReferenceScanner(DocumentStore documents) {
        this.documents = documents;
    }

    /** Spellings the target is referenced by: bare (valid inside its pack) and qualified (anywhere). */
    record Spellings(Set<String> bare, Set<String> qualified) {}

    static Spellings spellings(ReferenceQuery query) {
        Set<String> bare = new LinkedHashSet<>();
        Set<String> qualified = new LinkedHashSet<>();
        String target = query.target();
        if (query.kind() == ReferenceKind.ASSET) {
            // assets/<category>/<logical path>[.ext] — references drop the category prefix,
            // and usually the extension.
            String rest = target.startsWith("assets/") ? target.substring("assets/".length()) : target;
            int slash = rest.indexOf('/');
            String logical = slash < 0 ? rest : rest.substring(slash + 1);
            String stripped = logical;
            int dot = logical.lastIndexOf('.');
            if (dot > logical.lastIndexOf('/')) {
                stripped = logical.substring(0, dot);
            }
            for (String form : List.of(logical, stripped, target, rest)) {
                bare.add(form);
                qualified.add(query.pack() + ":" + form);
            }
        } else {
            bare.add(target);
            qualified.add(query.pack() + ":" + target);
        }
        return new Spellings(bare, qualified);
    }

    ReferenceResult scan(ReferenceQuery query) {
        Spellings spellings = spellings(query);
        List<ReferenceHit> hits = new ArrayList<>();
        int[] scanned = {0};
        int[] unreadable = {0};
        boolean[] truncated = {false};
        long[] used = {0};
        DocumentStore.SummaryReadBudget budget = new DocumentStore.SummaryReadBudget() {
            @Override
            public long remainingBytes() {
                return READ_BUDGET_BYTES - used[0];
            }

            @Override
            public void consume(long bytes) {
                if (bytes < 0 || bytes > remainingBytes()) {
                    throw new BudgetExceeded();
                }
                used[0] += bytes;
            }

            @Override
            public void requireRemainingCapacity() {
                if (remainingBytes() <= 0) {
                    throw new BudgetExceeded();
                }
            }
        };
        Set<String> seen = new LinkedHashSet<>();
        try {
            documents.forEachResourceDocument(null, null, row -> {
                if (truncated[0]) {
                    return;
                }
                ResourceRef ref = new ResourceRef(
                        row.resource().pack(), row.resource().type(),
                        row.resource().path(), row.resource().resourceId());
                String key = ref.pack() + "/" + ref.path();
                boolean first = seen.add(key);
                if (first) {
                    scanned[0]++;
                }
                if (row.document() == null || row.document().parseError().isPresent()) {
                    if (first) {
                        unreadable[0]++;
                    }
                    return;
                }
                Object root = row.document().data().get(ref.resourceId());
                if (root == null) {
                    return;
                }
                boolean samePack = ref.pack().equals(query.pack());
                collect(LosslessYaml.stringKeyedDeep(root), new ArrayList<>(), 0, spellings, samePack,
                        (field, value) -> {
                            if (hits.size() >= MAX_HITS) {
                                truncated[0] = true;
                                return;
                            }
                            hits.add(new ReferenceHit(ref, List.copyOf(field), value));
                        });
            }, budget);
        } catch (BudgetExceeded e) {
            truncated[0] = true;
        }
        boolean complete = !truncated[0] && unreadable[0] == 0;
        return new ReferenceResult(hits, new ReferenceCoverage(
                scanned[0], unreadable[0], truncated[0], complete, NOT_SCANNED));
    }

    private interface HitSink {
        void hit(List<String> field, String value);
    }

    private static void collect(
            Object node, List<String> path, int depth, Spellings spellings, boolean samePack, HitSink sink) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                path.add(String.valueOf(entry.getKey()));
                collect(entry.getValue(), path, depth + 1, spellings, samePack, sink);
                path.remove(path.size() - 1);
            }
        } else if (node instanceof Iterable<?> list) {
            int index = 0;
            for (Object child : list) {
                path.add(String.valueOf(index++));
                collect(child, path, depth + 1, spellings, samePack, sink);
                path.remove(path.size() - 1);
            }
        } else if (node instanceof String text) {
            String value = text.trim();
            if (spellings.qualified().contains(value)
                    || (samePack && spellings.bare().contains(value))) {
                sink.hit(path, text);
            }
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super("reference scan budget exhausted", null, false, false);
        }
    }
}
