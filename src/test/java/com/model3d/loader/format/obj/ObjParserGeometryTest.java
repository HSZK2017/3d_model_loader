package com.model3d.loader.format.obj;

import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.model3d.loader.format.obj.ObjTestSupport.DELTA;
import static com.model3d.loader.format.obj.ObjTestSupport.assertBounds;
import static com.model3d.loader.format.obj.ObjTestSupport.assertSceneShape;
import static com.model3d.loader.format.obj.ObjTestSupport.assertUnitLength;
import static com.model3d.loader.format.obj.ObjTestSupport.assertUv;
import static com.model3d.loader.format.obj.ObjTestSupport.assertVector;
import static com.model3d.loader.format.obj.ObjTestSupport.distinctNormals;
import static com.model3d.loader.format.obj.ObjTestSupport.onlyPrimitive;
import static com.model3d.loader.format.obj.ObjTestSupport.positionOf;
import static com.model3d.loader.format.obj.ObjTestSupport.primitives;
import static com.model3d.loader.format.obj.ObjTestSupport.report;
import static com.model3d.loader.format.obj.ObjTestSupport.normalOf;
import static com.model3d.loader.format.obj.ObjTestSupport.triangleCount;
import static com.model3d.loader.format.obj.ObjTestSupport.uvOf;
import static com.model3d.loader.format.obj.ObjTestSupport.vertexAt;
import static com.model3d.loader.format.obj.ObjTestSupport.vertexCount;
import static com.model3d.loader.format.obj.ObjTestSupport.verticesAt;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry, indexing, triangulation and normal generation of the OBJ reader. */
class ObjParserGeometryTest {

    private static final String MAIN = "models/test/model.obj";

