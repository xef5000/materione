package dev.materione.api.gui;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Read access to decoded texture pixels for raster components (gui.md §4, §10).
 * Only compile-time code reads through it: {@link CompiledGuiLayer#paint} receives
 * a source bound to the owning pack, and parsing never does, so {@code parse} stays
 * pure. A missing or unreadable texture is an empty result; the painting component
 * reports it by throwing, and the compiler skips that one layer with a diagnostic.
 */
@FunctionalInterface
public interface TextureSource {

    /** A source with no textures: every lookup is empty. */
    TextureSource NONE = texture -> Optional.empty();

    /**
     * Decoded pixels of a texture reference: {@code ns:path} (no extension), or a bare
     * {@code path} that the caller already bound to its own namespace.
     */
    Optional<Pixels> texture(String texture);

    /**
     * One decoded texture. {@link #argb()} is row-major, straight alpha (not premultiplied),
     * exactly {@code width * height} long, and must not be modified by the reader.
     */
    final class Pixels {

        private final int width;
        private final int height;
        private final int[] argb;

        /**
         * @param width pixels per row, at least 1
         * @param height rows, at least 1
         * @param argb the pixels; copied, so the caller may reuse its array
         */
        public Pixels(int width, int height, int[] argb) {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("texture size must be at least 1x1: " + width + "x" + height);
            }
            Objects.requireNonNull(argb, "argb");
            if (argb.length != (long) width * height) {
                throw new IllegalArgumentException("argb holds " + argb.length + " pixels, expected " + width * height);
            }
            this.width = width;
            this.height = height;
            this.argb = argb.clone();
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** The row-major pixels. Shared, not copied: callers must treat it as read-only. */
        public int[] argb() {
            return argb;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Pixels pixels && width == pixels.width && height == pixels.height
                    && Arrays.equals(argb, pixels.argb);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * width + height) + Arrays.hashCode(argb);
        }

        @Override
        public String toString() {
            return "Pixels[" + width + "x" + height + "]";
        }
    }
}
