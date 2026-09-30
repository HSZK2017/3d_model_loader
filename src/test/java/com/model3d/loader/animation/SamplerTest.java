package com.model3d.loader.animation;

import com.model3d.loader.scene.ModelAnimation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static com.model3d.loader.animation.AnimationFixtures.assertQuat;
import static com.model3d.loader.animation.AnimationFixtures.assertVec;
import static com.model3d.loader.animation.AnimationFixtures.quatAngleDegrees;
import static com.model3d.loader.animation.AnimationFixtures.quatZ;
import static com.model3d.loader.animation.AnimationFixtures.singleTrack;
import static com.model3d.loader.animation.AnimationFixtures.track;
import static com.model3d.loader.animation.AnimationFixtures.cubicTrack;
import static com.model3d.loader.animation.AnimationFixtures.vec3;
import static com.model3d.loader.animation.AnimationFixtures.vec4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyframe evaluation, pinned against values computed by hand.
 *
 * <p>Every assertion prints the number it checks. A green run therefore leaves the sampled values
 * in the log, which is what makes "the maths is right" reviewable rather than merely asserted.
 */
class SamplerTest {

    private static final float DELTA = 1e-4f;

    @Test
    @DisplayName("LINEAR: midpoint of a 0 -> 10 translation is exactly 5")
    void linearMidpointIsExact() {
        float[] times = { 0f, 1f };
        float[] values = { 0, 0, 0, 10, 0, 0 };
        ModelAnimation animation = singleTrack("linear",
                times, values, track(0, ModelAnimation.Path.TRANSLATION,
                        ModelAnimation.Interpolation.LINEAR, 0, 2, 0));
        float[] out = new float[3];

        Sampler.evaluate(animation, animation.tracks()[0], 0.25f, out, 0);
        System.out.println("[LINEAR] t=0.25  sampled " + vec3(out, 0));
        assertEquals(2.5f, out[0], DELTA, "quarter point");

        Sampler.evaluate(animation, animation.tracks()[0], 0.5f, out, 0);
        System.out.println("[LINEAR] t=0.50  sampled " + vec3(out, 0) + "  (expect x=5)");
        assertVec("LINEAR midpoint", new float[] { 5f, 0f, 0f }, out, 0, DELTA);
    }

    @Test
    @DisplayName("STEP: holds the previous keyframe's value across the whole segment")
    void stepHoldsPreviousValue() {
        float[] times = { 0f, 1f, 2f };
        float[] values = { 1, 0, 0, 2, 0, 0, 3, 0, 0 };
        ModelAnimation animation = singleTrack("step",
                times, values, track(0, ModelAnimation.Path.TRANSLATION,
                        ModelAnimation.Interpolation.STEP, 0, 3, 0));
        float[] out = new float[3];

        float[][] probes = { { 0.0f, 1f }, { 0.999f, 1f }, { 1.0f, 2f }, { 1.999f, 2f }, { 2.0f, 3f } };
        for (float[] probe : probes) {
            Sampler.evaluate(animation, animation.tracks()[0], probe[0], out, 0);
            System.out.printf(Locale.ROOT, "[STEP] t=%.3f  sampled x=%.6f  expected x=%.6f%n",
                    probe[0], out[0], probe[1]);
            assertEquals(probe[1], out[0], DELTA, "STEP at t=" + probe[0]);
        }
    }

    @Test
    @DisplayName("LINEAR rotation: 0 -> 180 degrees about Z slerps to 90 degrees at the midpoint")
    void slerpQuarterTurnMidpoint() {
        float[] times = { 0f, 1f };
        float[] q0 = quatZ(0);
        float[] q1 = quatZ(180);
        float[] values = { q0[0], q0[1], q0[2], q0[3], q1[0], q1[1], q1[2], q1[3] };
        ModelAnimation animation = singleTrack("turn",
                times, values, track(0, ModelAnimation.Path.ROTATION,
                        ModelAnimation.Interpolation.LINEAR, 0, 2, 0));
        float[] out = new float[4];

        Sampler.evaluate(animation, animation.tracks()[0], 0.5f, out, 0);
        double angle = quatAngleDegrees(out, 0);
        float dot = q0[0] * q1[0] + q0[1] * q1[1] + q0[2] * q1[2] + q0[3] * q1[3];
        System.out.printf(Locale.ROOT,
                "[slerp] q0=%s q1=%s dot=%.6f -> midpoint %s angle=%.4f deg%n",
                vec4(q0, 0), vec4(q1, 0), dot, vec4(out, 0), angle);

        // 180 degrees about Z is (0,0,1,0); the half-way quaternion is the 90 degree rotation.
        assertQuat("slerp midpoint of 0 -> 180 deg", quatZ(90), out, 0, DELTA);
        assertEquals(90.0, angle, 1e-3, "midpoint angle in degrees");
    }

