package com.model3d.loader.format.gltf;

import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.scene.ModelScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure tests: every malformed construct must produce a {@link ModelParseException} that names the
 * element, and every non-fatal construct must produce a scene instead.
 *
 * <p>The distinction is the point of the whole loader. "Which accessor, in which primitive, of which
 * mesh?" is what turns an invisible entity into a fixable file; and a texture that cannot be read
 * must cost the user a texture, never the model.
 */
class GltfErrorTest {

    private static ModelScene parse(String json, TestData.Bin bin) throws Exception {
        MemoryModelSource source = MemoryModelSource.gltf(json);
        if (bin != null) {
            source.with("scene.bin", bin.toArray());
        }
        return new GltfParser().parse(source, "test");
    }

    /** Minimal single-mesh, single-node document around {@code members} (accessors, meshes, ...). */
    private static String document(String members) {
        return "{\"asset\":{\"version\":\"2.0\"}," + members
                + ",\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],\"scene\":0}";
    }

    private static ModelParseException failure(String json, TestData.Bin bin) {
        return assertThrows(ModelParseException.class, () -> parse(json, bin),
                "expected a parse failure for: " + json);
    }

    private static void assertFails(String json, String fragment) {
        ModelParseException error = failure(json, null);
        assertTrue(error.getMessage().contains(fragment),
                "expected message to mention '" + fragment + "' but was: " + error.getMessage());
    }

    private static void assertFails(String json, TestData.Bin bin, String fragment) {
        ModelParseException error = failure(json, bin);
        assertTrue(error.getMessage().contains(fragment),
                "expected message to mention '" + fragment + "' but was: " + error.getMessage());
    }

    @Test
    @DisplayName("an accessor pointing outside bufferViews[] names the accessor and the view")
    void reportsAccessorWithOutOfRangeBufferView() {
        // The example from the task: "accessor[7]: bufferView 3 out of range".
        ModelParseException error = failure(document("""
                "accessors":[{"bufferView":3,"componentType":5126,"type":"VEC3","count":3}],
                "bufferViews":[{"buffer":0,"byteLength":36}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), null);
        assertTrue(error.getMessage().contains("accessor[0]"), error.getMessage());
        assertTrue(error.getMessage().contains("bufferView 3 out of range"), error.getMessage());
        assertTrue(error.getMessage().contains("bufferViews: 1"), error.getMessage());
        assertTrue(error.getMessage().contains("meshes[0]"), "should name the requesting primitive: "
                + error.getMessage());
    }

    @Test
    @DisplayName("an accessor that needs more bytes than its view holds says how many")
    void reportsAccessorTooLargeForItsView() {
        TestData.Bin bin = new TestData.Bin();
        bin.floats(0, 0, 0);
        assertFails(document("""
                "accessors":[{"bufferView":0,"componentType":5126,"type":"VEC3","count":4}],
                "bufferViews":[{"buffer":0,"byteLength":12}],
                "buffers":[{"byteLength":12,"uri":"scene.bin"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), bin, "needs 48 bytes from bufferView 0 but the view provides 12");
    }

    @Test
    @DisplayName("an index outside the vertex array is named, not clamped")
    void reportsIndexOutsideVertexArray() {
        TestData.Bin bin = new TestData.Bin();
        bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0);
        bin.shorts(0, 1, 9);
        assertFails(document("""
                "accessors":[
                  {"bufferView":0,"componentType":5126,"type":"VEC3","count":3},
                  {"bufferView":1,"componentType":5123,"type":"SCALAR","count":3}],
                "bufferViews":[{"buffer":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],
                "buffers":[{"byteLength":42,"uri":"scene.bin"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]
                """), bin, "is 9, outside the 3 vertices");
    }

    @Test
    @DisplayName("a float index accessor is refused rather than coerced")
    void refusesFloatIndices() {
        TestData.Bin bin = new TestData.Bin();
        bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 2);
        assertFails(document("""
                "accessors":[
                  {"bufferView":0,"componentType":5126,"type":"VEC3","count":3},
                  {"bufferView":0,"byteOffset":36,"componentType":5126,"type":"SCALAR","count":3}],
                "bufferViews":[{"buffer":0,"byteLength":48}],
                "buffers":[{"byteLength":48,"uri":"scene.bin"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]
                """), bin, "indices must use an unsigned integer componentType");
    }

