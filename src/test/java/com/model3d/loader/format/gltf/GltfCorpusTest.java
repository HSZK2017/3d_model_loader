package com.model3d.loader.format.gltf;

import com.model3d.loader.format.ModelFormat;
import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelImage;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parse tests against the real, licensed Su-30 corpus, asserting the numbers this machine measured
 * rather than "greater than zero".
 *
 * <p>Every assertion here is a value read off the files: change a parser default, mis-handle
 * {@code byteStride}, or drop a material, and one of these numbers moves.
 *
 * <p>Two of the files are the <b>same model</b> in two containers - {@code scene.gltf} with an
 * external {@code scene.bin} and {@code sukhoi_su-30_flanker_c.glb} with the same bytes plus embedded
 * images - so their inventories must match exactly. That pair is what catches a text/GLB divergence.
 */
class GltfCorpusTest {

    private static final String TEXT_MODEL = "sukhoi_su-30_flanker_c/scene.gltf";
    private static final String EMBEDDED_GLB = "sukhoi_su-30_flanker_c.glb";
    private static final String BLENDER_GLB = "sukhoi-su-30-flanker-c/source/Su30 export version 2024_9_28.glb";
    private static final String PBR_GLB = "pbr_sukhoi_su-30.glb";

    /** Measured on the corpus; the shared inventory of the Sketchfab model in both containers. */
    private static final int SKETCHFAB_NODES = 26;
    private static final int SKETCHFAB_MESHES = 22;
    private static final int SKETCHFAB_PRIMITIVES = 22;
    private static final int SKETCHFAB_VERTICES = 24244;
    private static final int SKETCHFAB_TRIANGLES = 21585;
    private static final int SKETCHFAB_MATERIALS = 22;
    private static final float[] SKETCHFAB_BOUNDS = {
            -1.1015015f, -21.759520f, -21.498993f, 278.18677f, 4.9049454f, 21.498993f };
    private static final float SKETCHFAB_LONGEST = 279.28827f;

    private static ModelScene parse(Path modelFile, ModelFormat format) throws Exception {
        DirectoryModelSource source = Corpus.open(modelFile);
        return format == ModelFormat.GLB
                ? new GlbParser().parse(source, modelFile.getFileName().toString())
                : new GltfParser().parse(source, modelFile.getFileName().toString());
    }

    private static void assertSketchfabInventory(ModelScene scene, String label) {
        Corpus.printInventory(label, "text glTF + external .bin", scene);
        assertEquals(SKETCHFAB_NODES, scene.nodeCount(), label + ": node count");
        assertEquals(SKETCHFAB_MESHES, scene.meshes().length, label + ": mesh count");
        assertEquals(SKETCHFAB_PRIMITIVES, Corpus.primitiveCount(scene), label + ": primitive count");
        assertEquals(SKETCHFAB_VERTICES, Corpus.vertexCount(scene), label + ": vertex count");
        assertEquals(SKETCHFAB_TRIANGLES, Corpus.triangleCount(scene), label + ": triangle count");
        assertEquals(SKETCHFAB_MATERIALS, scene.materials().length, label + ": material count");
        assertEquals(0, scene.skins().length, label + ": skins");
        assertEquals(0, scene.animations().size(), label + ": animations");
        assertArrayEquals(new int[] { 0 }, scene.rootNodes(), label + ": root nodes");
        assertArrayEquals(SKETCHFAB_BOUNDS, scene.bounds(), 1e-3f, label + ": bounds");
        assertEquals(SKETCHFAB_LONGEST, scene.longestExtent(), 1e-2f, label + ": longest extent");
        // 5 base-colour textures and one normal map, one BLEND material, everything double-sided.
        assertEquals(5, Corpus.materialsWith(scene, "baseColor"), label + ": base colour textures");
        assertEquals(1, Corpus.materialsWith(scene, "normal"), label + ": normal maps");
        assertEquals(0, Corpus.materialsWith(scene, "emissive"), label + ": emissive textures");
        // Two materials emit (the engine glow and the plumes); the rest default to black.
        int emissive = 0;
        for (ModelMaterial material : scene.materials()) {
            float[] factor = material.emissiveFactor();
            if (factor[0] != 0f || factor[1] != 0f || factor[2] != 0f) {
                emissive++;
            }
        }
        assertEquals(2, emissive, label + ": materials with a non-black emissive factor");
        assertEquals(0, skinnedPrimitives(scene), label + ": skinned primitives");
    }

