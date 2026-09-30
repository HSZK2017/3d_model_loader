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
 */
public final class AnimationState {

    private ModelAnimation animation;
    private float time;
    private float speed = 1.0f;
    private boolean looping = true;
    private boolean playing;
    /** Set when a non-looping animation reached its end; cleared by {@link #play}. */
    private boolean finished;
    /** Number of times playback crossed the end, for one-shot animations. */
    private int completionCount;
    /** Freeze-frame flag: the pose stops updating but the current pose is kept. */
    private boolean paused;

    /** True when the animation is currently playing. */
    public boolean isPlaying() {
        return playing;
    }

    /** True when a one-shot animation has run to its end. */
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

    /** Playback rate multiplier; 0 freezes, negative plays backwards is NOT supported (clamped to 0). */
    public float speed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = Math.max(0.0f, speed);
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
     * Advances the clock by {@code deltaSeconds}. No-op when stopped, paused, or when the
     * current animation has a zero duration.
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
     */
    public void advance(float deltaSeconds) {
        if (!playing || paused || animation == null) {
            return;
        }
        if (!Float.isFinite(deltaSeconds)) {
            return;
        }
        float duration = animation.duration();
        if (duration <= 0.0f) {
            return;
        }
        float delta = Math.min(deltaSeconds, MAX_STEP_SECONDS) * speed;
        if (delta <= 0.0f) {
            return;
        }
        time += delta;
        if (time >= duration) {
            if (looping) {
                time = time % duration;
                completionCount++;
            } else {
                time = duration;
                playing = false;
                finished = true;
                completionCount++;
            }
        }
    }

    /** How many times playback has crossed the end. Lets a caller chain one-shot animations. */
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
