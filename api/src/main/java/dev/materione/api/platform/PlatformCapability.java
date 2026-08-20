package dev.materione.api.platform;

/**
 * Version-gated platform features (plugin architecture §6). The platform
 * module resolves which of these the running server supports; content and
 * pack code consume capabilities, never versions.
 */
public enum PlatformCapability {
    /** Display/block/text display entities (1.19.4+). */
    DISPLAY_ENTITIES,
    /** Vanilla data components (1.20.5+). */
    DATA_COMPONENTS,
    /** {@code equippable.asset_id} equipment path (1.21.2+). */
    EQUIPPABLE_ASSET,
    /** 1.21.4+ item model definitions (the D10 floor feature). */
    ITEM_MODELS,
    /** Server-side dialogs (1.21+). */
    DIALOGS,
    /** Folia region threading (server flavor, not a version). */
    FOLIA
}
