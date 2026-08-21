package dev.materione.core.content;

import java.nio.file.Path;
import java.util.Map;

/**
 * The compiler-facing output surface handed to content types (plugin
 * architecture §4, compile stage). JSON-shaped payloads ({@code Map/List/
 * String/Number/Boolean/null}) keep the contract free of any serializer —
 * the pack module owns how bytes are produced (creative, M1).
 */
public interface PackEmitter {

    /** Emits {@code assets/<ns>/items/<id>.json} (1.21.4+ item definition). */
    void itemDefinition(dev.materione.api.NamespacedId id, Map<String, Object> itemModelJson);

    /** Emits {@code assets/<ns>/models/<id>.json}. */
    void model(dev.materione.api.NamespacedId id, Map<String, Object> modelJson);

    /** Copies a pack asset file verbatim ({@code assets/<kind>/<path>}). */
    void copyAsset(Path sourceFile, String destination);

    /** Sets pack.mcmeta description text (pack format is the platform's). */
    void description(String text);
}
