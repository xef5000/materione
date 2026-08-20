package dev.materione.core.pipeline.graph;

/**
 * The typed edges of the content graph (plugin architecture §4, graph
 * build). Edge types drive scope expansion: a texture change invalidates
 * the items referencing it (§4.1).
 */
public enum ContentEdgeType {
    /** item → its item model (generated or hand-authored). */
    ITEM_MODEL,
    /** definition → the template it inherits from. */
    TEMPLATE,
    /** model/definition → texture asset. */
    TEXTURE,
    /** recipe → ingredient item. */
    RECIPE_INGREDIENT,
    /** recipe → result item. */
    RECIPE_RESULT,
    /** pack → pack dependency (manifest {@code depends}). */
    PACK_DEPENDENCY
}
