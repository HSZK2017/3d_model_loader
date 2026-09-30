package com.model3d.loader.format.obj;

import com.model3d.loader.scene.ModelMesh;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Helpers for asserting on a parsed scene, plus the per-test summary line the task asks for.
 *
 * <p>Everything here reads real numbers out of the scene instead of asking "is it non-empty": a
 * loader bug of the kind this suite exists to catch - a wrapped index, a fan that produced one
 * triangle instead of two, a normal that points the wrong way - is invisible to a truthiness check.
 */
final class ObjTestSupport {

    static final float DELTA = 1e-4f;

    private ObjTestSupport() {
    }

    /** Every primitive of every mesh, in mesh order: the whole drawable geometry of a scene. */
    static List<ModelPrimitive> primitives(ModelScene scene) {
        List<ModelPrimitive> primitives = new ArrayList<>();
        for (ModelMesh mesh : scene.meshes()) {
            primitives.addAll(List.of(mesh.primitives()));
        }
        return primitives;
    }

    static ModelPrimitive onlyPrimitive(ModelScene scene) {
        List<ModelPrimitive> primitives = primitives(scene);
        assertEquals(1, primitives.size(), "expected exactly one primitive, got " + primitives);
        return primitives.get(0);
    }

    /** Triangles across the whole scene, counted from the index arrays a renderer would draw. */
    static int triangleCount(ModelScene scene) {
        int triangles = 0;
        for (ModelPrimitive primitive : primitives(scene)) {
            assertEquals(0, primitive.indexCount() % 3, "index count is not a multiple of 3");
            triangles += primitive.indexCount() / 3;
        }
        return triangles;
    }

    static int vertexCount(ModelScene scene) {
        int vertices = 0;
        for (ModelPrimitive primitive : primitives(scene)) {
            vertices += primitive.vertexCount();
        }
        return vertices;
    }

