package dev.materione.plugin.escrow.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Wire-safe identity of one transfer-capable holder; stack holders are deliberately absent. */
public record TransferHolder(Id128 owner, Kind kind, String key) {

    public enum Kind {
        PLAYER(1),
        KEYED(2);

        private final int code;

        Kind(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static Kind ofCode(int code) {
            for (Kind kind : values()) {
                if (kind.code == code) {
                    return kind;
                }
            }
            return null;
        }
    }

    public static final int MAX_KEY_BYTES = 192;

    public TransferHolder {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        if (owner.isZero()) {
            throw new IllegalArgumentException("a transfer holder needs an owner");
        }
        if (kind == Kind.PLAYER) {
            if (key != null && !key.isEmpty()) {
                throw new IllegalArgumentException("a player holder has no key");
            }
            key = "";
        } else {
            if (!canonical(key)) {
                throw new IllegalArgumentException("transfer holder key is not canonical");
            }
        }
    }

    public byte[] keyBytes() {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean canonical(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_BYTES || key.contains("..")
                || !first(key.charAt(0))) {
            return false;
        }
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!first(c) && c != '.' && c != ':' && c != '/' && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean first(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }
}
