package com.model3d.loader.animation;

import com.model3d.loader.scene.ModelAnimation;

/**
 * Playback state of one animation over one model instance.
 *
 * <p>Deliberately a plain mutable value object with no reference to a model, an entity or GL:
 * it is the testable core of animation playback. Given a time delta it produces a clock, and
 * {@code AnimationPlayer} turns that clock into a pose. Everything that decides <i>whether</i>
 * to play something lives in the caller.
 *
 * <p>Time is in seconds and only ever moves through {@link #advance(float)} (elapsed time) or
 * {@link #seek(float)} (a hand-set position), never by reading a clock inside this class - so a
 * paused game, a lag spike and a unit test all behave the same.
 *
 * <p><b>The clock is signed.</b> {@link #setSpeed(float)} accepts a negative rate and the clock
 * then runs towards zero, wrapping at both ends of the clip. See {@link #advance(float)} for the
 * direction rules; they exist because a lid that opens forwards has to close from wherever it
 * currently is, not by replaying a different clip.
 */
public final class AnimationState {

    private ModelAnimation animation;
    private float time;
    private float speed = 1.0f;
    private boolean looping = true;
    private boolean playing;
    /** Set when a one-shot animation stopped at a terminus; cleared by {@link #play}. */
    private boolean finished;
    /** Number of times playback crossed a terminus (the end forwards, the start backwards). */
    private int completionCount;
    /** Freeze-frame flag: the pose stops updating but the current pose is kept. */
    private boolean paused;

    /**
     * Segment traversal: while {@link #segment} is set the clock runs from {@link #segmentFrom} to
     * {@link #segmentStop} once and stops there, instead of wrapping. See {@link #playSegment}.
     */
    private boolean segment;
    private float segmentFrom;
    private float segmentStop;
    /** {@code +1} or {@code -1}: the direction the active segment is traversed in. */
    private float segmentDirection = 1.0f;

    /** True when the animation is currently playing. */
    public boolean isPlaying() {
        return playing;
    }

    /**
     * True when playback has stopped on a terminus and is holding there: the end of a one-shot run
     * forwards, the start of one run backwards, or the stop of a {@link #playSegment} pass.
     */
    public boolean isFinished() {
        return finished;
    }

