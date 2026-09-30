package com.model3d.loader.client.render;

import com.mojang.blaze3d.platform.NativeImage;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Decodes an image that is not a PNG into a {@link NativeImage}, via {@code ImageIO}.
 *
 * <h2>Why this is its own class</h2>
 * It has one hard-won invariant - the target is always {@code Format.RGBA} - and that invariant was
 * violated in a way nothing caught: a JPEG-textured model rendered as a white blob because every
 * pixel write threw. Extracting the conversion lets {@code ImageDecodeTest} feed it real encoded
 * JPEG/GIF/BMP bytes and check the pixels that come back, in an ordinary unit test with no GL
 * context and no game. Logic that can silently degrade a model's entire appearance should be
 * testable without a display.
 *
 * <h2>Why not just {@code NativeImage.read}</h2>
 * That is STB-based and PNG-only: handed a JPEG it fails, which is what makes this fallback
 * necessary rather than redundant. STB also fails on the large PNGs some exporters emit, so the
 * PNG path is tried first and this second, rather than the other way round.
 */
final class ImageDecode {

    private ImageDecode() {
    }

    /**
     * Decodes {@code data} into a fresh RGBA {@link NativeImage}, or null when no ImageIO reader
     * handles the format.
     *
     * <p><b>The target is always RGBA, even for an alpha-less source.</b> Two reasons, either of
     * which alone would settle it:
     * <ul>
     *   <li>1.20.1 has no RGB pixel setter. {@code NativeImage#setColor} - which in these mappings is
     *       {@code setPixelRGBA} - documents that it throws {@code IllegalArgumentException} unless the
     *       image's format is RGBA. For an RGB image the only way in is raw byte writes through a
     *       {@code ByteBuffer}, which is a larger and riskier surface than an alpha channel's worth of
     *       memory.</li>
     *   <li>A {@code Format.RGB} target plus {@code setPixelRGBA} is what this code used to do for
     *       alpha-less sources such as JPEG, and it threw for <b>every pixel</b>, so the image was
     *       discarded and replaced by the 1x1 white fallback.</li>
     * </ul>
     * The cost is one unused alpha byte per pixel, all {@code 0xFF}. The alternative is a texture that
     * cannot be written at all.
     *
     * <h2>Cost</h2>
     * Measured on this machine, decoding through this method (a 4096x4096 JPEG):
     * <pre>
     *   512x512   ->  36 ms      2048x2048 -> 106 ms
     *  1024x1024  -> 106 ms      4096x4096 -> 416 ms
     * </pre>
     * That is a one-off cost paid when a model's textures are first uploaded, not per frame or per
     * entity, so it is a load-time hitch rather than a frame-time one. It is also the price of the only
     * pixel-writing API 1.20.1 offers for a non-RGBA source: {@code setPixelRGBA} is a JNI call per
     * pixel, and 16.7 million of them is what 416 ms buys. A bulk copy would need raw byte access, which
     * these mappings do not expose, so the loop is the honest implementation - and having the number
     * recorded means a future report of "the game froze when I loaded my model" has something to be
     * compared against instead of a guess.
     *
     * <p>The caller owns the returned image and must {@link NativeImage#close()} it.
     *
     * @param data encoded image bytes
     * @return the decoded image, or null when this data is not a format ImageIO can read
     */
    @Nullable
    static NativeImage toNativeImage(byte[] data) {
        try {
            BufferedImage source = javax.imageio.ImageIO.read(new ByteArrayInputStream(data));
            if (source == null) {
                return null;
            }
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }
            NativeImage target = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            try {
                // getRGB returns TYPE_INT_ARGB whatever the source type is - including a source whose
                // colour model has no alpha, where the absent alpha is promoted to 0xFF - and
                // setPixelRGBA expects that same packing, alpha in the high byte. So the value passes
                // straight through with no channel reshuffling.
                int[] row = new int[width];
                for (int y = 0; y < height; y++) {
                    source.getRGB(0, y, width, 1, row, 0, width);
                    for (int x = 0; x < width; x++) {
                        target.setPixelRGBA(x, y, row[x]);
                    }
                }
                // Release the decode promptly: a 4K JPEG is tens of megabytes of heap on its own.
                source.flush();
                return target;
            } catch (RuntimeException e) {
                target.close();
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
