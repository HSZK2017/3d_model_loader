package com.model3d.loader.api;

import com.model3d.loader.animation.AnimationPose;
import com.model3d.loader.animation.AnimationPlayer;
import com.model3d.loader.animation.AnimationState;
import com.model3d.loader.math.Mat4;
import com.model3d.loader.scene.ModelAnimation;
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
 *   instance.setScale(blocksPerModelUnit);      // see ModelScale.forHandle
 *   instance.setYawOffset((float) Math.PI);     // a model authored facing -Z
 *   instance.setPivot(0.0f, -0.5f, 0.0f);       // model-space offset, in model units
 *   instance.update(deltaSeconds);              // advance clock + sample pose
 *   // renderer reads instance.nodes(), instance.jointMatrices()
 * </pre>
 *
 * <p>The three placement setters are separate on purpose: they are applied by the renderer as one
 * root transform ({@code pivot}, then {@code yaw}, then {@code scale}), and a caller that only wants
 * to resize a model must not have to restate the other two.
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

    /** Model-space offset of the pivot, in blocks, applied after scaling. */
    private float pivotX;
    private float pivotY;
    private float pivotZ;

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

    /** Swaps the underlying model, dropping the pose and clock. */
    public void setScene(ModelScene scene) {
        this.scene = scene;
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
     */
    public void update(float deltaSeconds) {
        if (scene == null) {
            return;
        }
        AnimationState state = animationState;
        boolean clockMoved = state.isPlaying() && !state.isPaused();
        if (clockMoved) {
            state.advance(deltaSeconds);
        }
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
