package dev.materione.plugin.runtime.behavior;

import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.platform.probe.PlatformProbe;
import java.lang.reflect.Method;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI soft hook (runtime-systems §2.1: placeholders resolve inside
 * string interpolations). Resolved reflectively so the plugin never hard-depends
 * on PAPI; when absent, text passes through untouched — {@code %...%} tokens
 * simply stay visible, and nothing else degrades.
 */
public final class PlaceholderResolver {

    private static final Method SET_PLACEHOLDERS = lookup();
    private static final boolean FOLIA = PlatformProbe.detectFoliaServer();

    private PlaceholderResolver() {}

    /** True when PlaceholderAPI is on the server. */
    public static boolean available() {
        return SET_PLACEHOLDERS != null;
    }

    /** Applies PAPI replacements when available; passes text through otherwise. */
    public static String apply(OfflinePlayer player, String text) {
        if (SET_PLACEHOLDERS == null || text == null || text.indexOf('%') < 0) {
            return text;
        }
        if (FOLIA && !containsToken(text)) {
            return text;
        }
        requireSafe(FOLIA);
        try {
            Object result = SET_PLACEHOLDERS.invoke(null, player, text);
            return result instanceof String resolved ? resolved : text;
        } catch (Exception e) {
            return text; // a broken placeholder provider must not break actions
        }
    }

    static void requireSafe(boolean folia) {
        if (folia) {
            throw new ReadUnavailableException("PlaceholderAPI expansions have not declared Folia-safe reads");
        }
    }

    static boolean containsToken(String text) {
        int start = text.indexOf('%');
        while (start >= 0) {
            int end = text.indexOf('%', start + 1);
            if (end < 0) {
                return false;
            }
            if (end > start + 1) {
                return true;
            }
            start = end;
        }
        return false;
    }

    private static Method lookup() {
        try {
            return Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                    .getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