    @Test
    @DisplayName("slerp takes the SHORT arc when the two quaternions are on opposite hemispheres")
    void slerpTakesShortArc() {
        // 0 degrees and 350 degrees about Z: as quaternions these are near-antipodal - the raw
        // dot product is about -0.996 - so without the sign flip the interpolation walks the
        // 350-degree way round instead of the 10-degree way.
        float[] times = { 0f, 1f };
        float[] q0 = quatZ(0);
        float[] q1 = quatZ(350);
        float[] values = { q0[0], q0[1], q0[2], q0[3], q1[0], q1[1], q1[2], q1[3] };
        ModelAnimation animation = singleTrack("short-arc",
                times, values, track(0, ModelAnimation.Path.ROTATION,
                        ModelAnimation.Interpolation.LINEAR, 0, 2, 0));
        float[] out = new float[4];
        float[] longWay = quatZ(175);

        Sampler.evaluate(animation, animation.tracks()[0], 0.5f, out, 0);
        float dot = q0[0] * q1[0] + q0[1] * q1[1] + q0[2] * q1[2] + q0[3] * q1[3];
        double angle = quatAngleDegrees(out, 0);
        System.out.printf(Locale.ROOT,
                "[slerp-short-arc] q0=%s q1=%s dot=%.6f%n"
                        + "[slerp-short-arc] midpoint %s angle=%.4f deg (long arc would be %s, 175.0000 deg)%n",
                vec4(q0, 0), vec4(q1, 0), dot, vec4(out, 0), angle, vec4(longWay, 0));

        assertTrue(dot < -0.99f, "fixture must be near-antipodal, dot=" + dot);
        // -5 degrees: reached by negating q1 and slerping the short way.
        assertQuat("slerp short-arc midpoint", quatZ(-5), out, 0, DELTA);
        assertEquals(-5.0, angle, 1e-3, "short-arc midpoint angle in degrees");
        assertTrue(Math.abs(out[2]) < 0.5f,
                "long-arc result would sit at z=" + longWay[2] + ", got z=" + out[2]);
    }

    @Test
    @DisplayName("CUBICSPLINE translation: Hermite with tangents scaled by the segment duration")
    void cubicSplineHermiteMatchesHandComputedValue() {
        // Two keyframes, t = 0 and t = 2, so the segment duration dt = 2.
        //   keyframe 0: in-tangent (0,0,0), value (0,0,0), out-tangent (0,0,3)  -> 3 units/second
        //   keyframe 1: in-tangent (0,0,0), value (0,0,4), out-tangent (0,0,0)
        // Hand computation at u = 0.5 with dt = 2:
        //   h00=0.5, h10=0.125, h01=0.5, h11=-0.125
        //   z = 0.5*0 + 0.125*2*3 + 0.5*4 + (-0.125)*2*0 = 0.75 + 2.0 = 2.75
        // At u = 0.75: h00=0.15625, h10=0.046875, h01=0.84375, h11=-0.140625
        //   z = 0.046875*2*3 + 0.84375*4 = 0.28125 + 3.375 = 3.65625
        // Without the deltaTime factor the same two points would be 2.375 and 3.515625, which is
        // the "slightly wrong motion" the spec factor exists to prevent.
        float[] times = { 0f, 2f };
        float[] values = {
                0, 0, 0, 0, 0, 0, 0, 0, 3,
                0, 0, 0, 0, 0, 4, 0, 0, 0
        };
        ModelAnimation animation = singleTrack("cubic",
                times, values, cubicTrack(0, ModelAnimation.Path.TRANSLATION, 0, 2, 0));
        float[] out = new float[3];

        Sampler.evaluate(animation, animation.tracks()[0], 1.0f, out, 0);
        System.out.printf(Locale.ROOT,
                "[CUBICSPLINE] dt=2.0 u=0.50 sampled %s  expected z=2.750000 (no dt factor: 2.375000)%n",
                vec3(out, 0));
        assertVec("CUBICSPLINE u=0.5", new float[] { 0f, 0f, 2.75f }, out, 0, DELTA);

        Sampler.evaluate(animation, animation.tracks()[0], 1.5f, out, 0);
        System.out.printf(Locale.ROOT,
                "[CUBICSPLINE] dt=2.0 u=0.75 sampled %s  expected z=3.656250 (no dt factor: 3.515625)%n",
                vec3(out, 0));
        assertVec("CUBICSPLINE u=0.75", new float[] { 0f, 0f, 3.65625f }, out, 0, DELTA);

        // The endpoints are exact: the Hermite basis is built to pass through p0 and p1.
        Sampler.evaluate(animation, animation.tracks()[0], 0.0f, out, 0);
        System.out.println("[CUBICSPLINE] t=0.0 sampled " + vec3(out, 0) + "  (keyframe value)");
        assertVec("CUBICSPLINE at t=0", new float[] { 0f, 0f, 0f }, out, 0, DELTA);
        Sampler.evaluate(animation, animation.tracks()[0], 2.0f, out, 0);
        System.out.println("[CUBICSPLINE] t=2.0 sampled " + vec3(out, 0) + "  (keyframe value)");
        assertVec("CUBICSPLINE at t=2", new float[] { 0f, 0f, 4f }, out, 0, DELTA);
    }

