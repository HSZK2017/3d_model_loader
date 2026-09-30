package com.model3d.loader.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the composition order of {@link Mat4#compose}.
 *
 * <h2>The defect this guards</h2>
 * {@code compose} documented itself as {@code Translation * Rotation * Scale} - the order glTF
 * defines for a node's TRS - but scaled the matrix's <b>rows</b>:
 *
 * <pre>
 *   r[c * 4]     *= scale[0];   // for c = 0,1,2 - the same factor on every column's x
 *   r[c * 4 + 1] *= scale[1];
 *   r[c * 4 + 2] *= scale[2];
 * </pre>
 *
 * <p>With column-major storage that is {@code S * R}, not {@code R * S}. The two agree whenever the
 * rotation is diagonal in the scale's own basis - which is why the corpus and the fixtures, whose
 * rotations are 180 degrees about an axis or uniform-scale quarter turns, never showed it - and they
 * differ by a whole scale factor on any other rotation. Measured: for a quarter turn about +Y with
 * scale (2, 1, 4), a point at (1, 0, 0) lands at (0, 0, -4) instead of (0, 0, -2).
 *
 * <p>These tests need no game and no GL: the assertion is against an explicitly built
 * {@code T * R * S} product and against hand-computed coordinates, so it cannot pass by agreeing
 * with the implementation's own arithmetic.
 */
class Mat4ComposeTest {

    /** {@code +90} degrees about +Y, as a unit quaternion {@code (x, y, z, w)}. */
    private static float[] quarterTurnAboutY() {
        float half = (float) Math.sin(Math.PI / 4.0);
        return new float[] {0.0f, half, 0.0f, half};
    }

    @Test
    @DisplayName("scale is applied before the rotation: R * S, not S * R")
    void scaleIsAppliedBeforeTheRotation() {
        Mat4 composed = Mat4.compose(new float[] {0f, 0f, 0f}, quarterTurnAboutY(),
                new float[] {2f, 1f, 4f});
        float[] out = new float[3];
        Mat4.transform(composed.raw(), 0, 1f, 0f, 0f, 1f, out);

        // R(+90 about Y) maps +X to -Z. R * S * (1,0,0) = R * (2,0,0) = (0, 0, -2).
        // The pre-fix S * R gave (0, 0, -4): the scale factor of the *z* axis applied to a point
        // that the rotation moved onto the z axis.
        assertEquals(0.0f, out[0], 1e-5f, "x");
        assertEquals(0.0f, out[1], 1e-5f, "y");
        assertEquals(-2.0f, out[2], 1e-5f, "z: the x axis scaled by 2, then rotated onto -Z");
    }

    @Test
    @DisplayName("compose equals an explicitly built Translation * Rotation * Scale product")
    void composeEqualsTheExplicitProduct() {
        float[] translation = {5f, 6f, 7f};
        float[] rotation = quarterTurnAboutY();
        float[] scale = {2f, 1f, 4f};

        Mat4 explicit = Mat4.translation(translation[0], translation[1], translation[2])
                .multiply(Mat4.fromQuat(rotation[0], rotation[1], rotation[2], rotation[3]))
                .multiply(Mat4.scale(scale[0], scale[1], scale[2]));

        Mat4 composed = Mat4.compose(translation, rotation, scale);
        assertEquals(0f, explicit.maxDifference(composed), 1e-5f,
                "compose must be T * R * S, the order its own javadoc names");
    }

    @Test
    @DisplayName("a diagonal rotation leaves the two orders indistinguishable (why the old tests missed it)")
    void diagonalRotationCannotDiscriminate() {
        // 180 degrees about X: R = diag(1, -1, -1), which commutes with any diagonal scale, so the
        // buggy and correct orders agree. Recorded here so nobody "simplifies" the test above into
        // this case and believes it still covers the order.
        float[] halfTurnAboutX = {1f, 0f, 0f, 0f};
        float[] scale = {2f, 1f, 4f};
        Mat4 composed = Mat4.compose(new float[] {0f, 0f, 0f}, halfTurnAboutX, scale);
        Mat4 explicit = Mat4.fromQuat(1f, 0f, 0f, 0f).multiply(Mat4.scale(2f, 1f, 4f));
        assertEquals(0f, explicit.maxDifference(composed), 1e-6f);
    }
}
