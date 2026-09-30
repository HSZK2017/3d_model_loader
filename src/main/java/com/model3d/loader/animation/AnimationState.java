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
 * <p>Time is in seconds and advances only through {@link #advance(float)}, never by reading a
 * clock inside this class - so a paused game, a lag spike and a unit test all behave the same.
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

    /** True when the animation is currently playing. */
    public boolean isPlaying() {
        return playing;
    }

    /** True when a one-shot animation has run to the end, or been run back to the start. */
    public boolean isFinished() {
        return finished;
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
    }

    public void stop() {
        this.playing = false;
        this.finished = false;
        this.animation = null;
        this.time = 0.0f;
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
    }

    @Override
    public String toString() {
        return "AnimationState('" + animationName() + "' t=" + String.format("%.3f", time)
                + "/" + String.format("%.3f", duration()) + " playing=" + playing
                + " loop=" + looping + ")";
    }
}