    @Test
    @DisplayName("CUBICSPLINE on a rotation falls back to slerp instead of Hermite")
    void cubicSplineRotationFallsBackToSlerp() {
        // glTF stores rotation tangents as quaternions, which do not compose under the cubic
        // Hermite formula; ModelAnimation.Interpolation documents the downgrade to LINEAR. The
        // tangents here are deliberately non-zero so a Hermite result would be visibly different.
        float[] times = { 0f, 1f };
        float[] q180 = quatZ(180);
        float[] values = {
                0, 0, 0.5f, 0.5f, 0, 0, 0, 1, 0, 0, 0.5f, 0.5f,
                0, 0, -0.5f, -0.5f, q180[0], q180[1], q180[2], q180[3], 0, 0, 0, 0
        };
        ModelAnimation animation = singleTrack("cubic-rotation",
                times, values, cubicTrack(0, ModelAnimation.Path.ROTATION, 0, 2, 0));
        float[] out = new float[4];

        Sampler.evaluate(animation, animation.tracks()[0], 0.5f, out, 0);
        double angle = quatAngleDegrees(out, 0);
        System.out.printf(Locale.ROOT,
                "[cubic-rotation] midpoint %s angle=%.4f deg - slerp of identity and 180 deg, tangents ignored%n",
                vec4(out, 0), angle);
        assertQuat("cubic rotation downgraded to slerp", quatZ(90), out, 0, DELTA);
        assertEquals(90.0, angle, 1e-3, "downgraded rotation midpoint angle");
    }

    @Test
    @DisplayName("time outside the keyframe range clamps to the first/last value")
    void timeOutsideRangeClamps() {
        float[] times = { 1f, 3f };
        float[] values = { 0, 0, 0, 10, 0, 0 };
        ModelAnimation animation = singleTrack("clamp",
                times, values, track(0, ModelAnimation.Path.TRANSLATION,
                        ModelAnimation.Interpolation.LINEAR, 0, 2, 0));
        float[] out = new float[3];

        float[][] probes = { { 0.5f, 0f }, { 1.0f, 0f }, { 2.0f, 5f }, { 3.0f, 10f }, { 99f, 10f }, { Float.NaN, 0f } };
        for (float[] probe : probes) {
            Sampler.evaluate(animation, animation.tracks()[0], probe[0], out, 0);
            System.out.printf(Locale.ROOT, "[clamp] t=%.3f  sampled x=%.6f  expected x=%.6f%n",
                    probe[0], out[0], probe[1]);
            assertEquals(probe[1], out[0], DELTA, "clamp at t=" + probe[0]);
        }
    }

    @Test
    @DisplayName("a one-keyframe track is constant at every time")
    void singleKeyframeIsConstant() {
        float[] times = { 0.5f };
        float[] values = { 7, -1, 2 };
        ModelAnimation animation = singleTrack("constant",
                times, values, track(0, ModelAnimation.Path.TRANSLATION,
                        ModelAnimation.Interpolation.LINEAR, 0, 1, 0));
        float[] out = new float[3];

        for (float probe : new float[] { -5f, 0f, 0.5f, 12f }) {
            Sampler.evaluate(animation, animation.tracks()[0], probe, out, 0);
            System.out.printf(Locale.ROOT, "[single-key] t=%.3f  sampled %s%n", probe, vec3(out, 0));
            assertVec("single keyframe at t=" + probe, new float[] { 7f, -1f, 2f }, out, 0, DELTA);
        }
    }

