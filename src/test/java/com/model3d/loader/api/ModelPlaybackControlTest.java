package com.model3d.loader.api;

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
 * Playback control: setting the clock by hand ({@code seek}), holding a pose ({@code pause} /
 * {@code resume}), and running a bounded pass of a clip that stops at a named time
 * ({@code playSegment} / {@code hasFinished}).
 *
 * <h2>What is actually being pinned</h2>
 * These are the primitives a consumer needs to build a state machine - open the lid, hold it,
 * close it from wherever it got to - without polling and hand-managing the clock, so the assertions
 * that matter are not "the number changed" but:
 * <ul>
 *   <li><b>a seeked pose equals the pose of playing forward to the same time</b> - that equality is
 *       the entire reason seeking can substitute for replaying a clip to reach a pose. Two paths
 *       into the sampler that disagreed would make the model jump the moment a consumer seeked
 *       instead of replayed;</li>
 *   <li><b>a seek lands where the clock would have gone</b> - wrapping on a looping clip, clamping
 *       on a one-shot. A seek that wrapped a one-shot, or clamped a loop, would put the clock
 *       somewhere the following playback immediately leaves, which reads as the model snapping;</li>
 *   <li><b>a hold is bit-identical, not merely close</b> - a held pose that is resampled from a
 *       moving clock drifts a little every frame, and a drift of a few 1e-7 per frame is invisible
 *       in a screenshot while still not being the pose the caller asked to hold;</li>
 *   <li><b>a segment stops on its stop time and stays there</b> - including on a clip whose own
 *       looping flag is set, and including after later {@code update} calls. A pass that wrapped
 *       into a second lap would make an open lid keep rotating;</li>
 *   <li><b>a missing clip name changes nothing</b> - a typo in a state name must read as the name
 *       being wrong, not as the model stopping or jumping.</li>
 * </ul>
 *
 * <p>The fixture is the project's own generated skinned cube, parsed in process through the real
 * {@code ModelFormatRegistry}, exactly as {@code ModelPlaybackSpeedTest} and
 * {@code AnimatedModelIntegrationTest} do: {@code spin} is a 2.0 s rotation and {@code bob} a 1.0 s
 * translation, so different clock positions give different joint-matrix blocks and the same position
 * gives the same block. No GL and no game.
 */
