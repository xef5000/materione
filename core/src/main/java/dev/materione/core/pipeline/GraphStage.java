package dev.materione.core.pipeline;

import dev.materione.core.pipeline.validate.ReferenceValidator;

/**
 * GRAPH BUILD (plugin architecture §4): turns validated resources into the
 * typed content graph with lineage. Only resources that survived validation
 * (not excluded, pack manifest valid) become nodes — the graph is the
 * complete validated truth (master §6.1), never a half-applied view.
 */
public final class GraphStage {

    public void run(PipelineContext context) {
        // assets are nodes too: scope expansion walks texture/model nodes back
        // to the items referencing them (§4.1), so edges always land on nodes
        for (dev.materione.api.NamespacedId asset : context.knownAssets()) {
            context.graph().addNode(asset, "asset", null);
        }
        // all nodes exist before any edge is drawn: definitions reference
        // siblings declared later in their own pack (evolution stages,
        // drop tables), so single-pass wiring would record phantom unresolved
        for (ContentPack pack : context.packs()) {
            if (pack.manifest() == null) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                if (context.excluded().contains(resource.id())) {
                    continue;
                }
                context.graph().addNode(resource.id(), resource.contentType(), resource.sourceFile());
            }
        }
        for (ContentPack pack : context.packs()) {
            if (pack.manifest() == null) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                if (context.excluded().contains(resource.id())) {
                    continue;
                }
                Object definition = resource.document().data().get(resource.id().value());
                if (definition instanceof java.util.Map<?, ?> mapping) {
                    wireReferences(context, resource, mapping);
                }
            }
        }
    }

    private static void wireReferences(
            PipelineContext context, ContentResource resource, java.util.Map<?, ?> mapping) {
        for (String field : ReferenceValidator.REFERENCE_FIELDS) {
            Object value = mapping.get(field);
            if (value instanceof String target && !target.isBlank()) {
                addEdge(context, resource, target, ReferenceValidator.edgeTypeFor(field));
            }
        }
        if (mapping.get("drops") instanceof java.util.List<?> drops) {
            for (Object drop : drops) {
                if (drop instanceof String target && !target.isBlank()) {
                    addEdge(context, resource, target, dev.materione.core.pipeline.graph.ContentEdgeType.DROPS_ITEM);
                }
            }
        }
        if ("block".equals(resource.contentType())
                && mapping.get("item") instanceof String placedBy && !placedBy.isBlank()) {
            addEdge(context, resource, placedBy, dev.materione.core.pipeline.graph.ContentEdgeType.PLACED_BY_ITEM);
        }
        if ("furniture".equals(resource.contentType())) {
            if (mapping.get("item") instanceof String placedBy && !placedBy.isBlank()) {
                addEdge(context, resource, placedBy, dev.materione.core.pipeline.graph.ContentEdgeType.PLACED_BY_ITEM);
            }
            if (mapping.get("evolution") instanceof java.util.List<?> stages) {
                for (Object stage : stages) {
                    if (stage instanceof java.util.Map<?, ?> step
                            && step.get("target") instanceof String target
                            && !target.isBlank()) {
                        addEdge(context, resource, target,
                                dev.materione.core.pipeline.graph.ContentEdgeType.FURNITURE_STAGE);
                    }
                }
            }
        }
    }

    /**
     * One reference edge. A reference that is not an id draws no edge: validation reports it (MTR-0009) and
     * quarantines the resource, and a resource of a type validation does not check must not abort the run (#364).
     */
    private static void addEdge(
            PipelineContext context,
            ContentResource resource,
            String target,
            dev.materione.core.pipeline.graph.ContentEdgeType type) {
        var targetId = ReferenceValidator.tryResolve(resource.id().namespace(), target);
        if (targetId != null) {
            context.graph().addEdge(resource.id(), targetId, type);
        }
    }
}
