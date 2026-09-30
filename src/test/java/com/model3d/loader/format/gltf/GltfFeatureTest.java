package com.model3d.loader.format.gltf;

import com.model3d.loader.math.Mat4;
import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.scene.ModelSkin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature tests for the glTF reader, on hand-written files whose bytes are spelled out in the test.
 *
 * <p>Every one of these covers something the licensed corpus cannot: the corpus files have no
 * animations, no skins, no sparse accessors, no normalized attributes and no triangle strips. Writing
 * their bytes here is the only way to show those code paths work rather than hoping they do.
 */
class GltfFeatureTest {

    private static ModelScene parse(String json, TestData.Bin bin) throws Exception {
        MemoryModelSource source = MemoryModelSource.gltf(json);
        if (bin != null) {
            source.with("scene.bin", bin.toArray());
        }
        return new GltfParser().parse(source, "test");
    }

    private static ModelPrimitive onlyPrimitive(ModelScene scene) {
        return scene.meshes()[0].primitives()[0];
    }

    /** Renders a float array as JSON, so a test can assert against the very matrix it wrote. */
    private static String array(float[] values) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(values[i]);
        }
        return text.append(']').toString();
    }

    @Test
    @DisplayName("interleaved vertex data is read through byteStride, not as if it were packed")
    void readsInterleavedVertexData() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        // 32-byte stride: position (12 bytes), normal (12 bytes), then 8 bytes of a second UV set this
        // test deliberately does not read. Reading tightly would make vertex 1's position its normal.
        bin.floats(1, 2, 3, 0, 1, 0, 9, 9);
        bin.floats(4, 5, 6, 1, 0, 0, 9, 9);
        bin.floats(7, 8, 9, 0, 0, 1, 9, 9);
        int indices = bin.shorts(0, 1, 2);

        ModelScene scene = parse("""
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "scene.bin" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0, "byteLength": 96, "byteStride": 32, "target": 34962 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 6, "target": 34963 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "byteOffset": 0, "componentType": 5126, "type": "VEC3", "count": 3 },
                    { "bufferView": 0, "byteOffset": 12, "componentType": 5126, "type": "VEC3", "count": 3 },
                    { "bufferView": 1, "componentType": 5123, "type": "SCALAR", "count": 3 }
                  ],
                  "meshes": [ { "name": "M", "primitives": [
                    { "attributes": { "POSITION": 0, "NORMAL": 1 }, "indices": 2 } ] } ],
                  "nodes": [ { "mesh": 0 } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """.formatted(bin.size(), indices), bin);

        ModelPrimitive primitive = onlyPrimitive(scene);
        assertArrayEquals(new float[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }, primitive.positions(), 0f,
                "byteStride was ignored: vertex 1 read as (0,1,0) would be its normal");
        assertArrayEquals(new float[] { 0, 1, 0, 1, 0, 0, 0, 0, 1 }, primitive.normals(), 0f);
        assertArrayEquals(new short[] { 0, 1, 2 }, primitive.indices());
        assertEquals(3, primitive.vertexCount());
    }

    @Test
    @DisplayName("sparse accessors override individual elements, including over a zero base")
    void appliesSparseOverrides() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        int base = bin.floats(0, 0, 0, 10, 10, 10, 20, 20, 20, 30, 30, 30);
        int sparseIndex = bin.shorts(2);
        bin.pad(2, 0);
        int sparseValue = bin.floats(100, 200, 300);
        int normalIndex = bin.shorts(0, 3);
        int normalValue = bin.floats(0, 1, 0, 0, 0, 1);
        int indices = bin.shorts(0, 1, 2, 3);

        ModelScene scene = parse("""
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "scene.bin" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": %d, "byteLength": 48, "target": 34962 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 2 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 12 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 4 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 24 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 8, "target": 34963 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "type": "VEC3", "count": 4,
                      "sparse": { "count": 1,
                        "indices": { "bufferView": 1, "componentType": 5123 },
                        "values": { "bufferView": 2 } } },
                    { "componentType": 5126, "type": "VEC3", "count": 4,
                      "sparse": { "count": 2,
                        "indices": { "bufferView": 3, "componentType": 5123 },
                        "values": { "bufferView": 4 } } },
                    { "bufferView": 5, "componentType": 5123, "type": "SCALAR", "count": 4 }
                  ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0, "NORMAL": 1 },
                    "indices": 2, "mode": 5 } ] } ],
                  "nodes": [ { "mesh": 0 } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """.formatted(bin.size(), base, sparseIndex, sparseValue, normalIndex, normalValue,
                indices), bin);

        ModelPrimitive primitive = onlyPrimitive(scene);
        assertArrayEquals(new float[] { 0, 0, 0, 10, 10, 10, 100, 200, 300, 30, 30, 30 },
                primitive.positions(), 0f, "element 2 should be the sparse override, not the base value");
        // No bufferView at all: the accessor starts as zeros and only the two sparse values exist.
        assertArrayEquals(new float[] { 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 }, primitive.normals(), 0f);
        // TRIANGLE_STRIP over four vertices becomes two triangles.
        assertArrayEquals(new short[] { 0, 1, 2, 2, 1, 3 }, primitive.indices());
    }

    @Test
    @DisplayName("a primitive without indices draws its vertices in order")
    void supportsNonIndexedPrimitives() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0);

        ModelScene scene = parse("""
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "scene.bin" } ],
                  "bufferViews": [ { "buffer": 0, "byteLength": %d } ],
                  "accessors": [ { "bufferView": 0, "componentType": 5126, "type": "VEC3", "count": 3 } ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 } } ] } ],
                  "nodes": [ { "mesh": 0 } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """.formatted(bin.size(), bin.size()), bin);

        ModelPrimitive primitive = onlyPrimitive(scene);
        assertArrayEquals(new short[] { 0, 1, 2 }, primitive.indices());
        assertArrayEquals(new float[] { 0, 0, 0, 1, 0, 0, 0, 1, 0 }, primitive.positions(), 0f);
        assertEquals(3, primitive.indexCount());
    }

    @Test
    @DisplayName("normalized integer attributes divide by the type maximum and clamp the signed minimum")
    void normalizesIntegerAttributes() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        int unsignedNormals = bin.bytes(0, 0, 255, 128, 0, 0, 255, 0, 128, 255, 255, 0);
        int signedNormals = bin.bytes(-128, 127, 0, 0, 0, 0, 64, -64, 0, 0, -128, 127);
        int positions = bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1);
        int indices = bin.shorts(0, 1, 2, 0, 1, 3);

        ModelScene scene = parse("""
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "scene.bin" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": %d, "byteLength": 12 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 12 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 48 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 12 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5121, "normalized": true, "type": "VEC3",
                      "count": 4 },
                    { "bufferView": 1, "componentType": 5120, "normalized": true, "type": "VEC3",
                      "count": 4 },
                    { "bufferView": 2, "componentType": 5126, "type": "VEC3", "count": 4 },
                    { "bufferView": 3, "componentType": 5123, "type": "SCALAR", "count": 6 }
                  ],
                  "meshes": [ { "primitives": [
                    { "attributes": { "POSITION": 2, "NORMAL": 0 }, "indices": 3 },
                    { "attributes": { "POSITION": 2, "NORMAL": 1 }, "indices": 3 } ] } ],
                  "nodes": [ { "mesh": 0 } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """.formatted(bin.size(), unsignedNormals, signedNormals, positions, indices), bin);

        ModelPrimitive unsigned = scene.meshes()[0].primitives()[0];
        assertArrayEquals(new float[] { 0f, 0f, 1f, 128f / 255f, 0f, 0f, 1f, 0f, 128f / 255f, 1f, 1f, 0f },
                unsigned.normals(), 1e-6f);

        ModelPrimitive signed = scene.meshes()[0].primitives()[1];
        // -128 / 127 is -1.0079; the spec's normalized form clamps it to exactly -1.
        assertArrayEquals(new float[] { -1f, 1f, 0f, 0f, 0f, 0f, 64f / 127f, -64f / 127f, 0f, 0f, -1f,
                1f }, signed.normals(), 1e-6f);
    }

    @Test
    @DisplayName("TRIANGLE_STRIP and TRIANGLE_FAN become triangle lists with correct winding")
    void convertsTriangleStripsAndFans() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        int positions = bin.floats(0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 2, 2, 0);
        int strip = bin.shorts(0, 1, 2, 3);
        int stitching = bin.shorts(0, 1, 2, 2, 3, 4);
        int fan = bin.shorts(0, 1, 2, 3, 4);
        int triangles = bin.shorts(0, 1, 2, 3, 2, 4);

        ModelScene scene = parse("""
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "scene.bin" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": %d, "byteLength": 60 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 8 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 12 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 10 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 12 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "type": "VEC3", "count": 5 },
                    { "bufferView": 1, "componentType": 5123, "type": "SCALAR", "count": 4 },
                    { "bufferView": 2, "componentType": 5123, "type": "SCALAR", "count": 6 },
                    { "bufferView": 3, "componentType": 5123, "type": "SCALAR", "count": 5 },
                    { "bufferView": 4, "componentType": 5123, "type": "SCALAR", "count": 6 }
                  ],
                  "meshes": [ { "primitives": [
                    { "attributes": { "POSITION": 0 }, "indices": 1, "mode": 5 },
                    { "attributes": { "POSITION": 0 }, "indices": 2, "mode": 5 },
                    { "attributes": { "POSITION": 0 }, "indices": 3, "mode": 6 },
                    { "attributes": { "POSITION": 0 }, "indices": 4, "mode": 4 },
                    { "attributes": { "POSITION": 0 }, "mode": 5 } ] } ],
                  "nodes": [ { "mesh": 0 } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """.formatted(bin.size(), positions, strip, stitching, fan, triangles), bin);

        ModelPrimitive[] primitives = scene.meshes()[0].primitives();
        // Odd triangles swap their first two indices, which is what keeps the winding consistent.
        assertArrayEquals(new short[] { 0, 1, 2, 2, 1, 3 }, primitives[0].indices());
        // A degenerate triangle (the strip-stitching idiom) contributes no geometry.
        assertArrayEquals(new short[] { 0, 1, 2, 3, 2, 4 }, primitives[1].indices());
        // A fan of five vertices is three triangles, all sharing vertex 0.
        assertArrayEquals(new short[] { 0, 1, 2, 0, 2, 3, 0, 3, 4 }, primitives[2].indices());
        assertEquals(3, primitives[2].indexCount() / 3);
        assertArrayEquals(new short[] { 0, 1, 2, 3, 2, 4 }, primitives[3].indices());
        // A non-indexed strip gets the synthesized 0..n-1 sequence, then the same conversion.
        assertArrayEquals(new short[] { 0, 1, 2, 2, 1, 3, 2, 3, 4 }, primitives[4].indices());
        assertEquals(3, primitives[4].indexCount() / 3);
        assertEquals(5, primitives[4].vertexCount());
    }

    @Test
    @DisplayName("an accessor byteOffset may start late in its view without being read as an overrun")
    void doesNotDoubleCountTheAccessorOffsetInRangeChecks() throws Exception {
        // Regression test for a real bug: `required` is measured from the accessor's own offset while
        // `start` is already absolute, so comparing `start + required` against the buffer counted the
        // accessor offset twice and rejected 22 legal accessors of models/sukhoi_su-30_flanker_c/
        // scene.gltf. Here the read ends at 186 of a 200-byte buffer, but the buggy sum is 236.
        TestData.Bin bin = new TestData.Bin();
        bin.pad(150, 0);
        int positions = bin.floats(1, 2, 3, 4, 5, 6, 7, 8, 9);
        bin.pad(200 - bin.size(), 0);

        ModelScene scene = parse("""
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "scene.bin" } ],
                  "bufferViews": [ { "buffer": 0, "byteOffset": 100, "byteLength": 100 } ],
                  "accessors": [ { "bufferView": 0, "byteOffset": 50, "componentType": 5126,
                    "type": "VEC3", "count": 3 } ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 } } ] } ],
                  "nodes": [ { "mesh": 0 } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """.formatted(bin.size()), bin);

        assertEquals(150, positions);
        assertArrayEquals(new float[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }, onlyPrimitive(scene).positions(),
                0f);
    }

    @Test
    @DisplayName("a node matrix is decomposed into TRS that reproduces the matrix exactly")
    void decomposesNodeMatrices() throws Exception {
        // Node 3 of the corpus model: non-uniform scale, a 180-degree rotation about X and a
        // translation - the case where dropping the matrix collapses the aircraft into its origin.
        float[] sketchfab = { 0.05351f, 0, 0, 0, 0, -0.1223f, 0, 0, 0, 0, -0.23447f, 0,
                -8.76325f, 2.04827f, 0.00003f, 1 };
        // A negative determinant cannot be written as a rotation; it has to become a negative scale.
        float[] mirror = { -2, 0, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0, 5, 6, 7, 1 };
        float[] quarterTurn = { 0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

        String json = """
                {
                  "asset": { "version": "2.0" },
                  "nodes": [
                    { "name": "sketchfab", "matrix": %s },
                    { "name": "mirror", "matrix": %s },
                    { "name": "quarterTurn", "matrix": %s }
                  ],
                  "accessors": [ { "componentType": 5126, "type": "VEC3", "count": 3 } ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 } } ] } ],
                  "scenes": [ { "nodes": [ 0, 1, 2 ] } ],
                  "scene": 0
                }
                """.formatted(array(sketchfab), array(mirror), array(quarterTurn));
        ModelScene scene = parse(json, null);

        ModelNode node = scene.nodeTemplates()[0];
        assertArrayEquals(new float[] { -8.76325f, 2.04827f, 0.00003f }, node.restTranslationArray(),
                1e-6f);
        assertArrayEquals(new float[] { 0.05351f, 0.1223f, 0.23447f }, node.restScaleArray(), 1e-6f);
        assertReproduces(node, sketchfab, 1e-5f);
        assertArrayEquals(new float[] { 1f, 0f, 0f, 0f }, signedIdentical(node.restRotationArray()),
                1e-5f);

        ModelNode mirrored = scene.nodeTemplates()[1];
        assertArrayEquals(new float[] { -2f, 3f, 4f }, mirrored.restScaleArray(), 1e-6f);
        assertReproduces(mirrored, mirror, 1e-5f);

        ModelNode turned = scene.nodeTemplates()[2];
        assertArrayEquals(new float[] { 0f, 0f, 0.70710678f, 0.70710678f },
                signedIdentical(turned.restRotationArray()), 1e-6f);
        assertReproduces(turned, quarterTurn, 1e-5f);
    }

    /**
     * The decomposition is only useful if composing it back yields the matrix the file carried -
     * comparing against the file's own numbers, not against {@code ModelNode}'s rest transform,
     * which is built from the same decomposition and would make the check tautological.
     */
    private static void assertReproduces(ModelNode node, float[] fileMatrix, float delta) {
        Mat4 recomposed = Mat4.compose(node.restTranslationArray(), node.restRotationArray(),
                node.restScaleArray());
        assertEquals(0f, recomposed.maxDifference(new Mat4(fileMatrix)), delta,
                "compose(TRS) should reproduce the node's matrix");
    }

    @Test
    @DisplayName("a non-axis rotation with a non-uniform scale recomposes exactly")
    void composesNonUniformScaleWithANonAxisRotation() throws Exception {
        // 90 degrees about +Y with scale (2, 1, 4). This is the case that separates R * S from
        // S * R: for a diagonal rotation the two orders coincide, which is why every fixture above
        // (and the corpus) passed while compose() scaled rows instead of columns.
        //
        // Written as the product itself: column 0 is R * (2,0,0), column 1 is R * (0,1,0),
        // column 2 is R * (0,0,4), and the translation is the last column.
        float[] mixed = { 0f, 0f, -2f, 0f,
                          0f, 1f, 0f, 0f,
                          4f, 0f, 0f, 0f,
                          5f, 6f, 7f, 1f };
        String json = """
                {
                  "asset": { "version": "2.0" },
                  "nodes": [ { "name": "mixed", "matrix": %s } ],
                  "accessors": [ { "componentType": 5126, "type": "VEC3", "count": 3 } ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 } } ] } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """.formatted(array(mixed));
        ModelScene scene = parse(json, null);

        ModelNode node = scene.nodeTemplates()[0];
        System.out.println("mixed: " + node.restLocalTransform()
                + " scale=" + java.util.Arrays.toString(node.restScaleArray()));
        assertArrayEquals(new float[] { 2f, 1f, 4f }, node.restScaleArray(), 1e-5f,
                "the scale factors are the column lengths");
        assertReproduces(node, mixed, 1e-5f);
    }

    /** Quaternion q and -q are the same rotation; canonicalise so the assertion can be exact. */
    private static float[] signedIdentical(float[] quaternion) {
        float[] out = quaternion.clone();
        if (out[3] < 0) {
            for (int i = 0; i < 4; i++) {
                out[i] = -out[i];
            }
        }
        return out;
    }

    @Test
    @DisplayName("CUBICSPLINE keeps three values per keyframe; rotation tangents are downgraded")
    void readsCubicSplineKeyframes() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        int times3 = bin.floats(0, 1, 2);
        int times2 = bin.floats(0, 2);
        int translationValues = bin.floats(
                0, 0, 0, 0, 0, 0, 1, 0, 0,
                2, 0, 0, 10, 0, 0, 3, 0, 0,
                4, 0, 0, 20, 0, 0, 0, 0, 0);
        int rotationValues = bin.floats(0, 0, 0, 1, 0, 0, 0.70710678f, 0.70710678f);
        int cubicRotationValues = bin.floats(
                0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0.1f, 0.99f,
                0, 0, 0, 1, 0, 0, 0.70710678f, 0.70710678f, 0, 0, 0.1f, 0.99f,
                0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 1);

        ModelScene scene = parse("""
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "scene.bin" } ],
                  "bufferViews": [ { "buffer": 0, "byteLength": %d } ],
                  "accessors": [
                    { "bufferView": 0, "byteOffset": %d, "componentType": 5126, "type": "SCALAR",
                      "count": 3 },
                    { "bufferView": 0, "byteOffset": %d, "componentType": 5126, "type": "SCALAR",
                      "count": 2 },
                    { "bufferView": 0, "byteOffset": %d, "componentType": 5126, "type": "VEC3",
                      "count": 9 },
                    { "bufferView": 0, "byteOffset": %d, "componentType": 5126, "type": "VEC4",
                      "count": 2 },
                    { "bufferView": 0, "byteOffset": %d, "componentType": 5126, "type": "VEC4",
                      "count": 9 },
                    { "componentType": 5126, "type": "VEC3", "count": 3 }
                  ],
                  "animations": [ { "name": "Flight", "channels": [
                    { "sampler": 0, "target": { "node": 0, "path": "translation" } },
                    { "sampler": 1, "target": { "node": 0, "path": "rotation" } },
                    { "sampler": 2, "target": { "node": 1, "path": "rotation" } } ],
                    "samplers": [
                      { "input": 0, "output": 2, "interpolation": "CUBICSPLINE" },
                      { "input": 1, "output": 3, "interpolation": "LINEAR" },
                      { "input": 0, "output": 4, "interpolation": "CUBICSPLINE" } ] } ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 5 } } ] } ],
                  "nodes": [ { "mesh": 0 }, { "name": "other" } ],
                  "scenes": [ { "nodes": [ 0, 1 ] } ],
                  "scene": 0
                }
                """.formatted(bin.size(), bin.size(), times3, times2, translationValues,
                rotationValues, cubicRotationValues), bin);

        assertEquals(1, scene.animations().size());
        ModelAnimation animation = scene.animations().get(0);
        assertEquals("Flight", animation.name());
        assertEquals(3, animation.trackCount());
        // Shared arrays: every track's keyframe times concatenated, in channel order.
        assertArrayEquals(new float[] { 0, 1, 2, 0, 2, 0, 1, 2 }, animation.times(), 0f);
        assertEquals(2.0f, animation.duration(), 1e-6f);

        ModelAnimation.Track translation = animation.tracks()[0];
        assertEquals(0, translation.firstKeyframe());
        assertEquals(3, translation.keyframeCount());
        assertEquals(0, translation.valueOffset());
        assertEquals(3, translation.valueComponents());
        assertEquals(3, translation.componentsPerKey());
        assertEquals(ModelAnimation.Interpolation.CUBICSPLINE, translation.interpolation());
        assertEquals(ModelAnimation.Path.TRANSLATION, translation.path());
        assertEquals(0, translation.targetNode());
        assertEquals(2.0f, translation.lastKeyframeTime(animation), 1e-6f);
        float[] values = animation.values();
        assertEquals(47, values.length);
        // In-tangent, value, out-tangent for keyframe 1 -> the middle triple is the real value.
        assertArrayEquals(new float[] { 10, 0, 0 }, Arrays.copyOfRange(values, 12, 15), 0f);

        ModelAnimation.Track rotation = animation.tracks()[1];
        assertEquals(3, rotation.firstKeyframe());
        assertEquals(2, rotation.keyframeCount());
        assertEquals(27, rotation.valueOffset());
        assertEquals(4, rotation.valueComponents());
        assertEquals(1, rotation.componentsPerKey());
        assertEquals(ModelAnimation.Interpolation.LINEAR, rotation.interpolation());
        assertArrayEquals(new float[] { 0, 0, 0.70710678f, 0.70710678f },
                Arrays.copyOfRange(values, 31, 35), 1e-6f);

        ModelAnimation.Track cubicRotation = animation.tracks()[2];
        assertEquals(5, cubicRotation.firstKeyframe());
        assertEquals(3, cubicRotation.keyframeCount());
        // glTF rotation tangents are quaternions, which the Hermite form does not apply to: the track
        // is downgraded to LINEAR with the tangents dropped, not silently mis-evaluated.
        assertEquals(ModelAnimation.Interpolation.LINEAR, cubicRotation.interpolation());
        assertEquals(1, cubicRotation.componentsPerKey());
        assertEquals(35, cubicRotation.valueOffset());
        assertEquals(12, cubicRotation.valueComponents() * cubicRotation.keyframeCount());
        // Only the middle value of each in/value/out triple survives the downgrade.
        assertArrayEquals(new float[] { 0, 0, 0, 1 }, Arrays.copyOfRange(values, 35, 39), 0f);
        assertArrayEquals(new float[] { 0, 0, 0.70710678f, 0.70710678f },
                Arrays.copyOfRange(values, 39, 43), 0f);
        assertArrayEquals(new float[] { 0, 1, 0, 0 }, Arrays.copyOfRange(values, 43, 47), 0f);
    }

    @Test
    @DisplayName("materials map texture indices to image paths and get the spec defaults")
    void readsMaterialsAndTextureChain() throws Exception {
        String json = """
                {
                  "asset": { "version": "2.0" },
                  "images": [
                    { "uri": "textures/a.png" },
                    { "uri": "textures/sub dir/b%20c.jpeg" }
                  ],
                  "textures": [ { "source": 0 }, { "source": 1 } ],
                  "materials": [ {
                    "name": "Skin",
                    "pbrMetallicRoughness": {
                      "baseColorFactor": [0.1, 0.2, 0.3, 0.4],
                      "baseColorTexture": { "index": 0 },
                      "metallicFactor": 0.5,
                      "roughnessFactor": 0.75 },
                    "normalTexture": { "index": 1, "scale": 0.5 },
                    "occlusionTexture": { "index": 0, "strength": 0.3 },
                    "emissiveTexture": { "index": 1 },
                    "emissiveFactor": [0.2, 0.3, 0.4],
                    "alphaMode": "MASK",
                    "alphaCutoff": 0.25,
                    "doubleSided": true
                  } ],
                  "accessors": [ { "componentType": 5126, "type": "VEC3", "count": 3 } ],
                  "meshes": [ { "primitives": [
                    { "attributes": { "POSITION": 0 }, "material": 0 },
                    { "attributes": { "POSITION": 0 } } ] } ],
                  "nodes": [ { "mesh": 0 } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """;
        ModelScene scene = parse(json, null);

        // The primitive without a material gets the spec's default material, appended so that the
        // file's own material indices keep their meaning.
        assertEquals(2, scene.materials().length);
        ModelMaterial material = scene.materials()[0];
        assertEquals("Skin", material.name());
        assertArrayEquals(new float[] { 0.1f, 0.2f, 0.3f, 0.4f }, material.baseColorFactor(), 0f);
        assertEquals(0.5f, material.metallicFactor(), 0f);
        assertEquals(0.75f, material.roughnessFactor(), 0f);
        assertEquals("textures/a.png", material.baseColorTexture());
        assertEquals("textures/sub dir/b%20c.jpeg", material.normalTexture());
        assertEquals(0.5f, material.normalScale(), 0f);
        assertEquals("textures/a.png", material.occlusionTexture());
        assertEquals(0.3f, material.occlusionStrength(), 0f);
        assertEquals("textures/sub dir/b%20c.jpeg", material.emissiveTexture());
        assertArrayEquals(new float[] { 0.2f, 0.3f, 0.4f }, material.emissiveFactor(), 0f);
        assertEquals(ModelMaterial.AlphaMode.MASK, material.alphaMode());
        assertEquals(0.25f, material.alphaCutoff(), 0f);
        assertTrue(material.doubleSided());

        ModelMaterial fallback = scene.materials()[1];
        assertEquals("default", fallback.name());
        // The glTF default is metallic 1, roughness 1 - a common source of "why is my model black".
        assertEquals(1.0f, fallback.metallicFactor(), 0f);
        assertEquals(1.0f, fallback.roughnessFactor(), 0f);
        assertArrayEquals(new float[] { 1, 1, 1, 1 }, fallback.baseColorFactor(), 0f);
        assertFalse(fallback.hasAnyTexture());

        ModelPrimitive[] primitives = scene.meshes()[0].primitives();
        assertEquals(0, primitives[0].materialIndex());
        assertEquals(1, primitives[1].materialIndex());
    }

    @Test
    @DisplayName("a bufferView-embedded image becomes a named placeholder, never a dropped texture")
    void reportsEmbeddedImages() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        int image = bin.bytes(0x89, 0x50, 0x4E, 0x47);
        int positions = bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0);

        String json = """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "scene.bin" } ],
                  "bufferViews": [ { "buffer": 0, "byteOffset": %d, "byteLength": 4 },
                                   { "buffer": 0, "byteOffset": %d, "byteLength": 36 } ],
                  "images": [ { "bufferView": 0, "mimeType": "image/png" } ],
                  "textures": [ { "source": 0 } ],
                  "materials": [ { "pbrMetallicRoughness": { "baseColorTexture": { "index": 0 } } } ],
                  "accessors": [ { "bufferView": 1, "componentType": 5126, "type": "VEC3",
                    "count": 3 } ],
                  "meshes": [ { "primitives": [
                    { "attributes": { "POSITION": 0 }, "material": 0 } ] } ],
                  "nodes": [ { "mesh": 0 } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """.formatted(bin.size(), image, positions);
        ModelScene scene = parse(json, bin);

        assertEquals("embedded/image0", scene.materials()[0].baseColorTexture(),
                "an embedded texture must resolve to the reserved scheme, not to a file path");
        assertTrue(scene.materials()[0].hasAnyTexture());
        assertEquals(1, scene.embeddedImages().size());
        assertEquals("image0", scene.embeddedImages().get(0).name(),
                "an image with no name falls back to its index");
        assertArrayEquals(new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 },
                scene.embeddedImages().get(0).data(),
                "the sliced bytes must be the bufferView's, not the whole buffer");
        assertEquals("image/png", scene.embeddedImages().get(0).mimeType());
        assertSame(scene.embeddedImages().get(0),
                scene.embeddedImage(scene.materials()[0].baseColorTexture()));
    }

    @Test
    @DisplayName("nodes are linked into a hierarchy and world transforms compose through it")
    void buildsNodeHierarchy() throws Exception {
        String json = """
                {
                  "asset": { "version": "2.0" },
                  "accessors": [ { "componentType": 5126, "type": "VEC3", "count": 3 } ],
                  "meshes": [ { "name": "Body", "primitives": [ { "attributes": { "POSITION": 0 } } ] } ],
                  "nodes": [
                    { "name": "root", "translation": [1, 0, 0], "children": [1] },
                    { "name": "middle", "translation": [0, 2, 0], "children": [2] },
                    { "name": "leaf", "translation": [0, 0, 3], "mesh": 0 },
                    { "name": "orphan", "mesh": 0 }
                  ],
                  "scenes": [ { "name": "Scene", "nodes": [0] } ],
                  "scene": 0
                }
                """;
        ModelScene scene = parse(json, null);

        assertEquals(4, scene.nodeCount());
        assertArrayEquals(new int[] { 0 }, scene.rootNodes());
        // node 1 is a child of node 0, node 2 of node 1, and node 3 is an orphan.
        assertArrayEquals(new int[] { 0, 1, -1 }, new int[] {
                scene.nodeTemplates()[1].parentIndex(),
                scene.nodeTemplates()[2].parentIndex(),
                scene.nodeTemplates()[3].parentIndex() });
        assertEquals(-1, scene.nodeTemplates()[0].parentIndex());
        assertEquals(-1, scene.nodeTemplates()[3].parentIndex(),
                "the orphan has no parent, but it is not a scene root either");
        assertEquals(1, scene.nodeTemplates()[0].children().size());
        assertEquals(1, scene.nodeTemplates()[1].children().size());
        assertEquals("leaf", scene.nodeTemplates()[2].name());
        assertEquals(0, scene.nodeTemplates()[2].meshIndex());
        assertEquals(-1, scene.nodeTemplates()[1].meshIndex());

        // World transforms are only correct if the walk visits parents before children; the leaf lands
        // at the sum of the three translations.
        ModelNode[] pose = scene.instantiate();
        ModelScene.updateWorldTransforms(pose, scene.rootNodes());
        assertEquals(1.0f, pose[2].globalTransform().get(3, 0), 1e-6f);
        assertEquals(2.0f, pose[2].globalTransform().get(3, 1), 1e-6f);
        assertEquals(3.0f, pose[2].globalTransform().get(3, 2), 1e-6f);
        // The orphan is not reachable from the scene root, so its world transform stays local.
        assertEquals(0.0f, pose[3].globalTransform().get(3, 0), 1e-6f);
    }

    @Test
    @DisplayName("buffer uris are resolved as base64 and as percent-encoded data uris")
    void resolvesBothDataUriForms() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0);
        bin.shorts(0, 1, 2);
        int positions = 0;
        int indices = 36;

        for (String uri : new String[] { bin.asBase64DataUri(), bin.asPercentEncodedDataUri() }) {
            String json = """
                    {
                      "asset": { "version": "2.0" },
                      "buffers": [ { "byteLength": %d, "uri": "%s" } ],
                      "bufferViews": [
                        { "buffer": 0, "byteOffset": %d, "byteLength": 36 },
                        { "buffer": 0, "byteOffset": %d, "byteLength": 6 }
                      ],
                      "accessors": [
                        { "bufferView": 0, "componentType": 5126, "type": "VEC3", "count": 3 },
                        { "bufferView": 1, "componentType": 5123, "type": "SCALAR", "count": 3 }
                      ],
                      "meshes": [ { "primitives": [
                        { "attributes": { "POSITION": 0 }, "indices": 1 } ] } ],
                      "nodes": [ { "mesh": 0 } ],
                      "scenes": [ { "nodes": [ 0 ] } ],
                      "scene": 0
                    }
                    """.formatted(bin.size(), uri, positions, indices);
            ModelScene scene = parse(json, null);
            assertArrayEquals(new float[] { 0, 0, 0, 1, 0, 0, 0, 1, 0 },
                    onlyPrimitive(scene).positions(), 0f);
            assertArrayEquals(new short[] { 0, 1, 2 }, onlyPrimitive(scene).indices());
        }
    }

    @Test
    @DisplayName("more than four influences per vertex keep the four heaviest and renormalize")
    void mergesAdditionalJointSets() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        int positions = bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0);
        int joints0 = bin.bytes(0, 1, 2, 3, 0, 1, 2, 3, 0, 0, 0, 0);
        int weights0 = bin.floats(0.5f, 0.25f, 0.25f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        int joints1 = bin.bytes(4, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0);
        int weights1 = bin.floats(0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f);
        int indices = bin.shorts(0, 1, 2);

        String json = """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "scene.bin" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": %d, "byteLength": 36 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 12 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 48 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 12 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 48 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 6 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "type": "VEC3", "count": 3 },
                    { "bufferView": 1, "componentType": 5121, "type": "VEC4", "count": 3 },
                    { "bufferView": 2, "componentType": 5126, "type": "VEC4", "count": 3 },
                    { "bufferView": 3, "componentType": 5121, "type": "VEC4", "count": 3 },
                    { "bufferView": 4, "componentType": 5126, "type": "VEC4", "count": 3 },
                    { "bufferView": 5, "componentType": 5123, "type": "SCALAR", "count": 3 }
                  ],
                  "skins": [ { "name": "rig", "joints": [0, 1, 2, 3, 4] } ],
                  "meshes": [ { "primitives": [ { "attributes": {
                    "POSITION": 0, "JOINTS_0": 1, "WEIGHTS_0": 2, "JOINTS_1": 3, "WEIGHTS_1": 4 },
                    "indices": 5 } ] } ],
                  "nodes": [
                    { "name": "skinned", "mesh": 0, "skin": 0 },
                    { "name": "j1" }, { "name": "j2" }, { "name": "j3" }, { "name": "j4" }
                  ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """.formatted(bin.size(), positions, joints0, weights0, joints1, weights1, indices);
        ModelScene scene = parse(json, bin);

        ModelPrimitive primitive = onlyPrimitive(scene);
        assertTrue(primitive.isSkinned());
        // Vertex 0: 0.5/0.5/0.25/0.25 over joints 0/4/1/2 sums to 1.5 and is rescaled by 2/3.
        assertArrayEquals(new float[] { 0, 4, 1, 2 }, Arrays.copyOf(primitive.jointIndices(), 4), 0f);
        assertArrayEquals(new float[] { 1f / 3f, 1f / 3f, 1f / 6f, 1f / 6f },
                Arrays.copyOf(primitive.jointWeights(), 4), 1e-6f);
        // Vertex 1: a single influence of 1 needs no rescaling.
        assertArrayEquals(new float[] { 1f, 0f, 0f, 0f },
                Arrays.copyOfRange(primitive.jointWeights(), 4, 8), 0f);
        assertEquals(4, primitive.maxJointIndex());

        assertEquals(1, scene.skins().length);
        ModelSkin skin = scene.skins()[0];
        assertEquals("rig", skin.name());
        assertArrayEquals(new int[] { 0, 1, 2, 3, 4 }, skin.joints());
        // An undefined inverseBindMatrices means identity per the spec, not "no skinning".
        assertEquals(5, skin.inverseBindMatrices().length);
        assertEquals(0f, skin.inverseBindMatrices()[3].maxDifference(Mat4.IDENTITY), 0f);
    }

    @Test
    @DisplayName("weights that do not sum to one are rescaled, not left to collapse the vertex")
    void renormalizesLooseWeights() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        int positions = bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0);
        int joints = bin.bytes(0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0);
        int weights = bin.floats(0.4f, 0.4f, 0f, 0f, 0.2f, 0.2f, 0f, 0f, 0f, 0f, 0f, 0f);

        String json = """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "scene.bin" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": %d, "byteLength": 36 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 12 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 48 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "type": "VEC3", "count": 3 },
                    { "bufferView": 1, "componentType": 5121, "type": "VEC4", "count": 3 },
                    { "bufferView": 2, "componentType": 5126, "type": "VEC4", "count": 3 }
                  ],
                  "skins": [ { "joints": [0, 1] } ],
                  "meshes": [ { "primitives": [ { "attributes": {
                    "POSITION": 0, "JOINTS_0": 1, "WEIGHTS_0": 2 } } ] } ],
                  "nodes": [ { "mesh": 0, "skin": 0 }, { "name": "joint" } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """.formatted(bin.size(), positions, joints, weights);
        ModelScene scene = parse(json, bin);

        float[] stored = onlyPrimitive(scene).jointWeights();
        // Vertex 0 sums to 0.8 and vertex 1 to 0.4; both are rescaled to 0.5/0.5.
        assertArrayEquals(new float[] { 0.5f, 0.5f, 0f, 0f }, Arrays.copyOf(stored, 4), 1e-6f);
        // Vertex 2 has no influence at all, which would collapse it onto the origin.
        assertArrayEquals(new float[] { 1f, 0f, 0f, 0f }, Arrays.copyOfRange(stored, 8, 12), 1e-6f);
        for (int vertex = 0; vertex < 3; vertex++) {
            float sum = 0;
            for (int k = 0; k < 4; k++) {
                sum += stored[vertex * 4 + k];
            }
            assertEquals(1.0f, sum, 1e-6f, "every vertex must end up with weights summing to 1");
        }
    }

    @Test
    @DisplayName("a non-required extension is ignored; the model still loads")
    void ignoresNonRequiredExtensions() throws Exception {
        String json = """
                {
                  "asset": { "version": "2.0" },
                  "extensionsUsed": [ "KHR_materials_emissive_strength", "KHR_texture_transform" ],
                  "accessors": [ { "componentType": 5126, "type": "VEC3", "count": 3 } ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 } } ] } ],
                  "nodes": [ { "mesh": 0 } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """;
        ModelScene scene = parse(json, null);
        assertEquals(1, scene.meshes().length);
        assertNotNull(scene.meshes()[0].primitives()[0].positions());
        assertTrue(scene.animations().isEmpty());
    }

    @Test
    @DisplayName("scene bounds are the union of the primitives' raw positions")
    void computesSceneBounds() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        int first = bin.floats(-1, -2, -3, 4, 5, 6, 0, 0, 0);
        int second = bin.floats(-7, 0, 0, 0, 8, 0, 0, 0, 9);

        String json = """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "scene.bin" } ],
                  "bufferViews": [ { "buffer": 0, "byteLength": %d } ],
                  "accessors": [
                    { "bufferView": 0, "byteOffset": %d, "componentType": 5126, "type": "VEC3",
                      "count": 3 },
                    { "bufferView": 0, "byteOffset": %d, "componentType": 5126, "type": "VEC3",
                      "count": 3 }
                  ],
                  "meshes": [ { "primitives": [
                    { "attributes": { "POSITION": 0 } },
                    { "attributes": { "POSITION": 1 } } ] } ],
                  "nodes": [ { "mesh": 0 } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0
                }
                """.formatted(bin.size(), bin.size(), first, second);
        ModelScene scene = parse(json, bin);

        assertArrayEquals(new float[] { -7, -2, -3, 4, 8, 9 }, scene.bounds(), 0f);
        assertEquals(12.0f, scene.longestExtent(), 0f);
        assertEquals(2, scene.meshes()[0].primitiveCount());
        assertNull(scene.meshes()[0].primitives()[0].normals());
        assertNull(scene.meshes()[0].primitives()[0].uvs());
        assertFalse(scene.isSkinned());
        assertFalse(scene.isAnimated());
    }

    @Test
    @DisplayName("a file may omit scenes and still load through its parentless nodes")
    void fallsBackToParentlessRoots() throws Exception {
        String json = """
                {
                  "asset": { "version": "2.0" },
                  "accessors": [ { "componentType": 5126, "type": "VEC3", "count": 3 } ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 } } ] } ],
                  "nodes": [ { "mesh": 0 }, { "name": "loose", "children": [2] }, { "mesh": 0 } ]
                }
                """;
        ModelScene scene = parse(json, null);
        assertArrayEquals(new int[] { 0, 1 }, scene.rootNodes());
        // node 2 is a child of node 1, so only nodes 0 and 1 can be scene roots.
        assertEquals(1, scene.nodeTemplates()[2].parentIndex());
    }
}
