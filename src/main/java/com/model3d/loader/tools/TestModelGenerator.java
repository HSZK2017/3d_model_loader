package com.model3d.loader.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the mod's animated test model: a small, <b>skinned</b>, <b>animated</b> GLB.
 *
 * <h2>Why this tool exists rather than a file someone exported once</h2>
 * The third-party corpus in this repository contains no animation and no skin data at all - all
 * five glTF/GLB files have zero {@code animations} and zero {@code skins} keys. So the two features
 * this mod exists for cannot be exercised against any real asset available here, and a hand-made
 * binary blob committed to {@code resources} with no generator would be unreproducible: nobody
 * could tell whether a later failure was a parser regression or a corrupted fixture, and nobody
 * could change the fixture's shape (more joints, a different interpolation mode) to test a
 * hypothesis.
 *
 * <p>Generating it means the fixture is reviewable source, its expected geometry is stated in the
 * test that consumes it, and it can be regenerated after any change:
 * <pre>
 *   gradlew generateTestModel          # writes into src/main/resources
 *   gradlew generateTestModel --args="build/other.glb"
 * </pre>
 *
 * <h2>What it builds</h2>
 * <pre>
 *   bone_root           translation (0, 0, 0)
 *     bone_spinner      translation (0, 0.5, 0)
 *
 *   one mesh, one primitive, 8 vertices / 12 triangles (a cube)
 *   every vertex skinned: 100% to the nearest bone -> two rigid halves that separate visibly
 *   one animation 'spin', LINEAR, 2.0 s: bone_spinner rotation about +Y, 0 -> 180 -> 0 degrees
 *   one animation 'bob',  LINEAR, 1.0 s: bone_spinner translation y 0.5 -> 0.8 -> 0.5
 * </pre>
 *
 * <p>The skin is deliberately rigid-per-vertex (one joint per vertex with weight 1). A blended
 * skin would be a better test of the blending maths but a worse diagnostic: a wrong joint matrix
 * shows up as a hard, obvious discontinuity between the two halves rather than as a subtly
 * distorted cube, and "obvious when wrong" is the whole point of a fixture.
 *
 * <p>The cube's two halves are assigned to the two bones by a plane through y = 0.5, the spinner's
 * own origin, so a rotation of {@code bone_spinner} moves one half and leaves the other still. If
 * the skinning matrices are transposed, off by one, or the inverse-bind matrices are ignored, the
 * halves do not meet where they should and the fixture has failed visibly.
 */
public final class TestModelGenerator {

    /**
     * Where the generator writes by default, relative to the project directory.
     *
     * <p>Under {@code data/}, not {@code assets/}: a dedicated server's resource manager is built
     * with {@code PackType.SERVER_DATA} and serves only {@code data/}, so a model under
     * {@code assets/} would be invisible to the very command meant to validate it. See
     * {@code ModelLocation}'s class comment for the measurement.
     */
    public static final String DEFAULT_OUTPUT =
            "src/main/resources/data/model3d/model3d/animated_test/model.glb";

    /** Y coordinate of the plane separating the two bones' vertices. */
    private static final float JOINT_PLANE_Y = 0.5f;
    private static final float SPINNER_ORIGIN_Y = 0.5f;
    private static final float SPIN_SECONDS = 2.0f;
    private static final float BOB_SECONDS = 1.0f;

