package com.model3d.loader.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ImageDecode}, against real encoded image bytes rather than a mock.
 *
 * <h2>The bug these exist for</h2>
 * A live client logged, for a model textured with JPEGs:
 * <pre>
 *   embedded image 'image0' (jpeg) failed to decode with ImageIO:
 *     java.lang.IllegalArgumentException: setPixelRGBA only works on RGBA images; have RGB
 * </pre>
 * four times over, then fell back to the 1x1 white texture for each - so an entire aircraft rendered
 * as a white silhouette against a similarly pale sky, which reads as "the model did not load". The
 * cause was one ternary: an alpha-less source allocated {@code Format.RGB}, and
 * {@code NativeImage#setPixelRGBA} rejects anything that is not exactly {@code Format.RGBA} (the check
 * is a reference comparison in the 1.20.1 bytecode, with the message this log shows).
 *
 * <p>So these tests encode real JPEG, GIF, BMP and PNG bytes - the formats that reach this fallback,
 * because {@code NativeImage.read} is PNG-only - run them through the decoder, and check the colours
 * that come back. A regression here changes a whole model's appearance, which is the kind of bug that
 * survives every check that only looks at geometry.
 *
 * <h2>The channel order, measured rather than assumed</h2>
 * {@code NativeImage#getPixelRGBA} is the exact inverse of {@code setPixelRGBA}: writing
 * {@code 0xFF204080} reads back {@code 0xFF204080}, and a pixel decoded from a
 * {@code TYPE_INT_ARGB} source comes back with all four channels unchanged. The single transformation
 * the decoder performs is on a source with <b>no</b> alpha channel, where the absent alpha becomes
 * {@code 0xFF} - the right default for a texture, since a JPEG should be opaque rather than invisible.
 *
 * <p>Both facts were established by probing the real class, and they are pinned below because an
 * earlier version of this very file asserted the opposite: it assumed the accessor returned channels
 * reversed, applied a compensating swap in its helper, and then appeared to find a channel-order bug in
 * code that had none. Three probe runs and two false failures went into discovering that the
 * measurement was what needed checking, not the decoder. That is recorded here rather than in a commit
 * message because this file is where someone would look before making the same assumption again.
 */
class ImageDecodeTest {

    /** Encodes a small image to the named format using the JDK's own writer. */
    private static byte[] encode(String format, int width, int height, Color colour, boolean alpha)
            throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) {
            throw new IllegalStateException("no ImageIO writer for " + format);
        }
        return out.toByteArray();
    }

    /**
     * The channel readers, with the accessor's reverse order applied in one named place.
     *
     * <p>Named rather than inlined so the reversal is stated once instead of being rediscovered by
     * every assertion, and so a reader can see it was measured (class comment) rather than guessed.
     */
    private static int redOf(NativeImage image, int x, int y) {
        return (image.getPixelRGBA(x, y) >>> 16) & 0xFF;
    }

    private static int greenOf(NativeImage image, int x, int y) {
        return (image.getPixelRGBA(x, y) >>> 8) & 0xFF;
    }

    private static int blueOf(NativeImage image, int x, int y) {
        return image.getPixelRGBA(x, y) & 0xFF;
    }

    private static int alphaOf(NativeImage image, int x, int y) {
        return (image.getPixelRGBA(x, y) >>> 24) & 0xFF;
    }

    /**
     * The value {@code getPixelRGBA} returns for a pixel decoded from a {@code TYPE_INT_ARGB} source
     * that had an alpha channel.
     *
     * <p>Measured: such a pixel comes back <b>exactly</b> as it went in, all four channels. The only
     * transformation the decoder performs is on a source with no alpha channel at all, which is forced
     * opaque - see {@link #opaqueIfNoAlpha}. Stated as its own function so neither fact is re-derived
     * per assertion; an earlier version of this file got them backwards and asserted a value the
     * accessor never produces.
     */
    private static int asWritten(int sourceArgb) {
        return sourceArgb;
    }

    /**
     * The value {@code getPixelRGBA} returns when the source image had no alpha channel.
     *
     * <p>The RGB channels survive; the absent alpha becomes {@code 0xFF}. That is the right default for
     * a texture - a JPEG should be opaque - and it is worth pinning, because the alternative
     * (transparent) would make every JPEG-textured model invisible rather than merely colourless.
     */
    private static int opaqueIfNoAlpha(int sourceArgb) {
        return sourceArgb | 0xFF000000;
    }

    @Test
    @DisplayName("a JPEG decodes instead of throwing, because the target is RGBA")
    void jpegDecodes() throws Exception {
        byte[] jpeg = encode("jpg", 4, 3, new Color(200, 30, 90), false);

        NativeImage image = ImageDecode.toNativeImage(jpeg);
        assertNotNull(image, "a JPEG must decode; the RGB target this used to allocate threw for"
                + " every pixel and discarded the whole image");
        try {
            assertEquals(4, image.getWidth());
            assertEquals(3, image.getHeight());
            assertEquals(NativeImage.Format.RGBA, image.format(),
                    "the target must be RGBA: 1.20.1 has no RGB pixel setter, and setPixelRGBA"
                            + " rejects any other format by reference comparison");

            // JPEG is lossy, so compare approximately - the point is that the channels are in the
            // right places and the result is not the white fallback.
            int red = redOf(image, 0, 0);
            int green = greenOf(image, 0, 0);
            int blue = blueOf(image, 0, 0);
            System.out.printf("jpeg decoded -> %dx%d %s rgb=(%d,%d,%d) alpha=%d%n",
                    image.getWidth(), image.getHeight(), image.format(), red, green, blue,
                    alphaOf(image, 0, 0));
            assertEquals(255, alphaOf(image, 0, 0), "an alpha-less source must still decode opaque");
            assertEquals(200, red, 12, "red channel");
            assertEquals(30, green, 12, "green channel");
            assertEquals(90, blue, 12, "blue channel");
            // The bug's signature was the 1x1 white fallback, (255,255,255). Distinguishing the
            // decoded colour from white is what makes this a test of the fix rather than of the
            // decoder merely returning something.
            assertTrue(red < 240 && green < 240 && blue < 240,
                    "the decoded pixel must not be the white fallback; got (" + red + "," + green
                            + "," + blue + ")");
        } finally {
            image.close();
        }
    }

    @Test
    @DisplayName("a GIF decodes")
    void gifDecodes() throws Exception {
        byte[] gif = encode("gif", 2, 2, new Color(200, 30, 90), true);

        NativeImage image = ImageDecode.toNativeImage(gif);
        assertNotNull(image, "a GIF must decode");
        try {
            System.out.printf("gif decoded -> %dx%d %s rgb=(%d,%d,%d)%n", image.getWidth(),
                    image.getHeight(), image.format(), redOf(image, 0, 0), greenOf(image, 0, 0),
                    blueOf(image, 0, 0));
            assertEquals(NativeImage.Format.RGBA, image.format());
            assertEquals(200, redOf(image, 0, 0), 12, "red channel");
            assertEquals(30, greenOf(image, 0, 0), 12, "green channel");
            assertEquals(90, blueOf(image, 0, 0), 12, "blue channel");
        } finally {
            image.close();
        }
    }

    @Test
    @DisplayName("a BMP decodes exactly")
    void bmpDecodes() throws Exception {
        byte[] bmp = encode("bmp", 3, 3, new Color(200, 30, 90), false);

        NativeImage image = ImageDecode.toNativeImage(bmp);
        assertNotNull(image, "a BMP must decode");
        try {
            System.out.printf("bmp decoded -> %dx%d %s rgb=(%d,%d,%d)%n", image.getWidth(),
                    image.getHeight(), image.format(), redOf(image, 0, 0), greenOf(image, 0, 0),
                    blueOf(image, 0, 0));
            // BMP is lossless, so this can be exact.
            assertEquals(200, redOf(image, 0, 0), "red channel, exact");
            assertEquals(30, greenOf(image, 0, 0), "green channel, exact");
            assertEquals(90, blueOf(image, 0, 0), "blue channel, exact");
            assertEquals(255, alphaOf(image, 0, 0), "opaque");
        } finally {
            image.close();
        }
    }

    @Test
    @DisplayName("a PNG also decodes here, so the fallback is not format-specific")
    void pngDecodes() throws Exception {
        byte[] png = encode("png", 2, 2, new Color(200, 30, 90), true);

        // The mod tries NativeImage.read (STB) first for PNG, so this path is the backstop for the
        // large PNGs STB rejects. It must not be the case that the fallback only handles the formats
        // the primary path already handled.
        NativeImage image = ImageDecode.toNativeImage(png);
        assertNotNull(image);
        try {
            assertEquals(NativeImage.Format.RGBA, image.format());
            assertEquals(200, redOf(image, 0, 0), "red channel, exact");
            assertEquals(30, greenOf(image, 0, 0), "green channel, exact");
            assertEquals(90, blueOf(image, 0, 0), "blue channel, exact");
        } finally {
            image.close();
        }
    }

    @Test
    @DisplayName("unreadable bytes return null instead of throwing, so one bad image is not fatal")
    void garbageReturnsNull() {
        assertNull(ImageDecode.toNativeImage(new byte[] {1, 2, 3, 4, 5}));
        assertNull(ImageDecode.toNativeImage(new byte[0]));
        assertNull(ImageDecode.toNativeImage("this is not an image at all".getBytes()));
    }

    @Test
    @DisplayName("the decoded image is RGBA with four components, which is what the upload expects")
    void layoutIsRgbaWithFourComponents() throws Exception {
        byte[] bmp = encode("bmp", 2, 1, new Color(10, 20, 30), false);

        NativeImage image = ImageDecode.toNativeImage(bmp);
        assertNotNull(image);
        try {
            assertEquals(NativeImage.Format.RGBA, image.format());
            // Four components per pixel: the texture upload declares GL_RGBA8 and hands this
            // storage to glTexSubImage2D as GL_RGBA, so a three-component image would shear.
            assertEquals(4, image.format().components(),
                    "RGBA is four components per pixel; the GL upload reads four bytes per pixel");
        } finally {
            image.close();
        }
    }

    @Test
    @DisplayName("every channel survives the conversion, including a non-opaque alpha")
    void channelsSurviveUnchanged() throws Exception {
        // Four distinct values, so a swap of any pair is detectable: something like 0xFF808080 would
        // pass under several wrong orderings.
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x80402010); // alpha 0x80, red 0x40, green 0x20, blue 0x10
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);

        NativeImage image = ImageDecode.toNativeImage(out.toByteArray());
        assertNotNull(image);
        try {
            int pixel = image.getPixelRGBA(0, 0);
            System.out.printf("decoded pixel = 0x%08X (source ARGB 0x80402010)%n", pixel);
            // Source 0x80402010 is alpha 0x80, red 0x40, green 0x20, blue 0x10. Measurements against
            // the real class show the RGB channels survive exactly and only an ABSENT alpha is
            // rewritten, so this source (which has one) comes back unchanged. Each channel is also
            // asserted separately below, so a swap of any pair cannot hide.
            assertEquals(asWritten(0x80402010), pixel,
                    "the conversion must not alter any channel; got 0x" + Integer.toHexString(pixel));
            assertEquals(0x80, alphaOf(image, 0, 0), "alpha must not be forced opaque");
            assertEquals(0x40, redOf(image, 0, 0), "red");
            assertEquals(0x20, greenOf(image, 0, 0), "green");
            assertEquals(0x10, blueOf(image, 0, 0), "blue");
        } finally {
            image.close();
        }
    }

    @Test
    @DisplayName("pixels keep their positions, so rows and columns are not transposed")
    void pixelPositionsArePreserved() throws Exception {
        // Two different colours side by side in one row: a transposed, reversed or off-by-one write
        // shows up here, which a single-pixel image cannot detect.
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, 0x00FF0000); // red
        source.setRGB(1, 0, 0x000000FF); // blue
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "bmp", out);

        NativeImage image = ImageDecode.toNativeImage(out.toByteArray());
        assertNotNull(image);
        try {
            assertEquals(2, image.getWidth(), "width must not be swapped with height");
            assertEquals(1, image.getHeight());
            assertEquals(opaqueIfNoAlpha(0x00FF0000), image.getPixelRGBA(0, 0),
                    "first pixel keeps its own value");
            assertEquals(opaqueIfNoAlpha(0x000000FF), image.getPixelRGBA(1, 0),
                    "second pixel keeps its own value");
            assertTrue(redOf(image, 0, 0) > 200 && blueOf(image, 1, 0) > 200,
                    "the two pixels must remain distinguishable and in place: pixel0="
                            + Integer.toHexString(image.getPixelRGBA(0, 0)) + " pixel1="
                            + Integer.toHexString(image.getPixelRGBA(1, 0)));
        } finally {
            image.close();
        }
    }
}
