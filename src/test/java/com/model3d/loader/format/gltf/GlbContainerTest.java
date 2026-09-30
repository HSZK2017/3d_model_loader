package com.model3d.loader.format.gltf;

import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.math.Mat4;
import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.scene.ModelSkin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Container tests for {@code .glb}: the header/chunk walk and the BIN chunk as a buffer.
 *
 * <p>The malformed cases matter more than they look. A {@code .glb} has no line numbers and its JSON
 * is often the smaller half of the file, so the only way a header failure can be acted on is if it
 * names the byte offset at which the walk gave up. Each test below therefore asserts the offset as
 * well as the reason.
 *
 * <p>The last test parses a complete synthetic {@code .glb} with a skin and two animations, which is
 * the only end-to-end coverage of the skinned/animated path through the GLB container: no file in
 * the licensed corpus has a skin or an animation at all.
 */
class GlbContainerTest {

    private static final String MINIMAL_JSON = """
            { "asset": { "version": "2.0" },
              "accessors": [ { "componentType": 5126, "type": "VEC3", "count": 3 } ],
              "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 } } ] } ],
              "nodes": [ { "mesh": 0 } ],
              "scenes": [ { "nodes": [ 0 ] } ],
              "scene": 0 }
            """;

    private static ModelScene parseGlb(byte[] file) throws Exception {
        return new GlbParser().parse(MemoryModelSource.binary("model.glb", file), "model.glb");
    }

    private static ModelParseException failure(byte[] file) {
        return assertThrows(ModelParseException.class, () -> parseGlb(file));
    }

    @Test
    @DisplayName("a JSON-only .glb with no BIN chunk parses")
    void acceptsJsonOnlyGlb() throws Exception {
        ModelScene scene = parseGlb(TestData.glb(MINIMAL_JSON, null));
        assertEquals(1, scene.meshes().length);
        assertEquals(3, scene.meshes()[0].primitives()[0].vertexCount());
    }

    @Test
    @DisplayName("a bad magic number is reported with its byte offset")
    void rejectsBadMagic() {
        byte[] file = TestData.glb(MINIMAL_JSON, null);
        TestData.putInt(file, 0, 0x12345678);
        ModelParseException error = failure(file);
        assertTrue(error.getMessage().contains("byte 0: bad magic 0x12345678"), error.getMessage());
        assertTrue(error.getMessage().contains("0x46546C67"), error.getMessage());
    }

    @Test
    @DisplayName("glTF container version 1 is refused, not guessed at")
    void rejectsWrongVersion() {
        byte[] file = TestData.glb(MINIMAL_JSON, null);
        TestData.putInt(file, 4, 1);
        ModelParseException error = failure(file);
        assertTrue(error.getMessage().contains("byte 4: container version 1 is not supported"),
                error.getMessage());
    }

    @Test
    @DisplayName("a header length that disagrees with the file is refused")
    void rejectsInconsistentLength() {
        byte[] file = TestData.glb(MINIMAL_JSON, null);
        TestData.putInt(file, 8, file.length + 100);
        ModelParseException error = failure(file);
        assertTrue(error.getMessage().contains("byte 8: the header declares a total length of "
                + (file.length + 100)), error.getMessage());

        byte[] truncated = new byte[file.length - 4];
        System.arraycopy(file, 0, truncated, 0, truncated.length);
        ModelParseException error2 = failure(truncated);
        assertTrue(error2.getMessage().contains("byte 8:"), error2.getMessage());
        assertTrue(error2.getMessage().contains("the file is " + truncated.length + " bytes"),
                error2.getMessage());
    }

    @Test
    @DisplayName("a file shorter than the header is refused before anything is read")
    void rejectsShortFile() {
        ModelParseException error = failure(new byte[] { 0x67, 0x6C, 0x54, 0x46, 2, 0, 0, 0 });
        assertTrue(error.getMessage().contains("byte 0: the file is 8 bytes, too short for the "
                + "12-byte glTF header"), error.getMessage());
    }

    @Test
    @DisplayName("a chunk that runs past the declared end names the chunk's byte offset")
    void rejectsChunkOverrun() {
        byte[] payload = new byte[8];
        byte[] chunk = TestData.chunk(GlbContainer.CHUNK_JSON, payload);
        TestData.putInt(chunk, 0, 100);
        byte[] file = TestData.concat(TestData.header(GlbContainer.MAGIC_GLTF, 2, 12 + chunk.length),
                chunk);
        ModelParseException error = failure(file);
        assertTrue(error.getMessage().contains("byte 12: the chunk of type 0x4e4f534a declares 100 "
                + "bytes, but only 8 remain"), error.getMessage());
    }

    @Test
    @DisplayName("a truncated chunk header names the byte where it stopped")
    void rejectsTruncatedChunkHeader() {
        byte[] file = TestData.concat(
                TestData.header(GlbContainer.MAGIC_GLTF, 2, 12 + 4), new byte[4]);
        ModelParseException error = failure(file);
        assertTrue(error.getMessage().contains("byte 12: a chunk header needs 8 bytes but only 4 "
                + "remain"), error.getMessage());
    }

