package dev.materione.plugin.view;

/**
 * Why a view reads a variable (views.md section 5.2): its content (layers, templates, lists) or, for a
 * {@code predicate} kind, whether it is visible to a player at all. A write to a visibility read must reach players
 * the definition is not attached to yet.
 */
public enum ReadRole {
    CONTENT,
    VISIBILITY
}