    private static int skinnedPrimitives(ModelScene scene) {
        int skinned = 0;
        for (var mesh : scene.meshes()) {
            for (ModelPrimitive primitive : mesh.primitives()) {
                if (primitive.isSkinned()) {
                    skinned++;
                }
            }
        }
        return skinned;
    }

    @Test
    @DisplayName("text glTF with an external .bin and 6 external textures: 22 meshes / 21585 triangles")
    void parsesTextGltfWithExternalBuffer() throws Exception {
        Path model = Corpus.require(TEXT_MODEL, "the text glTF twin of the Sketchfab model");
        ModelScene scene = parse(model, ModelFormat.GLTF);
        assertSketchfabInventory(scene, TEXT_MODEL);

        // The texture chain texture -> image -> external .jpeg resolves to a model-relative path the
        // resource layer can open; the paths really exist next to scene.gltf.
        ModelMaterial textured = scene.materials()[1];
        assertEquals("Su30_MKI_L", textured.name());
        assertEquals("textures/Su30_MKI_L_baseColor.jpeg", textured.baseColorTexture());
        assertEquals("Su30_MKI_R", scene.materials()[5].name());
        assertEquals("textures/Su30_MKI_R_normal.jpeg", scene.materials()[5].normalTexture());
        assertEquals(0.5f, scene.materials()[5].normalScale(), 0f);
        assertEquals(ModelMaterial.AlphaMode.BLEND, scene.materials()[2].alphaMode());
        assertTrue(scene.materials()[2].doubleSided());
        for (ModelMaterial material : scene.materials()) {
            assertTrue(material.doubleSided(), material.name() + " is double-sided in this file");
        }

        // Three of the 26 nodes carry a matrix; the rest carry TRS. The matrix nodes must still be
        // placed, and their decomposed TRS must reproduce the file's matrix.
        ModelNode transform = scene.nodeTemplates()[3];
        assertEquals("Su30 _0", transform.name());
        assertArrayEquals(new float[] { -8.76325f, 2.04827f, 0.00003f },
                transform.restTranslationArray(), 1e-4f);
        assertArrayEquals(new float[] { 0.05351f, 0.1223f, 0.23447f },
                transform.restScaleArray(), 1e-4f);
        // Compared against a matrix written out by hand rather than against restLocalTransform:
        // ModelNode builds restLocalTransform by calling the very Mat4.compose() under test, so
        // comparing the two is a tautology that cannot fail. This node is
        // diag(0.05351, -0.1223, -0.23447) plus a translation - a 180-degree rotation about X, which
        // is diagonal, so it still cannot tell a row-scale from a column-scale compose; the node in
        // GltfFeatureTest's "non-axis rotation with a non-uniform scale" case does that.
        com.model3d.loader.math.Mat4 expected = new com.model3d.loader.math.Mat4(new float[] {
                0.05351f, 0f, 0f, 0f,
                0f, -0.1223f, 0f, 0f,
                0f, 0f, -0.23447f, 0f,
                -8.76325f, 2.04827f, 0.00003f, 1f});
        assertEquals(0f, expected.maxDifference(transform.restLocalTransform()), 1e-4f,
                "the decomposed TRS must rebuild the matrix the file carried");

        // The 22 mesh nodes hang off node 3 and are the only leaves with meshes.
        assertEquals(22, transform.children().size(),
                "node 3 'Su30 _0' should have the 22 mesh nodes as children");
        assertEquals(0, scene.nodeTemplates()[4].meshIndex());
        assertEquals("Object_4", scene.nodeTemplates()[4].name());
    }

