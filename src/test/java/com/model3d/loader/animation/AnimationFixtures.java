package com.model3d.loader.animation;

import com.model3d.loader.math.Mat4;
import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelMesh;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.scene.ModelSkin;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-built scene/animation fixtures for the animation suite.
 *
 * <p>Deliberately independent of every parser: the runtime is testable from raw arrays, so a
 * failure here is a failure of the animation maths and never of a file reader.
 */
final class AnimationFixtures {

    static final float[] IDENTITY_QUAT = { 0f, 0f, 0f, 1f };
    static final float[] UNIT_SCALE = { 1f, 1f, 1f };

    static final float[] IDENTITY_MATRIX = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
    };

    private AnimationFixtures() {
    }

    static float[] vec(float x, float y, float z) {
        return new float[] { x, y, z };
    }

    /** Quaternion for a rotation of {@code degrees} about +Z. */
    static float[] quatZ(double degrees) {
        double half = Math.toRadians(degrees) * 0.5;
        return new float[] { 0f, 0f, (float) Math.sin(half), (float) Math.cos(half) };
    }

    /** Rotation angle of {@code (x,y,z,w)} in degrees, in {@code (-180, 180]}. */
    static double quatAngleDegrees(float[] q, int offset) {
        return Math.toDegrees(2.0 * Math.atan2(q[offset + 2], q[offset + 3]));
    }

    static ModelNode node(int index, String name, int parent, float[] translation, float[] rotation, float[] scale) {
        return new ModelNode(index, name, parent, -1, -1, translation, rotation, scale);
    }

    static ModelScene scene(String name, ModelNode[] nodes, int[] roots, ModelSkin[] skins) {
        return new ModelScene(name, roots, nodes, new ModelMesh[0], new ModelMaterial[0], skins,
                List.of(), new float[] { 0, 0, 0, 1, 1, 1 }, "animation test fixture");
    }

    /** Track for STEP/LINEAR: one value block per keyframe. */
    static ModelAnimation.Track track(int node, ModelAnimation.Path path,
                                      ModelAnimation.Interpolation interpolation,
                                      int firstKeyframe, int keyframeCount, int valueOffset) {
        return new ModelAnimation.Track(node, path, interpolation, firstKeyframe, keyframeCount, valueOffset,
                componentsOf(path), 1);
    }

    /** Track for CUBICSPLINE: in-tangent, value and out-tangent blocks per keyframe. */
    static ModelAnimation.Track cubicTrack(int node, ModelAnimation.Path path,
                                           int firstKeyframe, int keyframeCount, int valueOffset) {
        return new ModelAnimation.Track(node, path, ModelAnimation.Interpolation.CUBICSPLINE, firstKeyframe,
                keyframeCount, valueOffset, componentsOf(path), 3);
    }

    private static int componentsOf(ModelAnimation.Path path) {
        return path == ModelAnimation.Path.ROTATION ? 4 : 3;
    }

    static Mat4 matrix(float[] sixteen) {
        return new Mat4(sixteen.clone());
    }

    /** One animation whose single track starts at keyframe 0, the common fixture shape. */
    static ModelAnimation singleTrack(String name, float[] times, float[] values, ModelAnimation.Track track) {
        return new ModelAnimation(name, times, values, new ModelAnimation.Track[] { track });
    }

    static String vec3(float[] a, int offset) {
        return String.format(Locale.ROOT, "(%.6f, %.6f, %.6f)", a[offset], a[offset + 1], a[offset + 2]);
    }

    static String vec4(float[] a, int offset) {
        return String.format(Locale.ROOT, "(%.6f, %.6f, %.6f, %.6f)",
                a[offset], a[offset + 1], a[offset + 2], a[offset + 3]);
    }

    /** Column-major, as three rows of four columns, so a transpose is visible on sight. */
    static String matrix(float[] a, int offset) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < 4; row++) {
            if (row > 0) {
                sb.append(" | ");
            }
            sb.append(String.format(Locale.ROOT, "%.6f %.6f %.6f %.6f",
                    a[offset + row], a[offset + 4 + row], a[offset + 8 + row], a[offset + 12 + row]));
        }
        return sb.toString();
    }

    static void assertVec(String label, float[] expected, float[] actual, int offset, float delta) {
        System.out.printf(Locale.ROOT, "[assert] %-34s expected %s  actual %s%n",
                label, vec3(expected, 0), vec3(actual, offset));
        for (int c = 0; c < 3; c++) {
            assertEquals(expected[c], actual[offset + c], delta, label + " component " + c);
        }
    }

    static void assertQuat(String label, float[] expected, float[] actual, int offset, float delta) {
        System.out.printf(Locale.ROOT, "[assert] %-34s expected %s  actual %s%n",
                label, vec4(expected, 0), vec4(actual, offset));
        for (int c = 0; c < 4; c++) {
            assertEquals(expected[c], actual[offset + c], delta, label + " component " + c);
        }
    }

    /** Asserts a 16-float column-major matrix and prints both, so a transposed result is obvious. */
    static void assertMatrix(String label, float[] expected, float[] actual, int offset, float delta) {
        float maxDelta = 0.0f;
        for (int i = 0; i < 16; i++) {
            maxDelta = Math.max(maxDelta, Math.abs(expected[i] - actual[offset + i]));
        }
        System.out.printf(Locale.ROOT, "[assert] %s%n    expected %s%n    actual   %s%n    maxDelta %.8f%n",
                label, matrix(expected, 0), matrix(actual, offset), maxDelta);
        for (int i = 0; i < 16; i++) {
            assertEquals(expected[i], actual[offset + i], delta, label + " element " + i);
        }
    }

    static void assertAllZero(String label, float[] actual, int offset, int count) {
        for (int i = 0; i < count; i++) {
            assertEquals(0.0f, actual[offset + i], 0.0f, label + " element " + i + " should be untouched");
        }
    }

    static void assertAllZero(String label, byte[] actual, int offset, int count) {
        for (int i = 0; i < count; i++) {
            assertEquals((byte) 0, actual[offset + i], label + " element " + i + " should be cleared");
        }
    }

    static void assertFinite(String label, float[] actual, int offset, int count) {
        for (int i = 0; i < count; i++) {
            assertTrue(Float.isFinite(actual[offset + i]), label + " element " + i + " = " + actual[offset + i]);
        }
    }
}
