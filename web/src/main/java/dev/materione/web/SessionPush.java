package dev.materione.web;

/**
 * Server-initiated pushes to every connected editor (the "preview" group of
 * web.md §4.2). Lives in {@code web} so hosts never touch protocol classes
 * (R8): they call these static helpers from their own event sources.
 */
public final class SessionPush {

    private SessionPush() {}

    /** One diagnostic rendered for the wire — shared by the RPC feed and pushes. */
    public static com.google.gson.JsonObject entryJson(dev.materione.api.diagnostics.Diagnostic diagnostic) {
        com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
        entry.addProperty("code", diagnostic.code().code());
        entry.addProperty(
                "severity",
                diagnostic.severity().name().toLowerCase(java.util.Locale.ROOT));
        entry.addProperty("message", diagnostic.message());
        if (diagnostic.resource() != null) {
            entry.addProperty("resource", diagnostic.resource().toString());
        }
        if (diagnostic.location() != null) {
            entry.addProperty("location", diagnostic.location().toString());
        }
        if (diagnostic.hint() != null) {
            entry.addProperty("hint", diagnostic.hint());
        }
        if (diagnostic.target() != null) {
            com.google.gson.JsonObject target = new com.google.gson.JsonObject();
            com.google.gson.JsonArray field = new com.google.gson.JsonArray();
            diagnostic.target().field().forEach(field::add);
            target.add("field", field);
            entry.add("target", target);
        }
        return entry;
    }

    /** Live diagnostic stream (web.md §4.2 "admin diagnostics feed"). */
    public static void diagnostic(EditorSessions sessions, dev.materione.api.diagnostics.Diagnostic diagnostic) {
        com.google.gson.JsonObject push = new com.google.gson.JsonObject();
        push.addProperty("op", "push.diagnostic");
        push.addProperty("id", "diag-" + System.nanoTime());
        push.add("payload", entryJson(diagnostic));
        byte[] body = dev.materione.web.protocol.Wire.canonicalBytes(push);
        for (EditorSession session : sessions.active()) {
            try {
                session.sendFrame(body);
            } catch (RuntimeException e) {
                // one dead socket never blocks the others (D6)
            }
        }
    }

    /** Announces one finished targeted reload (the live preview loop's last mile). */
    public static void reload(
            EditorSessions sessions,
            String files,
            boolean accepted,
            boolean noop,
            int itemsLive,
            int quarantined) {
        com.google.gson.JsonObject push = new com.google.gson.JsonObject();
        push.addProperty("op", "push.reload");
        push.addProperty("id", "reload-" + System.nanoTime());
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        payload.addProperty("files", files);
        payload.addProperty("accepted", accepted);
        payload.addProperty("noop", noop);
        payload.addProperty("itemsLive", itemsLive);
        payload.addProperty("quarantined", quarantined);
        push.add("payload", payload);
        byte[] body = dev.materione.web.protocol.Wire.canonicalBytes(push);
        for (EditorSession session : sessions.active()) {
            try {
                session.sendFrame(body);
            } catch (RuntimeException e) {
                // one dead socket never blocks the others (D6)
            }
        }
    }

    /**
     * Announces one externally changed document (file watcher): connected
     * editors refresh the tree and flag open tabs instead of silently
     * clobbering on the next save.
     */
    public static void documentChanged(EditorSessions sessions, String pack, String path, boolean deleted) {
        com.google.gson.JsonObject push = new com.google.gson.JsonObject();
        push.addProperty("op", "push.document.changed");
        push.addProperty("id", "doc-" + System.nanoTime());
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        payload.addProperty("pack", pack);
        payload.addProperty("path", path);
        payload.addProperty("deleted", deleted);
        push.add("payload", payload);
        byte[] body = dev.materione.web.protocol.Wire.canonicalBytes(push);
        for (EditorSession session : sessions.active()) {
            try {
                session.sendFrame(body);
            } catch (RuntimeException e) {
                // one dead socket never blocks the others (D6)
            }
        }
    }
}
