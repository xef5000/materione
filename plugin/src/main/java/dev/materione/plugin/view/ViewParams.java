package dev.materione.plugin.view;

import dev.materione.api.logic.ParamReader;

/** Reading the parameters the view verbs share, so each verb is only its own step (logic.md §9 rule 2). */
public final class ViewParams {

    private ViewParams() {}

    /**
     * The text of an optional parameter, or null when it is absent or blank (the editor writes an empty shorthand for a
     * field the author has not filled in, and that means "none"). A value that is not text is the reader's error.
     */
    public static String optional(ParamReader params, String key) {
        if (!params.has(key)) {
            return null;
        }
        String text = params.raw(key).orElse("") instanceof String written ? written.trim() : params.text(key);
        return text.isEmpty() ? null : text;
    }
}
