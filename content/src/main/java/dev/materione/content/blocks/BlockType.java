package dev.materione.content.blocks;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.blocks.BlockStateSpaces;
import dev.materione.core.content.Allocations;
import dev.materione.core.content.CompileRejectException;
import dev.materione.core.content.ContentType;
import dev.materione.core.content.PackEmitter;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.content.Templates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The block content type (M2, runtime-systems §4): a custom block is a
 * hijacked host blockstate (note block / tripwire / chorus plant / technical
 * host) rendered through a generated or hand-authored model. The compiler
 * claims a durable state slot per definition through the allocation table
 * and emits one {@code blockstates/<host>.json} variant for it — pack and
 * runtime can never disagree about which state is which block. Exhausting a
 * technique's space rejects the definition with MTR-0019 (fail-soft).
 */
public final class BlockType implements ContentType {

    /** Allocation-kind prefix; the value space is per host block. */
    public static final String ALLOCATION_KIND_PREFIX = "block:";

    @Override
    public String name() {
        return "block";
    }

    @Override
    public String directory() {
        return "blocks";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Block",
                "A custom block: host technique, appearance, drops and world policy",
                List.of(
                        new ParameterSchema("display_name", ParameterType.STRING, false, null,
                                "Display name of the block's item form", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Display name", "identity", 0, false, "text", "",
                                        "Ruby ore")),
                        new ParameterSchema("technique", ParameterType.ENUM, false, "note_block",
                                "How the block is hosted in the world. note_block: the best "
                                        + "default, a full solid block. string: plants and thin "
                                        + "decorations. chorus: directional plants. stairs and "
                                        + "slab: partial shapes. technical: you pick the host "
                                        + "block yourself.",
                                List.of("note_block", "string", "chorus", "technical",
                                        "stairs", "slab"), null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Technique", "identity", 10, false, "select", "",
                                        "note_block")),
                        new ParameterSchema("host", ParameterType.STRING, false, null,
                                "Vanilla host block id (required for technical)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Host block", "identity", 20, true, "text", "",
                                        "minecraft:tripwire")),
                        new ParameterSchema("template", ParameterType.REFERENCE, false, null,
                                "Template this block inherits from", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Template", "identity", 30, true, "reference",
                                        "template", "")),
                        new ParameterSchema("texture", ParameterType.REFERENCE, false, null,
                                "Texture for the generated model (auto-model path)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Texture", "appearance", 10, false, "texture", "texture",
                                        "block/ruby_ore")),
                        new ParameterSchema("model", ParameterType.REFERENCE, false, null,
                                "Hand-authored model reference (assets/<ns>/models/<id>.json)",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "3D model", "appearance", 20, false, "model", "model", "")),
                        new ParameterSchema("model_parent", ParameterType.STRING, false,
                                "block/cube_all",
                                "Parent for the generated model", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Model style", "appearance", 30, true, "text", "",
                                        "block/cube_all")),
                        new ParameterSchema("drops", ParameterType.LIST, false, null,
                                "Item ids dropped when broken (default: nothing custom)",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Drops", "gameplay", 10, false, "list", "item",
                                        "myserver:ruby")),
                        new ParameterSchema("blast_resistant", ParameterType.BOOLEAN, false,
                                "false",
                                "Whether explosions leave the block intact", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Survives explosions", "gameplay", 20, false, "toggle",
                                        "", "")),
                        new ParameterSchema("move_policy", ParameterType.ENUM, false, "keep",
                                "What pistons do to this block",
                                List.of("keep", "break"), null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "When a piston pushes it", "gameplay", 30, false,
                                        "select", "", "keep")),
                        new ParameterSchema("item", ParameterType.REFERENCE, false, null,
                                "Optional custom placement item; auto-generated from this block "
                                        + "when omitted",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Placement item", "gameplay", 40, true, "reference",
                                        "item", "")),
                        new ParameterSchema("behavior", ParameterType.LIST, false, null,
                                "ECA rules fired on place/break/interact (runtime-systems 2)",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Behaviour rules", "behavior", 10, false,
                                        "structured-list", "", ""))),
                "1.0.0");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty(); // unknown or diamond template — quarantine upstream
        }
        BlockStateSpaces.Technique technique = technique(Templates.text(merged, "technique"));
        if (technique == null) {
            return Optional.empty();
        }
        String host = Templates.text(merged, "host");
        if (technique == BlockStateSpaces.Technique.TECHNICAL && host == null) {
            return Optional.empty(); // technical blocks must name their host
        }
        String texture = Templates.text(merged, "texture");
        String model = Templates.text(merged, "model");
        if (texture == null && model == null) {
            return Optional.empty(); // nothing to render
        }
        String movePolicy = orDefault(Templates.text(merged, "move_policy"), "keep");
        if (!"keep".equals(movePolicy) && !"break".equals(movePolicy)) {
            return Optional.empty();
        }
        String itemRef = Templates.text(merged, "item");
        NamespacedId placedBy = itemRef == null
                ? null
                : itemRef.contains(":")
                        ? NamespacedId.parse(itemRef)
                        : NamespacedId.of(id.namespace(), itemRef);
        return Optional.of(new BlockDefinition(
                id,
                technique,
                host,
                model,
                texture,
                orDefault(Templates.text(merged, "model_parent"), "block/cube_all"),
                Templates.text(merged, "display_name"),
                refs(id.namespace(), merged.get("drops")),
                Boolean.TRUE.equals(merged.get("blast_resistant")),
                movePolicy,
                placedBy,
                behaviorMaps(merged.get("behavior")),
                merged));
    }

    /** The raw {@code behavior:} rule list; non-list shapes yield empty (fail-soft). */
    private static List<Map<String, Object>> behaviorMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<Map<String, Object>>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> mapping) {
                // stringKeyed: bare `on:` loads as a YAML-1.1 boolean key
                out.add(dev.materione.core.document.LosslessYaml.stringKeyed(mapping));
            }
        }
        return out;
    }

    @Override
    public void compile(
            Object definition, NamespacedId allocatedModelId, PackEmitter emitter, Allocations allocations) {
        if (!(definition instanceof BlockDefinition block)) {
            return;
        }
        if (block.technique() == BlockStateSpaces.Technique.STAIRS) {
            compileStairs(block, allocatedModelId, emitter);
            emitPlacementItem(block, emitter);
            return;
        }
        if (block.technique() == BlockStateSpaces.Technique.SLAB) {
            compileSlab(block, allocatedModelId, emitter);
            emitPlacementItem(block, emitter);
            return;
        }
        String modelRef;
        if (block.wantsGeneratedModel()) {
            var model = new LinkedHashMap<String, Object>();
            model.put("parent", block.modelParent());
            model.put("textures", texturesFor(block.modelParent(), textureRef(block)));
            emitter.model(allocatedModelId, model);
            modelRef = allocatedModelId.toString();
        } else {
            modelRef = block.modelRef();
        }
        emitPlacementItem(block, emitter);
        if (!BlockStateSpaces.allocatesState(block.technique())) {
            return; // technical: PDC-identified at runtime, no state slot to claim
        }
        String host = BlockStateSpaces.hostOf(block.technique(), block.host());
        int capacity = BlockStateSpaces.capacity(block.technique());
        int slot = Integer.parseInt(allocations.allocate(block.id(), ALLOCATION_KIND_PREFIX + host));
        if (slot >= capacity) {
            throw new CompileRejectException(
                    DiagnosticCodes.BLOCK_STATE_EXHAUSTED,
                    block.id() + ": the " + host + " state space is exhausted (" + capacity + " slots)",
                    "remove unused " + block.technique().name().toLowerCase(java.util.Locale.ROOT)
                            + " blocks, or move this definition to another technique");
        }
        var entry = new LinkedHashMap<String, Object>();
        entry.put("model", modelRef);
        emitter.vanillaFile(
                "blockstates/" + host + ".json",
                Map.of("variants", Map.of(BlockStateSpaces.variantKey(block.technique(), slot), entry)));
        if (block.technique() == BlockStateSpaces.Technique.STRING) {
            // reserve the all-false wire state: stray vanilla tripwire renders nothing
            emitter.vanillaFile(
                    "blockstates/tripwire.json",
                    Map.of("variants", Map.of(
                            BlockStateSpaces.tripwireReservedKey(),
                            Map.of("model", "minecraft:block/barrier"))));
        }
    }

    // --- helpers -------------------------------------------------------------

    /**
     * The auto placement item (runtime-systems §13): a block with no
     * {@code item:} link is grabbable through a synthesized item definition
     * at the block's own id, referencing the block model. Dropped at merge
     * time when a real item resource owns the id — the implicit same-id
     * link rides that item's own definition instead.
     */
    private static void emitPlacementItem(BlockDefinition block, PackEmitter emitter) {
        if (block.placedBy() != null) {
            return; // explicit link: the linked item owns its own definition
        }
        var reference = new LinkedHashMap<String, Object>();
        reference.put("type", "minecraft:model");
        reference.put("model", block.modelRef());
        var root = new LinkedHashMap<String, Object>();
        root.put("model", reference);
        emitter.syntheticItemDefinition(block.id(), root);
    }

    /**
     * Shaped stairs (M2 D7): identity rides chunk PDC — the host state keeps
     * facing/half/shape functional, so the pack claims <em>every</em> host
     * state with our model (corner shapes render as straight models in v1),
     * rotated exactly like vanilla's own stair blockstates.
     */
    private void compileStairs(
            BlockDefinition block, NamespacedId allocatedModelId, PackEmitter emitter) {
        String modelRef = shapedModel(block, allocatedModelId, emitter, "block/stairs");
        var variants = new LinkedHashMap<String, Object>();
        for (String facing : List.of("east", "south", "west", "north")) {
            int y = switch (facing) {
                case "south" -> 90;
                case "west" -> 180;
                case "north" -> 270;
                default -> 0;
            };
            for (String half : List.of("bottom", "top")) {
                for (String shape : List.of("straight", "inner_left", "inner_right",
                        "outer_left", "outer_right")) {
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("model", modelRef);
                    if (half.equals("top")) {
                        entry.put("x", 180);
                    }
                    if (y != 0) {
                        entry.put("y", y);
                    }
                    variants.put("facing=" + facing + ",half=" + half + ",shape=" + shape, entry);
                }
            }
        }
        emitter.vanillaFile("blockstates/waxed_cut_copper_stairs.json", Map.of("variants", variants));
    }

    /**
     * Shaped slabs: three host states claim three models — bottom, top and a
     * full double cube — mirroring vanilla's own slab blockstate split.
     */
    private void compileSlab(
            BlockDefinition block, NamespacedId allocatedModelId, PackEmitter emitter) {
        if (block.wantsGeneratedModel()) {
            String texture = textureRef(block);
            var bottom = new LinkedHashMap<String, Object>();
            bottom.put("parent", "block/slab");
            bottom.put("textures", Map.<String, Object>of("side", texture, "top", texture, "bottom", texture));
            emitter.model(allocatedModelId, bottom);
            var top = new LinkedHashMap<String, Object>();
            top.put("parent", "block/slab_top");
            top.put("textures", Map.<String, Object>of("side", texture, "top", texture, "bottom", texture));
            emitter.model(sibling(allocatedModelId, "_top"), top);
            var doubleModel = new LinkedHashMap<String, Object>();
            doubleModel.put("parent", "block/cube_all");
            doubleModel.put("textures", Map.<String, Object>of("all", texture));
            emitter.model(sibling(allocatedModelId, "_double"), doubleModel);
        }
        String base = block.modelRef();
        emitter.vanillaFile("blockstates/waxed_cut_copper_slab.json", Map.of("variants", Map.of(
                "type=bottom", Map.of("model", base),
                "type=top", Map.of("model", siblingRef(allocatedModelId, "_top")),
                "type=double", Map.of("model", siblingRef(allocatedModelId, "_double")))));
    }

    /** Generates (or resolves) the one shaped model; stairs need no orientation siblings. */
    private static String shapedModel(
            BlockDefinition block, NamespacedId allocatedModelId, PackEmitter emitter, String parent) {
        if (!block.wantsGeneratedModel()) {
            return block.modelRef();
        }
        String texture = textureRef(block);
        var model = new LinkedHashMap<String, Object>();
        model.put("parent", parent);
        model.put("textures", Map.<String, Object>of("side", texture, "top", texture, "bottom", texture));
        emitter.model(allocatedModelId, model);
        return allocatedModelId.toString();
    }

    private static NamespacedId sibling(NamespacedId id, String suffix) {
        return NamespacedId.of(id.namespace(), id.value() + suffix);
    }

    /**
     * The generated model's texture map: the parent's texture variables all
     * pointing at the one texture. {@code block/cube_all} reads {@code #all}
     * — the item idiom {@code layer0} resolves on nothing and every face
     * renders the missing texture. Unknown parents keep {@code layer0}
     * (hand-authored parents manage their own references).
     */
    private static Map<String, Object> texturesFor(String parent, String texture) {
        var textures = new LinkedHashMap<String, Object>();
        switch (parent) {
            case "block/cube_all" -> textures.put("all", texture);
            case "block/cross" -> textures.put("cross", texture);
            case "block/cube" -> {
                textures.put("particle", texture);
                for (String face : List.of("north", "east", "south", "west", "up", "down")) {
                    textures.put(face, texture);
                }
            }
            case "block/cube_column" -> {
                textures.put("end", texture);
                textures.put("side", texture);
            }
            case "block/cube_bottom_top" -> {
                textures.put("particle", texture);
                textures.put("bottom", texture);
                textures.put("side", texture);
                textures.put("top", texture);
            }
            case "block/orientable" -> {
                textures.put("front", texture);
                textures.put("side", texture);
                textures.put("top", texture);
            }
            default -> textures.put("layer0", texture);
        }
        return textures;
    }

    private static String siblingRef(NamespacedId id, String suffix) {
        return sibling(id, suffix).toString();
    }

    private static String textureRef(BlockDefinition block) {
        return block.texture().contains(":")
                ? block.texture()
                : block.id().namespace() + ":" + block.texture();
    }

    private static BlockStateSpaces.Technique technique(String text) {
        if (text == null) {
            return BlockStateSpaces.Technique.NOTE_BLOCK;
        }
        return switch (text) {
            case "note_block" -> BlockStateSpaces.Technique.NOTE_BLOCK;
            case "string" -> BlockStateSpaces.Technique.STRING;
            case "chorus" -> BlockStateSpaces.Technique.CHORUS;
            case "technical" -> BlockStateSpaces.Technique.TECHNICAL;
            case "stairs" -> BlockStateSpaces.Technique.STAIRS;
            case "slab" -> BlockStateSpaces.Technique.SLAB;
            default -> null;
        };
    }

    private static List<NamespacedId> refs(String defaultNamespace, Object value) {
        var out = new ArrayList<NamespacedId>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof String text && !text.isBlank()) {
                    out.add(NamespacedId.parse(Templates.shortRef(defaultNamespace, text)));
                }
            }
        }
        return out;
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