    @Test
    @DisplayName("a .glb without a JSON chunk, or with a misplaced one, is refused")
    void rejectsMissingOrMisplacedJsonChunk() {
        byte[] binOnly = TestData.concat(
                TestData.header(GlbContainer.MAGIC_GLTF, 2, 12 + 8),
                TestData.chunk(GlbContainer.CHUNK_BIN, new byte[0]));
        ModelParseException missing = failure(binOnly);
        assertTrue(missing.getMessage().contains("no JSON chunk found"), missing.getMessage());

        byte[] jsonChunk = TestData.chunk(GlbContainer.CHUNK_JSON, "{}".getBytes());
        byte[] binChunk = TestData.chunk(GlbContainer.CHUNK_BIN, new byte[0]);
        byte[] swapped = TestData.concat(
                TestData.header(GlbContainer.MAGIC_GLTF, 2, 12 + jsonChunk.length + binChunk.length),
                binChunk, jsonChunk);
        ModelParseException misplaced = failure(swapped);
        assertTrue(misplaced.getMessage().contains("the JSON chunk must be the first chunk"),
                misplaced.getMessage());

        byte[] duplicated = TestData.concat(
                TestData.header(GlbContainer.MAGIC_GLTF, 2, 12 + 2 * jsonChunk.length),
                jsonChunk, jsonChunk);
        ModelParseException twice = failure(duplicated);
        assertTrue(twice.getMessage().contains("a second JSON chunk"), twice.getMessage());
    }

    @Test
    @DisplayName("an unknown chunk type is skipped, as the spec requires of readers")
    void skipsUnknownChunks() throws Exception {
        byte[] json = TestData.chunk(GlbContainer.CHUNK_JSON, MINIMAL_JSON.getBytes());
        byte[] unknown = TestData.chunk(0x12345678, new byte[] { 1, 2, 3, 4 });
        byte[] file = TestData.concat(
                TestData.header(GlbContainer.MAGIC_GLTF, 2, 12 + json.length + unknown.length),
                json, unknown);
        ModelScene scene = parseGlb(file);
        assertEquals(1, scene.meshes().length);
    }

