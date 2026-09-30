package com.model3d.loader.format.gltf;

import com.model3d.loader.Model3D;
import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.format.json.JsonArray;
import com.model3d.loader.format.json.JsonObject;
import com.model3d.loader.scene.ModelImage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@code images[]} table: for each image index, either an embedded {@link ModelImage} whose bytes
 * were sliced out of a bufferView, or a model-relative file path for the resource layer.
 *
 * <p>Both of the real corpus models are the embedded case (seven and six images), and that is the
 * normal shape of a single-file {@code .glb}, so the two outcomes have to be equally first-class: a
 * material's texture field is a string either way, and {@link ModelImage#SCHEME} is what tells them
 * apart.
 *
 * <h2>Images degrade, geometry does not</h2>
 * A broken <b>accessor</b> fails the load, because there is no sensible mesh without it. A broken
 * <b>image</b> does not: an out-of-range bufferView, a malformed data uri or an image with neither a
 * uri nor a bufferView costs one texture and leaves the material slot unassigned, and the failure is
 * collected into a single warning naming every image index involved. Losing the whole aircraft
 * because one decal's bytes are missing would be the wrong trade.
 */
final class ImageTable {

    /** Per image index: a file path, an {@code embedded/<name>} path, or null when unusable. */
    private final String[] texturePaths;
    private final List<ModelImage> images;
    private final List<String> unusable;

    private ImageTable(String[] texturePaths, List<ModelImage> images, List<String> unusable) {
        this.texturePaths = texturePaths;
        this.images = images;
        this.unusable = unusable;
    }

    static ImageTable read(JsonObject root, GltfBuffers buffers) throws ModelParseException {
        JsonArray declared = root.getArray("images");
        if (declared == null || declared.isEmpty()) {
            return new ImageTable(new String[0], List.of(), List.of());
        }
        JsonArray bufferViews = root.getArray("bufferViews");
        String[] paths = new String[declared.size()];
        List<ModelImage> images = new ArrayList<>(declared.size());
        List<String> unusable = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < declared.size(); i++) {
            String element = "images[" + i + "]";
            try {
                JsonObject image = declared.requireObject(i);
                String uri = image.getString("uri");
                String mimeType = image.getString("mimeType");
                if (uri != null) {
                    paths[i] = fromUri(uri, image, i, mimeType, element, images, usedNames);
                    continue;
                }
                if (image.has("bufferView")) {
                    paths[i] = fromBufferView(image, bufferViews, buffers, i, mimeType, element, images,
                            usedNames);
                    continue;
                }
                unusable.add(element + ": neither a uri nor a bufferView");
            } catch (ModelParseException e) {
                // One unreadable image must not cost the model its geometry.
                unusable.add(element + ": " + e.getMessage());
            }
        }
        return new ImageTable(paths, images, unusable);
    }

    private static String fromUri(String uri, JsonObject image, int index, String mimeType,
                                  String element, List<ModelImage> images, Set<String> usedNames)
            throws ModelParseException {
        if (!Uris.isDataUri(uri)) {
            // Returned exactly as written: ModelSource owns percent-decoding and the case-insensitive
            // fallback, so decoding here would double-decode a name that legitimately contains '%'.
            String reason = Uris.unsupportedReason(Uris.decodePercent(uri, element));
            if (reason != null) {
                throw new ModelParseException(reason);
            }
            return uri;
        }
        byte[] data = Uris.decodeDataUri(uri, element);
        if (data.length == 0) {
            throw new ModelParseException("the data uri carries no bytes");
        }
        // A data uri holds the encoded image bytes, which is exactly what ModelImage carries.
        ModelImage embedded = new ModelImage(uniqueName(image.getString("name"), index, usedNames), data,
                mimeType);
        images.add(embedded);
        return embedded.texturePath();
    }

    private static String fromBufferView(JsonObject image, JsonArray bufferViews, GltfBuffers buffers,
                                         int index, String mimeType, String element,
                                         List<ModelImage> images, Set<String> usedNames)
            throws ModelParseException {
        int viewIndex = image.requireInt("bufferView");
        if (bufferViews == null || viewIndex < 0 || viewIndex >= bufferViews.size()) {
            throw new ModelParseException("bufferView " + viewIndex + " out of range (bufferViews: "
                    + (bufferViews == null ? 0 : bufferViews.size()) + ")");
        }
        JsonObject view = bufferViews.requireObject(viewIndex);
        int viewOffset = view.getInt("byteOffset", 0);
        int viewLength = view.requireInt("byteLength");
        GltfBuffers.Buffer buffer = buffers.require(view.requireInt("buffer"), element);
        if (viewOffset < 0 || viewLength <= 0
                || (long) viewOffset + viewLength > buffer.declaredLength()) {
            throw new ModelParseException("bufferView " + viewIndex + " spans bytes [" + viewOffset
                    + ", " + ((long) viewOffset + viewLength) + ") but buffer "
                    + view.requireInt("buffer") + " (" + buffer.description() + ") is only "
                    + buffer.declaredLength() + " bytes");
        }
        byte[] data = Arrays.copyOfRange(buffer.data(), viewOffset, viewOffset + viewLength);
        ModelImage embedded = new ModelImage(uniqueName(image.getString("name"), index, usedNames), data,
                mimeType);
        images.add(embedded);
        return embedded.texturePath();
    }

    /**
     * The glTF {@code name} is optional and not unique; a collision would make two different textures
     * resolve to one path, so later duplicates get a numeric suffix. Spaces and dots are left alone -
     * this is an identifier matched exactly by {@link ModelImage#find}, never a file path.
     */
    private static String uniqueName(String declared, int index, Set<String> usedNames) {
        String base = declared == null || declared.isBlank() ? "image" + index : declared;
        String name = base;
        int suffix = 2;
        while (!usedNames.add(name)) {
            name = base + "#" + suffix++;
        }
        return name;
    }

    /** Texture path for an image index, or null when the image could not be read. */
    String pathOf(int imageIndex) {
        return imageIndex >= 0 && imageIndex < texturePaths.length ? texturePaths[imageIndex] : null;
    }

    int count() {
        return texturePaths.length;
    }

    /** The embedded images, in file order, for {@code ModelScene}. */
    List<ModelImage> images() {
        return List.copyOf(images);
    }

    /** One warning per model naming every image that could not be read, or nothing when all is well. */
    void warnUnusable(String sourceDescription) {
        if (!unusable.isEmpty()) {
            Model3D.LOGGER.warn("{}: {} image(s) could not be read, so surfaces using them will be "
                    + "untextured: {}", sourceDescription, unusable.size(),
                    String.join("; ", unusable));
        }
    }
}