    @Test
    @DisplayName("primitive attribute problems name the attribute and the mismatch")
    void reportsAttributeProblems() {
        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "meshes":[{"primitives":[{"attributes":{"NORMAL":0}}]}]
                """), "missing required attribute POSITION");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC2","count":3}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "POSITION accessor[0] is VEC2 but VEC3 is required");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3},
                             {"componentType":5126,"type":"VEC3","count":4}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1}}]}]
                """), "NORMAL accessor[1] has 4 elements but POSITION has 3");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0,"JOINTS_0":0}}]}]
                """), "JOINTS_0 is present without its counterpart WEIGHTS_0");
    }

    @Test
    @DisplayName("unsupported accessor layouts are refused with the spec reason")
    void reportsAccessorLayoutProblems() {
        assertFails(document("""
                "accessors":[{"componentType":5130,"type":"VEC3","count":3}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "unknown componentType 5130");

        assertFails(document("""
                "accessors":[{"componentType":5125,"normalized":true,"type":"VEC3","count":3}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "normalized is not allowed on an UNSIGNED_INT accessor");

        TestData.Bin bin = new TestData.Bin();
        bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0);
        assertFails(document("""
                "accessors":[{"bufferView":0,"componentType":5126,"type":"VEC3","count":3}],
                "bufferViews":[{"buffer":0,"byteLength":36,"byteStride":8}],
                "buffers":[{"byteLength":36,"uri":"scene.bin"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), bin, "declares byteStride 8 but one element needs 12 bytes");
    }

    @Test
    @DisplayName("an accessor type that does not match its attribute slot is named")
    void reportsAttributeTypeMismatch() {
        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3},
                             {"componentType":5126,"type":"VEC5","count":3}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1}}]}]
                """), "NORMAL accessor[1] is VEC5 but VEC3 is required");
    }

    @Test
    @DisplayName("sparse accessor problems are reported against the sparse object")
    void reportsSparseProblems() {
        TestData.Bin bin = new TestData.Bin();
        bin.floats(0, 0, 0, 1, 0, 0, 0, 1, 0);
        bin.shorts(9);
        bin.pad(2, 0);
        bin.floats(1, 1, 1);
        assertFails(document("""
                "accessors":[{"bufferView":0,"componentType":5126,"type":"VEC3","count":3,
                  "sparse":{"count":1,"indices":{"bufferView":1,"componentType":5123},
                  "values":{"bufferView":2}}}],
                "bufferViews":[{"buffer":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":2},
                  {"buffer":0,"byteOffset":40,"byteLength":12}],
                "buffers":[{"byteLength":52,"uri":"scene.bin"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), bin, "sparse index 9 (element 0) is out of range for an accessor of 3 elements");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3,
                  "sparse":{"count":9,"indices":{"bufferView":0,"componentType":5123},
                  "values":{"bufferView":0}}}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "count 9 exceeds the accessor's element count 3");
    }

    @Test
    @DisplayName("non-triangle primitive modes are refused, naming the mode")
    void refusesNonTriangleModes() {
        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"mode":1}]}]
                """), "mode 1 (LINES) is not supported");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"mode":0}]}]
                """), "mode 0 (POINTS) is not supported");
    }

    @Test
    @DisplayName("document-level problems name the version, the extension or the missing section")
    void reportsDocumentProblems() {
        assertFails("{\"asset\":{\"version\":\"1.0\"},\"meshes\":[],\"nodes\":[],\"scenes\":[]}",
                "asset.version '1.0' is not glTF 2.0");
        assertFails("{\"meshes\":[],\"nodes\":[],\"scenes\":[]}", "no 'asset' object");

        assertFails("{\"asset\":{\"version\":\"2.0\"},"
                        + "\"extensionsRequired\":[\"KHR_draco_mesh_compression\"],"
                        + "\"meshes\":[],\"nodes\":[]}",
                "'KHR_draco_mesh_compression' is required by this file but not supported");

        assertFails("{\"asset\":{\"version\":\"2.0\"},\"nodes\":[]}", "declares no meshes");
        assertFails("{\"asset\":{\"version\":\"2.0\"},"
                        + "\"accessors\":[{\"componentType\":5126,\"type\":\"VEC3\",\"count\":3}],"
                        + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}]}",
                "declares no nodes");
    }

    @Test
    @DisplayName("buffer reference problems name the buffer, the uri or the escape")
    void reportsBufferProblems() {
        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "buffers":[{"byteLength":36,"uri":"../outside.bin"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "escapes the model's own directory");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "buffers":[{"byteLength":36,"uri":"https://example.com/model.bin"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "uses the scheme 'https'");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "buffers":[{"byteLength":36,"uri":"scene.bin"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "cannot read 'scene.bin'");

        TestData.Bin bin = new TestData.Bin();
        bin.floats(0, 0, 0);
        assertFails(document("""
                "accessors":[{"bufferView":0,"componentType":5126,"type":"VEC3","count":3}],
                "bufferViews":[{"buffer":0,"byteLength":12}],
                "buffers":[{"byteLength":999,"uri":"scene.bin"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), bin, "declares byteLength 999 but 'scene.bin' provides only 12 bytes");
    }

    @Test
    @DisplayName("node hierarchy problems name the node, the child or the cycle")
    void reportsNodeProblems() {
        assertFails("{\"asset\":{\"version\":\"2.0\"},"
                        + "\"accessors\":[{\"componentType\":5126,\"type\":\"VEC3\",\"count\":3}],"
                        + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                        + "\"nodes\":[{\"mesh\":0,\"children\":[9]}],\"scenes\":[{\"nodes\":[0]}],"
                        + "\"scene\":0}",
                "child 9 out of range (nodes: 1)");

        assertFails("{\"asset\":{\"version\":\"2.0\"},"
                        + "\"accessors\":[{\"componentType\":5126,\"type\":\"VEC3\",\"count\":3}],"
                        + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                        + "\"nodes\":[{\"mesh\":0,\"children\":[1]},{\"children\":[0]}],"
                        + "\"scenes\":[{\"nodes\":[0]}],\"scene\":0}",
                "the parent chain forms a cycle");

        assertFails("{\"asset\":{\"version\":\"2.0\"},"
                        + "\"accessors\":[{\"componentType\":5126,\"type\":\"VEC3\",\"count\":3}],"
                        + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                        + "\"nodes\":[{\"mesh\":0},{\"children\":[3]},{\"children\":[3]},{}],"
                        + "\"scenes\":[{\"nodes\":[0]}],\"scene\":0}",
                "node 3 is already a child of node 1");

        assertFails("{\"asset\":{\"version\":\"2.0\"},"
                        + "\"accessors\":[{\"componentType\":5126,\"type\":\"VEC3\",\"count\":3}],"
                        + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                        + "\"nodes\":[{\"mesh\":0,\"children\":[0]}],\"scenes\":[{\"nodes\":[0]}],"
                        + "\"scene\":0}",
                "lists itself as its own child");

        assertFails("{\"asset\":{\"version\":\"2.0\"},"
                        + "\"accessors\":[{\"componentType\":5126,\"type\":\"VEC3\",\"count\":3}],"
                        + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                        + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],\"scene\":7}",
                "scene: index 7 out of range (scenes: 1)");
    }

    @Test
    @DisplayName("material, skin and animation reference problems name the referring element")
    void reportsReferenceProblems() {
        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "materials":[{"name":"only"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":5}]}]
                """), "material 5 out of range (materials: 1)");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "images":[{"uri":"a.png"}],"textures":[{"source":0}],
                "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":9}}}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}]
                """), "baseColorTexture.index 9 out of range (textures: 1)");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "textures":[{"source":9}],
                "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}]
                """), "source 9 out of range (images: 0)");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "materials":[{"alphaMode":"GLOW"}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}]
                """), "alphaMode 'GLOW' is not one of OPAQUE, MASK or BLEND");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "skins":[{"joints":[9]}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "joints[0] = 9 out of range (nodes: 1)");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3},
                             {"componentType":5126,"type":"MAT4","count":1}],
                "skins":[{"joints":[0,0],"inverseBindMatrices":1}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "has 1 matrices but the skin has 2 joints");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3},
                             {"componentType":5126,"type":"SCALAR","count":1},
                             {"componentType":5126,"type":"VEC3","count":1}],
                "animations":[{"channels":[{"sampler":9,"target":{"node":0,"path":"translation"}}],
                  "samplers":[{"input":1,"output":2}]}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "sampler 9 out of range (samplers: 1)");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3},
                             {"componentType":5126,"type":"SCALAR","count":1},
                             {"componentType":5126,"type":"VEC3","count":1}],
                "animations":[{"channels":[{"sampler":0,"target":{"node":9,"path":"translation"}}],
                  "samplers":[{"input":1,"output":2}]}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "target.node 9 out of range (nodes: 1)");

        // Keyframe times are real here (0 and 1) so that the output-count check is the one that fires.
        TestData.Bin keyframes = new TestData.Bin();
        keyframes.floats(0, 1);
        keyframes.floats(0, 0, 0, 1, 1, 1, 2, 2, 2);
        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3},
                             {"bufferView":0,"componentType":5126,"type":"SCALAR","count":2},
                             {"bufferView":1,"componentType":5126,"type":"VEC3","count":3}],
                "bufferViews":[{"buffer":0,"byteLength":8},{"buffer":0,"byteOffset":8,"byteLength":36}],
                "buffers":[{"byteLength":44,"uri":"scene.bin"}],
                "animations":[{"channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}],
                  "samplers":[{"input":1,"output":2}]}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), keyframes, "output accessor[2] has 9 values but 2 keyframes x 3 components needs 6");

        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3},
                             {"componentType":5126,"type":"SCALAR","count":2},
                             {"componentType":5126,"type":"VEC3","count":2}],
                "animations":[{"channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}],
                  "samplers":[{"input":1,"output":2,"interpolation":"QUADRATIC"}]}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "interpolation 'QUADRATIC' is not one of STEP, LINEAR or CUBICSPLINE");
    }

    @Test
    @DisplayName("an animation whose keyframe times repeat is refused: interpolation would divide by zero")
    void refusesNonIncreasingKeyframeTimes() {
        // Both times default to 0 because the accessor has no bufferView, so t[1] == t[0].
        assertFails(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3},
                             {"componentType":5126,"type":"SCALAR","count":2},
                             {"componentType":5126,"type":"VEC3","count":2}],
                "animations":[{"name":"flat","channels":[
                  {"sampler":0,"target":{"node":0,"path":"translation"}}],
                  "samplers":[{"input":1,"output":2}]}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), "keyframe times are not strictly increasing");
    }

    @Test
    @DisplayName("a primitive larger than the 16-bit index limit is refused by name, not truncated")
    void refusesOversizedPrimitive() {
        // A count-only accessor: 65537 vertices exceed ModelPrimitive's short[] indices.
        ModelParseException error = failure(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":65537}],
                "meshes":[{"name":"Huge","primitives":[{"attributes":{"POSITION":0}}]}]
                """), null);
        assertTrue(error.getMessage().contains("65537 vertices"), error.getMessage());
        assertTrue(error.getMessage().contains("Huge"), error.getMessage());
        assertTrue(error.getMessage().contains("65536"), error.getMessage());
    }

    @Test
    @DisplayName("an unreadable image costs a texture, never the geometry")
    void degradesOnUnreadableImages() throws Exception {
        TestData.Bin bin = new TestData.Bin();
        bin.bytes(1, 2, 3, 4);
        ModelScene scene = parse(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "bufferViews":[{"buffer":0,"byteOffset":900,"byteLength":16}],
                "buffers":[{"byteLength":4,"uri":"scene.bin"}],
                "images":[{"bufferView":0},{"uri":"https://example.com/remote.png"},
                          {"name":"nowhere"}],
                "textures":[{"source":0},{"source":1},{"source":2}],
                "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}},
                  "normalTexture":{"index":1},"emissiveTexture":{"index":2}}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}]
                """), bin);

        // Geometry is intact; the three broken images left their slots unassigned.
        assertEquals(1, scene.meshes().length);
        assertEquals(3, scene.meshes()[0].primitives()[0].vertexCount());
        assertEquals(0, scene.embeddedImages().size());
        assertNull(scene.materials()[0].baseColorTexture());
        assertNull(scene.materials()[0].normalTexture());
        assertNull(scene.materials()[0].emissiveTexture());
        assertFalse(scene.materials()[0].hasAnyTexture());
    }

    @Test
    @DisplayName("an image with neither uri nor bufferView is reported and skipped")
    void skipsImageWithNoSource() throws Exception {
        ModelScene scene = parse(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "images":[{"name":"empty"}],
                "textures":[{"source":0}],
                "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}]
                """), null);
        assertNull(scene.materials()[0].baseColorTexture());
        assertEquals(0, scene.embeddedImages().size());
    }

    @Test
    @DisplayName("a JSON syntax error inside a .gltf travels as a parse failure with its position")
    void reportsJsonSyntaxErrors() {
        ModelParseException error = failure("{ \"asset\": { \"version\": \"2.0\" }, }", null);
        assertTrue(error.getMessage().contains("model.gltf:"), error.getMessage());
        assertTrue(error.getMessage().contains("expected a member name"), error.getMessage());
    }

    @Test
    @DisplayName("a primitive with a zero-length POSITION is refused: an empty draw call is not a model")
    void refusesEmptyPrimitive() {
        ModelParseException error = failure(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":0}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), null);
        assertTrue(error.getMessage().contains("POSITION accessor has no elements"),
                error.getMessage());
    }

    @Test
    @DisplayName("an accessor with no bufferView is refused on its count, before a 6 GiB array exists")
    void refusesUnbackedAccessorBeforeAllocating() {
        // 536 870 912 VEC3 elements is 1 610 612 736 floats, about 6 GiB. A ~150-byte document can
        // ask for that, and the failure mode must be a reported parse error rather than the
        // allocation: OutOfMemoryError is an Error, so it escapes every
        // catch (ModelParseException | IOException | RuntimeException) the loader has, and takes the
        // load (or the resource reload) down with it.
        ModelParseException error = failure(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":536870912}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), null);
        assertTrue(error.getMessage().contains("no bufferView"), error.getMessage());
        assertTrue(error.getMessage().contains("536870912"), error.getMessage());
    }

    @Test
    @DisplayName("the unbacked cap still admits a small count-only accessor, which sparse data uses")
    void stillAdmitsSmallUnbackedAccessor() throws Exception {
        // The guard must reject by size, not by kind: an accessor with no bufferView is legitimate -
        // sparse data overrides individual elements over a zero base, and the class doc promises the
        // zeros. (The sparse override itself is covered by GltfFeatureTest.)
        ModelScene scene = parse(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]
                """), null);
        assertEquals(3, scene.meshes()[0].primitives()[0].vertexCount());
    }
}
