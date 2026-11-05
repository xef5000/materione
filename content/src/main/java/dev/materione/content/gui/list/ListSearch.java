package dev.materione.content.gui.list;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A list's {@code search:} (logic.md §6.7, §28.21): which entry fields a search text is matched against, and the
 * session variable the text is read from. A prompt writes the text into that variable ({@code var: session.search}),
 * and the list keeps the entries whose listed field contains it, ignoring case. An empty or unset text keeps every
 * entry, so the list is unfiltered until a search is made.
 *
 * <pre>{@code
 * search: { fields: [name] }                 # reads gui.session.search
 * search: { var: query, fields: [name, lore] }
 * }</pre>
 *
 * @param var the session variable the search text is read from (a plain name)
 * @param fields the entry fields matched, in order; never empty
 */
public record ListSearch(String var, List<String> fields) {

    /** The session variable a search reads when the author names none. */
    public static final String DEFAULT_VAR = "search";

    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public ListSearch {
        if (var == null || !PLAIN_NAME.matcher(var).matches()) {
            throw new IllegalArgumentException("search var is a plain name (letters, digits, underscores)");
        }
        fields = List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("search names at least one field to match");
        }
    }

    /**
     * Parses the authored mapping.
     *
     * @throws IllegalArgumentException with the reason, for a value the list cannot search with
     */
    public static ListSearch parse(Object raw) {
        if (!(raw instanceof Map<?, ?> mapping)) {
            throw new IllegalArgumentException("search is a mapping: { fields: [name] }, with an optional var:");
        }
        for (Object key : mapping.keySet()) {
            if (!("var".equals(key) || "fields".equals(key))) {
                throw new IllegalArgumentException("search takes var and fields, not '" + key + "'");
            }
        }
        Object fieldsRaw = mapping.get("fields");
        var fields = new ArrayList<String>();
        if (fieldsRaw instanceof String single) {
            fields.add(single);
        } else if (fieldsRaw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof String name) || name.isBlank()) {
                    throw new IllegalArgumentException("search fields are entry field names, as text");
                }
                fields.add(name.trim());
            }
        } else {
            throw new IllegalArgumentException("search fields is a list of entry field names, such as [name]");
        }
        Object varRaw = mapping.get("var");
        String var = varRaw == null ? DEFAULT_VAR : String.valueOf(varRaw).trim();
        try {
            return new ListSearch(var, fields);
        } catch (IllegalArgumentException rejected) {
            throw new IllegalArgumentException(rejected.getMessage());
        }
    }
}
