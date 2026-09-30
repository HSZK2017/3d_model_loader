package com.model3d.loader.api;

import com.model3d.loader.animation.AnimationPose;
import com.model3d.loader.animation.AnimationPlayer;
import com.model3d.loader.animation.AnimationState;
import com.model3d.loader.math.Mat4;
import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelScene;

/**
 * A runtime handle on one model, attached to one thing in the world.
 *
 * <p>This is the type the public API hands out. {@link ModelScene} is shared, read-only,
 * load-time data; this object is the per-attachment state - the animatable node tree, the
 * animation clock, the joint matrices, the scale - and there is exactly one per entity.
 *
 * <h2>Threading</h2>
 * CPU-only and <b>not thread-safe</b>. The intended pattern is: the server sets a model name
 * and an animation name (plain data, synced by the entity), and the <i>client</i> owns a
 * {@code ModelInstance} that it advances and poses from the render thread only. Nothing here
 * may be touched from a netty thread or from the server tick.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   ModelInstance instance = new ModelInstance(scene, "su30");
 *   instance.play("fly", true);                 // by name, from the file
 *   instance.playSegment("gear", 1.2f, 3.4f, 1.0f);  // or just part of a clip, holding at the end
 *   instance.setScale(blocksPerModelUnit);      // see ModelScale.forHandle
 *   instance.setYawOffset((float) Math.PI);     // a model authored facing -Z
 *   instance.setPivot(0.0f, -0.5f, 0.0f);       // model-space offset, in model units
 *   instance.node("elevator_L").setRotation(pitchDeg, 0, 0);   // drive one part
 *   instance.node("gear_door").setVisible(false);              // and hide another
 *   instance.setMaterialTint("afterburner", 1.5f, 1.4f, 1.2f, 1.0f);  // and tint a third
 *   instance.update(deltaSeconds);              // advance clock + sample pose + apply overrides
 *   // renderer reads instance.nodes(), instance.jointMatrices()
 * </pre>
 *
 * <p>The three placement setters are separate on purpose: they are applied by the renderer as one
 * root transform ({@code pivot}, then {@code yaw}, then {@code scale}), and a caller that only wants
 * to resize a model must not have to restate the other two.
 *
 * <h2>Reverse playback, and playing a specific part of a clip</h2>
 * A clip is a curve, not a state machine: there is no {@code open()} and no {@code close()} in the
 * file, only one animation that runs from closed to open. Closing it therefore means running the
 * same clip at a negative rate, and that is all {@link #setPlaybackSpeed} does - it multiplies the
 * clip's own rate, keeps the clip's position, and is read on every update. A sign flip mid-flight
 * reverses from where the clock currently is, so a lid toggled halfway through opening closes from
 * halfway through opening.
 *
 * <p>For a transition that is a <i>part</i> of a clip rather than the whole of it - gear that
 * extends from 1.2 s to 3.4 s into the gear clip, a lid that opens over the first half - the
 * primitive is {@link #playSegment(String, float, float, float)}: one call states the start, the
 * stop and the rate, and the clock holds on the stop pose. {@link #hasFinished()} is then what a
 * consumer polls to see that it arrived, instead of comparing {@link #animationTime()} against
 * {@link #animationDuration()} by hand.
 *
 * <p>The whole-clip version of the same thing is still the caller's recipe, because whether "the
 * end of the clip" means "hold the open pose" or "start the next clip" is the caller's business:
 * <pre>
 *   instance.play("lid_open", false);   // non-looping: the clock stops at exactly the duration
 *   instance.setPlaybackSpeed(+1.0f);   // forward, from wherever the clock is
 *
 *   // once per frame, after instance.update(dt):
 *   if (instance.hasFinished()) {
 *       instance.setPlaybackSpeed(0.0f);        // freeze on the open pose
 *   }
 * </pre>
 * Closing is the same recipe with {@code -1.0f}. Both halves rely on the two properties
 * {@link #animationTime()} and {@link #animationDuration()} document: a one-shot clip that reached
 * its end reports exactly its duration (not 0), and a rewound clip is allowed to resume from the
 * terminus it stopped at rather than needing a fresh {@code play()} that would snap it back to the
 * start.
 *
 * <p>{@link #seek(float)} sets the clock by hand, {@link #pause()} and {@link #resume()} hold the
 * current pose and continue from it, and {@link #playSegment} is the bounded pass described above.
 * None of the three resamples the pose themselves: {@link #update(float)} is still the only place a
 * pose is produced (see its javadoc), so a caller that has just moved the clock and wants to draw
 * this frame calls {@code update(0.0f)} - which resamples at the position it is already at.

 *
 * <h2>Driving individual parts</h2>
 * {@link #node(String)} addresses one node and holds per-node overrides - rotation, translation,
 * scale, visibility - that are re-applied on top of the animation every frame, so a driven part
 * does not fight the clip it plays. {@link #setMaterialTint} does the same for one material's
 * colour, which is how a flame is brightened under afterburner. None of this touches the shared
 * {@link ModelScene}: every override lives on this instance's own node tree.
 */
