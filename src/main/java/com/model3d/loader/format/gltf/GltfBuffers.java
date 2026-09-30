package com.model3d.loader.format.gltf;

import com.model3d.loader.Model3D;
import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.format.ModelSource;
import com.model3d.loader.format.json.JsonArray;
import com.model3d.loader.format.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;

/**
 * The {@code buffers[]} of a glTF file, resolved to bytes.
 *
 * <p>A buffer is one of three things and the container decides which:
 * <ul>
 *   <li>the BIN chunk of a {@code .glb}, referenced by <b>omitting</b> {@code uri} on
 *       {@code buffers[0]} - the spec allows that for that one index only;</li>
 *   <li>a sibling file, addressed by a relative {@code uri} through {@link ModelSource};</li>
 *   <li>an inline {@code data:} URI, either base64 or percent-encoded raw bytes.</li>
 * </ul>
 *
 * <p>{@code byteLength} is treated as a claim to be checked, not a fact: the resolved bytes must be
 * at least that long. They may be longer - a GLB BIN chunk is padded to a 4-byte boundary, and the
 * real corpus file {@code pbr_sukhoi_su-30.glb} declares 42 783 799 bytes in a 42 783 800-byte
 * chunk - so the check is {@code >=}, and the declared length is what every later range check uses.
 */
final class GltfBuffers {

    /** One resolved buffer. {@code data} may be longer than {@code declaredLength} (padding). */
    record Buffer(byte[] data, int declaredLength, String description) {
    }

    private final Buffer[] buffers;

    private GltfBuffers(Buffer[] buffers) {
        this.buffers = buffers;
    }

    /** Buffer {@code index}, or a parse failure naming {@code element} when it does not exist. */
    Buffer require(int index, String element) throws ModelParseException {
        if (index < 0 || index >= buffers.length) {
            throw ModelParseException.at(element, "buffer " + index + " out of range (buffers: "
                    + buffers.length + ")");
        }
        return buffers[index];
    }

    /**
     * Resolves buffers for a {@code .glb}: {@code buffers[0]} without a uri is the BIN chunk, and any
     * buffer <i>with</i> a uri is resolved through the source exactly as in a text file.
     */
    static GltfBuffers fromGlb(JsonObject root, byte[] bin, ModelSource source)
            throws ModelParseException {
        JsonArray declared = root.getArray("buffers");
        if (declared == null) {
            return new GltfBuffers(new Buffer[0]);
        }
        Buffer[] resolved = new Buffer[declared.size()];
        for (int i = 0; i < resolved.length; i++) {
            String element = "buffers[" + i + "]";
            JsonObject buffer = declared.requireObject(i);
            int byteLength = buffer.requireInt("byteLength");
            String uri = buffer.getString("uri");
            if (uri == null) {
                if (i != 0) {
                    throw ModelParseException.at(element, "a buffer without a uri is only allowed at "
                            + "index 0 of a .glb, where it refers to the BIN chunk");
                }
                if (bin == null) {
                    throw ModelParseException.at(element, "the file declares a buffer without a uri "
                            + "but contains no BIN chunk");
                }
                resolved[i] = new Buffer(bin, checkLength(bin.length, byteLength, element,
                        "the GLB BIN chunk"), "the GLB BIN chunk");
                continue;
            }
            resolved[i] = resolve(uri, byteLength, element, source);
        }
        return new GltfBuffers(resolved);
    }

    /** Resolves buffers for a text {@code .gltf}: every buffer must carry a usable uri. */
    static GltfBuffers fromFiles(JsonObject root, ModelSource source) throws ModelParseException {
        JsonArray declared = root.getArray("buffers");
        if (declared == null) {
            return new GltfBuffers(new Buffer[0]);
        }
        Buffer[] resolved = new Buffer[declared.size()];
        for (int i = 0; i < resolved.length; i++) {
            String element = "buffers[" + i + "]";
            JsonObject buffer = declared.requireObject(i);
            int byteLength = buffer.requireInt("byteLength");
            String uri = buffer.getString("uri");
            if (uri == null) {
                throw ModelParseException.at(element, "no uri; a buffer without a uri is only valid "
                        + "for buffers[0] of a .glb, where it refers to the BIN chunk");
            }
            resolved[i] = resolve(uri, byteLength, element, source);
        }
        return new GltfBuffers(resolved);
    }

    private static Buffer resolve(String uri, int byteLength, String element, ModelSource source)
            throws ModelParseException {
        if (Uris.isDataUri(uri)) {
            byte[] data = Uris.decodeDataUri(uri, element);
            return new Buffer(data, checkLength(data.length, byteLength, element, "the data uri"),
                    "a data uri of " + data.length + " bytes");
        }
        String path = Uris.decodePercent(uri, element);
        String reason = Uris.unsupportedReason(path);
        if (reason != null) {
            throw ModelParseException.at(element, reason);
        }
        ModelSource.Reference reference;
        try {
            reference = source.open(path);
        } catch (IOException e) {
            throw ModelParseException.at(element, "cannot open '" + path + "': " + e.getMessage(), e);
        }
        if (!reference.isPresent()) {
            throw ModelParseException.at(element, "cannot read '" + path + "': " + reference.note());
        }
        if (!reference.exact()) {
            Model3D.LOGGER.debug("glTF buffer '{}' resolved as '{}' ({})", path,
                    reference.resolvedAs(), reference.note());
        }
        byte[] data = readFully(reference.data(), element + " ('" + path + "')");
        return new Buffer(data, checkLength(data.length, byteLength, element, "'" + path + "'"),
                "'" + reference.resolvedAs() + "'");
    }

    private static int checkLength(int available, int declared, String element, String what)
            throws ModelParseException {
        if (declared < 0) {
            throw ModelParseException.at(element, "byteLength " + declared + " is negative");
        }
        if (available < declared) {
            throw ModelParseException.at(element, "declares byteLength " + declared + " but " + what
                    + " provides only " + available + " bytes");
        }
        return declared;
    }

    private static byte[] readFully(InputStream in, String where) throws ModelParseException {
        try (InputStream stream = in) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw ModelParseException.at(where, "cannot read the buffer: " + e.getMessage(), e);
        }
    }
}
