package dev.materione.content.gui.raster;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The in-repo PNG writer (gui.md §4, D7). It emits exactly three chunks, {@code IHDR},
 * {@code IDAT} and {@code IEND}, with no ancillary chunks (no timestamps, gamma or
 * physical size), a fixed filter (type 0, none, on every row) and a fixed deflate level
 * (6, zlib-wrapped). The same pixels always produce the same bytes for a given JDK; the
 * goldens also compare decoded pixels, so a deflate change cannot hide a pixel regression.
 *
 * <p>Two colour types: RGBA8 for raster output (colour type 6) and grayscale8 for
 * intensity fonts (colour type 0), whose glyphs the client tints with the text colour.
 */
public final class PngEncoder {

    private static final byte[] SIGNATURE = {(byte) 137, 'P', 'N', 'G', '\r', '\n', 26, '\n'};
    private static final int DEFLATE_LEVEL = 6;
    private static final int COLOUR_GRAYSCALE = 0;
    private static final int COLOUR_RGBA = 6;

    private PngEncoder() {}

    /** An RGBA8 PNG of {@code width × height} straight-alpha ARGB pixels, row-major. */
    public static byte[] encodeRgba(int width, int height, int[] argb) {
        checkSize(width, height, argb.length);
        byte[] raw = new byte[height * (1 + width * 4)];
        int at = 0;
        for (int row = 0; row < height; row++) {
            raw[at++] = 0; // filter type: none
            for (int column = 0; column < width; column++) {
                int pixel = argb[row * width + column];
                raw[at++] = (byte) (pixel >>> 16);
                raw[at++] = (byte) (pixel >>> 8);
                raw[at++] = (byte) pixel;
                raw[at++] = (byte) (pixel >>> 24);
            }
        }
        return container(width, height, COLOUR_RGBA, raw);
    }

    /**
     * A grayscale8 PNG of {@code width × height} luminance values (one byte per pixel,
     * row-major, {@code 0..255}).
     */
    public static byte[] encodeGrayscale(int width, int height, byte[] luminance) {
        checkSize(width, height, luminance.length);
        byte[] raw = new byte[height * (1 + width)];
        int at = 0;
        for (int row = 0; row < height; row++) {
            raw[at++] = 0;
            System.arraycopy(luminance, row * width, raw, at, width);
            at += width;
        }
        return container(width, height, COLOUR_GRAYSCALE, raw);
    }

    private static void checkSize(int width, int height, int length) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("PNG must be at least 1x1: " + width + "x" + height);
        }
        if (length != width * height) {
            throw new IllegalArgumentException("pixel count " + length + " does not match " + width + "x" + height);
        }
    }

    private static byte[] container(int width, int height, int colourType, byte[] scanlines) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8; // bit depth
        header[9] = (byte) colourType;
        header[10] = 0; // compression: deflate
        header[11] = 0; // filter method 0
        header[12] = 0; // no interlace
        chunk(out, "IHDR", header);
        chunk(out, "IDAT", deflate(scanlines));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] input) {
        var deflater = new Deflater(DEFLATE_LEVEL, false);
        deflater.setInput(input);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            int produced = deflater.deflate(buffer);
            out.write(buffer, 0, produced);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        byte[] length = new byte[4];
        putInt(length, 0, data.length);
        out.writeBytes(length);
        out.writeBytes(name);
        out.writeBytes(data);
        var crc = new CRC32();
        crc.update(name);
        crc.update(data);
        byte[] checksum = new byte[4];
        putInt(checksum, 0, (int) crc.getValue());
        out.writeBytes(checksum);
    }

    private static void putInt(byte[] target, int at, int value) {
        target[at] = (byte) (value >>> 24);
        target[at + 1] = (byte) (value >>> 16);
        target[at + 2] = (byte) (value >>> 8);
        target[at + 3] = (byte) value;
    }
}
