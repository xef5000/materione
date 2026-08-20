package dev.materione.api.registry;

/** Parameter value kinds usable in a {@link ParameterSchema}. */
public enum ParameterType {
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    ENUM,
    /** Reference to another content resource (typed by the field's context). */
    REFERENCE,
    /** Nested list of parameters (composite schemas). */
    LIST,
    /** Escape hatch: raw text validated by the entry, not the schema. */
    RAW
}