    @Test
    @DisplayName("the same model as a .glb is byteStride-interleaved and has the identical inventory")
    void parsesGlbTwinIdentically() throws Exception {
        Path model = Corpus.require(EMBEDDED_GLB, "the .glb twin of the Sketchfab model");
        ModelScene scene = parse(model, ModelFormat.GLB);
        assertSketchfabInventory(scene, EMBEDDED_GLB);

        // This is the corpus file that actually exercises byteStride: its vertex views are stride 8
        // (UV), 12 (position and normal) and 16 (tangent), each accessor selecting its own slice by
        // byteOffset. All six images are embedded in the BIN chunk, so every texture slot refers to
        // an image carried on the scene rather than to a sibling file.
        assertEquals(6, scene.materials().length > 0 ? scene.embeddedImages().size() : -1,
                "measured: 6 images, all embedded in the BIN chunk");
        for (ModelMaterial material : scene.materials()) {
            if (material.baseColorTexture() != null) {
                assertTrue(ModelImage.isEmbedded(material.baseColorTexture()),
                        "embedded image should use the reserved scheme, was: "
                                + material.baseColorTexture());
                assertNotNull(scene.embeddedImage(material.baseColorTexture()),
                        "every embedded texture path must resolve to its image bytes");
            }
        }
    }

    @Test
    @DisplayName("Blender-exported .glb: one node, 22 primitives, 21 materials plus the default")
    void parsesBlenderExport() throws Exception {
        Path model = Corpus.require(BLENDER_GLB, "the Blender 3.6 export");
        ModelScene scene = parse(model, ModelFormat.GLB);
        Corpus.printInventory("sukhoi-su-30-flanker-c/source/Su30 export version 2024_9_28.glb", "GLB",
                scene);

        assertEquals(1, scene.nodeCount());
        assertEquals(1, scene.meshes().length);
        assertEquals(22, Corpus.primitiveCount(scene));
        assertEquals(24244, Corpus.vertexCount(scene));
        assertEquals(21585, Corpus.triangleCount(scene));
        // 21 declared materials; the 22nd is the spec default, appended because one primitive names
        // no material at all - which is why the count is 22 here too.
        assertEquals(22, scene.materials().length);
        assertEquals("default", scene.materials()[21].name());
        assertEquals(21, scene.meshes()[0].primitives()[4].materialIndex());
        assertArrayEquals(new int[] { 0 }, scene.rootNodes());
        assertArrayEquals(SKETCHFAB_BOUNDS, scene.bounds(), 1e-3f);
        assertEquals(24244, Corpus.vertexCount(scene));

        // The Blender export stores the same geometry with the transform on the node instead of on a
        // parent matrix: node 0's TRS must equal what the text file's node-3 matrix decomposes to.
        ModelNode node = scene.nodeTemplates()[0];
        assertEquals("Su30 ", node.name());
        assertArrayEquals(new float[] { -8.763246f, 2.0482655f, 0.00002627f },
                node.restTranslationArray(), 1e-4f);
        assertArrayEquals(new float[] { 0.05350814f, 0.12229558f, 0.23446511f },
                node.restScaleArray(), 1e-6f);
        assertEquals(1.0f, Math.abs(node.restRotationArray()[0]), 1e-4f,
                "a half turn about X: the quaternion should be (+-1, 0, 0, ~0)");

        // Seven bufferView-embedded images feed 7 texture slots across 5 materials, and every one of
        // them is carried on the scene as encoded bytes.
        int embeddedSlots = 0;
        for (ModelMaterial material : scene.materials()) {
            embeddedSlots += material.baseColorTexture() != null ? 1 : 0;
            embeddedSlots += material.normalTexture() != null ? 1 : 0;
        }
        assertEquals(7, embeddedSlots);
        assertEquals(7, scene.embeddedImages().size(),
                "measured: 7 bufferView-embedded images in this export");
        for (ModelImage image : scene.embeddedImages()) {
            assertTrue(image.byteSize() > 0, image.name() + " must carry bytes");
            assertTrue(image.mimeType().startsWith("image/"), image.name()
                    + " should declare an image mime type but declared '" + image.mimeType() + "'");
        }
    }

