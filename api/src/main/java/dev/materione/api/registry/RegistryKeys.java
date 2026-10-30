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

    /** {@link dev.materione.api.logic.ActionType} implementations (D4 — one action vocabulary everywhere). */
    public static final RegistryKey ACTIONS = new RegistryKey("actions");

    /** {@link dev.materione.api.logic.ConditionType} implementations. */
    public static final RegistryKey CONDITIONS = new RegistryKey("conditions");

    /** {@link dev.materione.api.logic.TriggerType} descriptions: the events a rule can answer (logic.md §4.3). */
    public static final RegistryKey TRIGGERS = new RegistryKey("triggers");

    /** {@link dev.materione.api.logic.ExpressionFunction} implementations callable from expressions (logic.md §4.2). */
    public static final RegistryKey EXPRESSION_FUNCTIONS = new RegistryKey("expression-functions");

    /** {@link dev.materione.api.logic.provider.CurrencyIntegration} entries: how a currency reaches its money (logic.md §4.5). */
    public static final RegistryKey CURRENCY_INTEGRATIONS = new RegistryKey("currency-integrations");

    /** Delivery hosting backends (master §8.4). */
    public static final RegistryKey DELIVERY_BACKENDS = new RegistryKey("delivery-backends");

    /** {@link dev.materione.api.gui.GuiComponent} implementations: the layer kinds of a GUI (gui.md §3). */
    public static final RegistryKey GUI_COMPONENTS = new RegistryKey("gui-components");

    private RegistryKeys() {}
}
