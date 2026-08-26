package dev.materione.web;

/**
 * Server-initiated pushes to every connected editor (the "preview" group of
 * web.md §4.2). Lives in {@code web} so hosts never touch protocol classes
 * (R8): they call these static helpers from their own event sources.
 */
public final class SessionPush {

    private SessionPush() {}

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
}