    @Test
    @DisplayName("the 43 MB PBR model parses in the 2 GB fork: 2 meshes, 19542 triangles, 4 UV sets")
    void parsesLargePbrModel() throws Exception {
        Path model = Corpus.require(PBR_GLB, "the 43 MB PBR model");
        ModelScene scene = parse(model, ModelFormat.GLB);
        Corpus.printInventory("pbr_sukhoi_su-30.glb", "GLB (43 MB)", scene);

        assertEquals(7, scene.nodeCount());
        assertEquals(2, scene.meshes().length);
        assertEquals(2, Corpus.primitiveCount(scene));
        assertEquals(20234, Corpus.vertexCount(scene));
        assertEquals(19542, Corpus.triangleCount(scene));
        assertEquals(2, scene.materials().length);
        assertEquals(0, scene.skins().length);
        assertEquals(0, scene.animations().size());
        assertArrayEquals(new int[] { 0 }, scene.rootNodes());
        assertArrayEquals(new float[] { -968.36755f, -2.173523f, -744.72607f, 1230.92f, 579.38965f,
                744.72998f }, scene.bounds(), 1e-2f);
        assertEquals(2199.2876f, scene.longestExtent(), 1e-1f);

        // UNSIGNED_INT indices and four UV sets: the largest primitive is the fuselage.
        ModelPrimitive fuselage = scene.meshes()[0].primitives()[0];
        assertEquals(19566, fuselage.vertexCount());
        assertEquals(55602, fuselage.indexCount());
        assertArrayEquals(new float[] { 1, 1, 1, 1 }, scene.materials()[0].baseColorFactor(), 0f);
        assertEquals(ModelMaterial.AlphaMode.BLEND, scene.materials()[1].alphaMode());
        assertEquals(0.209032f, scene.materials()[1].baseColorFactor()[3], 1e-6f);
        assertEquals(0.3f, scene.materials()[0].normalScale(), 1e-6f);
        // material 0's normalTexture asks for texCoord 2, which ModelMaterial cannot express; the
        // slot is still reported rather than dropped, and its bytes are on the scene.
        assertEquals("embedded/image2", scene.materials()[0].normalTexture());
        assertEquals(6, scene.embeddedImages().size(), "measured: 6 embedded PNGs");
        for (ModelImage image : scene.embeddedImages()) {
            assertTrue(image.byteSize() > 0, image.name() + " must carry bytes");
        }
    }

    @Test
    @DisplayName("every corpus model that exists is reported without animations")
    void corpusHasNoAnimationData() throws Exception {
        // Measured fact, worth asserting: nothing in this corpus carries animation or skinning, so
        // the animated and skinned code paths are covered by the synthetic tests instead.
        Path[] models = {
                Corpus.require(TEXT_MODEL, "scene.gltf"),
                Corpus.require(EMBEDDED_GLB, "glb twin"),
                Corpus.require(BLENDER_GLB, "blender export") };
        ModelFormat[] formats = { ModelFormat.GLTF, ModelFormat.GLB, ModelFormat.GLB };
        for (int i = 0; i < models.length; i++) {
            ModelScene scene = parse(models[i], formats[i]);
            assertTrue(scene.animations().isEmpty(), models[i] + " has no animations");
            assertFalse(scene.isAnimated());
            assertFalse(scene.isSkinned());
            for (ModelAnimation animation : scene.animations()) {
                assertionError(animation);
            }
        }
    }

    private static void assertionError(ModelAnimation animation) {
        throw new AssertionError("unreachable: " + animation);
    }
}