public final class ModelInstance {

    private final String instanceName;
    private ModelScene scene;

    /** Animatable node tree; exactly one per instance - see ModelScene#instantiate(). */
    private ModelNode[] nodes;
    private AnimationPose pose;
    private final AnimationState animationState = new AnimationState();

    /** Scale applied to the model's own units to reach Minecraft blocks. */
    private float scale = 1.0f;

    /**
     * Extra yaw in radians applied to the whole model, so a model authored facing -Z can be
     * faced +Z (Minecraft's forward) without editing the file.
     */
    private float yawOffset;

    /**
     * Playback hold: see {@link #pause()}. The rate the clock runs at while paused is 0 - that is
     * the single source of truth for "is the clock moving" - and this flag plus
     * {@link #speedBeforePause} are what {@link #resume()} needs to put the rate back.
     */
    private boolean paused;

    /** The rate {@link #pause()} replaced with 0, restored by {@link #resume()}. */
    private float speedBeforePause = 1.0f;

    /** Model-space offset of the pivot, in blocks, applied after scaling. */
    private float pivotX;
    private float pivotY;
    private float pivotZ;

    /**
     * Per-material colour overrides, indexed by {@link ModelScene#materials()} index: 4 floats RGBA
     * each, or a null slot for "use the material's own colour".
     *
     * <p>Allocated on the first {@link #setMaterialTint} so an instance that never flashes anything
     * carries no tint storage, and keyed by index rather than by material name so the per-group
     * upload in the draw path needs no string hashing or comparison.
     */
    private float[][] materialTints;

    /**
     * Cached root transform, rebuilt by every placement setter below.
     *
     * @see #setScale(float)
     * @see #setYawOffset(float)
     * @see #setPivot(float, float, float)
     */
    private Mat4 rootTransform = Mat4.IDENTITY;

    /** Incremented whenever the pose is resampled; renderers cache skinned vertices per generation. */
    private int poseGeneration;

