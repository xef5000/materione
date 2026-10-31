package dev.materione.plugin.logic.item;

import dev.materione.api.logic.ItemResolution;
import dev.materione.api.logic.ItemSource;
import dev.materione.api.logic.ItemSourceDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import com.destroystokyo.paper.profile.PlayerProfile;

/**
 * {@code materione:head}: a player head (logic.md §4.4). The author gives exactly one of {@code player} (a
 * name: the viewer's own head is {@code player: "${player.name}"}), {@code texture} (a skin's hash or its
 * textures URL) or {@code base64} (a textures value, as servers store them). Values are templates, so a head
 * can follow the viewer. A literal value is checked at load; an unknown player is a barrier at render, never
 * an error.
 */
public final class HeadItemSource implements ItemSource<HeadItemSource.Head> {

    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TEXTURE_URL =
            Pattern.compile("https?://textures\\.minecraft\\.net/texture/([0-9a-f]{64})");
    private static final Pattern SKIN_URL = Pattern.compile("\"SKIN\"\\s*:\\s*\\{\\s*\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final String TEXTURE_BASE = "https://textures.minecraft.net/texture/";

    /** Which of the three ways a head names its skin. */
    public enum Kind { PLAYER, TEXTURE, BASE64 }

    /**
     * The parsed head.
     *
     * @param kind which of the three keys the author wrote
     * @param value the value, as a template
     */
    public record Head(Kind kind, TemplateText value) {}

    @Override
    public String id() {
        return "head";
    }

    @Override
    public ItemSourceDescription describe() {
        return ItemSourceDescription.of(new SelfDescription(
                "Head",
                "A player head: a player's skin, a texture hash or URL, or a base64 textures value. Give exactly one",
                List.of(
                        new ParameterSchema("player", ParameterType.STRING, false, null,
                                "A player's name, or ${player.name} for the viewer", null, null),
                        new ParameterSchema("texture", ParameterType.STRING, false, null,
                                "A skin hash, or its textures.minecraft.net URL", null, null),
                        new ParameterSchema("base64", ParameterType.STRING, false, null,
                                "A base64 textures value, as a server stores it", null, null)),
                "1.0.0"),
                null);
    }

    @Override
    public Head parse(ParamReader params) {
        List<Kind> given = new java.util.ArrayList<>();
        for (Kind kind : Kind.values()) {
            if (params.has(kind.name().toLowerCase(java.util.Locale.ROOT))) {
                given.add(kind);
            }
        }
        if (given.size() != 1) {
            throw new IllegalArgumentException("head takes exactly one of player, texture or base64");
        }
        Kind kind = given.get(0);
        TemplateText value = params.template(kind.name().toLowerCase(java.util.Locale.ROOT));
        String literal = value.source().trim();
        if (!literal.contains("${")) {
            checkLiteral(kind, literal);
        }
        return new Head(kind, value);
    }

    private static void checkLiteral(Kind kind, String literal) {
        boolean valid = switch (kind) {
            case PLAYER -> PLAYER_NAME.matcher(literal).matches();
            case TEXTURE -> textureHash(literal) != null;
            case BASE64 -> textureHashOfBase64(literal) != null;
        };
        if (!valid) {
            throw new IllegalArgumentException(switch (kind) {
                case PLAYER -> "'" + literal + "' is not a player name";
                case TEXTURE -> "'" + literal + "' is not a skin hash or textures URL";
                case BASE64 -> "the base64 value does not name a skin";
            });
        }
    }

    @Override
    public ItemResolution resolve(Head head, LogicContext context) {
        String text = head.value().render(context).trim();
        return switch (head.kind()) {
            case PLAYER -> player(text);
            case TEXTURE -> hashed(textureHash(text), text);
            case BASE64 -> hashed(textureHashOfBase64(text), text);
        };
    }

    private static ItemResolution player(String name) {
        if (!PLAYER_NAME.matcher(name).matches()) {
            return ItemResolution.unresolved("'" + name + "' is not a player name");
        }
        Player online = Bukkit.getPlayerExact(name);
        OfflinePlayer owner = online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
        if (owner == null) {
            return ItemResolution.unresolved("no player named '" + name + "' has joined this server");
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        return ItemResolution.ok(head);
    }

    private static ItemResolution hashed(String hash, String text) {
        if (hash == null) {
            return ItemResolution.unresolved("'" + text + "' is not a skin hash or textures URL");
        }
        PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)), null);
        try {
            profile.getTextures().setSkin(URI.create(TEXTURE_BASE + hash).toURL());
        } catch (MalformedURLException impossible) {
            return ItemResolution.unresolved("the skin '" + hash + "' has no valid URL");
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> meta.setPlayerProfile(profile));
        return ItemResolution.ok(head);
    }

    /** The skin hash a texture hash or textures URL names, or null. */
    static String textureHash(String text) {
        Matcher url = TEXTURE_URL.matcher(text);
        if (url.matches()) {
            return url.group(1);
        }
        return HASH.matcher(text).matches() ? text : null;
    }

    /** The skin hash inside a base64 textures value, or null when it does not decode to one. */
    static String textureHashOfBase64(String text) {
        try {
            String json = new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
            Matcher skin = SKIN_URL.matcher(json);
            return skin.find() ? textureHash(skin.group(1)) : null;
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }
}
