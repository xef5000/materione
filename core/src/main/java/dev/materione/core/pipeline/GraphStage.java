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
        for (ContentPack pack : context.packs()) {
            if (pack.manifest() == null) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                if (context.excluded().contains(resource.id())) {
                    continue;
                }
                context.graph().addNode(resource.id(), resource.contentType(), resource.sourceFile());
                Object definition = resource.document().data().get(resource.id().value());
                if (definition instanceof java.util.Map<?, ?> mapping) {
                    for (String field : ReferenceValidator.REFERENCE_FIELDS) {
                        Object value = mapping.get(field);
                        if (value instanceof String target && !target.isBlank()) {
                            var targetId = ReferenceValidator.resolve(resource.id().namespace(), target);
                            context.graph().addEdge(
                                    resource.id(), targetId, ReferenceValidator.edgeTypeFor(field));
                        }
                    }
                    if (mapping.get("drops") instanceof java.util.List<?> drops) {
                        for (Object drop : drops) {
                            if (drop instanceof String target && !target.isBlank()) {
                                context.graph().addEdge(
                                        resource.id(),
                                        ReferenceValidator.resolve(resource.id().namespace(), target),
                                        dev.materione.core.pipeline.graph.ContentEdgeType.DROPS_ITEM);
                            }
                        }
                    }
                    if ("block".equals(resource.contentType())
                            && mapping.get("item") instanceof String placedBy && !placedBy.isBlank()) {
                        context.graph().addEdge(
                                resource.id(),
                                ReferenceValidator.resolve(resource.id().namespace(), placedBy),
                                dev.materione.core.pipeline.graph.ContentEdgeType.PLACED_BY_ITEM);
                    }
                    if ("furniture".equals(resource.contentType())) {
                        if (mapping.get("item") instanceof String placedBy && !placedBy.isBlank()) {
                            context.graph().addEdge(
                                    resource.id(),
                                    ReferenceValidator.resolve(resource.id().namespace(), placedBy),
                                    dev.materione.core.pipeline.graph.ContentEdgeType.PLACED_BY_ITEM);
                        }
                        if (mapping.get("evolution") instanceof java.util.List<?> stages) {
                            for (Object stage : stages) {
                                if (stage instanceof java.util.Map<?, ?> step
                                        && step.get("target") instanceof String target
                                        && !target.isBlank()) {
                                    context.graph().addEdge(
                                            resource.id(),
                                            ReferenceValidator.resolve(resource.id().namespace(), target),
                                            dev.materione.core.pipeline.graph.ContentEdgeType.FURNITURE_STAGE);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