class ModelPlaybackControlTest {

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
        Path output = Path.of("build", "test-fixture", "playback_control_fixture.glb").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, TestModelGenerator.build());
        scene = ModelFormatRegistry.parse(
                new DirectoryModelSource(output.getParent(), "model3d",
                        output.getFileName().toString()),
                "playback_control");
    }

    // ------------------------------------------------------------------
    // 1. seek: where it lands, and when the pose follows
    // ------------------------------------------------------------------

    @Test
    @DisplayName("seek moves the clock immediately and the pose on the next update, as documented")
    void seekMovesTheClockNowAndThePoseOnTheNextUpdate() {
        ModelInstance instance = new ModelInstance(scene, "seek-order");
        instance.play(SPIN, true);
        step(instance, 0.4f);
        float[] before = instance.jointMatrices().clone();

        instance.seek(1.5f);
        assertEquals(1.5f, instance.animationTime(), 0.0f,
                "the clock moves inside seek(): animationTime() is the position, not a prediction");
        assertTrue(Arrays.equals(before, instance.jointMatrices()),
                "seek() must not resample: update() is the one place a pose is produced, and a "
                        + "renderer that has not called update() yet is still holding the old pose");

        instance.update(0.0f);
        assertEquals(1.5f, instance.animationTime(), 0.0f,
                "update(0) resamples WITHOUT moving the clock, which is what makes 'seek then draw "
                        + "this frame' a single call");
        assertFalse(Arrays.equals(before, instance.jointMatrices()),
                "the resample must actually show the seeked position; an equal pose here would "
                        + "mean the pose is not a function of the clock");
    }

    @Test
    @DisplayName("seek past the end of a looping clip wraps, forwards and backwards")
    void seekPastTheEndOfALoopingClipWraps() {
        ModelInstance instance = new ModelInstance(scene, "seek-loop");
        instance.play(SPIN, true);

        instance.seek(SPIN_SECONDS + 0.25f);
        System.out.printf(Locale.ROOT, "[seek] looping: %.2f -> %.4f (duration %.1f)%n",
                SPIN_SECONDS + 0.25f, instance.animationTime(), instance.animationDuration());
        assertEquals(0.25f, instance.animationTime(), 1e-4f,
                "0.25 s past the end of a looping clip is 0.25 s into it");

        instance.seek(2.0f * SPIN_SECONDS);
        assertEquals(0.0f, instance.animationTime(), 1e-4f,
                "exactly one full clip past the start is the start");

        // The negative half: a backwards-running clock crosses zero and continues from the end, so
        // that is where a seek to a negative time has to land. Clamping to 0 here would be a
        // different movement from the one the clock makes.
        instance.seek(-0.5f);
        System.out.printf(Locale.ROOT, "[seek] looping: -0.50 -> %.4f%n", instance.animationTime());
        assertEquals(SPIN_SECONDS - 0.5f, instance.animationTime(), 1e-4f,
                "a negative seek on a looping clip wraps from the end");
        assertTrue(instance.animationTime() >= 0.0f, "the clock must never be negative");
    }

    @Test
    @DisplayName("seek past either end of a non-looping clip clamps")
    void seekClampsOnANonLoopingClip() {
        ModelInstance instance = new ModelInstance(scene, "seek-oneshot");
        instance.play(SPIN, false);

        instance.seek(5.0f);
        System.out.printf(Locale.ROOT, "[seek] one-shot: 5.00 -> %.4f (duration %.1f)%n",
                instance.animationTime(), instance.animationDuration());
        assertEquals(SPIN_SECONDS, instance.animationTime(), 1e-4f,
                "there is no wrap to continue into: a one-shot clamps to its end");
        assertTrue(instance.hasFinished(),
                "the clock is on the end terminus, which is the 'holding there' hasFinished() describes");

        // Seeking back inside re-arms the clip, in both directions: this is what makes a seek a
        // substitute for replaying. Without it, a one-shot that had finished would refuse to move
        // backwards from the seeked position.
        instance.seek(0.5f);
        assertFalse(instance.hasFinished(), "a position inside the clip is not a finished playback");
        instance.setPlaybackSpeed(-1.0f);
        instance.update(FRAME);
        assertEquals(0.4f, instance.animationTime(), 1e-4f,
                "a rewound clip seeked into its middle must run backwards from there");

        instance.seek(-1.0f);
        System.out.printf(Locale.ROOT, "[seek] one-shot: -1.00 -> %.4f%n", instance.animationTime());
        assertEquals(0.0f, instance.animationTime(), 1e-4f,
                "a negative seek on a one-shot clamps to its start");
    }

    @Test
    @DisplayName("the pose after seek(t) equals the pose of playing forward to t")
    void poseAfterSeekEqualsPlayingForwardToTheSameTime() {
        ModelInstance played = new ModelInstance(scene, "played");
        played.play(SPIN, true);
        step(played, 0.5f);

        ModelInstance seeked = new ModelInstance(scene, "seeked");
        seeked.play(SPIN, true);
        seeked.seek(0.5f);
        // The documented contract: the pose follows on the next update, and update(0) is that
        // update without moving the clock.
        seeked.update(0.0f);

        assertEquals(played.animationTime(), seeked.animationTime(), 1e-4f,
                "both instances must be on the same clock position");
        float[] playedPose = played.jointMatrices();
        float[] seekedPose = seeked.jointMatrices();
        assertNotNull(playedPose, "the fixture is skinned, so both must produce joint matrices");
        assertNotNull(seekedPose, "the fixture is skinned, so both must produce joint matrices");

        float maxDelta = maxDelta(playedPose, seekedPose);
        System.out.printf(Locale.ROOT,
                "[seek] t=0.5 s played vs seeked -> max |jointMatrix delta| = %.3e%n", maxDelta);
        // This is the property the whole primitive stands on: a pose is a function of the clock, so
        // a consumer may jump to it instead of replaying the clip to get there.
        assertEquals(0.0f, maxDelta, 1e-5f,
                "seeking to a time must produce the pose of playing to that time");

        // Guard the guard: if the fixture posed identically everywhere, the assertion above would be
        // vacuous. Another time must give a visibly different pose.
        ModelInstance elsewhere = new ModelInstance(scene, "elsewhere");
        elsewhere.play(SPIN, true);
        elsewhere.seek(1.0f);
        elsewhere.update(0.0f);
        float distinct = maxDelta(playedPose, elsewhere.jointMatrices());
        System.out.printf(Locale.ROOT, "[seek] t=0.5 s vs t=1.0 s -> max delta = %.3e (must be > 0)%n",
                distinct);
        assertTrue(distinct > 0.005f, "another clock position must pose differently; delta=" + distinct);
    }

    @Test
    @DisplayName("seek with nothing playing is a no-op, not a position waiting for a clip")
    void seekWithNoClipIsANoOp() {
        ModelInstance instance = new ModelInstance(scene, "seek-empty");
        instance.seek(1.0f);
        instance.update(0.0f);
        assertEquals(0.0f, instance.animationTime(), 0.0f, "no clip: no clock position");
        assertEquals(0.0f, instance.animationDuration(), 0.0f, "no clip: no duration to seek within");

        // And a clip that has since been stopped must not be resurrected by a seek: stop() has to
        // stay a stop, or a late seek from a consumer would restart playback with no play() call.
        instance.play(SPIN, true);
        step(instance, 0.5f);
        instance.stopAnimation();
        instance.seek(1.0f);
        instance.update(FRAME);
        assertEquals(0.0f, instance.animationTime(), 0.0f,
                "a seek after stopAnimation() must not resurrect the clip");
        assertEquals(0.0f, instance.animationDuration(), 0.0f, "and must not reattach it");
    }

    @Test
    @DisplayName("seek inside a segment pass clamps into the segment's window")
    void seekInsideASegmentStaysWithinTheWindow() {
        ModelInstance instance = new ModelInstance(scene, "seek-segment");
        assertTrue(instance.playSegment(SPIN, 0.2f, 1.0f, 1.0f), "the fixture clip must be playable");
        instance.update(0.0f);
        assertEquals(0.2f, instance.animationTime(), 1e-4f, "the pass starts at its from-endpoint");

        instance.seek(0.6f);
        instance.update(0.0f);
        System.out.printf(Locale.ROOT, "[seek] inside the 0.2-1.0 pass: seek(0.6) -> %.4f%n",
                instance.animationTime());
        assertEquals(0.6f, instance.animationTime(), 1e-4f, "inside the window: the seek lands");
        assertFalse(instance.hasFinished(), "a seek into the pass re-arms it");

        // Continuing from the seek lands on the stop, which is the point of seeking inside a pass.
        step(instance, 0.5f);
        assertEquals(1.0f, instance.animationTime(), 1e-4f,
                "the pass still ends on its stop after a seek inside it");
        assertTrue(instance.hasFinished(), "and reports that it arrived");

        // Outside the window the seek clamps: while a pass is active the window is the playback, and
        // a position outside it would make the next step sweep the clip to reach the stop.
        instance.seek(5.0f);
        instance.update(0.0f);
        assertEquals(1.0f, instance.animationTime(), 1e-4f,
                "a seek past the stop clamps onto it, not past it");
        assertTrue(instance.hasFinished(), "the clamp landed on the stop, so the pass is complete");
    }

    @Test
    @DisplayName("a seek while paused moves the held pose without releasing the hold")
    void seekWhilePausedMovesTheHeldPose() {
        ModelInstance instance = new ModelInstance(scene, "seek-paused");
        instance.play(SPIN, true);
        step(instance, 0.4f);
        instance.pause();

        instance.seek(1.2f);
        instance.update(0.0f);
        assertEquals(1.2f, instance.animationTime(), 1e-4f, "the held clock moved to the seeked time");
        assertTrue(instance.isPaused(), "a seek is not a resume");

        ModelInstance reference = new ModelInstance(scene, "reference");
        reference.play(SPIN, true);
        reference.seek(1.2f);
        reference.update(0.0f);
        float maxDelta = maxDelta(reference.jointMatrices(), instance.jointMatrices());
        System.out.printf(Locale.ROOT, "[seek] held pose at 1.2 s vs a fresh 1.2 s pose: %.3e%n",
                maxDelta);
        assertEquals(0.0f, maxDelta, 1e-5f,
                "the held pose must be the pose of the seeked position");
    }

    // ------------------------------------------------------------------
    // 2. pause / resume
    // ------------------------------------------------------------------

    @Test
    @DisplayName("pause freezes the pose bit-identically, and resume continues from where it stopped")
    void pauseHoldsThePoseAndResumeContinues() {
        ModelInstance instance = new ModelInstance(scene, "pause");
        instance.play(SPIN, true);
        instance.setPlaybackSpeed(2.0f);
        step(instance, 0.4f);
        float held = instance.animationTime();
        assertTrue(held > 0.0f, "the clock must have moved before it is paused");

        instance.pause();
        assertTrue(instance.isPaused(), "pause() must report itself");
        assertEquals(0.0f, instance.playbackSpeed(), 0.0f,
                "the hold is written as a rate of 0 - the one source of truth for 'is the clock moving'");

        float[] before = instance.jointMatrices().clone();
        instance.update(FRAME);
        instance.update(FRAME);
        instance.update(1.0f);   // a coarse step too: the cap must not be the only thing stopping it
        assertFalse(instance.hasFinished(), "a clip held mid-flight is not a finished one");
        System.out.printf(Locale.ROOT, "[pause] t=%.6f -> %.6f after 3 updates, matrices equal=%s%n",
                held, instance.animationTime(), Arrays.equals(before, instance.jointMatrices()));
        assertEquals(held, instance.animationTime(), 0.0f, "a paused clock must not move at all");
        // Bit-identical, not merely close: the pose is still resampled every update, so a clock that
        // crept forward by even a little would show up here and nowhere else.
        assertTrue(Arrays.equals(before, instance.jointMatrices()),
                "a held pose must be bit-identical frame to frame");

        instance.resume();
        assertFalse(instance.isPaused(), "resume() must report itself");
        assertEquals(2.0f, instance.playbackSpeed(), 0.0f,
                "resume() restores the rate the clip had before the hold, not the default");
        instance.update(FRAME);
        assertEquals(held + 2.0f * FRAME, instance.animationTime(), 1e-4f,
                "playback continues from the held position at the remembered rate");

        // An explicit non-zero rate is the other way out of a hold, because the rate setter is the
        // primitive the hold is written in.
        instance.pause();
        instance.setPlaybackSpeed(1.0f);
        assertFalse(instance.isPaused(), "setting a rate is a request for motion");
        instance.update(FRAME);
        assertEquals(held + 2.0f * FRAME + FRAME, instance.animationTime(), 1e-4f,
                "and the clock moves at the rate that was set");
    }

    // ------------------------------------------------------------------
    // 3. Segments: play part of a clip and stop there
    // ------------------------------------------------------------------

    @Test
    @DisplayName("playSegment forward stops exactly on its stop time and holds there")
    void playSegmentForwardStopsAtTheStopAndHolds() {
        ModelInstance instance = new ModelInstance(scene, "segment-forward");
        assertTrue(instance.playSegment(SPIN, 0.25f, 1.25f, 1.0f),
                "the fixture's spin clip must be playable");
        assertEquals(0.25f, instance.animationTime(), 1e-4f, "the pass starts at its from-endpoint");
        assertFalse(instance.hasFinished(), "a pass that has not run is not finished");

        instance.update(FRAME);
        assertEquals(0.35f, instance.animationTime(), 1e-4f, "it runs forwards from the start");

        int frames = 0;
        while (!instance.hasFinished() && frames++ < 40) {
            instance.update(FRAME);
        }
        System.out.printf(Locale.ROOT, "[segment] forwards 0.25 -> 1.25 s: %d frames, t=%.6f%n",
                frames, instance.animationTime());
        assertEquals(1.25f, instance.animationTime(), 1e-4f,
                "the pass must stop on its stop time, not on the clip's end (" + SPIN_SECONDS + " s)");
        assertTrue(instance.hasFinished(), "reaching the stop is what hasFinished() reports");
        assertTrue(instance.animationTime() < instance.animationDuration(),
                "this assertion is only meaningful because the stop is before the clip's end");

        float stopTime = instance.animationTime();
        float[] stopPose = instance.jointMatrices().clone();
        instance.update(FRAME);
        instance.update(1.0f);
        assertEquals(stopTime, instance.animationTime(), 0.0f,
                "later updates must not move a completed pass, even a coarse one");
        assertTrue(Arrays.equals(stopPose, instance.jointMatrices()),
                "and must not move the pose it is holding");
        assertTrue(instance.hasFinished(), "and it stays finished");
    }

    @Test
    @DisplayName("playSegment with to < from runs the pass backwards and stops at the lower endpoint")
    void playSegmentBackwardsStopsAtTheLowerEndpoint() {
        ModelInstance instance = new ModelInstance(scene, "segment-backwards");
        assertTrue(instance.playSegment(SPIN, 1.75f, 0.75f, 1.0f), "the clip must be playable");
        assertEquals(1.75f, instance.animationTime(), 1e-4f, "the pass starts at its from-endpoint");

        instance.update(FRAME);
        System.out.printf(Locale.ROOT, "[segment] backwards 1.75 -> 0.75 s: one frame -> %.4f%n",
                instance.animationTime());
        assertEquals(1.65f, instance.animationTime(), 1e-4f,
                "to < from means the pass runs DOWN, not that the endpoints are swapped");

        int frames = 0;
        while (!instance.hasFinished() && frames++ < 40) {
            instance.update(FRAME);
        }
        assertEquals(0.75f, instance.animationTime(), 1e-4f,
                "and it stops on toSeconds, holding the pose there");
        assertTrue(instance.hasFinished(), "a completed backwards pass is finished too");

        // The whole-clip version of the same call: this is "close it", and it must land exactly on
        // the clip's start rather than near it.
        ModelInstance closing = new ModelInstance(scene, "segment-close");
        assertTrue(closing.playSegment(SPIN, SPIN_SECONDS, 0.0f, 1.0f));
        int closeFrames = 0;
        while (!closing.hasFinished() && closeFrames++ < 40) {
            closing.update(FRAME);
        }
        System.out.printf(Locale.ROOT, "[segment] close %s -> 0 s: %d frames, t=%.6f%n",
                SPIN_SECONDS, closeFrames, closing.animationTime());
        assertEquals(0.0f, closing.animationTime(), 1e-4f,
                "a full backwards pass must stop exactly at 0, not at the first step short of it");
        assertTrue(closing.hasFinished(), "and report that it arrived");
    }

    @Test
    @DisplayName("playSegment at rate 0 holds part-way, unfinished, until a rate is set")
    void playSegmentAtRateZeroHoldsUnfinished() {
        ModelInstance instance = new ModelInstance(scene, "segment-zero");
        assertTrue(instance.playSegment(SPIN, 0.5f, 1.5f, 0.0f), "the clip must be playable");
        instance.update(0.0f);
        assertEquals(0.5f, instance.animationTime(), 1e-4f, "rate 0 parks the clock at the start");

        instance.update(FRAME);
        instance.update(1.0f);
        System.out.printf(Locale.ROOT, "[segment] rate 0 from 0.5 to 1.5: t=%.6f after 2 updates%n",
                instance.animationTime());
        assertEquals(0.5f, instance.animationTime(), 0.0f, "rate 0 must not move the clock");
        assertFalse(instance.hasFinished(),
                "it never reached its stop, so it is a held transition, not a completed one");
        assertFalse(instance.isPaused(),
                "rate 0 is a rate, not a pause: pause() is a separate hold with its own release");

        // Releasing it: the pass is still armed, so a rate alone runs it to the stop from here.
        instance.setPlaybackSpeed(1.0f);
        instance.update(0.2f);
        assertEquals(0.7f, instance.animationTime(), 1e-4f,
                "an explicit rate runs the armed pass on from where it was held");
        instance.update(0.5f);
        assertFalse(instance.hasFinished(), "a half-second step does not reach the stop from 0.7 s");
        // 0.7 + the documented per-call cap (0.25), not 0.7 + 0.5: the cap applies to a segment step
        // exactly as it does to a wrapping clock, which is what keeps the two step functions alike.
        assertEquals(0.95f, instance.animationTime(), 1e-3f,
                "0.7 s plus one capped 0.25 s step");
    }

    @Test
    @DisplayName("playSegment on a looping clip makes one pass and stops, without un-looping the clip")
    void playSegmentOnALoopingClipRunsOnePass() {
        ModelInstance instance = new ModelInstance(scene, "segment-loop");
        instance.play(SPIN, true);
        assertTrue(instance.animationState().isLooping(), "the clip was asked to loop");

        assertTrue(instance.playSegment(SPIN, 0.5f, 0.8f, 1.0f), "the clip must be playable");
        int frames = 0;
        while (!instance.hasFinished() && frames++ < 40) {
            instance.update(FRAME);
        }
        System.out.printf(Locale.ROOT, "[segment] one pass over a looping clip: %d frames, t=%.6f%n",
                frames, instance.animationTime());
        assertEquals(0.8f, instance.animationTime(), 1e-4f, "the pass stops at its stop time");
        assertTrue(instance.hasFinished(),
                "a segment on a looping clip is one pass and reports finished");

        // The difference between a pass and a loop, stated: further updates must not wrap into a
        // second lap. A lid that kept rotating would be this assertion failing.
        instance.update(FRAME);
        instance.update(FRAME);
        assertEquals(0.8f, instance.animationTime(), 1e-4f, "no second lap");
        assertTrue(instance.hasFinished(), "and it does not go back to running");

        // The pass must not have changed the clip's own looping setting: that is what play() sets,
        // and a bounded pass is a different request.
        assertTrue(instance.animationState().isLooping(),
                "playSegment must leave the clip's looping flag alone");

        // So a plain play() after the pass loops again - the pass did not break the clip.
        assertTrue(instance.play(SPIN, true));
        assertFalse(instance.animationState().hasSegment(),
                "play() is a different request, so it must drop the pass's window rather than keep a "
                        + "hidden stop point for the new playback");
        step(instance, SPIN_SECONDS + 0.3f);
        assertFalse(instance.hasFinished(), "a looping clip never reports finished");
        assertTrue(instance.animationTime() < SPIN_SECONDS,
                "and its clock stays wrapped inside the clip: t=" + instance.animationTime());
    }

    @Test
    @DisplayName("hasFinished is false while a one-shot runs and false for a looping clip")
    void hasFinishedIsFalseWhileRunningAndForALoopingClip() {
        ModelInstance oneShot = new ModelInstance(scene, "finished-oneshot");
        oneShot.play(SPIN, false);
        oneShot.update(FRAME);
        assertFalse(oneShot.hasFinished(), "a one-shot mid-flight has not finished");

        int frames = 0;
        while (!oneShot.hasFinished() && frames++ < 40) {
            oneShot.update(FRAME);
        }
        System.out.printf(Locale.ROOT, "[finished] one-shot reached t=%.4f/%s s after %d frames%n",
                oneShot.animationTime(), oneShot.animationDuration(), frames);
        assertTrue(oneShot.hasFinished(), "a one-shot that ran to its end has finished");
        assertEquals(oneShot.animationDuration(), oneShot.animationTime(), 1e-4f,
                "and it is holding at exactly its duration");

        ModelInstance looping = new ModelInstance(scene, "finished-looping");
        looping.play(SPIN, true);
        step(looping, SPIN_SECONDS + 0.5f);
        assertFalse(looping.hasFinished(),
                "a looping clip wraps instead of stopping, so it is never finished - even after "
                        + "more than a full lap");
        assertTrue(looping.animationTime() >= 0.0f && looping.animationTime() < SPIN_SECONDS,
                "its clock stays inside the clip: t=" + looping.animationTime());
    }

    @Test
    @DisplayName("a segment naming a clip the model does not have changes nothing")
    void playSegmentWithAMissingClipLeavesPlaybackAlone() {
        ModelInstance instance = new ModelInstance(scene, "segment-missing");
        instance.play(SPIN, true);
        step(instance, 0.3f);
        float timeBefore = instance.animationTime();
        String clipBefore = instance.animationState().animationName();
        float speedBefore = instance.playbackSpeed();
        float durationBefore = instance.animationDuration();

        assertFalse(instance.playSegment("no_such_clip", 0.0f, 1.0f, 4.0f),
                "a name the model does not have must be reported, not guessed at");
        assertFalse(instance.playSegment(null, 0.0f, 1.0f, 1.0f));

        System.out.printf(Locale.ROOT, "[segment] missing clip: t=%.4f speed=%.2f clip=%s%n",
                instance.animationTime(), instance.playbackSpeed(), clipBefore);
        assertEquals(timeBefore, instance.animationTime(), 0.0f, "the clock must not have moved");
        assertEquals(clipBefore, instance.animationState().animationName(),
                "nor may the failed call have changed clips");
        assertEquals(speedBefore, instance.playbackSpeed(), 0.0f,
                "nor the rate - a failed call must not apply its own speed");
        assertEquals(durationBefore, instance.animationDuration(), 0.0f, "nor the clip");

        // And the playback it left alone keeps running normally.
        instance.update(FRAME);
        assertEquals(timeBefore + FRAME, instance.animationTime(), 1e-4f,
                "the clip that was playing must still be playing");

        // The same call on an instance that was never played is a start, like play() - and a name
        // that does not exist is refused there too, leaving nothing attached.
        ModelInstance stopped = new ModelInstance(scene, "segment-stopped");
        stopped.stopAnimation();
        assertFalse(stopped.playSegment("no_such_clip", 0.0f, 1.0f, 1.0f));
        assertEquals(0.0f, stopped.animationDuration(), 0.0f,
                "a failed call must not attach anything");
        assertTrue(stopped.playSegment(SPIN, 0.0f, 1.0f, 1.0f),
                "playSegment is a start, so it attaches a clip on an instance that had none");
        assertEquals(SPIN_SECONDS, stopped.animationDuration(), 1e-4f,
                "animationDuration() is the clip's own length; the segment is a window into it");
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