    @Test
    @DisplayName("buffers[0] without a uri needs a BIN chunk, and the BIN may be padded")
    void resolvesBinChunkIncludingPadding() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0);
        String json = """
                { "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": 36 } ],
                  "bufferViews": [ { "buffer": 0, "byteLength": 36 } ],
                  "accessors": [ { "bufferView": 0, "componentType": 5126, "type": "VEC3",
                    "count": 3 } ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 } } ] } ],
                  "nodes": [ { "mesh": 0 } ],
                  "scenes": [ { "nodes": [ 0 ] } ],
                  "scene": 0 }
                """;
        ModelScene scene = parseGlb(TestData.glb(json, bin.toArray()));
        assertArrayEquals(new float[] { 0, 0, 0, 1, 0, 0, 0, 1, 0 },
                scene.meshes()[0].primitives()[0].positions(), 0f);

        // No BIN chunk at all: the buffer cannot be resolved, and that is named.
        ModelParseException error = failure(TestData.glb(json, null));
        assertTrue(error.getMessage().contains("contains no BIN chunk"), error.getMessage());

        // The BIN chunk shorter than byteLength is an error, not a silent short read.
        ModelParseException shortChunk = failure(TestData.glb(json, new byte[16]));
        assertTrue(shortChunk.getMessage().contains("provides only 16 bytes"),
                shortChunk.getMessage());
    }

    @Test
    @DisplayName("a complete skinned, animated GLB parses: skin, inverse bind matrices and two clips")
    void parsesSkinnedAnimatedGlb() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        // 3 vertices of a triangle, plus four bone-space positions to keep the data honest.
        int positions = bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0);
        int joints = bin.bytes(0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0);
        int weights = bin.floats(1, 0, 0, 0, 0, 1, 0, 0, 0.5f, 0.5f, 0f, 0f);
        int indices = bin.shorts(0, 1, 2);
        // Two inverse bind matrices: identity and a translation of -1 on X.
        int inverseBind = bin.floats(
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -1, 0, 0, 1);
        int spinTimes = bin.floats(0, 1, 2, 3);
        int spinValues = bin.floats(0, 0, 0, 1, 0, 0, 0.70710678f, 0.70710678f, 0, 0, 1, 0, 0, 0, 0.70710678f,
                -0.70710678f);
        int bobTimes = bin.floats(0, 0.5f, 1);
        int bobValues = bin.floats(0, 0, 0, 0, 1, 0, 0, 0, 0);

        String json = """
                { "asset": { "version": "2.0", "generator": "test" },
                  "buffers": [ { "byteLength": %d } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": %d, "byteLength": 36, "target": 34962 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 12, "target": 34962 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 48, "target": 34962 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 6, "target": 34963 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 128 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 16 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 64 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 12 },
                    { "buffer": 0, "byteOffset": %d, "byteLength": 36 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "type": "VEC3", "count": 3 },
                    { "bufferView": 1, "componentType": 5121, "type": "VEC4", "count": 3 },
                    { "bufferView": 2, "componentType": 5126, "type": "VEC4", "count": 3 },
                    { "bufferView": 3, "componentType": 5123, "type": "SCALAR", "count": 3 },
                    { "bufferView": 4, "componentType": 5126, "type": "MAT4", "count": 2 },
                    { "bufferView": 5, "componentType": 5126, "type": "SCALAR", "count": 4 },
                    { "bufferView": 6, "componentType": 5126, "type": "VEC4", "count": 4 },
                    { "bufferView": 7, "componentType": 5126, "type": "SCALAR", "count": 3 },
                    { "bufferView": 8, "componentType": 5126, "type": "VEC3", "count": 3 }
                  ],
                  "skins": [ { "name": "two_bone", "joints": [0, 1],
                    "inverseBindMatrices": 4, "skeleton": 0 } ],
                  "animations": [
                    { "name": "spin", "channels": [ { "sampler": 0,
                      "target": { "node": 1, "path": "rotation" } } ],
                      "samplers": [ { "input": 5, "output": 6, "interpolation": "LINEAR" } ] },
                    { "name": "bob", "channels": [ { "sampler": 0,
                      "target": { "node": 1, "path": "translation" } } ],
                      "samplers": [ { "input": 7, "output": 8, "interpolation": "STEP" } ] } ],
                  "meshes": [ { "name": "hull", "primitives": [ { "attributes": {
                    "POSITION": 0, "JOINTS_0": 1, "WEIGHTS_0": 2 }, "indices": 3 } ] } ],
                  "nodes": [ { "name": "root", "children": [1] },
                             { "name": "bone", "mesh": 0, "skin": 0 } ],
                  "scenes": [ { "name": "Scene", "nodes": [ 0 ] } ],
                  "scene": 0 }
                """.formatted(bin.size(), positions, joints, weights, indices, inverseBind, spinTimes,
                spinValues, bobTimes, bobValues);

        ModelScene scene = parseGlb(TestData.glb(json, bin.toArray()));
        Corpus.printInventory("synthetic skinned GLB", "GLB", scene);

        assertEquals(2, scene.nodeCount());
        assertArrayEquals(new int[] { 0 }, scene.rootNodes());
        assertEquals(1, scene.skins().length);
        assertTrue(scene.isSkinned());
        assertTrue(scene.isAnimated());

        ModelSkin skin = scene.skins()[0];
        assertEquals("two_bone", skin.name());
        assertArrayEquals(new int[] { 0, 1 }, skin.joints());
        assertEquals(0, skin.skeletonRoot());
        assertEquals(2, skin.inverseBindMatrices().length);
        assertEquals(0f, skin.inverseBindMatrices()[0].maxDifference(Mat4.IDENTITY), 0f);
        assertEquals(-1f, skin.inverseBindMatrices()[1].get(3, 0), 1e-6f);
        assertEquals(0, scene.nodeTemplates()[1].skinIndex(),
                "the node names skin 0, the only skin in the file");

        ModelPrimitive primitive = scene.meshes()[0].primitives()[0];
        assertTrue(primitive.isSkinned());
        assertArrayEquals(new float[] { 1, 0, 0, 0 }, java.util.Arrays.copyOf(primitive.jointWeights(), 4),
                0f);
        // Influences are stored heaviest-first, so the vertex bound only to joint 1 has joint 1 in
        // slot 0 - the slot order is not the file's JOINTS order, and must not be.
        assertArrayEquals(new float[] { 1, 0, 0, 0 },
                java.util.Arrays.copyOfRange(primitive.jointIndices(), 4, 8), 0f);
        assertArrayEquals(new float[] { 0, 1, 0, 0 },
                java.util.Arrays.copyOfRange(primitive.jointIndices(), 8, 12), 0f);
        assertEquals(1, primitive.maxJointIndex());

        assertEquals(2, scene.animations().size());
        ModelAnimation spin = scene.animation("spin");
        assertEquals(1, spin.trackCount());
        assertEquals(3.0f, spin.duration(), 1e-6f);
        assertEquals(ModelAnimation.Path.ROTATION, spin.tracks()[0].path());
        assertEquals(ModelAnimation.Interpolation.LINEAR, spin.tracks()[0].interpolation());
        assertEquals(1, spin.tracks()[0].targetNode());
        assertEquals(4, spin.tracks()[0].valueComponents());
        assertArrayEquals(new float[] { 0, 1, 2, 3 }, spin.times(), 0f);

        ModelAnimation bob = scene.animation("bob");
        assertEquals(1.0f, bob.duration(), 1e-6f);
        assertEquals(ModelAnimation.Interpolation.STEP, bob.tracks()[0].interpolation());
        assertEquals(3, bob.times().length);
        assertEquals(9, bob.values().length);
        // The two clips share no state: their times live in separate arrays.
        assertArrayEquals(new float[] { 0, 0.5f, 1 }, bob.times(), 0f);
    }
}