    /**
     * An instance of {@code handle}'s model, named after it.
     *
     * <p>The way to build one without naming a scene: the constructor below takes the parsed scene,
     * whose type is internal, so a caller can invoke it but cannot declare its parameter. This factory
     * takes the handle the API handed out and does the rest.
     *
     * <p>Most callers do not need it: {@code ClientModelManager#instanceFor} creates, configures and
     * advances an instance per entity, which is what the render path expects. Build one directly for
     * something that is not an entity - a preview, a tool, a test.
     */
    public static ModelInstance of(ModelHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("ModelInstance.of(null handle)");
        }
        return new ModelInstance(handle.scene(), handle.name());
    }

    /**
     * An instance of {@code scene}.
     *
     * <p>{@code ModelScene} is internal shape: prefer {@link #of(ModelHandle)} unless you are inside
     * this mod, where the scene is already in hand.
     */
    public ModelInstance(ModelScene scene, String instanceName) {
        this.instanceName = instanceName;
        setScene(scene);
    }

    public String instanceName() {
        return instanceName;
    }

    public ModelScene scene() {
        return scene;
    }

    /**
     * Swaps the underlying model, dropping the pose and clock.
     *
     * <p>Node overrides go with it, and not by explicit clearing: {@link ModelScene#instantiate()}
     * builds a fresh tree, and an override lives on the nodes of the old one. Material tints <b>are</b>
     * dropped explicitly, because they are keyed by the old scene's material indices and would
     * otherwise tint whatever material happens to occupy that index in the new model - a nozzle
     * tint landing on a canopy reads as a rendering bug, not as a stale override.
     *
     * <p>A {@link #pause()} hold survives, like the rate it is written in: the new scene's clock
     * starts at 0 with the rate still 0, and the next {@code play} holds its first frame until
     * {@link #resume()} or an explicit non-zero {@link #setPlaybackSpeed}. That is the same rule
     * {@code pause()} documents - nothing but those two calls releases it - and it is what keeps a
     * model swap from silently un-freezing a consumer that paused deliberately.
     */
    public void setScene(ModelScene scene) {
        this.scene = scene;
        this.materialTints = null;
        if (scene == null) {
            this.nodes = null;
            this.pose = null;
            return;
        }
        this.nodes = scene.instantiate();
        this.pose = new AnimationPose(scene.nodeCount(), scene.isSkinned() ? scene.skins()[0].jointCount() : 0);
        this.animationState.stop();
        this.poseGeneration++;
    }

    /** Per-instance node tree. Mutated by the animation player; read by the renderer. */
    public ModelNode[] nodes() {
        return nodes;
    }

    public AnimationPose pose() {
        return pose;
    }

    public AnimationState animationState() {
        return animationState;
    }

    // ------------------------------------------------------------------
    // Animation API
    // ------------------------------------------------------------------

    /** Plays a named animation from the model file, looping. Returns false when absent. */
    public boolean play(String animationName) {
        return play(animationName, true);
    }

    /**
     * Plays a named animation.
     *
     * @return false when the model has no animation of that name - the caller decides whether
     *         that is an error worth reporting; the first playback of each name is logged once
     *         by {@link #play} so a typo is visible without spamming.
     */
    public boolean play(String animationName, boolean looping) {
        if (scene == null || animationName == null) {
            return false;
        }
        ModelAnimation animation = scene.animation(animationName);
        if (animation == null) {
            return false;
        }
        animationState.play(animation, looping, false, 0.0f);
        return true;
    }

    /** Names of every animation the attached model provides; empty when it has none. */
    public java.util.List<String> animationNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (scene != null) {
            for (ModelAnimation animation : scene.animations()) {
                names.add(animation.name());
            }
        }
        return names;
    }

    public void stopAnimation() {
        animationState.stop();
    }

    // ------------------------------------------------------------------
    // Playback rate, clock position and the open/close recipe
    // ------------------------------------------------------------------

    /**
     * Scales the current clip's own rate: 1 is the file's rate, 0 freezes the pose, and a
     * <b>negative</b> value plays the clip backwards.
     *
     * <p>Read on every {@link #update(float)}, so changing it mid-flight reverses from the clock's
     * current position rather than restarting: a door half open closes from half open, which is the
     * whole reason a signed rate exists. Nothing here touches the clip's position or its looping
     * flag.
     *
     * <p>Non-finite values (NaN, ±infinity) are <b>ignored</b>, leaving the previous rate in force.
     * Two reasons: {@code Math.max(0.0f, NaN)} is NaN, so a clamp could not have guarded this, and
     * an infinite rate would make the next step land on a terminus and read as "the animation
     * jumped" rather than as a bad argument. Values other than those two cases are not restricted -
     * a rate of 4 is four times as fast, and the step cap still applies per update.
     *
     * <p>The rate is <b>sticky</b>: {@link #play(String, boolean)} does not reset it, so a caller
     * that opens with +1 and closes with -1 sets it once per transition and not per frame. It also
     * survives {@link #stopAnimation()}, which is deliberate - the value is a setting, not a
     * property of the clip that happened to be loaded.
     *
     * <p>Setting a non-zero rate <b>releases a hold</b> taken by {@link #pause()}: the rate setter is
     * the primitive the hold is written in, so writing a rate is the caller saying "move at this
     * rate now". Setting 0 while paused leaves the hold in force (nothing would move either way).
     * Nothing else releases it - see {@link #pause()} for why {@code play} and {@code stopAnimation}
     * deliberately do not.
     */
    public void setPlaybackSpeed(float speed) {
        if (paused && Float.isFinite(speed) && speed != 0.0f) {
            // Finite and non-zero only: AnimationState ignores a NaN or an infinity, and a write the
            // clock ignores must not release the hold either, or a bad argument would silently
            // un-freeze a pose the caller asked to hold - at whatever rate was remembered.
            paused = false;
        }
        animationState.setSpeed(speed);
    }

    /** The rate set by {@link #setPlaybackSpeed}; 1.0 until a caller changes it. */
    public float playbackSpeed() {
        return animationState.speed();
    }

    /**
     * Moves the clock to {@code seconds} within the current playback, without changing the rate or
     * the clip. A no-op when nothing is playing (never played, or after {@link #stopAnimation()}).
     *
     * <p>Wrapping follows the playback rather than the number, because that is the movement a
     * continued clock would make from there:
     * <ul>
     *   <li>a <b>looping</b> clip wraps. {@code seek(-0.5f)} lands at
     *       {@code duration - 0.5f} - the position a backwards-running clock reaches after crossing
     *       zero - so seeking and playing out of the seek agree;</li>
     *   <li>a <b>non-looping</b> clip clamps into {@code [0, duration]}. There is no wrap to
     *       continue into: the clip has two termini and the clock stops on the nearer one;</li>
     *   <li>inside an active {@link #playSegment} pass it clamps into the segment's window, because
     *       while a pass is running the window <i>is</i> the current playback.</li>
     * </ul>
     *
     * <p><b>The pose follows on the next {@link #update(float)}, not inside this call.</b> A seek
     * edits the clock; {@code update} is the one place a pose is produced (every call resamples,
     * including {@code update(0.0f)}), so seeking here and resampling there keeps one definition of
     * "the pose is up to date" and keeps the renderer's {@link #poseGeneration()} cache honest. A
     * caller that has moved the clock and wants to draw the result this frame calls
     * {@code update(0.0f)}; a caller that seeks while {@link #pause() paused} can hold the new pose
     * by doing the same, since a paused clock does not advance through it.
     *
     * <p>Seeking a clip that had stopped on a terminus re-arms it: the clock is inside the clip
     * again, so a later {@code update} moves in either direction from there. {@link #hasFinished()}
     * reports false again for a mid-clip position, and true when the seek lands on the terminus the
     * playback runs towards (a one-shot's end, or a segment's stop).
     *
     * <p>A non-finite value is ignored, exactly as it is for a rate or an update delta.
     */
    public void seek(float seconds) {
        animationState.seek(seconds);
    }

    /**
     * Holds the current pose: the clock stops and {@link #resume()} continues from exactly there.
     *
     * <p>Written over the rate primitive on purpose - {@code pause()} remembers the current
     * {@link #playbackSpeed()} and sets it to 0, and {@code resume()} puts it back - so there is
     * <b>one</b> source of truth for "is the clock moving": the rate the clock is stepped by. A
     * second freeze flag inside the clock would be a second answer to that question, and the two
     * would disagree the first time a consumer paused and then set a rate, or set a rate and then
     * paused; this way the disagreement cannot exist.
     *
     * <p>Two consequences worth stating, because both are observable:
     * <ul>
     *   <li>{@link #playbackSpeed()} reads {@code 0.0} while paused - that is the rate the clock is
     *       actually running at - and {@link #resume()} restores the remembered one;</li>
     *   <li>the hold is <b>sticky</b> across {@link #play(String, boolean)},
     *       {@link #stopAnimation()} and {@link #setScene(ModelScene)}: the rate is documented as a
     *       setting that {@code play} does not reset, and the hold is written in that rate, so a
     *       paused instance that starts a new clip holds it at its first frame until
     *       {@link #resume()} (or an explicit non-zero {@link #setPlaybackSpeed}) says otherwise. A
     *       hold that an unrelated play could silently undo would not be a hold.</li>
     * </ul>
     *
     * <p>Pausing is not "finished": {@link #hasFinished()} stays false for a clip held mid-flight.
     * The pose is still resampled by every {@code update} call - from a clock that is not moving -
     * so the held matrices are bit-identical frame to frame, which is the property the API's own
     * test suite pins.
     */
    public void pause() {
        if (paused) {
            return;
        }
        speedBeforePause = animationState.speed();
        paused = true;
        animationState.setSpeed(0.0f);
    }

    /** Continues from the held position at the rate the clip had before {@link #pause()}. */
    public void resume() {
        if (!paused) {
            return;
        }
        paused = false;
        animationState.setSpeed(speedBeforePause);
    }

    /** True while {@link #pause()} is holding the clock; see it for what the hold survives. */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Plays the part of {@code animationName} between {@code fromSeconds} and {@code toSeconds} at
     * {@code speed}, then stops on the pose it reached and holds it.
     *
     * <p>This is the "start and stop timing" primitive: the shape of a state transition - a lid
     * opening, gear extending, a hatch swinging - is an interval of a clip, and one call states the
     * interval instead of a caller polling {@link #animationTime()} every frame and stopping the
     * clock by hand. It is one pass, always: the clock stops at {@code toSeconds} and stays there,
     * including on a clip whose own looping flag is set ({@link #play(String, boolean)} is what
     * loops a clip; a segment is a transition, not a loop).
     *
     * <h2>The edges, stated</h2>
     * <ul>
     *   <li><b>{@code toSeconds < fromSeconds}</b> plays the interval <b>backwards</b> and stops at
     *       {@code toSeconds}: closing a lid is the same pass with its endpoints the other way
     *       round, so {@code playSegment("lid", 2.0f, 0.0f, 1.0f)} closes it. The alternative
     *       reading - swapping the endpoints and always moving forwards - would make that call
     *       <i>open</i> the lid, and the endpoint order is the plainest way to say which way the
     *       pass goes;</li>
     *   <li><b>{@code speed}</b> is a magnitude (it scales the clip's own rate, so 2 is twice as
     *       fast); its <b>sign is ignored</b>, because the direction is already carried by the
     *       endpoint order and honouring both would let one call state two contradictory
     *       directions. A non-finite speed means 0 rather than "keep the previous rate";</li>
     *   <li><b>{@code speed == 0}</b> holds the clock at {@code fromSeconds} with the pass
     *       unfinished ({@link #hasFinished()} false): a transition held part-way, not a completed
     *       one. This is also the release valve for "start the pass but do not move yet";</li>
     *   <li><b>endpoints outside the clip</b> are clamped into {@code [0, duration]} - the same rule
     *       {@link #seek} applies to a one-shot. A segment is a bounded traversal, so an endpoint
     *       past the clip's end cannot mean "wrap around and sweep the whole clip";</li>
     *   <li><b>{@code from == to}</b> is a zero-length pass: the clock sits on the point and the
     *       first {@code update} reports it complete;</li>
     *   <li><b>a paused instance</b> is released by this call exactly as it is by a non-zero
     *       {@link #setPlaybackSpeed}, except at {@code speed == 0}, which keeps the hold - see
     *       {@link #pause()} for the one rule.</li>
     * </ul>
     *
     * <p>The pose reflects the seek to {@code fromSeconds} on the next {@link #update(float)}
     * (nothing here resamples - see {@link #seek(float)}), so a caller starting a pass and drawing
     * in the same frame calls {@code update(0.0f)} after this.
     *
     * @return false when the model has no animation of that name, in which case <b>the current
     *         playback is left exactly as it was</b> - a missing clip must not stop, rewind or
     *         speed up whatever was already playing, or a typo in a state name would read as the
     *         model misbehaving rather than as the name being wrong.
     */
    public boolean playSegment(String animationName, float fromSeconds, float toSeconds, float speed) {
        if (scene == null || animationName == null) {
            return false;
        }
        ModelAnimation animation = scene.animation(animationName);
        if (animation == null) {
            return false;
        }
        // Via setPlaybackSpeed so that the pause rule is applied in exactly one place; the rate it
        // writes is finite (a non-finite speed becomes 0 inside AnimationState.playSegment) and so is
        // either released-from-hold or 0.
        float rate = Float.isFinite(speed) ? Math.abs(speed) : 0.0f;
        setPlaybackSpeed(rate);
        animationState.playSegment(animation, fromSeconds, toSeconds, rate);
        return true;
    }

    /**
     * True when a non-looping playback has reached its terminus and is holding there - the value a
     * consumer polls instead of comparing {@link #animationTime()} against
     * {@link #animationDuration()} itself.
     *
     * <p>It is the clock's own {@code finished} flag, and that is the whole mapping: the flag is set
     * when a one-shot stops on a terminus (its end forwards, its start backwards) and when a
     * {@link #playSegment} pass reaches its stop, and it is <b>never</b> set by a looping clip,
     * whose clock wraps instead of stopping. So:
     * <ul>
     *   <li>false while a clip is running, and false for a looping clip no matter how long it has
     *       been playing;</li>
     *   <li>false while {@link #pause() paused} mid-clip - a held pose is not an arrived one;</li>
     *   <li>true after a segment completes, even on a clip whose looping flag is set, because the
     *       pass is what ended;</li>
     *   <li>true when a clip is paused <i>on</i> its terminus: the pose is the terminus pose and the
     *       clock is not going anywhere, so calling that "not finished" would only make the state
     *       machine that just arrived re-run its transition.</li>
     * </ul>
     *
     * <p>{@link #seek(float)} away from a terminus clears it again; {@link #play(String, boolean)}
     * always does.
     */
    public boolean hasFinished() {
        return animationState.isFinished();
    }

    /**
     * Seconds into the current clip, always inside {@code [0, animationDuration()]}.
     *
     * <p>Wrapped, so a looping clip reports a position inside the clip rather than a running total:
     * this is a playhead, not an odometer, and the number is meant to be compared against
     * {@link #animationDuration()}.
     *
     * <p>{@code 0} when no clip is attached - never played, or after {@link #stopAnimation()}. A
     * one-shot clip that has run to its end reports exactly {@link #animationDuration()}, which is
     * what makes the "run to the end and hold" recipe in the class comment expressible; reporting 0
     * there would make a finished clip indistinguishable from an unplayed one.
     */
    public float animationTime() {
        return animationState.animation() == null ? 0.0f : animationState.time();
    }

    /** The current clip's length in seconds, or {@code 0} when no clip is attached. */
    public float animationDuration() {
        return animationState.duration();
    }

    // ------------------------------------------------------------------
    // Node addressing - driving individual parts
    // ------------------------------------------------------------------

    /**
     * Every node name of the attached model, in the model file's own order.
     *
     * <p>The order is the file's, which is also node index order: a parser assigns indices as it
     * reads the node array (see {@code ModelScene#instantiate}, which preserves them), so
     * {@code nodeNames().get(i)} names {@code nodes()[i]}. Empty when there is no scene.
     *
     * <p>A list, not a live view: this is a query for a command or a log line, not something a
     * render path calls per frame.
     */
    public java.util.List<String> nodeNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (nodes != null) {
            for (ModelNode node : nodes) {
                names.add(node.name());
            }
        }
        return names;
    }

    /**
     * A handle on the node named {@code name}, or null when this model has no such node.
     *
     * <p>The lookup is <b>case-insensitive</b>, the way this project treats model and file names
     * ({@code ModelLibrary} lower-cases a model name for its id, {@code AbstractModelSource} retries
     * a reference in lower case before giving up): model files in the wild disagree about
     * capitalisation - {@code elevator_L} and {@code Elevator_L} are the same part to the author, and
     * a name is usually typed by hand into a config - and a case-sensitive lookup fails with a null
     * the caller has to remember to check, so the symptom is a control surface that never moves
     * rather than a name that did not match.
     *
     * <p>Not every lookup here folds case - {@code ModelScene#animation} matches a clip name exactly -
     * and that is called out rather than glossed, because a caller who has read one of the two and
     * assumes the other behaves the same way is exactly who this paragraph is for.
     *
     * <p>The returned handle addresses <b>this instance's</b> node, so the same name on two
     * instances of one model drives two different parts. It stays valid for the lifetime of the
     * instance's current tree; {@link #setScene(ModelScene)} replaces that tree, after which a
     * retained handle refers to a node that is no longer rendered (it does not start driving
     * whichever node now occupies that index).
     *
     * <p>Null is returned rather than an empty handle so the failure is at the lookup, next to the
     * name that was wrong, instead of at the first setter.
     */
    public ModelNodeRef node(String name) {
        if (nodes == null || name == null) {
            return null;
        }
        for (ModelNode node : nodes) {
            if (name.equalsIgnoreCase(node.name())) {
                return new ModelNodeRef(this, node.index(), node);
            }
        }
        return null;
    }

    /**
     * Hides or reveals one node and, because visibility is inherited, its whole subtree.
     *
     * <p>Package-visible rather than public: {@code ModelNodeRef} is the public way in, and this
     * method takes an index and the node object so it can check that the handle still addresses this
     * instance's current tree before it writes.
     *
     * <p>The propagation is what makes hiding a gear-bay door hide the door's own primitives and
     * every child hung off it. It runs here, when the override is set, rather than during the
     * per-frame transform walk, so {@link ModelNode#isVisible()} is already correct for a caller
     * that hides a part and immediately builds a draw list.
     *
     * <p>Revealing a node inside a hidden subtree does not reveal it: the parent's effective
     * visibility is read first and folded in, which is why the walk starts from the parent rather
     * than assuming the node was visible before.
     */
    void setNodeVisible(int nodeIndex, ModelNode node, boolean visible) {
        ModelNode[] tree = nodes;
        node.setHiddenByOverride(!visible);
        boolean parentVisible = true;
        if (tree != null && nodeIndex < tree.length && tree[nodeIndex] == node) {
            int parent = node.parentIndex();
            if (parent >= 0 && parent < tree.length && tree[parent] != null) {
                parentVisible = tree[parent].isVisible();
            }
        }
        propagateVisibility(node, parentVisible);
    }

    /** Writes the inherited visibility flag down one subtree. See {@link #setNodeVisible}. */
    private static void propagateVisibility(ModelNode node, boolean parentVisible) {
        boolean effective = parentVisible && !node.isHiddenByOverride();
        node.setEffectiveVisible(effective);
        java.util.List<ModelNode> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            propagateVisibility(children.get(i), effective);
        }
    }

    // ------------------------------------------------------------------
    // Per-material colour overrides
    // ------------------------------------------------------------------

    /**
     * Overrides one material's colour <b>for this instance only</b>: the draw uploads this RGBA in
     * place of the material's own {@code baseColorFactor}.
     *
     * <p>This is how a part is made brighter or whiter without editing the model - an exhaust plume
     * under afterburner, an anti-collision light flashing - and it is per instance, so one aircraft
     * can be lit while the one next to it is not.
     *
     * <p>The name lookup folds case, exactly as {@link #node(String)} does - the same reason applies:
     * a material name is typed by hand and the file's capitalisation is not something a caller should
     * have to match. A name that matches no material of the attached model is <b>ignored, not
     * remembered</b>: there is nothing to tint, {@link #hasMaterialTint} then reports false, and a
     * later {@link #setScene(ModelScene)} could not make a remembered name correct anyway.
     *
     * <p>Values are in the material's own space - linear RGBA, the same space as
     * {@code baseColorFactor} - and brighter-than-one components are allowed and useful: this is a
     * multiplier, and a plume is meant to blow out to white.
     */
    public void setMaterialTint(String materialName, float red, float green, float blue, float alpha) {
        int index = materialIndex(materialName);
        if (index < 0) {
            return;
        }
        if (materialTints == null) {
            materialTints = new float[scene.materials().length][];
        }
        float[] tint = materialTints[index];
        if (tint == null) {
            tint = new float[4];
            materialTints[index] = tint;
        }
        // Written in place, not replaced: the draw path holds this array for the frame's upload, and
        // swapping it every frame would hand the renderer a different object per set while saving
        // nothing - a four-float array per material is not worth a churn.
        tint[0] = red;
        tint[1] = green;
        tint[2] = blue;
        tint[3] = alpha;
    }

    /** Drops this instance's tint for one material; a no-op when there is none. */
    public void clearMaterialTint(String materialName) {
        int index = materialIndex(materialName);
        if (index >= 0 && materialTints != null) {
            materialTints[index] = null;
        }
    }

    /** True when this instance carries a tint for a material of that name. */
    public boolean hasMaterialTint(String materialName) {
        int index = materialIndex(materialName);
        return index >= 0 && materialTints != null && materialTints[index] != null;
    }

    /**
     * The live RGBA tint for material {@code materialIndex}, or null when the material's own colour
     * stands.
     *
     * <p>Public because the draw path lives in another package and must read it per material group.
     * <b>Read the returned array, do not retain or write it</b>: it is this instance's storage, and
     * it is returned live on purpose - a defensive copy here would allocate once per material group
     * per frame, on the one path this project has already deallocated once.
     */
    public float[] materialTint(int materialIndex) {
        if (materialTints == null || materialIndex < 0 || materialIndex >= materialTints.length) {
            return null;
        }
        return materialTints[materialIndex];
    }

    /**
     * The index of the material named {@code materialName}, or -1.
     *
     * <p>{@code equalsIgnoreCase}, not {@code toLowerCase} plus a map: it folds case without
     * allocating a string, and it is not locale-sensitive (a Turkish locale maps 'I' to a dotless
     * 'ı', which would make {@code Main} and {@code main} different names on that machine alone).
     */
    private int materialIndex(String materialName) {
        if (scene == null || materialName == null) {
            return -1;
        }
        ModelMaterial[] materials = scene.materials();
        for (int i = 0; i < materials.length; i++) {
            if (materials[i] != null && materialName.equalsIgnoreCase(materials[i].name())) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Placement
    // ------------------------------------------------------------------

    public float scale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale <= 0.0f ? 1.0f : scale;
        rebuildRootTransform();
    }

    public float yawOffset() {
        return yawOffset;
    }

    public void setYawOffset(float yawOffsetRadians) {
        this.yawOffset = yawOffsetRadians;
        rebuildRootTransform();
    }

    public void setPivot(float x, float y, float z) {
        this.pivotX = x;
        this.pivotY = y;
        this.pivotZ = z;
        rebuildRootTransform();
    }

    /**
     * The transform from model space to the space the caller renders in: yaw, then scale, then
     * pivot offset. Minecraft's entity renderer applies the entity's own position and body yaw
     * on top of this.
     */
    public Mat4 rootTransform() {
        return rootTransform;
    }

    private void rebuildRootTransform() {
        Mat4 yaw = Mat4.fromQuat(0.0f, (float) Math.sin(yawOffset * 0.5f), 0.0f,
                (float) Math.cos(yawOffset * 0.5f));
        Mat4 scaled = Mat4.scale(scale, scale, scale);
        rootTransform = Mat4.translation(pivotX, pivotY, pivotZ).multiply(yaw).multiply(scaled);
    }

    // ------------------------------------------------------------------
    // Per-frame update
    // ------------------------------------------------------------------

    /**
     * Advances the animation clock by {@code deltaSeconds} and resamples the pose.
     *
     * <p>Call once per frame, per instance, from the render thread with that frame's elapsed time.
     * A static model costs almost nothing per frame; sampling a pose is never skipped, so that
     * there is exactly one definition of "the pose is up to date" (see the comment below).
     *
     * <p><b>{@code deltaSeconds} is in seconds</b>, and that is not a formality: Minecraft's own
     * frame delta ({@code Minecraft#getDeltaFrameTime()}) is in <b>ticks</b> - 20 per second, so
     * about 1.0 per frame - and feeding it here unconverted makes every animation in this mod run
     * about 20 times too fast. The mod's own per-frame hook converts at the call site
     * ({@code ClientEvents}, one named constant); a consumer that derives its own delta from the
     * game must do the same. The failure was real and silent for a long time: a model 20x too fast
     * still animates, and only a measurement against the wall clock separates it from a correct one.
     *
     * <h2>Node overrides are applied here, on top of the animation</h2>
     * Every frame the pipeline is: reset the tree to the file's rest pose, sample the active clip
     * over it, <b>then write this instance's node overrides</b> - see {@code AnimationPlayer#apply},
     * which is the call below and the only place a pose is produced. An override therefore survives
     * {@code update()} calls without being re-set, and it cannot flicker between the clip's pose and
     * the driven one: the clip is rewritten every frame, so an override applied anywhere earlier
     * would be overwritten by the very next sample.
     *
     * <p>Applying it inside the sampler rather than after this method returns is what makes a driven
     * <b>joint</b> work: world transforms, {@code pose.worldMatrices()} and the joint matrices are
     * all derived after the overrides are written, so a control surface that is a skin joint moves
     * the skin instead of only its own node matrix.
     *
     * <h2>Large deltas are clamped, and that is observable</h2>
     * The step is capped at {@link AnimationState#MAX_STEP_SECONDS} per call, so
     * {@code update(0.5f)} on a fresh instance leaves the clock at 0.25 s, not 0.5 s. This is
     * deliberate - stepping a spinning animation by a whole second in one call makes the visible
     * pose depend on frame rate rather than on elapsed time, and a lag spike would teleport every
     * animating model - but it does mean this call is <b>not</b> a faithful integrator over long
     * steps. A caller that genuinely has half a second of elapsed time (a paused debugger, a
     * test, a server-side simulation step) must call this repeatedly to advance the full amount.
     *
     * <p>This is documented rather than merely implemented because the failure is silent: the
     * model animates, just at half the expected rate, which reads as a wrong animation speed
     * rather than as a clamped clock. It cost one debugging round in this project's own
     * integration test, which asserted an exact pose after a single 0.5 s step.
     *
     * <h2>The step's direction comes from playbackSpeed, not from the sign of the delta</h2>
     * An update step is an elapsed time and is never negative; a rewind is expressed with
     * {@link #setPlaybackSpeed(float)}, so a negative {@code deltaSeconds} is discarded exactly as
     * it always was. The clamp above is applied to the <i>elapsed</i> time before the rate
     * multiplies it, so a rate of 2 does not buy a longer step - it moves twice as far within the
     * same 0.25 s budget.
     */
    public void update(float deltaSeconds) {
        if (scene == null) {
            return;
        }
        AnimationState state = animationState;
        // No "is it playing" test here any more: AnimationState#advance owns that decision, and it
        // has to, because its answer now depends on the direction. A clip that ran to the end of a
        // one-shot is not playing, yet a negative rate must still move it - deciding that here
        // would silently drop the rewind that closes the model. Everything else is unchanged:
        // advance() is a no-op when paused, when no clip is attached, or when nothing was started.
        state.advance(deltaSeconds);
        // Always go through AnimationPlayer, including for a model with no animation attached.
        // An earlier revision short-circuited to ModelScene.updateWorldTransforms here, which was
        // cheaper but left pose.worldMatrices() zero-filled - and AnimationPose documents those
        // matrices as valid after apply. A renderer reading them for a model that happens not to
        // be animated would have read zeros and drawn every vertex at the origin. One code path
        // for "pose is up to date" is worth the branch it costs.
        AnimationPlayer.apply(scene, nodes, state, pose);
        poseGeneration++;
    }

    /**
     * Forces a resample of the current clock position on the next render. Needed after a
     * hot reload, a scale change or a manual {@code setTime}.
     */
    public void invalidatePose() {
        poseGeneration++;
    }

    /** Bumped on every resample; a renderer keys its GPU vertex cache on this. */
    public int poseGeneration() {
        return poseGeneration;
    }

    /** Flat per-joint skinning matrices, or null when the model is unskinned. */
    public float[] jointMatrices() {
        return pose == null ? null : pose.jointMatrices();
    }

    @Override
    public String toString() {
        return "ModelInstance('" + instanceName + "' model=" + (scene == null ? "none" : scene.name())
                + " anim=" + animationState.animationName() + " scale=" + scale + ")";
    }
}
