package com.model3d.loader.scene;

import java.util.List;

/**
 * An image carried inside the model file rather than as a sibling file.
 *
 * <p>Needed because the single-file case is the common one: a {@code .glb} with its textures in
 * the BIN chunk is what most exporters produce, and both real sample models in this repository do
 * exactly that (seven and nine embedded images). Without this, the flagship sample renders
 * untextured — which looks like a rendering bug rather than a format limitation.
 *
 * <h2>How it fits the texture contract</h2>
 * {@link ModelMaterial}'s texture fields stay plain strings, so nothing that consumes them has to
 * change shape. An embedded image is referenced through the reserved scheme
 * {@code embedded/<name>}:
 *
 * <pre>
 *   if (ModelImage.isEmbedded(material.baseColorTexture())) {
 *       ModelImage image = scene.embeddedImage(material.baseColorTexture());
 *       // upload image.data(), sniffing image.mimeType()
 *   } else {
 *       // resolve the string as a model-relative path, as before
 *   }
 * </pre>
 *
 * <p>The scheme is a reserved prefix, not a real path: {@code ModelSource} never sees it, and a
 * literal file named {@code embedded/...} inside a model directory is unreachable by design. That
 * is the deliberate trade — one reserved namespace is cheaper than a second texture type threaded
 * through every material accessor.
 *
 * <p>{@link #data()} is the encoded file bytes, not decoded pixels: decoding is the renderer's job
 * (it owns the image decoder and the GPU), and keeping the encoded form means this class needs no
 * image library at all, which is what keeps the whole scene package testable offline.
 */
public record ModelImage(String name, byte[] data, String mimeType) {

    /** Reserved scheme prefix that marks a texture path as referring to a {@link ModelImage}. */
    public static final String SCHEME = "embedded/";

    public ModelImage {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("ModelImage needs a name");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("ModelImage '" + name + "' has no data");
        }
        if (mimeType == null) {
            mimeType = "";
        }
    }

    /** The texture-path string that refers to this image. */
    public String texturePath() {
        return pathFor(name);
    }

    /** The reserved path a material uses to refer to the embedded image called {@code name}. */
    public static String pathFor(String name) {
        return SCHEME + name;
    }

    /** True when {@code texturePath} refers to an embedded image rather than a file. */
    public static boolean isEmbedded(String texturePath) {
        return texturePath != null && texturePath.startsWith(SCHEME);
    }

    /** The image name inside an embedded texture path, or null when the path is a file reference. */
    public static String nameOf(String texturePath) {
        return isEmbedded(texturePath) ? texturePath.substring(SCHEME.length()) : null;
    }

    /** Finds {@code texturePath} in {@code images}, or null when it is absent or a file reference. */
    public static ModelImage find(List<ModelImage> images, String texturePath) {
        String name = nameOf(texturePath);
        if (name == null || images == null) {
            return null;
        }
        for (ModelImage image : images) {
            if (image.name().equals(name)) {
                return image;
            }
        }
        return null;
    }

    public int byteSize() {
        return data.length;
    }

    @Override
    public String toString() {
        return "ModelImage('" + name + "' " + mimeType + " " + data.length + " bytes)";
    }
}