    static float[] positionOf(ModelPrimitive primitive, int vertex) {
        float[] positions = primitive.positions();
        return new float[] { positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2] };
    }

    static float[] normalOf(ModelPrimitive primitive, int vertex) {
        float[] normals = primitive.normals();
        return new float[] { normals[vertex * 3], normals[vertex * 3 + 1], normals[vertex * 3 + 2] };
    }

    static float[] uvOf(ModelPrimitive primitive, int vertex) {
        float[] uvs = primitive.uvs();
        return new float[] { uvs[vertex * 2], uvs[vertex * 2 + 1] };
    }

    /** Every vertex index whose position matches, exactly; more than one when normals differ. */
    static List<Integer> verticesAt(ModelPrimitive primitive, float x, float y, float z) {
        List<Integer> matches = new ArrayList<>();
        for (int vertex = 0; vertex < primitive.vertexCount(); vertex++) {
            float[] position = positionOf(primitive, vertex);
            if (position[0] == x && position[1] == y && position[2] == z) {
                matches.add(vertex);
            }
        }
        return matches;
    }

    /** The single vertex at a position; fails when the primitive has none or several. */
    static int vertexAt(ModelPrimitive primitive, float x, float y, float z) {
        List<Integer> matches = verticesAt(primitive, x, y, z);
        assertEquals(1, matches.size(), "expected exactly one vertex at (" + x + "," + y + "," + z
                + "), found " + matches);
        return matches.get(0);
    }

    /** Distinct normals as rounded strings, for asserting the shape of a flat-shaded mesh. */
    static Set<String> distinctNormals(ModelPrimitive primitive) {
        Set<String> distinct = new LinkedHashSet<>();
        for (int vertex = 0; vertex < primitive.vertexCount(); vertex++) {
            float[] normal = normalOf(primitive, vertex);
            distinct.add(String.format("%.3f,%.3f,%.3f", normal[0], normal[1], normal[2]));
        }
        return distinct;
    }

    static void assertVector(float x, float y, float z, float[] actual, String what) {
        assertEquals(x, actual[0], DELTA, what + " x");
        assertEquals(y, actual[1], DELTA, what + " y");
        assertEquals(z, actual[2], DELTA, what + " z");
    }

    /** UVs are two-component, so they get their own assertion rather than a zero third one. */
    static void assertUv(float u, float v, float[] actual, String what) {
        assertEquals(u, actual[0], DELTA, what + " u");
        assertEquals(v, actual[1], DELTA, what + " v");
    }

    static void assertUnitLength(float[] vector, String what) {
        double length = Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
        assertEquals(1.0, length, DELTA, what + " should be unit length");
    }

    static void assertBounds(ModelScene scene, float... expected) {
        float[] bounds = scene.bounds();
        assertEquals(expected.length, bounds.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], bounds[i], DELTA, "bounds[" + i + "]");
        }
    }

    /**
     * Prints the measured geometry of a scene. The numbers appear in the test log so a reviewer can
     * see what was actually triangulated rather than only that some assertion passed.
     */
    static void report(String testName, ModelScene scene) {
        StringBuilder line = new StringBuilder("[obj-test] " + testName + ": ");
        line.append("nodes=").append(scene.nodeCount())
                .append(" meshes=").append(scene.meshes().length)
                .append(" primitives=").append(primitives(scene).size())
                .append(" vertices=").append(vertexCount(scene))
                .append(" triangles=").append(triangleCount(scene))
                .append(" materials=").append(scene.materials().length)
                .append(" skins=").append(scene.skins().length)
                .append(" animations=").append(scene.animations().size());
        System.out.println(line);
        for (ModelPrimitive primitive : primitives(scene)) {
            System.out.println("    primitive '" + primitive.name() + "' material=" + primitive.materialIndex()
                    + " vertices=" + primitive.vertexCount() + " indices=" + primitive.indexCount()
                    + " uvs=" + (primitive.uvs() == null ? "none" : "present"));
        }
    }

    /** Asserts the invariants every OBJ scene must satisfy, whatever the file said. */
    static void assertSceneShape(ModelScene scene) {
        assertEquals(0, scene.skins().length, "OBJ has no skinning");
        assertTrue(scene.animations().isEmpty(), "OBJ carries no animation");
        for (int node = 0; node < scene.nodeCount(); node++) {
            assertEquals(node, scene.nodeTemplates()[node].index(), "node index must match its position");
            assertEquals(-1, scene.nodeTemplates()[node].parentIndex(), "OBJ nodes are all roots");
            assertEquals(node, scene.nodeTemplates()[node].meshIndex(), "node " + node + " must own mesh " + node);
            assertEquals(0.0f, scene.nodeTemplates()[node].restTranslationArray()[0]);
            assertEquals(0.0f, scene.nodeTemplates()[node].restTranslationArray()[1]);
            assertEquals(0.0f, scene.nodeTemplates()[node].restTranslationArray()[2]);
            assertEquals(1.0f, scene.nodeTemplates()[node].restScaleArray()[0]);
            assertEquals(1.0f, scene.nodeTemplates()[node].restScaleArray()[1]);
            assertEquals(1.0f, scene.nodeTemplates()[node].restScaleArray()[2]);
            assertEquals(1.0f, scene.nodeTemplates()[node].restRotationArray()[3], "identity rotation");
        }
        assertEquals(scene.nodeCount(), scene.rootNodes().length);
        assertEquals(scene.meshes().length, scene.nodeCount());
        for (ModelPrimitive primitive : primitives(scene)) {
            assertTrue(primitive.materialIndex() >= 0 && primitive.materialIndex() < scene.materials().length,
                    "primitive material index " + primitive.materialIndex() + " is outside the material list");
            for (int index : primitive.indicesInt()) {
                assertTrue(index >= 0 && index < primitive.vertexCount(),
                        "index " + index + " is out of range for " + primitive.vertexCount() + " vertices");
            }
            if (primitive.normals() != null) {
                assertEquals(primitive.vertexCount() * 3, primitive.normals().length);
            }
        }
    }
}