    /** Shared attributes: three vertices, three uvs, one normal. */
    private static final String ATTRIBUTES = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            vt 0 0
            vt 1 0
            vt 0 1
            vn 0 0 1
            """;

    private static final String CUBE_ATTRIBUTES = """
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            v 0 0 1
            v 1 0 1
            v 1 1 1
            v 0 1 1
            """;

    /** Six outward-wound quads; each face's normal points away from the cube's centre. */
    private static final String CUBE_FACES = """
            s off
            f 1 4 3 2
            f 5 6 7 8
            f 1 2 6 5
            f 4 8 7 3
            f 1 5 8 4
            f 2 3 7 6
            """;

    private final RecordingParseLog log = new RecordingParseLog();

    private ModelScene parse(String obj) throws Exception {
        return parse(obj, "model");
    }

    private ModelScene parse(String obj, String name) throws Exception {
        InMemoryModelSource source = InMemoryModelSource.builder(MAIN).file(MAIN, obj).build();
        return new ObjParser(log).parse(source, name);
    }

    // ------------------------------------------------------------------------ face reference forms

    @Test
    void allFourFaceReferenceFormsAreAccepted() throws Exception {
        String[] faces = { "f 1 2 3", "f 1/1 2/2 3/3", "f 1//1 2//1 3//1", "f 1/1/1 2/2/1 3/3/1" };
        for (String face : faces) {
            ModelScene scene = parse(ATTRIBUTES + face);
            ModelPrimitive primitive = onlyPrimitive(scene);
            assertEquals(1, triangleCount(scene), face + " should be one triangle");
            assertEquals(3, primitive.vertexCount(), face + " should keep three corners");
            assertVector(0.0f, 0.0f, 1.0f, normalOf(primitive, 0), face + " normal");
            report("allFourFaceReferenceFormsAreAccepted " + face, scene);
        }
    }

    @Test
    void positionOnlyFaceHasComputedNormalAndNoUvs() throws Exception {
        ModelScene scene = parse(ATTRIBUTES + "f 1 2 3\n");
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertNull(primitive.uvs(), "declared but unreferenced uvs must not be invented");
        assertVector(0.0f, 0.0f, 1.0f, normalOf(primitive, 0), "computed normal");
        assertEquals(1, scene.rootNodes().length);
        assertEquals(1, scene.nodeCount());
        report("positionOnlyFaceHasComputedNormalAndNoUvs", scene);
    }

    @Test
    void uvReferencesArePreservedExactly() throws Exception {
        ModelScene scene = parse(ATTRIBUTES + "f 1/1 2/2 3/3\n");
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertUv(0.0f, 0.0f, uvOf(primitive, vertexAt(primitive, 0, 0, 0)), "uv of vertex 1");
        assertUv(1.0f, 0.0f, uvOf(primitive, vertexAt(primitive, 1, 0, 0)), "uv of vertex 2");
        assertUv(0.0f, 1.0f, uvOf(primitive, vertexAt(primitive, 0, 1, 0)), "uv of vertex 3");
        report("uvReferencesArePreservedExactly", scene);
    }

    @Test
    void normalReferencesAreUsedAndNormalized() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vn 0 0 2
                f 1//1 2//1 3//1
                """);
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertNull(primitive.uvs());
        float[] normal = normalOf(primitive, 0);
        assertVector(0.0f, 0.0f, 1.0f, normal, "written normal");
        assertUnitLength(normal, "a non-unit vn must be normalized for the lighting path");
        report("normalReferencesAreUsedAndNormalized", scene);
    }

    // ---------------------------------------------------------------------------- triangulation

    @Test
    void quadIsFanTriangulatedIntoTwoTriangles() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                s off
                f 1 2 3 4
                """);
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertEquals(4, primitive.vertexCount(), "a planar flat-shaded quad needs four vertices");
        assertEquals(6, primitive.indexCount());
        assertArrayEquals(new int[] { 0, 1, 2, 0, 2, 3 }, primitive.indicesInt(), "fan from corner 0");
        assertEquals(2, triangleCount(scene));
        assertBounds(scene, 0, 0, 0, 1, 1, 0);
        report("quadIsFanTriangulatedIntoTwoTriangles", scene);
    }

    @Test
    void pentagonIsFanTriangulatedIntoThreeTriangles() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 2 1 0
                v 1 2 0
                v 0 1 0
                s off
                f 1 2 3 4 5
                """);
        assertEquals(3, triangleCount(scene), "an n-gon becomes n-2 triangles");
        assertEquals(9, onlyPrimitive(scene).indexCount());
        report("pentagonIsFanTriangulatedIntoThreeTriangles", scene);
    }

    @Test
    void negativeIndicesResolveAgainstTheCountAtThatPoint() throws Exception {
        // The second face's '-1' refers to the fourth vertex because it is declared before it, which
        // is the whole point: resolving negative indices after the file is read would silently point
        // this face at the third vertex instead and produce a plausible-looking wrong triangle.
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f -3 -2 -1
                v 5 5 5
                f -1 -1 -1
                """);
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertEquals(1, triangleCount(scene), "the repeated-index face is degenerate and must be dropped");
        assertEquals(3, primitive.vertexCount());
        assertTrue(log.debugged("1 degenerate triangle"), "the dropped triangle must be counted at DEBUG");
        report("negativeIndicesResolveAgainstTheCountAtThatPoint", scene);
    }

    @Test
    void negativeIndicesWorkInEveryAttributeSlot() throws Exception {
        ModelScene scene = parse(ATTRIBUTES + "f -3/-3/-1 -2/-2/-1 -1/-1/-1\n");
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertEquals(1, triangleCount(scene));
        assertVector(0.0f, 0.0f, 1.0f, normalOf(primitive, 0), "vn -1");
        assertUv(1.0f, 0.0f, uvOf(primitive, vertexAt(primitive, 1, 0, 0)), "vt -2");
        report("negativeIndicesWorkInEveryAttributeSlot", scene);
    }

    // -------------------------------------------------------------------------------- normals

    @Test
    void missingNormalsAreAreaWeightedSmooth() throws Exception {
        // Two triangles sharing vertex 1: a large one in the XZ plane (normal -Y, area 2) and a small
        // one in the XY plane (normal +X, area 0.5). A plain average would give (0.71, -0.71, 0); the
        // area-weighted answer is (0.2425, -0.9701, 0), so this asserts the weighting is real.
        ModelScene scene = parse("""
                v 0 0 0
                v 2 0 0
                v 0 0 2
                v 0 1 0
                v 0 0 1
                s 1
                f 1 2 3
                f 1 4 5
                """);
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertEquals(2, triangleCount(scene));
        assertEquals(5, primitive.vertexCount(), "the shared vertex is smoothed, not duplicated");
        int shared = vertexAt(primitive, 0, 0, 0);
        assertVector(0.24253563f, -0.97014254f, 0.0f, normalOf(primitive, shared), "area-weighted normal");
        assertUnitLength(normalOf(primitive, shared), "smooth normal");
        assertVector(0.0f, -1.0f, 0.0f, normalOf(primitive, vertexAt(primitive, 2, 0, 0)), "unshared normal");
        assertVector(1.0f, 0.0f, 0.0f, normalOf(primitive, vertexAt(primitive, 0, 1, 0)), "unshared normal");
        report("missingNormalsAreAreaWeightedSmooth", scene);
    }

    @Test
    void missingNormalsAreFlatWhenSmoothingIsOff() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                s off
                f 1 2 3 4
                """);
        ModelPrimitive primitive = onlyPrimitive(scene);
        for (int vertex = 0; vertex < primitive.vertexCount(); vertex++) {
            assertVector(0.0f, 0.0f, 1.0f, normalOf(primitive, vertex), "flat normal at vertex " + vertex);
        }
        report("missingNormalsAreFlatWhenSmoothingIsOff", scene);
    }

    @Test
    void smoothingGroupsKeepCreasesSharp() throws Exception {
        // Non-coplanar triangles sharing vertices 1 and 3, each in its own smoothing group: the shared
        // vertices must appear twice, once per face, with that face's own normal.
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                v 0 0 1
                s 1
                f 1 2 3
                s 2
                f 1 3 4
                """);
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertEquals(2, triangleCount(scene));
        assertEquals(6, primitive.vertexCount(), "a crease needs one vertex per side");
        List<Integer> shared = verticesAt(primitive, 0, 0, 0);
        assertEquals(2, shared.size(), "vertex 1 belongs to both groups");
        Set<String> normals = Set.of(
                java.util.Arrays.toString(normalOf(primitive, shared.get(0))),
                java.util.Arrays.toString(normalOf(primitive, shared.get(1))));
        assertEquals(2, normals.size(), "the two sides of the crease must have different normals: " + normals);
        for (int vertex : shared) {
            float[] normal = normalOf(primitive, vertex);
            boolean xyFace = Math.abs(normal[0]) < DELTA && Math.abs(normal[1]) < DELTA
                    && Math.abs(normal[2] - 1.0f) < DELTA;
            boolean yzFace = Math.abs(normal[0] - 1.0f) < DELTA && Math.abs(normal[1]) < DELTA
                    && Math.abs(normal[2]) < DELTA;
            assertTrue(xyFace || yzFace, "expected a per-face normal, got " + java.util.Arrays.toString(normal));
        }
        report("smoothingGroupsKeepCreasesSharp", scene);
    }

    @Test
    void oneSmoothingGroupSmoothsAcrossACrease() throws Exception {
        // The same geometry with both faces in group 1 must merge the shared vertices instead.
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                v 0 0 1
                s 1
                f 1 2 3
                f 1 3 4
                """);
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertEquals(2, triangleCount(scene));
        assertEquals(4, primitive.vertexCount(), "one group means the shared vertices are welded");
        assertVector(0.70710678f, 0.0f, 0.70710678f, normalOf(primitive, vertexAt(primitive, 0, 0, 0)),
                "average of the two faces");
        report("oneSmoothingGroupSmoothsAcrossACrease", scene);
    }

    @Test
    void smoothingGroupSpellingsAreAccepted() throws Exception {
        String geometry = """
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                """;
        for (String smoothing : new String[] { "s off", "s 0", "s on", "s 1.0", "s 3" }) {
            ModelScene scene = parse(geometry + smoothing + "\nf 1 2 3 4\n");
            assertEquals(2, triangleCount(scene), smoothing + " must not lose geometry");
            assertTrue(scene.nodeCount() == 1 && scene.meshes().length == 1, smoothing);
        }
        assertTrue(log.debugged("'s on' is not part of the format"), "'s on' is not standard and must be reported");
        report("smoothingGroupSpellingsAreAccepted (last scene)", parse(geometry + "s off\nf 1 2 3 4\n"));
    }

    @Test
    void cubeFlatShadingProducesOneVertexPerFaceCorner() throws Exception {
        ModelScene scene = parse(CUBE_ATTRIBUTES + CUBE_FACES);
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertEquals(12, triangleCount(scene), "six quads are twelve triangles");
        assertEquals(24, primitive.vertexCount(), "flat shading needs four vertices per face");
        assertEquals(36, primitive.indexCount());
        assertEquals(6, distinctNormals(primitive).size(), "six outward axis normals: " + distinctNormals(primitive));
        for (int vertex = 0; vertex < primitive.vertexCount(); vertex++) {
            assertUnitLength(normalOf(primitive, vertex), "flat normal " + vertex);
            float[] position = positionOf(primitive, vertex);
            float[] normal = normalOf(primitive, vertex);
            // The vertex lies on the face plane its normal points away from.
            float distance = position[0] * normal[0] + position[1] * normal[1] + position[2] * normal[2];
            assertTrue(Math.abs(distance - 1.0f) < DELTA || Math.abs(distance) < DELTA,
                    "vertex " + vertex + " at " + java.util.Arrays.toString(position) + " does not lie on the face "
                            + "its normal " + java.util.Arrays.toString(normal) + " points away from");
        }
        assertBounds(scene, 0, 0, 0, 1, 1, 1);
        assertEquals(1.0f, scene.longestExtent(), DELTA);
        report("cubeFlatShadingProducesOneVertexPerFaceCorner", scene);
    }

    @Test
    void cubeSmoothShadingWeldsCorners() throws Exception {
        ModelScene scene = parse(CUBE_ATTRIBUTES + CUBE_FACES.replace("s off", "s 1"));
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertEquals(12, triangleCount(scene));
        assertEquals(8, primitive.vertexCount(), "smooth shading welds the eight cube corners");
        assertVector(-0.57735026f, -0.57735026f, -0.57735026f,
                normalOf(primitive, vertexAt(primitive, 0, 0, 0)), "corner normal");
        assertVector(0.57735026f, 0.57735026f, 0.57735026f,
                normalOf(primitive, vertexAt(primitive, 1, 1, 1)), "opposite corner normal");
        for (int vertex = 0; vertex < primitive.vertexCount(); vertex++) {
            assertUnitLength(normalOf(primitive, vertex), "smooth normal " + vertex);
        }
        report("cubeSmoothShadingWeldsCorners", scene);
    }

    @Test
    void normalsAreNeverNull() throws Exception {
        for (String face : new String[] { "f 1 2 3", "f 1/1 2/2 3/3", "f 1//1 2//1 3//1", "f 1/1/1 2/2/1 3/3/1" }) {
            ModelScene scene = parse(ATTRIBUTES + face);
            for (ModelPrimitive primitive : primitives(scene)) {
                assertNotNull(primitive.normals(), face + ": a null normal array renders unlit");
                for (int vertex = 0; vertex < primitive.vertexCount(); vertex++) {
                    assertUnitLength(normalOf(primitive, vertex), face + " vertex " + vertex);
                }
            }
        }
        report("normalsAreNeverNull (last scene)", parse(ATTRIBUTES + "f 1/1/1 2/2/1 3/3/1\n"));
    }

    // ------------------------------------------------------------------------------------ uvs

    @Test
    void vtWithOneTwoAndThreeComponents() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0.25
                vt 0.5 0.75
                vt 0.1 0.2 0.3
                f 1/1 2/2 3/3
                """);
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertUv(0.25f, 0.0f, uvOf(primitive, vertexAt(primitive, 0, 0, 0)), "1-component vt");
        assertUv(0.5f, 0.75f, uvOf(primitive, vertexAt(primitive, 1, 0, 0)), "2-component vt");
        assertUv(0.1f, 0.2f, uvOf(primitive, vertexAt(primitive, 0, 1, 0)), "3-component vt");
        assertTrue(log.debugged("3rd component"), "the unused depth component must be reported");
        report("vtWithOneTwoAndThreeComponents", scene);
    }

    @Test
    void mixedUvPresenceDropsUvsRatherThanInventingZeros() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                v 1 1 0
                vt 0 0
                vt 1 0
                vt 0 1
                f 1/1 2/2 3/3
                f 2 4 3
                """);
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertEquals(2, triangleCount(scene));
        assertNull(primitive.uvs(), "one untextured face must not pin the whole primitive to texel (0,0)");
        assertTrue(log.debugged("uvs are dropped"), "the dropped uvs must be explained");
        report("mixedUvPresenceDropsUvsRatherThanInventingZeros", scene);
    }

    // ------------------------------------------------------------------------- text-level input

    @Test
    void crlfCommentsBlankLinesAndContinuations() throws Exception {
        String obj = "# exported by a Windows tool\r\n"
                + "v 0 0 0\r\n"
                + "\r\n"
                + "   \r\n"
                + "v 1 0 0\r\n"
                + "v 0 \\\r\n"
                + " 1 0\r\n"
                + "f 1 2 3 # the front face\r\n";
        ModelScene scene = parse(obj);
        ModelPrimitive primitive = onlyPrimitive(scene);
        assertEquals(3, vertexCount(scene));
        vertexAt(primitive, 0, 1, 0);
        assertEquals(1, triangleCount(scene));
        report("crlfCommentsBlankLinesAndContinuations", scene);
    }

    @Test
    void byteOrderMarkIsIgnored() throws Exception {
        ModelScene scene = parse("\uFEFFv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
        assertEquals(3, vertexCount(scene), "a BOM must not swallow the first vertex");
        assertEquals(1, triangleCount(scene));
        report("byteOrderMarkIsIgnored", scene);
    }

    @Test
    void degenerateTrianglesAreDroppedAndCounted() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 2 0 0
                v 0 1 0
                f 1 2 3
                f 1 2 4
                """);
        assertEquals(1, triangleCount(scene), "the collinear triangle has no area and must be dropped");
        assertEquals(1, (int) log.debugMessages().stream()
                .filter(message -> message.contains("dropped 1 degenerate")).count());
        report("degenerateTrianglesAreDroppedAndCounted", scene);
    }

    @Test
    void repeatedIndexInAQuadKeepsTheValidTriangle() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f 1 1 2 3
                """);
        assertEquals(1, triangleCount(scene), "the repeated-index half of the fan is dropped");
        report("repeatedIndexInAQuadKeepsTheValidTriangle", scene);
    }

    @Test
    void lineAndPointElementsAreSkippedWithACount() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                l 1 2
                p 1
                l 2 3
                f 1 2 3
                """);
        assertEquals(1, triangleCount(scene));
        assertTrue(log.debugged("skipped 3 line/point"), "dropped l/p elements must be counted: " + log.debugMessages());
        report("lineAndPointElementsAreSkippedWithACount", scene);
    }

    @Test
    void unknownStatementsAreIgnoredAndCounted() throws Exception {
        ModelScene scene = parse("""
                vp 0 0
                curv 0 1 1 2
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f 1 2 3
                """);
        assertEquals(1, triangleCount(scene));
        assertTrue(log.debugged("unsupported statement 'vp'"));
        assertTrue(log.debugged("unsupported statement 'curv'"));
        assertTrue(log.debugged("ignored 2 unsupported statement(s)"));
        report("unknownStatementsAreIgnoredAndCounted", scene);
    }

    // ------------------------------------------------------------------------------ scene shape

    @Test
    void groupsAndObjectsProduceOneNodeEach() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                v 1 1 0
                f 1 2 3
                o Part
                f 1 2 3
                g wing
                f 2 4 3
                """);
        assertEquals(3, scene.nodeCount());
        assertEquals("default", scene.nodeTemplates()[0].name(), "geometry before any o/g is the default group");
        assertEquals("Part", scene.nodeTemplates()[1].name(), "an object without a group is named after the object");
        assertEquals("wing", scene.nodeTemplates()[2].name());
        assertEquals(3, triangleCount(scene));
        assertBounds(scene, 0, 0, 0, 1, 1, 0);
        assertSceneShape(scene);
        report("groupsAndObjectsProduceOneNodeEach", scene);
    }

    @Test
    void repeatedGroupStatementsMergeIntoOneNode() throws Exception {
        // Exporters emit 'g name' before every face block; one node per statement would produce
        // thousands of identical nodes and draw calls.
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                g body
                f 1 2 3
                g body
                f 1 2 3
                g wing
                f 1 2 3
                """);
        assertEquals(2, scene.nodeCount(), "nodes: " + java.util.Arrays.toString(
                java.util.Arrays.stream(scene.nodeTemplates()).map(ModelNode::name).toArray()));
        assertEquals("body", scene.nodeTemplates()[0].name());
        assertEquals(3, triangleCount(scene));
        assertEquals(6, scene.meshes()[0].primitives()[0].indexCount(),
                "both 'g body' blocks feed one mesh, and the identical second face de-duplicates");
        assertEquals(3, scene.meshes()[0].primitives()[0].vertexCount());
        report("repeatedGroupStatementsMergeIntoOneNode", scene);
    }

    @Test
    void groupWithSeveralMaterialsBecomesSeveralPrimitives() throws Exception {
        String materials = """
                newmtl red
                Kd 1 0 0
                newmtl blue
                Kd 0 0 1
                """;
        String obj = """
                mtllib materials.mtl
                v 0 0 0
                v 1 0 0
                v 0 1 0
                v 1 1 0
                o model
                g body
                usemtl red
                f 1 2 3
                usemtl blue
                f 2 4 3
                g wing
                usemtl red
                f 1 3 4
                """;
        InMemoryModelSource source = InMemoryModelSource.builder(MAIN)
                .file(MAIN, obj)
                .file("materials.mtl", materials)
                .build();
        ModelScene scene = new ObjParser(log).parse(source, "model");

        assertEquals(2, scene.nodeCount(), "body and wing");
        assertEquals(2, scene.meshes().length);
        assertEquals(3, primitives(scene).size(), "body splits into red+blue, wing is red");
        assertEquals(2, scene.materials().length, "materials are shared, not duplicated per group");
        assertEquals("red", scene.materials()[0].name());
        assertEquals("blue", scene.materials()[1].name());
        assertEquals(0, scene.meshes()[0].primitives()[0].materialIndex());
        assertEquals(1, scene.meshes()[0].primitives()[1].materialIndex());
        assertEquals(0, scene.meshes()[1].primitives()[0].materialIndex(), "the same red material is reused");
        assertEquals("body [red]", scene.meshes()[0].primitives()[0].name());
        assertEquals(3, triangleCount(scene));
        assertSceneShape(scene);
        report("groupWithSeveralMaterialsBecomesSeveralPrimitives", scene);
    }

    @Test
    void boundsAreTheUnionOfAllPrimitives() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f 1 2 3
                g far
                v 10 -2 3
                v 11 -2 3
                v 10 -1 3
                f 4 5 6
                """);
        assertBounds(scene, 0, -2, 0, 11, 1, 3);
        assertEquals(11.0f, scene.longestExtent(), DELTA);
        report("boundsAreTheUnionOfAllPrimitives", scene);
    }

    @Test
    void sectionWithoutFacesProducesNoNode() throws Exception {
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                o Empty
                g nothing
                g body
                f 1 2 3
                """);
        assertEquals(1, scene.nodeCount(), "an o/g section with no faces has nothing to draw");
        assertFalse(java.util.Arrays.stream(scene.nodeTemplates()).anyMatch(node -> node.name().equals("nothing")));
        report("sectionWithoutFacesProducesNoNode", scene);
    }

    @Test
    void oversizedGroupIsSplitIntoShortIndexedPrimitives() throws Exception {
        // More vertices than an unsigned short index can address. Wrapping the index would rewire the
        // strip to unrelated vertices, so the group must be split instead.
        int vertices = 70_000;
        StringBuilder obj = new StringBuilder(vertices * 16);
        obj.append("s off\n");
        for (int i = 0; i < vertices; i++) {
            obj.append("v ").append(i / 2).append(' ').append(i % 2).append(" 0\n");
        }
        int triangles = 0;
        for (int i = 1; i + 2 <= vertices; i++) {
            obj.append("f ").append(i).append(' ').append(i + 1).append(' ').append(i + 2).append('\n');
            triangles++;
        }
        ModelScene scene = parse(obj.toString());

        List<ModelPrimitive> parts = primitives(scene);
        assertTrue(parts.size() >= 2, "70k vertices cannot fit one primitive, got " + parts.size());
        int totalVertices = 0;
        int totalTriangles = 0;
        for (ModelPrimitive part : parts) {
            assertTrue(part.vertexCount() <= ObjParser.MAX_VERTICES_PER_PRIMITIVE,
                    "part has " + part.vertexCount() + " vertices");
            totalVertices += part.vertexCount();
            totalTriangles += part.indexCount() / 3;
            for (int index : part.indicesInt()) {
                assertTrue(index >= 0 && index < part.vertexCount(),
                        "index " + index + " is out of range for " + part.vertexCount() + " vertices");
            }
        }
        assertEquals(triangles, totalTriangles, "every input triangle must survive the split");
        assertTrue(totalVertices >= vertices,
                "each of the " + vertices + " positions needs at least one vertex, got " + totalVertices);
        assertBounds(scene, 0, 0, 0, (vertices - 1) / 2, 1, 0);
        System.out.println("[obj-test] oversizedGroupIsSplitIntoShortIndexedPrimitives: inputVertices=" + vertices
                + " inputTriangles=" + triangles + " parts=" + parts.size() + " partsTriangles=" + totalTriangles
                + " partsVertices=" + totalVertices);
    }
}