    private TestModelGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args.length > 0 ? args[0] : DEFAULT_OUTPUT).toAbsolutePath().normalize();
        byte[] glb = build();
        Files.createDirectories(output.getParent());
        Files.write(output, glb);
        System.out.println("Wrote " + output + " (" + glb.length + " bytes)");
        System.out.println("Animations: spin (" + SPIN_SECONDS + "s), bob (" + BOB_SECONDS + "s)");
    }

    /** Builds the GLB bytes. Exposed so a test can assert on them without touching the filesystem. */
    public static byte[] build() {
        Binary bin = new Binary();
        List<Map<String, Object>> accessors = new ArrayList<>();
        List<Map<String, Object>> bufferViews = new ArrayList<>();

        // A unit cube centred on the origin. Two of the eight corners sit exactly in the plane that
        // separates the bones, which makes the joint assignment unambiguous.
        float[] positions = {
                -0.5f, -0.5f, -0.5f,
                 0.5f, -0.5f, -0.5f,
                 0.5f,  0.5f, -0.5f,
                -0.5f,  0.5f, -0.5f,
                -0.5f, -0.5f,  0.5f,
                 0.5f, -0.5f,  0.5f,
                 0.5f,  0.5f,  0.5f,
                -0.5f,  0.5f,  0.5f,
        };
        float[] normals = {
                 0,  0, -1,  0,  0, -1,  0,  0, -1,  0,  0, -1,
                 0,  0,  1,  0,  0,  1,  0,  0,  1,  0,  0,  1,
        };
        float[] uvs = {
                0, 0, 1, 0, 1, 1, 0, 1,
                0, 0, 1, 0, 1, 1, 0, 1,
        };
        short[] indices = {
                0, 1, 2, 0, 2, 3,
                4, 6, 5, 4, 7, 6,
                0, 4, 5, 0, 5, 1,
                3, 2, 6, 3, 6, 7,
                0, 3, 7, 0, 7, 4,
                1, 5, 6, 1, 6, 2,
        };
        float[] jointIndices = new float[8 * 4];
        float[] jointWeights = new float[8 * 4];
        for (int vertex = 0; vertex < 8; vertex++) {
            float y = positions[vertex * 3 + 1];
            // Below the plane -> bone_root; at or above it -> bone_spinner. Weight 1 to one joint,
            // 0 to the other, so the joint matrix is used verbatim with no blending.
            int joint = y < JOINT_PLANE_Y ? 0 : 1;
            jointIndices[vertex * 4] = joint;
            jointWeights[vertex * 4] = 1.0f;
        }

        int positionAccessor = addAccessor(accessors, bufferViews, bin, positions, "VEC3", 5126, true);
        int normalAccessor = addAccessor(accessors, bufferViews, bin, normals, "VEC3", 5126, true);
        int uvAccessor = addAccessor(accessors, bufferViews, bin, uvs, "VEC2", 5126, true);
        int jointAccessor = addAccessor(accessors, bufferViews, bin, jointIndices, "VEC4", 5126, false);
        int weightAccessor = addAccessor(accessors, bufferViews, bin, jointWeights, "VEC4", 5126, false);
        int indexAccessor = addIndexAccessor(accessors, bufferViews, bin, indices);

        // --- animation samplers -------------------------------------------------------------
        // Times are shared per animation, exactly as glTF exporters emit them.
        int spinTimes = addAccessor(accessors, bufferViews, bin,
                new float[] { 0.0f, SPIN_SECONDS * 0.5f, SPIN_SECONDS }, "SCALAR", 5126, false);
        // 0 deg -> 180 deg -> 360 deg about +Y, as unit quaternions (x, y, z, w). A full turn
        // rather than a half turn so the loop is seamless: a half turn and back would look like a
        // correct animation that happens to reverse, which is not what this fixture claims to test.
        int spinRotations = addAccessor(accessors, bufferViews, bin, new float[] {
                0, 0, 0, 1,
                0, sinHalf((float) Math.PI), 0, cosHalf((float) Math.PI),
                0, sinHalf(2.0f * (float) Math.PI), 0, cosHalf(2.0f * (float) Math.PI),
        }, "VEC4", 5126, false);

        int bobTimes = addAccessor(accessors, bufferViews, bin,
                new float[] { 0.0f, BOB_SECONDS * 0.5f, BOB_SECONDS }, "SCALAR", 5126, false);
        int bobTranslations = addAccessor(accessors, bufferViews, bin, new float[] {
                0, SPINNER_ORIGIN_Y, 0,
                0, SPINNER_ORIGIN_Y + 0.3f, 0,
                0, SPINNER_ORIGIN_Y, 0,
        }, "VEC3", 5126, false);

        int rootNode = 0;
        int spinnerNode = 1;

        Map<String, Object> rootBone = new LinkedHashMap<>();
        rootBone.put("name", "bone_root");
        rootBone.put("translation", new float[] { 0, 0, 0 });
        rootBone.put("children", List.of(spinnerNode));

        Map<String, Object> spinnerBone = new LinkedHashMap<>();
        spinnerBone.put("name", "bone_spinner");
        spinnerBone.put("translation", new float[] { 0, SPINNER_ORIGIN_Y, 0 });
        spinnerBone.put("mesh", 0);
        spinnerBone.put("skin", 0);

        Map<String, Object> skin = new LinkedHashMap<>();
        skin.put("name", "two_bone");
        skin.put("joints", List.of(rootNode, spinnerNode));
        skin.put("skeleton", rootNode);
        skin.put("inverseBindMatrices", addMat4Accessor(accessors, bufferViews, bin, new float[][] {
                identity(),
                // Inverse of `translate(0, SPINNER_ORIGIN_Y, 0)`: the spinner bone's bind-space
                // position is (0, 0.5, 0), so its inverse bind matrix translates the other way.
                // Getting this sign wrong moves the skin twice as far as it should, which the
                // two-half fixture makes visible immediately.
                translation(0, -SPINNER_ORIGIN_Y, 0),
        }));

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("POSITION", positionAccessor);
        attributes.put("NORMAL", normalAccessor);
        attributes.put("TEXCOORD_0", uvAccessor);
        attributes.put("JOINTS_0", jointAccessor);
        attributes.put("WEIGHTS_0", weightAccessor);

        Map<String, Object> primitive = new LinkedHashMap<>();
        primitive.put("attributes", attributes);
        primitive.put("indices", indexAccessor);
        primitive.put("material", 0);
        primitive.put("mode", 4);

        Map<String, Object> mesh = new LinkedHashMap<>();
        mesh.put("name", "cube");
        mesh.put("primitives", List.of(primitive));

        Map<String, Object> material = new LinkedHashMap<>();
        material.put("name", "plain");
        material.put("pbrMetallicRoughness", Map.of(
                "baseColorFactor", new float[] { 0.85f, 0.30f, 0.25f, 1.0f },
                "metallicFactor", 0.0f,
                "roughnessFactor", 0.8f));

        Map<String, Object> spinSampler = new LinkedHashMap<>();
        spinSampler.put("input", spinTimes);
        spinSampler.put("output", spinRotations);
        spinSampler.put("interpolation", "LINEAR");
        Map<String, Object> spinChannel = Map.of(
                "sampler", 0,
                "target", Map.of("node", spinnerNode, "path", "rotation"));
        Map<String, Object> spin = new LinkedHashMap<>();
        spin.put("name", "spin");
        spin.put("samplers", List.of(spinSampler));
        spin.put("channels", List.of(spinChannel));

        Map<String, Object> bobSampler = new LinkedHashMap<>();
        bobSampler.put("input", bobTimes);
        bobSampler.put("output", bobTranslations);
        bobSampler.put("interpolation", "LINEAR");
        Map<String, Object> bobChannel = Map.of(
                "sampler", 0,
                "target", Map.of("node", spinnerNode, "path", "translation"));
        Map<String, Object> bob = new LinkedHashMap<>();
        bob.put("name", "bob");
        bob.put("samplers", List.of(bobSampler));
        bob.put("channels", List.of(bobChannel));

        Map<String, Object> gltf = new LinkedHashMap<>();
        gltf.put("asset", Map.of("version", "2.0", "generator",
                "Model3D TestModelGenerator (synthetic skinned animation fixture)"));
        gltf.put("scene", 0);
        gltf.put("scenes", List.of(Map.of("name", "test", "nodes", List.of(rootNode))));
        gltf.put("nodes", List.of(rootBone, spinnerBone));
        gltf.put("skins", List.of(skin));
        gltf.put("meshes", List.of(mesh));
        gltf.put("materials", List.of(material));
        gltf.put("animations", List.of(spin, bob));
        gltf.put("accessors", accessors);
        gltf.put("bufferViews", bin.bufferViews());
        gltf.put("buffers", List.of(Map.of("byteLength", bin.size())));

        return packGlb(toJson(gltf).getBytes(StandardCharsets.UTF_8), bin.toByteArray());
    }

    private static float sinHalf(float angle) {
        return (float) Math.sin(angle * 0.5);
    }

    private static float cosHalf(float angle) {
        return (float) Math.cos(angle * 0.5);
    }

    private static float[] identity() {
        return new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };
    }

    private static float[] translation(float x, float y, float z) {
        return new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1 };
    }

    // ------------------------------------------------------------------
    // glTF assembly helpers
    // ------------------------------------------------------------------

    private static int addAccessor(List<Map<String, Object>> accessors,
                                   List<Map<String, Object>> bufferViews, Binary bin,
                                   float[] data, String type, int componentType, boolean withMinMax) {
        int components = switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            // MAT4 is here for the skin's inverse bind matrices: 16 floats per element, in
            // column-major order, exactly as glTF stores them.
            case "MAT4" -> 16;
            default -> throw new IllegalArgumentException("unsupported type " + type);
        };
        int count = data.length / components;
        ByteBuffer buffer = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(data);
        int view = bin.add(buffer.array(), 4);

        Map<String, Object> accessor = new LinkedHashMap<>();
        accessor.put("bufferView", view);
        accessor.put("componentType", componentType);
        accessor.put("count", count);
        accessor.put("type", type);
        if (withMinMax) {
            // The spec requires min/max on POSITION accessors. This generator is a producer, so it
            // emits them for every float accessor it can - it is what a real exporter does, and a
            // generator that skips them would not exercise the same parser path as real files.
            float[] min = new float[components];
            float[] max = new float[components];
            java.util.Arrays.fill(min, Float.POSITIVE_INFINITY);
            java.util.Arrays.fill(max, Float.NEGATIVE_INFINITY);
            for (int i = 0; i < count; i++) {
                for (int c = 0; c < components; c++) {
                    float value = data[i * components + c];
                    min[c] = Math.min(min[c], value);
                    max[c] = Math.max(max[c], value);
                }
            }
            accessor.put("min", min);
            accessor.put("max", max);
        }
        accessors.add(accessor);
        return accessors.size() - 1;
    }

    private static int addMat4Accessor(List<Map<String, Object>> accessors,
                                       List<Map<String, Object>> bufferViews, Binary bin,
                                       float[][] matrices) {
        float[] flat = new float[matrices.length * 16];
        for (int i = 0; i < matrices.length; i++) {
            System.arraycopy(matrices[i], 0, flat, i * 16, 16);
        }
        return addAccessor(accessors, bufferViews, bin, flat, "MAT4", 5126, false);
    }

    private static int addIndexAccessor(List<Map<String, Object>> accessors,
                                        List<Map<String, Object>> bufferViews, Binary bin,
                                        short[] indices) {
        ByteBuffer buffer = ByteBuffer.allocate(indices.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asShortBuffer().put(indices);
        int view = bin.add(buffer.array(), 4);
        Map<String, Object> accessor = new LinkedHashMap<>();
        accessor.put("bufferView", view);
        accessor.put("componentType", 5123);
        accessor.put("count", indices.length);
        accessor.put("type", "SCALAR");
        accessors.add(accessor);
        return accessors.size() - 1;
    }

    /**
     * Accumulates the binary blob and the matching {@code bufferViews}.
     *
     * <p>One bufferView per attribute array, each with its own {@code byteOffset}: real exporters
     * share bufferViews and use accessor {@code byteOffset}s plus {@code byteStride}, and the
     * parser is tested for that separately. This fixture deliberately uses the simple
     * one-view-per-array layout so that a failure here means the skinning or animation maths is
     * wrong, not that the accessor arithmetic is - a fixture should isolate one variable.
     */
    private static final class Binary {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final List<Map<String, Object>> bufferViews = new ArrayList<>();

        /** Appends {@code data} at a {@code 4}-byte boundary and records a bufferView for it. */
        int add(byte[] data, int alignment) {
            int pad = (alignment - (out.size() % alignment)) % alignment;
            for (int i = 0; i < pad; i++) {
                out.write(0);
            }
            int offset = out.size();
            out.write(data, 0, data.length);
            bufferViews.add(Map.of("buffer", 0, "byteOffset", offset, "byteLength", data.length));
            return bufferViews.size() - 1;
        }

        List<Map<String, Object>> bufferViews() {
            return bufferViews;
        }

        int size() {
            return out.size();
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    private static byte[] packGlb(byte[] json, byte[] bin) {
        int jsonPad = (4 - (json.length % 4)) % 4;
        int binPad = (4 - (bin.length % 4)) % 4;
        int jsonChunkLength = json.length + jsonPad;
        int binChunkLength = bin.length + binPad;
        int total = 12 + 8 + jsonChunkLength + 8 + binChunkLength;

        ByteBuffer glb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67); // "glTF"
        glb.putInt(2);
        glb.putInt(total);

        glb.putInt(jsonChunkLength);
        glb.putInt(0x4E4F534A); // "JSON"
        glb.put(json);
        for (int i = 0; i < jsonPad; i++) {
            glb.put((byte) 0x20); // the spec pads a JSON chunk with spaces
        }

        glb.putInt(binChunkLength);
        glb.putInt(0x004E4942); // "BIN\0"
        glb.put(bin);
        for (int i = 0; i < binPad; i++) {
            glb.put((byte) 0);
        }
        return glb.array();
    }

    // ------------------------------------------------------------------
    // A minimal JSON writer
    // ------------------------------------------------------------------
    // The glTF parser's own JSON reader lives in the format package and is a *reader*; using it here
    // would mean this generator could not be run before the parser compiles. A generator that
    // depends on the thing it helps test is a circular dependency in the build.

    @SuppressWarnings("unchecked")
    private static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(entry.getKey())).append(':').append(toJson(entry.getValue()));
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(toJson(list.get(i)));
            }
            sb.append(']');
        } else if (value instanceof float[] array) {
            float[] floats = array;
            sb.append('[');
            for (int i = 0; i < floats.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(formatFloat(floats[i]));
            }
            sb.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append(quote(value.toString()));
        }
        return sb.toString();
    }

    private static String formatFloat(float value) {
        // Float.toString round-trips exactly, which matters for the quaternions and the inverse
        // bind matrix: a lossy format would make the fixture's expected values approximate and
        // turn an exact test assertion into a tolerance guess.
        if (value == Math.rint(value) && Math.abs(value) < 1e7f) {
            return Integer.toString((int) value);
        }
        return Float.toString(value);
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 2);
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