    /** True while a one-pass segment traversal is active; see {@link #playSegment}. */
    public boolean hasSegment() {
        return segment;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isLooping() {
        return looping;
    }

    public void setLooping(boolean looping) {
        this.looping = looping;
    }

    /**
     * Playback rate multiplier: 1 is the clip's own rate, 0 freezes the clock, and a negative
     * value runs it backwards.
     *
     * <p>This used to be clamped to {@code >= 0} and documented as "negative is not supported".
     * That clamp was the reason a consumer could not close what it had opened: a clip that plays a
     * lid forward has no closing clip to play in reverse, so a reverse rate on the <i>same</i>
     * clip is the only way to express "put it back", and a clamp to zero turns that into a model
     * frozen in mid-air. Measured on the fixture: with the old clamp, setting -1 and stepping
     * moved the clock by 0.0 for every step, silently.
     *
     * <p>Non-finite values are <b>ignored</b> rather than clamped: {@code Math.max(0, NaN)} is NaN
     * (so the old clamp did not even guard this), an infinite rate would jump the clock to the
     * terminus on the next step, and neither is something a caller can have meant. Ignoring leaves
     * the previous rate in force, which is the recoverable state.
     */
    public float speed() {
        return speed;
    }

    /** Sets the playback rate; see {@link #speed()} for the sign and non-finite rules. */
    public void setSpeed(float speed) {
        if (!Float.isFinite(speed)) {
            return;
        }
        this.speed = speed;
    }

    /** Current position in seconds, always within {@code [0, duration]} while playing. */
    public float time() {
        return time;
    }

    public void setTime(float time) {
        this.time = Math.max(0.0f, time);
    }

    /** The animation being played, or null when nothing is loaded into this state. */
    public ModelAnimation animation() {
        return animation;
    }

    public String animationName() {
        return animation == null ? null : animation.name();
    }

    public float duration() {
        return animation == null ? 0.0f : animation.duration();
    }

    /** Normalized position in {@code [0, 1]}, or 0 when there is no animation. */
    public float normalizedTime() {
        float duration = duration();
        if (duration <= 0.0f) {
            return 0.0f;
        }
        return Math.min(1.0f, time / duration);
    }

    /**
     * Starts (or restarts) {@code animation}.
     *
     * <p>The rate is deliberately <b>not</b> reset here: {@code play} is "start this clip", and a
     * caller that has set a reverse rate to close something keeps it. {@link #setSpeed} documents
     * why the rate is the caller's business.
     *
     * @param behind true the animation is not a fresh start but a late join: the clock is
     *               advanced to a phase derived from the joining client's own world time, so two
     *               players watching the same entity see the same frame. Without this, an
     *               instanced animation on a shared model runs at a random offset per client.
     */
    public void play(ModelAnimation animation, boolean looping, boolean behind, float phaseSeedSeconds) {
        this.animation = animation;
        this.looping = looping;
        this.playing = animation != null && !animation.isEmpty();
        this.finished = false;
        this.paused = false;
        // play() is "start this clip", so a segment left over from a previous playSegment() is not
        // carried into it: its window describes the clip the caller named then, and running the new
        // clip to a stop point nobody chose for it would read as the clip being truncated.
        this.segment = false;
        if (behind && animation != null && animation.duration() > 0.0f) {
            float duration = animation.duration();
            float phase = phaseSeedSeconds % duration;
            if (phase < 0.0f) {
                phase += duration;
            }
            this.time = phase;
        } else {
            this.time = 0.0f;
        }
    }

    /** Replaces the animation without touching the clock; used when the duration is unchanged. */
    public void setAnimation(ModelAnimation animation) {
        this.animation = animation;
        if (animation == null) {
            this.playing = false;
        }
        // Same reason as play(): the window belonged to the clip that was named, so it goes with it.
        // The clock itself is deliberately left alone - that is this method's contract.
        this.segment = false;
    }

    public void stop() {
        this.playing = false;
        this.finished = false;
        this.animation = null;
        this.time = 0.0f;
        this.segment = false;
    }

    /**
     * Starts a <b>one-pass</b> traversal of {@code animation} from {@code fromSeconds} to
     * {@code toSeconds} and stops there, holding the pose.
     *
     * <p>This is the "play this part of the clip and stop" primitive: the shape of a state
     * transition (a lid opening, gear extending) is an interval of a clip, and expressing it as a
     * bounded pass is one call instead of a caller polling the clock and stopping it by hand.
     *
     * <h2>The rules, and why each one is what it is</h2>
     * <ul>
     *   <li><b>Endpoints are clamped into {@code [0, duration]}.</b> A segment is a bounded
     *       traversal, so wrapping an endpoint past the clip's end would make the pass sweep the
     *       whole clip - not what "from 1.9 s to 2.1 s" can have meant.</li>
     *   <li><b>{@code toSeconds < fromSeconds} plays the pass backwards</b> and stops at
     *       {@code toSeconds}. The alternative - swapping the endpoints - would make
     *       {@code playSegment("lid", 2, 0, 1)} open rather than close, and it is the ordering, not
     *       the rate, that a closing call states most plainly.</li>
     *   <li><b>{@code rate} is a magnitude; its sign is ignored.</b> The direction is already
     *       carried by the endpoint order, so honouring both would let one call state two
     *       contradictory directions. A non-finite rate means 0, not "keep the previous rate": the
     *       rate here is given by this call, and running the pass at whatever rate happened to be
     *       set last is a pass nobody asked for.</li>
     *   <li><b>{@code rate == 0} holds the clock at {@code fromSeconds}, unfinished.</b> It never
     *       reaches {@code toSeconds}, so {@link #isFinished()} stays false - a held part of a
     *       transition, not a completed one.</li>
     *   <li><b>A segment is one pass even when the clip's own {@link #looping} flag is set.</b>
     *       The flag is deliberately <b>not</b> changed by this call: it describes the clip, and a
     *       caller that asked for a bounded pass must not silently lose its looping setting. The
     *       window governs while the pass is active, and the clock holds at {@code toSeconds} when
     *       it ends.</li>
     *   <li><b>{@code from == to} completes on the first {@link #advance}.</b> A zero-length pass is
     *       already at its stop; the completion is reported by the step that applies it rather than
     *       by this call, so {@code isFinished()} is false until the clock is next advanced.</li>
     *   <li><b>Cleared by {@link #play}, {@link #setAnimation} and {@link #stop}.</b> A window is
     *       part of the request that created it; a later "play this clip" is a different request and
     *       must not inherit a stop point nobody chose for that clip.</li>
     * </ul>
     *
     * <p>The clock is placed at {@code fromSeconds} immediately (it is a request to play, and the
     * caller can pose it with {@code ModelInstance#update(0)} or seek inside the window before the
     * first advance). Nothing else about the state changes: not the clip's looping flag, and not
     * the rate's stickiness across other calls - only this call's {@code rate} is written.
     */
    public void playSegment(ModelAnimation animation, float fromSeconds, float toSeconds, float rate) {
        this.animation = animation;
        this.playing = animation != null && !animation.isEmpty();
        this.finished = false;
        this.paused = false;
        this.segment = false;
        this.time = 0.0f;
        // Written directly rather than through setSpeed(): see the "rate is a magnitude" rule above.
        this.speed = Float.isFinite(rate) ? Math.abs(rate) : 0.0f;
        float duration = animation == null ? 0.0f : animation.duration();
        if (!(duration > 0.0f)) {
            return;
        }
        float from = clampInto(fromSeconds, duration);
        float to = clampInto(toSeconds, duration);
        this.time = from;
        this.segmentFrom = from;
        this.segmentStop = to;
        this.segmentDirection = to >= from ? 1.0f : -1.0f;
        this.segment = true;
    }

    /**
     * Moves the clock to {@code seconds} inside the current playback without changing the rate,
     * the looping flag or the clip.
     *
     * <p>Wrapping follows the playback, not the number: a looping clip wraps (so
     * {@code seek(-0.5f)} lands at {@code duration - 0.5}, which is where a clock running backwards
     * through zero ends up), a non-looping clip clamps to {@code [0, duration]}, and a segment
     * clamps into its window - while a pass is active the window <i>is</i> the current playback, and
     * a position outside it would make the next advance sweep the whole clip to reach the stop
     * instead of finishing the part that was asked for.
     *
     * <h2>What a seek does to "finished"</h2>
     * A seek is a hand-set position, so any earlier "stopped at a terminus" claim is stale unless
     * the new position <i>is</i> the terminus the playback travels to:
     * <ul>
     *   <li>a non-looping clip seeked to its duration is holding at its end
     *       ({@code finished = true}); seeked anywhere before it, it is re-armed
     *       ({@code finished = false}, {@code playing = true}) so the next advance moves in either
     *       direction. Without that last part a seek would be a one-way door: a finished one-shot
     *       seeked back into the middle refuses to move backwards, because the old advance guard
     *       reads the cut as "already at this terminus";</li>
     *   <li>a segment seeked onto its stop is complete; seeked inside its window it is re-armed;</li>
     *   <li>a looping clip is unaffected: its clock wraps and it never reports finished.</li>
     * </ul>
     *
     * <p>A non-finite value is discarded, exactly as it is for a rate or a delta: {@code Math.min}
     * and friends propagate NaN, and a NaN clock samples the first keyframe of every track - a model
     * frozen in its bind pose with nothing in the log to explain it. A no-op when nothing is playing
     * (never started, or after {@link #stop}) or when the clip has no duration to seek within.
     */
    public void seek(float seconds) {
        if (animation == null || !Float.isFinite(seconds)) {
            return;
        }
        float duration = animation.duration();
        if (!(duration > 0.0f)) {
            return;
        }
        float target;
        if (segment) {
            float low = Math.min(segmentFrom, segmentStop);
            float high = Math.max(segmentFrom, segmentStop);
            target = Math.min(high, Math.max(low, seconds));
        } else if (looping) {
            target = wrapInto(seconds, duration);
        } else {
            target = clampInto(seconds, duration);
        }
        time = target;
        if (segment) {
            boolean atStop = segmentDirection > 0.0f ? target >= segmentStop : target <= segmentStop;
            finished = atStop;
            playing = !atStop;
        } else if (!looping) {
            boolean atEnd = target >= duration;
            finished = atEnd;
            playing = !atEnd;
        }
    }

    /**
     * Clamps {@code seconds} into {@code [0, duration]}; a non-finite value becomes 0 rather than
     * propagating, for the reason {@link #seek} gives.
     */
    private static float clampInto(float seconds, float duration) {
        if (!Float.isFinite(seconds)) {
            return 0.0f;
        }
        return Math.min(duration, Math.max(0.0f, seconds));
    }

    public void pause() {
        this.paused = true;
    }

    public void resume() {
        this.paused = false;
    }

    /**
     * Largest clock step one {@link #advance} call may apply, in seconds.
     *
     * <p>Public because it is observable through the API: {@code ModelInstance.update(0.5f)} moves
     * the clock by {@link #MAX_STEP_SECONDS}, not by 0.5, and a caller integrating a coarse time
     * step has to iterate. See {@link #advance} for why the cap exists.
     */
    public static final float MAX_STEP_SECONDS = 0.25f;

    /**
     * Advances the clock by {@code deltaSeconds} times the current {@link #speed()}. No-op when
     * paused, when the current animation has a zero duration, or when nothing has been started.
     *
     * <p>A large delta (a lag spike, a debugger pause, or a test stepping by half a second) is
     * clamped to {@link #MAX_STEP_SECONDS}. Stepping a looping animation by a minute would
     * otherwise run the wrap logic thousands of times in one call and, worse, would make the
     * visible pose depend on frame rate rather than on elapsed time - a lag spike would teleport
     * every animating model to an arbitrary phase.
     *
     * <p>The cap means this is not a faithful integrator: {@code advance(0.5f)} twice moves the
     * clock by 0.5 s, but {@code advance(0.5f)} once moves it by 0.25 s. Callers that need a long
     * step to be honoured exactly must loop. Documented at this length because the mistake is
     * silent - the animation plays at the wrong rate rather than stopping.
     *
     * <p>A non-finite delta is discarded rather than propagated. {@code Math.min(NaN, 0.25f)} is
     * NaN, so an unguarded NaN would make the clock NaN, and a NaN clock collapses every sampled
     * keyframe to the first one - a model frozen in its bind pose with nothing in the log to say
     * why.
     *
     * <h2>Direction</h2>
     * The sign of the movement comes from {@link #speed()}, never from the delta: an update step is
     * an elapsed time, so a negative delta is not a rewind and is discarded the way it always was.
     * The clock then wraps at <b>both</b> ends - a looping clip played past its end continues from
     * zero, and one played before its start continues from its duration - which is what makes
     * "reverse from wherever it is now" a continuous operation rather than one that needs the caller
     * to notice a boundary and jump.
     *
     * <h2>Reverse playback, and the one-shot case</h2>
     * A non-looping clip stops at the terminus it reached ({@code playing = false},
     * {@code finished = true}, clock exactly at the terminus). Setting a speed that leaves that
     * terminus resumes the movement from it, which is what the open/close recipe needs:
     * <pre>
     *   play(clip, false); setSpeed(+1);   // forward, then the caller sets speed 0 at the end
     *   setSpeed(-1);                      // and this runs it back down to 0
     * </pre>
     * Pushing <i>further into</i> the terminus it already sits on stays the no-op it was, so a
     * caller that keeps advancing a finished clip at speed +1 does not accumulate completions.
     * A clip that was never started is also not resumed by a speed alone: {@code stop()} must stay
     * a stop, or a stale speed would restart playback on the next frame.
     *
     * <h2>A segment pass replaces the wrap with a stop</h2>
     * While {@link #playSegment} has a window active, the step runs towards the window's stop and
     * finishes there ({@code playing = false}, {@code finished = true}, the clock exactly on the
     * stop) whatever the clip's looping flag says. The direction is the segment's, so the rate's
     * sign is not consulted - see {@code playSegment} for why the endpoint order carries it instead.
     */
    public void advance(float deltaSeconds) {
        if (paused || animation == null) {
            return;
        }
        if (!Float.isFinite(deltaSeconds) || deltaSeconds <= 0.0f) {
            return;
        }
        float duration = animation.duration();
        if (duration <= 0.0f) {
            return;
        }
        if (!playing && !finished) {
            return;
        }
        float step = Math.min(deltaSeconds, MAX_STEP_SECONDS) * speed;
        if (step == 0.0f) {
            return;
        }
        if (segment) {
            // A pass is bounded and one-way, so it is a different step function from the wrapping
            // clock above: no wrap, no terminus at the clip's ends, and the rate contributes its
            // magnitude only (the direction is the segment's - see playSegment).
            advanceSegment(Math.min(deltaSeconds, MAX_STEP_SECONDS) * Math.abs(speed));
            return;
        }
        if (!playing) {
            boolean atEnd = time >= duration;
            if ((atEnd && step > 0.0f) || (!atEnd && step < 0.0f)) {
                return;
            }
            finished = false;
            playing = true;
        }
        time += step;
        if (time >= duration) {
            if (looping) {
                time = wrapInto(time, duration);
                completionCount++;
            } else {
                time = duration;
                playing = false;
                finished = true;
                completionCount++;
            }
        } else if (time < 0.0f) {
            if (looping) {
                time = wrapInto(time, duration);
                completionCount++;
            } else {
                time = 0.0f;
                playing = false;
                finished = true;
                completionCount++;
            }
        }
    }

    /**
     * One step of a {@link #playSegment} pass.
     *
     * <p>The remaining distance is measured as {@code (stop - time) * direction}, which makes "how
     * far is left" positive whether the pass runs up or down - one comparison for the stop instead
     * of two mirrored ones, and one place where "have we arrived" is decided.
     */
    private void advanceSegment(float travel) {
        if (!(travel > 0.0f)) {
            return;
        }
        float remaining = (segmentStop - time) * segmentDirection;
        if (!(remaining > 0.0f)) {
            // Already at (or past) the stop. Pushing further into it stays the no-op that advancing a
            // finished one-shot already is, so a caller that keeps stepping a completed pass neither
            // moves the held pose nor accumulates completions.
            playing = false;
            finished = true;
            return;
        }
        if (travel < remaining) {
            time += segmentDirection * travel;
            return;
        }
        // Land exactly on the stop rather than overshooting: the stop is a pose the caller named
        // (the fully open lid), and a step that ran past it would sample the clip beyond it.
        time = segmentStop;
        playing = false;
        finished = true;
        completionCount++;
    }

    /**
     * Folds {@code time} into {@code [0, duration)}.
     *
     * <p>Written out rather than using {@code time % duration} alone because Java's remainder keeps
     * the dividend's sign: {@code -0.1f % 2.0f} is {@code -0.1f}, so a rewind past the start would
     * leave the clock negative and the sampler would clamp to the clip's first keyframe - a lid
     * that snaps shut instead of continuing from the end. That is the same answer a caller would get
     * from a missing wrap, which is why this is easy to miss on a forward-only test.
     */
    private static float wrapInto(float time, float duration) {
        float wrapped = time % duration;
        return wrapped < 0.0f ? wrapped + duration : wrapped;
    }

    /** How many times playback has crossed a terminus. Lets a caller chain one-shot animations. */
    public int completionCount() {
        return completionCount;
    }

    public void reset() {
        time = 0.0f;
        finished = false;
        playing = animation != null && !animation.isEmpty();
        paused = false;
        completionCount = 0;
        segment = false;
    }

    @Override
    public String toString() {
        return "AnimationState('" + animationName() + "' t=" + String.format("%.3f", time)
                + "/" + String.format("%.3f", duration()) + " playing=" + playing
                + " loop=" + looping + ")";
    }
}
