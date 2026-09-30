package com.model3d.loader.format.gltf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Byte-level fixtures for the glTF tests: a little-endian append-only binary buffer and a .glb
 * assembler, so a test that asserts "interleaved data is read with byteStride" can spell out the
 * exact bytes instead of embedding an opaque blob.
 */
final class TestData {

    private TestData() {
    }

    /** Little-endian append-only binary buffer; every append returns the byte offset it landed at. */
    static final class Bin {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        int size() {
            return out.size();
        }

        int floats(float... values) {
            int offset = out.size();
            for (float value : values) {
                int bits = Float.floatToIntBits(value);
                out.write(bits & 0xFF);
                out.write((bits >>> 8) & 0xFF);
                out.write((bits >>> 16) & 0xFF);
                out.write((bits >>> 24) & 0xFF);
            }
            return offset;
        }

        int bytes(int... values) {
            int offset = out.size();
            for (int value : values) {
                out.write(value & 0xFF);
            }
            return offset;
        }

        int shorts(int... values) {
            int offset = out.size();
            for (int value : values) {
                out.write(value & 0xFF);
                out.write((value >>> 8) & 0xFF);
            }
            return offset;
        }

        int pad(int count, int value) {
            int offset = out.size();
            for (int i = 0; i < count; i++) {
                out.write(value);
            }
            return offset;
        }

        byte[] toArray() {
            return out.toByteArray();
        }

        /** The buffer as a base64 {@code data:} URI. */
        String asBase64DataUri() {
            return "data:application/octet-stream;base64,"
                    + Base64.getEncoder().encodeToString(toArray());
        }

        /** The buffer as a percent-encoded (non-base64) {@code data:} URI. */
        String asPercentEncodedDataUri() {
            StringBuilder text = new StringBuilder("data:application/octet-stream,");
            for (byte value : toArray()) {
                text.append('%').append(String.format("%02X", value & 0xFF));
            }
            return text.toString();
        }
    }

    static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    /** A well-formed GLB: header, padded JSON chunk, optional padded BIN chunk. */
    static byte[] glb(String json, byte[] bin) {
        byte[] jsonChunk = pad(json.getBytes(StandardCharsets.UTF_8), 0x20);
        byte[] binChunk = bin == null ? null : pad(bin, 0x00);
        int total = 12 + 8 + jsonChunk.length + (binChunk == null ? 0 : 8 + binChunk.length);
        byte[] out = new byte[total];
        putInt(out, 0, GlbContainer.MAGIC_GLTF);
        putInt(out, 4, GlbContainer.VERSION_2);
        putInt(out, 8, total);
        int offset = 12;
        putInt(out, offset, jsonChunk.length);
        putInt(out, offset + 4, GlbContainer.CHUNK_JSON);
        System.arraycopy(jsonChunk, 0, out, offset + 8, jsonChunk.length);
        offset += 8 + jsonChunk.length;
        if (binChunk != null) {
            putInt(out, offset, binChunk.length);
            putInt(out, offset + 4, GlbContainer.CHUNK_BIN);
            System.arraycopy(binChunk, 0, out, offset + 8, binChunk.length);
        }
        return out;
    }

    /** One raw chunk: {@code [length][type][payload]}, no padding. */
    static byte[] chunk(int type, byte[] payload) {
        byte[] out = new byte[8 + payload.length];
        putInt(out, 0, payload.length);
        putInt(out, 4, type);
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    /** A 12-byte glTF header declaring {@code totalLength}, with no validation of that claim. */
    static byte[] header(int magic, int version, int totalLength) {
        byte[] out = new byte[12];
        putInt(out, 0, magic);
        putInt(out, 4, version);
        putInt(out, 8, totalLength);
        return out;
    }

    private static byte[] pad(byte[] data, int paddingByte) {
        int remainder = data.length % 4;
        if (remainder == 0) {
            return data;
        }
        byte[] out = new byte[data.length + (4 - remainder)];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = data.length; i < out.length; i++) {
            out[i] = (byte) paddingByte;
        }
        return out;
    }
}
