package dev.materione.api.registry;

/**
 * The standard registry keys (api.md §3.2): every extensibility surface —
 * content types, mechanics, ECA actions and conditions, delivery backends —
 * is a registry entry behind one of these keys. Built-ins register through
 * the same keys addons use (D5); nobody constructs ad-hoc spellings.
 */
public final class RegistryKeys {

    /** {@link dev.materione.api.content.ContentType} implementations. */
    public static final RegistryKey CONTENT_TYPES = new RegistryKey("content-types");

    /** {@link dev.materione.api.mechanics.Mechanic} implementations (canned ECA bundles). */
    public static final RegistryKey MECHANICS = new RegistryKey("mechanics");

    /** ECA action executors (D4 — one action vocabulary everywhere). */
    public static final RegistryKey ACTIONS = new RegistryKey("actions");

    /** ECA condition types. */
    public static final RegistryKey CONDITIONS = new RegistryKey("conditions");

    /** Delivery hosting backends (master §8.4). */
    public static final RegistryKey DELIVERY_BACKENDS = new RegistryKey("delivery-backends");

    private RegistryKeys() {}
}
