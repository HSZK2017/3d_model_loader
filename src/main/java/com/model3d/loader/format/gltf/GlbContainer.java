package com.model3d.loader.format.gltf;

import com.model3d.loader.Model3D;
import com.model3d.loader.format.ModelParseException;

import java.io.IOException;
import java.io.InputStream;

/**
 * The container half of binary glTF: the 12-byte header, then a sequence of {@code [length][type]}
 * chunks.
 *
 * <p>Every failure names the <b>byte offset</b> it was detected at. A {@code .glb} is a binary
 * format with no line numbers and the JSON chunk is frequently the smaller half of the file, so
 * "byte 2884: JSON chunk declares 22112 bytes but only 2048 remain before the declared end of file"
 * is the only kind of message that can be acted on.
 *
 * <p>Three checks the spec requires and that a lenient reader gets wrong in a way nobody notices
 * until the geometry is subtly shifted:
 * <ul>
 *   <li>the header {@code length} must equal the actual file length - a truncated download otherwise
 *       parses up to the point where it runs out and returns a partial model;</li>
 *   <li>chunk 0 must be the JSON chunk, and there must be exactly one of them;</li>
 *   <li>a chunk's declared length may not run past the end of the file.</li>
 * </ul>
 *
 * <p>Unknown chunk types are skipped with a debug note rather than refused: the spec requires
 * readers to ignore chunks they do not know, and future glTF revisions add them.
 */
final class GlbContainer {

    static final int MAGIC_GLTF = 0x46546C67;
    static final int VERSION_2 = 2;
    static final int CHUNK_JSON = 0x4E4F534A;
    static final int CHUNK_BIN = 0x004E4942;

    private final byte[] json;
    private final byte[] bin;

    private GlbContainer(byte[] json, byte[] bin) {
        this.json = json;
        this.bin = bin;
    }

    byte[] json() {
        return json;
    }

    /** The BIN chunk, or null when the file has none (legal: a .glb may embed only JSON). */
    byte[] bin() {
        return bin;
    }

    static GlbContainer read(InputStream in, String path) throws ModelParseException {
        byte[] file = readAll(in, path);
        if (file.length < 12) {
            throw ModelParseException.at(path, "byte 0: the file is " + file.length
                    + " bytes, too short for the 12-byte glTF header");
        }
        int magic = readInt(file, 0);
        if (magic != MAGIC_GLTF) {
            throw ModelParseException.at(path, "byte 0: bad magic 0x" + Integer.toHexString(magic)
                    + " (expected 0x46546C67 'glTF'); this is not a binary glTF file (a text .gltf "
                    + "is JSON, not a container)");
        }
        int version = readInt(file, 4);
        if (version != VERSION_2) {
            throw ModelParseException.at(path, "byte 4: container version " + version
                    + " is not supported; this reader implements glTF 2.0");
        }
        long declaredLength = readInt(file, 8) & 0xFFFFFFFFL;
        if (declaredLength != file.length) {
            throw ModelParseException.at(path, "byte 8: the header declares a total length of "
                    + declaredLength + " bytes but the file is " + file.length
                    + " bytes (truncated download, or trailing data)");
        }

        byte[] jsonChunk = null;
        byte[] binChunk = null;
        int offset = 12;
        while (offset < file.length) {
            if (offset + 8 > file.length) {
                throw ModelParseException.at(path, "byte " + offset + ": a chunk header needs 8 bytes "
                        + "but only " + (file.length - offset) + " remain");
            }
            long chunkLength = readInt(file, offset) & 0xFFFFFFFFL;
            int chunkType = readInt(file, offset + 4);
            long dataStart = offset + 8L;
            if (dataStart + chunkLength > file.length) {
                throw ModelParseException.at(path, "byte " + offset + ": the chunk of type 0x"
                        + Integer.toHexString(chunkType) + " declares " + chunkLength
                        + " bytes, but only " + (file.length - dataStart)
                        + " remain before the declared end of file");
            }
            int start = (int) dataStart;
            int length = (int) chunkLength;
            if (chunkType == CHUNK_JSON) {
                if (jsonChunk != null) {
                    throw ModelParseException.at(path, "byte " + offset
                            + ": a second JSON chunk; a .glb must contain exactly one");
                }
                if (offset != 12) {
                    throw ModelParseException.at(path, "byte " + offset + ": the JSON chunk must be "
                            + "the first chunk of the file");
                }
                jsonChunk = slice(file, start, length);
            } else if (chunkType == CHUNK_BIN) {
                if (binChunk != null) {
                    throw ModelParseException.at(path, "byte " + offset
                            + ": a second BIN chunk; a .glb may contain at most one");
                }
                binChunk = slice(file, start, length);
            } else {
                Model3D.LOGGER.debug("{}: skipping unknown GLB chunk type 0x{} of {} bytes at byte {}",
                        path, Integer.toHexString(chunkType), length, offset);
            }
            offset += 8 + length;
        }
        if (jsonChunk == null) {
            throw ModelParseException.at(path, "byte 12: no JSON chunk found; a .glb must contain one");
        }
        if (jsonChunk.length == 0) {
            throw ModelParseException.at(path, "byte 20: the JSON chunk is empty");
        }
        return new GlbContainer(jsonChunk, binChunk);
    }

    private static byte[] slice(byte[] file, int start, int length) {
        byte[] out = new byte[length];
        System.arraycopy(file, start, out, 0, length);
        return out;
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static byte[] readAll(InputStream in, String path) throws ModelParseException {
        if (in == null) {
            throw ModelParseException.at(path, "the model file could not be opened");
        }
        try (InputStream stream = in) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw ModelParseException.at(path, "cannot read the model file: " + e.getMessage(), e);
        }
    }

    /** Name given to the JSON chunk in parse failures, so a line/column can be located in the file. */
    static String jsonChunkName(String path) {
        return path + " JSON chunk";
    }
}
