package com.model3d.loader.animation;

import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.scene.ModelSkin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static com.model3d.loader.animation.AnimationFixtures.IDENTITY_QUAT;
import static com.model3d.loader.animation.AnimationFixtures.UNIT_SCALE;
import static com.model3d.loader.animation.AnimationFixtures.assertVec;
import static com.model3d.loader.animation.AnimationFixtures.node;
import static com.model3d.loader.animation.AnimationFixtures.quatZ;
import static com.model3d.loader.animation.AnimationFixtures.scene;
import static com.model3d.loader.animation.AnimationFixtures.singleTrack;
import static com.model3d.loader.animation.AnimationFixtures.track;
import static com.model3d.loader.animation.AnimationFixtures.vec;
import static com.model3d.loader.animation.AnimationFixtures.vec3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clock half of the contract: what {@link AnimationState} does and does not do with a delta,
 * and the end-to-end consequence for the pose.
 *
 * <p>The distinction these tests pin: the state refuses a non-finite delta so the clock can never
 * become NaN, while the sampler still clamps defensively if a caller hands it NaN directly. Two
 * separate guards, because only one of them can rely on the other.
 */
class AnimationStateTest {

    private static final float DELTA = 1e-4f;

    /** root(0) -> mid(1) -> tip(2) with a one-second 0 -> 90 degree root rotation. */
    private static ModelScene chainScene() {
        ModelNode root = node(0, "root", -1, vec(0, 0, 0), IDENTITY_QUAT, UNIT_SCALE);
        ModelNode mid = node(1, "mid", 0, vec(1, 0, 0), IDENTITY_QUAT, UNIT_SCALE);
        ModelNode tip = node(2, "tip", 1, vec(1, 0, 0), IDENTITY_QUAT, UNIT_SCALE);
        return scene("chain", new ModelNode[] { root, mid, tip }, new int[] { 0 }, new ModelSkin[0]);
    }

    private static ModelAnimation armAnimation() {
        float[] q0 = quatZ(0);
        float[] q1 = quatZ(90);
        return singleTrack("arm", new float[] { 0f, 1f },
                new float[] { q0[0], q0[1], q0[2], q0[3], q1[0], q1[1], q1[2], q1[3] },
                track(0, ModelAnimation.Path.ROTATION, ModelAnimation.Interpolation.LINEAR, 0, 2, 0));
    }

    @Test
    @DisplayName("a non-finite delta is discarded: the clock never becomes NaN")
    void nonFiniteDeltaLeavesTheClockAlone() {
        AnimationState state = new AnimationState();
        state.play(armAnimation(), true, false, 0.0f);
        state.advance(0.25f);
        float before = state.time();
        System.out.printf(Locale.ROOT, "[clock] after advance(0.25): t=%.6f%n", before);

        state.advance(Float.NaN);
        System.out.printf(Locale.ROOT, "[clock] after advance(NaN): t=%.6f (unchanged), completions=%d%n",
                state.time(), state.completionCount());
        assertEquals(before, state.time(), 0.0f, "NaN delta must not move the clock");
        assertTrue(Float.isFinite(state.time()), "clock stayed finite");
        assertFalse(state.isFinished(), "NaN delta is not a completion");

        state.advance(Float.POSITIVE_INFINITY);
        state.advance(Float.NEGATIVE_INFINITY);
        System.out.printf(Locale.ROOT, "[clock] after advance(+/-Inf): t=%.6f (unchanged)%n", state.time());
        assertEquals(before, state.time(), 0.0f, "infinite delta must not move the clock either");

        state.advance(0.25f);
        System.out.printf(Locale.ROOT, "[clock] after a finite advance(0.25): t=%.6f%n", state.time());
        assertEquals(before + 0.25f, state.time(), DELTA, "a finite delta still advances normally");
    }

    @Test
    @DisplayName("a NaN delta in the middle of playback does not freeze the pose at keyframe 0")
    void nanDeltaDoesNotCollapseThePose() {
        // The failure this guards against: NaN clock -> every comparison in the sampler is false ->
        // the pose silently collapses to the first keyframe, i.e. the model appears frozen in its
        // bind pose with nothing in the log. Guarding the state is what keeps that from happening.
        ModelScene scene = chainScene();
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(3, 0);
        AnimationState state = new AnimationState();
        state.play(armAnimation(), true, false, 0.0f);

        for (int frame = 0; frame < 30; frame++) {
            state.advance(1.0f / 60.0f);
        }
        AnimationPlayer.apply(scene, nodes, state, pose);
        float[] tipPosition = new float[3];
        nodes[2].globalTransform().transformPoint(0, 0, 0, tipPosition, 0);
        System.out.printf(Locale.ROOT, "[clock-pose] 30 frames in: t=%.6f tip=%s%n",
                state.time(), vec3(tipPosition, 0));

        state.advance(Float.NaN);
        AnimationPlayer.apply(scene, nodes, state, pose);
        float[] afterNaNTip = new float[3];
        nodes[2].globalTransform().transformPoint(0, 0, 0, afterNaNTip, 0);
        System.out.printf(Locale.ROOT, "[clock-pose] after advance(NaN): t=%.6f tip=%s (pose unchanged)%n",
                state.time(), vec3(afterNaNTip, 0));

        assertVec("pose unchanged by a NaN delta", tipPosition, afterNaNTip, 0, 0.0f);
        // Half a second in, the root is at 45 degrees, so the two-unit tip is at (2cos45, 2sin45).
        assertVec("tip at the real clock position",
                new float[] { (float) (2.0 * Math.cos(Math.PI / 4)), (float) (2.0 * Math.sin(Math.PI / 4)), 0f },
                afterNaNTip, 0, DELTA);
    }
}