    @Test
    @DisplayName("duplicate keyframe times give a zero-length segment: later value, no NaN")
    void duplicateTimesTakeLaterValue() {
        // times 0, 1, 1, 2 - an exporter emitting a repeated keyframe (a hold) produces this.
        // The zero-length segment between keyframe 1 and 2 must never be used as a denominator.
        float[] times = { 0f, 1f, 1f, 2f };
        float[] values = { 0, 0, 0, 10, 0, 0, 20, 0, 0, 30, 0, 0 };
        ModelAnimation animation = singleTrack("duplicate",
                times, values, track(0, ModelAnimation.Path.TRANSLATION,
                        ModelAnimation.Interpolation.LINEAR, 0, 4, 0));
        float[] out = new float[3];

        float[][] probes = { { 0.5f, 5f }, { 1.0f, 20f }, { 1.5f, 25f }, { 2.0f, 30f } };
        for (float[] probe : probes) {
            Sampler.evaluate(animation, animation.tracks()[0], probe[0], out, 0);
            System.out.printf(Locale.ROOT, "[dup-time] t=%.3f  sampled x=%.6f  expected x=%.6f%n",
                    probe[0], out[0], probe[1]);
            assertEquals(probe[1], out[0], DELTA, "duplicate times at t=" + probe[0]);
            assertTrue(Float.isFinite(out[0]), "no NaN from the zero-length segment");
        }
    }

    @Test
    @DisplayName("a track whose metadata points outside its arrays is skipped, not thrown")
    void malformedTrackMetadataIsSkipped() {
        // ModelAnimation's constructor already range-checks the times slice - it reads the last
        // keyframe time - so a track whose firstKeyframe/keyframeCount disagree with `times` cannot
        // even be built (documented by the assertThrows below). What no constructor checks is the
        // VALUES slice, so a wrong valueOffset constructs fine and then reads off the end; that is
        // the case the sampler guards, because reading off the end kills the render thread.
        float[] times = { 0f, 1f };
        float[] values = { 0, 0, 0, 10, 0, 0 };
        ModelAnimation animation = singleTrack("short-values", times, values,
                track(0, ModelAnimation.Path.TRANSLATION, ModelAnimation.Interpolation.LINEAR, 0, 2, 99));
        float[] out = { -1, -1, -1 };

        Sampler.evaluate(animation, animation.tracks()[0], 0.5f, out, 0);
        System.out.println("[malformed] valueOffset past the end of values: target " + vec3(out, 0)
                + " (untouched, no throw)");
        assertVec("bad valueOffset leaves the target alone", new float[] { -1f, -1f, -1f }, out, 0, 0.0f);

        // A Track is immutable but the Sampler takes animation and track as separate arguments, so
        // a caller (the offline inspector, a tool) can hand it a pair that does not belong together.
        // That is the reachable path to the times guard, and it must skip rather than throw.
        ModelAnimation longAnimation = singleTrack("long", new float[] { 0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f },
                new float[] { 0, 0, 0, 1, 0, 0, 2, 0, 0, 3, 0, 0, 4, 0, 0, 5, 0, 0, 6, 0, 0, 7, 0, 0, 8, 0, 0, 9, 0, 0 },
                track(0, ModelAnimation.Path.TRANSLATION, ModelAnimation.Interpolation.LINEAR, 7, 3, 21));
        float[] mismatched = { -1, -1, -1 };
        Sampler.evaluate(animation, longAnimation.tracks()[0], 0.5f, mismatched, 0);
        System.out.println("[malformed] track borrowed from a different animation (times slice out of"
                + " range for this one): target " + vec3(mismatched, 0) + " (untouched, no throw)");
        assertVec("mismatched pair leaves the target alone", new float[] { -1f, -1f, -1f }, mismatched, 0, 0.0f);

        // And the constructor barrier itself, so the reason the first case is the only reachable one
        // is pinned rather than assumed. RuntimeException, not AIOOBE specifically: the contract is
        // "rejected", and the parser layer turns it into a ModelParseException.
        assertThrows(RuntimeException.class, () -> singleTrack("bad-times", new float[] { 0f, 1f }, values,
                track(0, ModelAnimation.Path.TRANSLATION, ModelAnimation.Interpolation.LINEAR, 7, 2, 0)));
        System.out.println("[malformed] ModelAnimation rejects a track whose times slice is out of range");
    }

    @Test
    @DisplayName("empty track writes nothing and never throws")
    void emptyTrackIsSkipped() {
        float[] times = { 0f };
        float[] values = new float[0];
        ModelAnimation animation = singleTrack("empty",
                times, values, track(0, ModelAnimation.Path.TRANSLATION,
                        ModelAnimation.Interpolation.LINEAR, 0, 0, 0));
        float[] out = { 1, 2, 3 };

        Sampler.evaluate(animation, animation.tracks()[0], 0.5f, out, 0);
        System.out.println("[empty-track] after sampling a 0-keyframe track: " + vec3(out, 0) + " (unchanged)");
        assertVec("empty track leaves the target alone", new float[] { 1f, 2f, 3f }, out, 0, 0.0f);
    }
}
