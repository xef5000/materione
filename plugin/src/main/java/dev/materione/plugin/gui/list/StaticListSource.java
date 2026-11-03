package dev.materione.plugin.gui.list;

import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.ListSource;
import dev.materione.api.logic.ListSourceDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code static}: entries written in the layer, {@code source: {entries: [{name: Ada}, {name: Grace}]}} (logic.md
 * §6.7). Each entry is a mapping of text, number and boolean fields, and the entry schema is the author's own, so
 * it declares the {@code entry.*} family.
 */
public final class StaticListSource implements ListSource<StaticListSource.Params> {

    /** The parsed entries, each an immutable mapping of its fields. */
    public record Params(List<Map<String, Object>> entries) {

        public Params {
            entries = List.copyOf(entries);
        }
    }

    @Override
    public String id() {
        return "static";
    }

    @Override
    public ListSourceDescription describe() {
        return ListSourceDescription.of(
                new SelfDescription("Static entries", "Entries written in the layer, each a mapping of fields",
                        List.of(new ParameterSchema("entries", ParameterType.RAW, true, null,
                                "The entries: a list of mappings", null, null)),
                        "1.0.0"),
                List.of(ContextVariable.of("entry.*", ParameterType.RAW, "A field of the entry, as written")));
    }

    @Override
    public Params parse(ParamReader params) {
        if (!(params.raw("entries").orElse(null) instanceof List<?> written)) {
            throw new IllegalArgumentException("entries is a list of mappings");
        }
        var entries = new ArrayList<Map<String, Object>>(written.size());
        for (Object item : written) {
            if (!(item instanceof Map<?, ?> mapping)) {
                throw new IllegalArgumentException("entries holds mappings of fields");
            }
            var entry = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> field : mapping.entrySet()) {
                if (!(field.getKey() instanceof String name)) {
                    throw new IllegalArgumentException("entry field names are text");
                }
                Object value = field.getValue();
                if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                    throw new IllegalArgumentException("entry fields are text, numbers or booleans");
                }
                entry.put(name, value);
            }
            entries.add(entry);
        }
        return new Params(entries);
    }

    @Override
    public List<Map<String, Object>> entries(Params parameters, LogicContext context) {
        return parameters.entries();
    }
}
