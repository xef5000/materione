package dev.materione.core.texture;

import dev.materione.api.gui.TextureSource;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Decodes a PNG into straight-alpha ARGB pixels (gui.md §4). ImageIO does the
 * decoding; its ARGB view is non-premultiplied, which is the form the raster
 * pixel rules work on. Any input that is not a readable image yields empty, so
 * a corrupt texture skips the layer that names it instead of breaking a reload.
 */
public final class PngDecoder {

    private PngDecoder() {}

    /** The decoded pixels of {@code bytes}, or empty when they are not a readable image. */
    public static Optional<TextureSource.Pixels> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
                return Optional.empty();
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] argb = image.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY
                    ? grayscale(image)
                    : image.getRGB(0, 0, width, height, null, 0, width);
            return Optional.of(new TextureSource.Pixels(width, height, argb));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Gray images read their raw samples. {@code getRGB} would convert them through the linear
     * gray colour space, so a luminance of 128 would come back as a different value; the raw
     * samples are the luminance the file stores.
     */
    private static int[] grayscale(BufferedImage image) {
        Raster raster = image.getRaster();
        ColorModel model = image.getColorModel();
        int max = (1 << model.getComponentSize(0)) - 1;
        boolean alpha = model.hasAlpha();
        int alphaMax = alpha ? (1 << model.getComponentSize(1)) - 1 : 255;
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray = raster.getSample(x, y, 0) * 255 / max;
                int a = alpha ? raster.getSample(x, y, 1) * 255 / alphaMax : 255;
                argb[y * width + x] = (a << 24) | (gray << 16) | (gray << 8) | gray;
            }
        }
        return argb;
    }
}
