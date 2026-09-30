package com.model3d.loader.api;

import com.model3d.loader.animation.AnimationState;
import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.resource.DirectoryModelSource;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.tools.TestModelGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reverse playback: the signed playback rate, the clock position a caller reads, and the
 * "run to the end and hold, then run back" recipe an open/close consumer needs.
 *
 * <h2>What is actually being pinned</h2>
 * The feature's whole purpose is that one clip can be played in both directions, so the assertions
 * that matter are not "the number changed" but:
 * <ul>
 *   <li><b>the pose is a function of the clock position, whichever way it was reached</b> - a lid
 *       that opened to 40 degrees and then closed from there must show exactly the pose a fresh
 *       forward play shows at 40 degrees. If the two differ, the model visibly jumps at the moment
 *       the direction flips, and no amount of "the clock moved" assertions would catch it;</li>
 *   <li><b>the clock wraps at both ends</b> - Java's remainder keeps the sign of the dividend, so a
 *       rewind past zero leaves a negative time unless it is folded, and a negative time makes the
 *       sampler clamp to the first keyframe: the model snaps shut instead of continuing from the
 *       end. A forward-only test cannot see that;</li>
 *   <li><b>rate 0 freezes bit for bit</b> - a "held" pose that is resampled with a stale delta
 *       drifts, and the drift is invisible in a screenshot;</li>
 *   <li><b>rate 1 is unchanged</b> - the existing suite is the regression guard for the rest of the
 *       clock, and the step cap is asserted here against {@link AnimationState#MAX_STEP_SECONDS}
 *       for the same reason {@code AnimatedModelIntegrationTest} does it.</li>
 * </ul>
 *
 * <p>The fixture is the project's own generated skinned cube, parsed in process through the real
 * {@code ModelFormatRegistry} - the same route {@code AnimatedModelIntegrationTest} takes, and the
 * only animated file available: no asset in the third-party corpus carries an animation. Its
 * {@code spin} clip is a 2.0 s rotation of node 1 from 0 to 360 degrees, so two different clock
 * positions give two different joint-matrix blocks and the same position gives the same block.
 * No GL and no game: the parsers and the animation runtime are deliberately Minecraft-free.
 */
class ModelPlaybackSpeedTest {

    private static final String SPIN = "spin";

    /** {@code spin}'s own duration, from TestModelGenerator's documented contract. */
    private static final float SPIN_SECONDS = 2.0f;

    /** Step used everywhere below: below the clock's per-call cap, as a renderer's frames are. */
    private static final float FRAME = 0.1f;

    private static ModelScene scene;

    @BeforeAll
    static void parseGeneratedFixture() throws Exception {
        // Its own output directory: this test must not depend on whether another test class's
        // @BeforeAll has run, and it must not write where another one writes.
        Path output = Path.of("build", "test-fixture", "playback_speed_fixture.glb").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, TestModelGenerator.build());
        scene = ModelFormatRegistry.parse(
                new DirectoryModelSource(output.getParent(), "model3d",
                        output.getFileName().toString()),
                "playback_speed");
    }

    // ------------------------------------------------------------------
    // 1. Defaults and the no-regression case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a fresh instance plays forward at rate 1, with no clip attached yet")
    void defaultsAreForwardAndOne() {
        ModelInstance instance = new ModelInstance(scene, "defaults");
        assertEquals(1.0f, instance.playbackSpeed(), 0.0f, "the default rate is the file's own");
        assertEquals(0.0f, instance.animationTime(), 0.0f, "no clip: no clock position");
        assertEquals(0.0f, instance.animationDuration(), 0.0f, "no clip: no duration");

        assertTrue(instance.play(SPIN, true), "the fixture's spin clip must be playable");
        assertEquals(1.0f, instance.playbackSpeed(), 0.0f, "play() must not change the rate");
        assertEquals(SPIN_SECONDS, instance.animationDuration(), 1e-4f, "the clip's own length");
        assertEquals(0.0f, instance.animationTime(), 0.0f, "play() starts at the clip's start");
    }

    @Test
    @DisplayName("rate 1 for one update(dt) advances exactly as the clock always did")
    void rateOneIsUnchanged() {
        ModelInstance coarse = new ModelInstance(scene, "coarse");
        coarse.play(SPIN, true);
        coarse.update(0.5f);
        // The documented per-call cap, asserted the way the integration test asserts it: one coarse
        // update must not integrate half a second, or every long frame would teleport the model.
        assertEquals(AnimationState.MAX_STEP_SECONDS, coarse.animationTime(), 1e-4f,
                "one update(0.5) must be capped at MAX_STEP_SECONDS");
        System.out.printf(Locale.ROOT, "[rate] update(0.5) -> t=%.4f s (cap %.4f s)%n",
                coarse.animationTime(), AnimationState.MAX_STEP_SECONDS);

        ModelInstance stepped = new ModelInstance(scene, "stepped");
        stepped.play(SPIN, true);
        step(stepped, 0.5f);
        assertEquals(0.5f, stepped.animationTime(), 1e-4f, "five 0.1 s steps reach 0.5 s");
        assertEquals(1.0f, stepped.playbackSpeed(), 0.0f, "and the rate is still the default");
    }

    // ------------------------------------------------------------------
    // 2. Backwards movement, and the property that makes it usable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a negative rate moves the clock backwards from where it is, without restarting")
    void negativeRateRewindsFromTheCurrentPosition() {
        ModelInstance instance = new ModelInstance(scene, "rewind");
        instance.play(SPIN, true);
        step(instance, 1.5f);
        assertEquals(1.5f, instance.animationTime(), 1e-3f, "forward to 1.5 s");

        instance.setPlaybackSpeed(-1.0f);
        instance.update(FRAME);
        System.out.printf(Locale.ROOT, "[rate] -1.0 for one 0.1 s frame: t=%.6f s%n",
                instance.animationTime());
        // 1.4, not 0.1 and not 1.9: reversing is a sign flip on the same clock, and a restart would
        // show up as a jump back to the start of the clip.
        assertEquals(1.4f, instance.animationTime(), 1e-3f,
                "a sign flip continues from the current position");

        step(instance, 0.4f);
        assertEquals(1.0f, instance.animationTime(), 1e-3f, "and keeps going down while it is negative");
        assertTrue(instance.animationState().isPlaying(), "a rewinding clip is still playing");
    }

    @Test
    @DisplayName("the pose at a time is the same whether that time was reached forwards or backwards")
    void poseIsAFunctionOfTheClockPositionOnly() {
        ModelInstance forwards = new ModelInstance(scene, "forwards");
        forwards.play(SPIN, true);
        step(forwards, 0.5f);

        ModelInstance backwards = new ModelInstance(scene, "backwards");
        backwards.play(SPIN, true);
        // Past the target, then back onto it. 1.5 s is three quarters of the way into a full turn,
        // so the pose there is distinct from both endpoints and from t = 0.5 reached from t = 1.5 by
        // a wrap - which is the other way this could be wrong.
        step(backwards, 1.5f);
        backwards.setPlaybackSpeed(-1.0f);
        step(backwards, 1.0f);

        assertEquals(0.5f, backwards.animationTime(), 1e-3f,
                "the rewound instance must land on the same clock position");
        float[] forwardPose = forwards.jointMatrices();
        float[] backwardPose = backwards.jointMatrices();
        assertNotNull(forwardPose, "the fixture is skinned, so both must produce joint matrices");
        assertNotNull(backwardPose, "the fixture is skinned, so both must produce joint matrices");

        float maxDelta = maxDelta(forwardPose, backwardPose);
        System.out.printf(Locale.ROOT,
                "[pose] t=0.5 s forwards vs backwards -> max |jointMatrix delta| = %.3e%n", maxDelta);
        // This is the property the open/close demo stands on: the lid closes onto exactly the poses
        // it passed through on the way open. A separate interpolation path for the reverse
        // direction, or a clock that restarts on the sign flip, shows up here as a large delta.
        assertEquals(0.0f, maxDelta, 1e-4f,
                "a clock position must pose identically however it was reached");
        // Guard the guard: two poses that were both all-zero (or both frozen) would compare equal.
        ModelInstance elsewhere = new ModelInstance(scene, "elsewhere");
        elsewhere.play(SPIN, true);
        step(elsewhere, 1.0f);
        float distinctPoseDelta = maxDelta(forwardPose, elsewhere.jointMatrices());
        System.out.printf(Locale.ROOT, "[pose] t=0.5 s vs t=1.0 s -> max delta = %.3e (must be > 0)%n",
                distinctPoseDelta);
        assertTrue(distinctPoseDelta > 0.005f,
                "the assertion above is vacuous unless another time gives a different pose");
    }

    // ------------------------------------------------------------------
    // 3. Wrapping at both ends
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a looping clip run past its end wraps to the start")
    void wrapsForwardAtTheEnd() {
        ModelInstance instance = new ModelInstance(scene, "forward-wrap");
        instance.play(SPIN, true);
        step(instance, 1.9f);
        assertEquals(1.9f, instance.animationTime(), 1e-3f, "just before the end");
        instance.update(0.2f);
        System.out.printf(Locale.ROOT, "[wrap] 1.9 s + 0.2 s forward -> t=%.6f s (duration %.1f)%n",
                instance.animationTime(), instance.animationDuration());
        assertEquals(0.1f, instance.animationTime(), 1e-3f,
                "0.1 s past the end of a 2 s loop is 0.1 s into it");
    }

    @Test
    @DisplayName("a looping clip run before its start wraps to the end")
    void wrapsBackwardsAtTheStart() {
        ModelInstance instance = new ModelInstance(scene, "backward-wrap");
        instance.play(SPIN, true);
        instance.update(0.05f);
        assertEquals(0.05f, instance.animationTime(), 1e-4f, "a little way in");
        instance.setPlaybackSpeed(-1.0f);
        instance.update(0.1f);
        System.out.printf(Locale.ROOT, "[wrap] 0.05 s - 0.1 s backwards -> t=%.6f s (duration %.1f)%n",
                instance.animationTime(), instance.animationDuration());
        // Not negative, and not 0: Java's remainder would leave -0.05 here, the sampler would clamp
        // it to the first keyframe, and the visual result is the model snapping to its closed pose
        // instead of continuing from the far end of the loop.
        assertEquals(SPIN_SECONDS - 0.05f, instance.animationTime(), 1e-3f,
                "a rewind past the start must continue from the end, not go negative");
        assertTrue(instance.animationTime() >= 0.0f, "the clock must never be negative under looping");
    }

    // ------------------------------------------------------------------
    // 4. Rate 0, and non-finite rates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rate 0 freezes the pose: repeated updates leave every joint matrix bit-identical")
    void zeroRateFreezesThePose() {
        ModelInstance instance = new ModelInstance(scene, "frozen");
        instance.play(SPIN, true);
        step(instance, 0.4f);
        instance.setPlaybackSpeed(0.0f);
        instance.update(FRAME);

        float frozenTime = instance.animationTime();
        int frozenGeneration = instance.poseGeneration();
        float[] before = instance.jointMatrices().clone();
        instance.update(FRAME);
        instance.update(1.0f);
        float[] after = instance.jointMatrices();
        System.out.printf(Locale.ROOT, "[freeze] rate 0: t=%.6f -> %.6f, matrices equal=%s%n",
                frozenTime, instance.animationTime(), Arrays.equals(before, after));

        assertEquals(0.0f, instance.playbackSpeed(), 0.0f, "rate 0 is a value, not an error");
        assertEquals(frozenTime, instance.animationTime(), 0.0f,
                "the clock must not move while the rate is 0, even for a 1 s step");
        // Bit-identical, not merely close: a hold that resamples the pose with a stale delta drifts
        // a little every frame, and a drift of a few 1e-7 per frame is invisible in a screenshot
        // while still being a pose that is not the one the caller asked to hold.
        assertTrue(Arrays.equals(before, after),
                "two updates at rate 0 must leave the joint matrices bit-identical");
        // The pose is still resampled (update() always resamples) - it is the clock that is frozen.
        assertTrue(instance.poseGeneration() > frozenGeneration,
                "update() must still resample the pose while frozen");
    }

    @Test
    @DisplayName("a non-finite rate is ignored, leaving the previous rate in force")
    void nonFiniteRateIsIgnored() {
        ModelInstance instance = new ModelInstance(scene, "nonfinite");
        instance.play(SPIN, true);
        step(instance, 0.5f);

        instance.setPlaybackSpeed(Float.NaN);
        assertEquals(1.0f, instance.playbackSpeed(), 0.0f, "NaN must not become the rate");
        instance.setPlaybackSpeed(Float.POSITIVE_INFINITY);
        assertEquals(1.0f, instance.playbackSpeed(), 0.0f, "+inf must not become the rate");
        instance.update(FRAME);
        assertEquals(0.6f, instance.animationTime(), 1e-3f,
                "and the clip keeps playing at the rate it had: a NaN rate would freeze it silently");

        instance.setPlaybackSpeed(-1.0f);
        instance.setPlaybackSpeed(Float.NEGATIVE_INFINITY);
        assertEquals(-1.0f, instance.playbackSpeed(), 0.0f, "-inf must not become the rate");
        instance.update(FRAME);
        assertEquals(0.5f, instance.animationTime(), 1e-3f,
                "the reverse rate that was set before the bad value is still in force");
    }

    // ------------------------------------------------------------------
    // 5. The recipe the demo uses: open forwards, hold, close backwards
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one-shot: run forward to the end, hold there, then rewind to the start and stop")
    void oneShotRunToTheEndCanBeRewound() {
        ModelInstance instance = new ModelInstance(scene, "recipe");
        assertTrue(instance.play(SPIN, false), "the clip must be playable as a one-shot");
        instance.setPlaybackSpeed(1.0f);

        // Opening: step as a renderer would until the clip reports it has reached its end.
        int openFrames = 0;
        while (instance.animationTime() < instance.animationDuration() && openFrames++ < 100) {
            instance.update(FRAME);
        }
        float openTime = instance.animationTime();
        float duration = instance.animationDuration();
        System.out.printf(Locale.ROOT,
                "[recipe] open: %d frames, t=%.6f / %.6f s, playing=%s%n",
                openFrames, openTime, duration, instance.animationState().isPlaying());
        assertEquals(duration, openTime, 1e-4f,
                "a finished one-shot must report exactly its duration - reporting 0 here is what "
                        + "makes 'run to the end and hold' impossible to express");
        assertFalse(instance.animationState().isPlaying(), "the one-shot stopped at its end");

        // Holding: the caller owns this, the API has no forced-looping mode. The pose must not move.
        instance.setPlaybackSpeed(0.0f);
        float[] openPose = instance.jointMatrices().clone();
        instance.update(FRAME);
        instance.update(FRAME);
        assertEquals(openTime, instance.animationTime(), 0.0f, "holding must not move the clock");
        assertTrue(Arrays.equals(openPose, instance.jointMatrices()),
                "and must not move the pose: the open state is held, not replayed");

        // Closing: the reverse rate resumes the clock from the terminus the one-shot stopped at.
        instance.setPlaybackSpeed(-1.0f);
        int closeFrames = 0;
        while (instance.animationTime() > 0.0f && closeFrames++ < 100) {
            instance.update(FRAME);
        }
        System.out.printf(Locale.ROOT, "[recipe] close: %d frames, t=%.6f s, playing=%s%n",
                closeFrames, instance.animationTime(), instance.animationState().isPlaying());
        assertEquals(0.0f, instance.animationTime(), 1e-4f, "the rewind must reach exactly the start");
        instance.setPlaybackSpeed(0.0f);

        // The closed pose must be the clip's own first frame: the whole point of the recipe.
        ModelInstance fresh = new ModelInstance(scene, "fresh");
        fresh.play(SPIN, false);
        fresh.update(0.0f);
        float maxDelta = maxDelta(fresh.jointMatrices(), instance.jointMatrices());
        System.out.printf(Locale.ROOT, "[recipe] closed pose vs a fresh t=0 pose: max delta %.3e%n",
                maxDelta);
        assertEquals(0.0f, maxDelta, 1e-5f,
                "closing must land on the clip's start pose, not near it");
    }

    @Test
    @DisplayName("a stopped instance reports nothing playing, and a rate alone does not restart it")
    void stoppedInstanceStaysStopped() {
        ModelInstance instance = new ModelInstance(scene, "stopped");
        instance.play(SPIN, true);
        step(instance, 0.5f);
        instance.setPlaybackSpeed(-1.0f);
        instance.stopAnimation();

        assertEquals(0.0f, instance.animationTime(), 0.0f, "no clip: no clock position");
        assertEquals(0.0f, instance.animationDuration(), 0.0f, "no clip: no duration");
        assertEquals(-1.0f, instance.playbackSpeed(), 0.0f,
                "the rate is a setting and survives stopAnimation()");

        instance.update(FRAME);
        instance.update(FRAME);
        assertEquals(0.0f, instance.animationTime(), 0.0f,
                "a stale rate must not resurrect a stopped clip");
        assertFalse(instance.animationState().isPlaying(), "and it must not report itself as playing");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Advances {@code total} seconds in sub-cap increments, as a renderer's frames would. */
    private static void step(ModelInstance instance, float total) {
        int steps = Math.round(total / FRAME);
        for (int i = 0; i < steps; i++) {
            instance.update(FRAME);
        }
    }

    /** Largest absolute difference between two equal-length float arrays; NaN if lengths differ. */
    private static float maxDelta(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return Float.NaN;
        }
        float max = 0.0f;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
        }
        return max;
    }
}
