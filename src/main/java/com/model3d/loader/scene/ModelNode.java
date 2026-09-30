package com.model3d.loader.scene;

import com.model3d.loader.math.Mat4;
import com.model3d.loader.math.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * One node of a model's transform hierarchy.
 *
 * <p>Serves as both the joint (when the node is referenced by a skin) and the mesh attachment
 * point (when it carries {@link #meshIndex}). glTF makes no distinction between the two, and
 * neither does this class.
 *
 * <p>Mutable by design: this object <b>is</b> the animation state. {@link #localTransform} is
 * overwritten every frame by the animation system, and {@link #globalTransform} is the
 * composed world-space matrix every frame. Sharing one {@code ModelNode} tree between two
 * concurrently animated instances of the same model would be a bug; that is why
 * {@link ModelScene#instantiate()} exists and the loaded {@link ModelScene} is treated as
 * read-only, load-time data.
 */
public final class ModelNode {

    private final int index;
    private final String name;

    /** Index into the parent's {@link #children}, or -1 if this node is a scene root. */
    private final int parent;

    /** Index into {@link ModelScene#meshes()}, or -1 for a pure transform/joint node. */
    private final int meshIndex;

    /** Index into {@link ModelScene#skins()}, or -1 when this node is not a skeleton root. */
    private final int skinIndex;

    private final List<ModelNode> children = new ArrayList<>();

    /** Rest pose as authored in the file, never modified after load. */
    private final float[] restTranslation;
    private final float[] restRotation;
    private final float[] restScale;
    private final Mat4 restLocalTransform;

    /** Animated pose, rewritten each frame when an animation drives this node. */
    private final float[] translation = new float[3];
    private final float[] rotation = { 0, 0, 0, 1 };
    private final float[] scale = { 1, 1, 1 };

    private Mat4 localTransform;
    private Mat4 globalTransform = Mat4.IDENTITY;

    /** Index of this node inside the skin's joint array, or -1 when not a joint of its skin. */
    private int jointIndex = -1;

    // ------------------------------------------------------------------
    // Per-instance overrides - see com.model3d.loader.api.ModelNodeRef
    // ------------------------------------------------------------------
    // These live on the node, not in a side table, and that is the whole isolation story: a scene's
    // nodeTemplates() are shared by every entity using the model, while an instance owns the copies
    // that ModelScene.instantiate() made (see ModelScene#instantiate). Writing an override to a
    // template would drive every entity's model at once - the same failure the per-instance node
    // tree exists to prevent, and one that reads as "all aircraft share one control surface".

    /**
     * Rotation override as a glTF quaternion {@code (x, y, z, w)}, or null when the rest pose and
     * the animation own this node's rotation.
     *
     * <p>Allocated on the first {@code setRotation} rather than up front: a 200-joint skeleton would
     * otherwise carry 800 floats of override storage that nothing ever writes, on every instance.
     */
    private float[] rotationOverride;

    /**
     * Additive translation offset in the node's own local space, or null when none.
     *
     * <p>Additive rather than replacing, because that is the useful direction for a driven part: an
     * elevator deflects relative to wherever the animation put it, and the file's authored offset
     * stays in force.
     */
    private float[] translationOverride;

    /** Uniform scale override; only read when {@link #scaleOverridden}. */
    private float uniformScaleOverride = 1.0f;
    private boolean scaleOverridden;

    /**
     * This node's <b>own</b> visibility override - true when it was hidden. This is not the answer
     * to "is this node drawn": visibility is inherited, so a node inside a hidden subtree is not
     * drawn whatever this flag says. {@link #isVisible()} is the effective state.
     */
    private boolean hiddenByOverride;

    /**
     * Effective visibility: false when this node or <b>any ancestor</b> is hidden.
     *
     * <p>Maintained by the tree owner ({@code ModelInstance}) rather than derived per frame, so
     * hiding a gear-bay door hides its children immediately - including before the next
     * {@code update()}, which is what a test that hides and then builds a draw list needs.
     */
    private boolean visible = true;

    public ModelNode(int index, String name, int parent, int meshIndex, int skinIndex,
                     float[] translation, float[] rotation, float[] scale) {
        this.index = index;
        this.name = name;
        this.parent = parent;
        this.meshIndex = meshIndex;
        this.skinIndex = skinIndex;
        this.restTranslation = translation.clone();
        this.restRotation = rotation.clone();
        this.restScale = scale.clone();
        this.restLocalTransform = Mat4.compose(translation, rotation, scale);
        resetToRest();
    }

    public int index() {
        return index;
    }

    public String name() {
        return name;
    }

    public int parentIndex() {
        return parent;
    }

    public int meshIndex() {
        return meshIndex;
    }

    public int skinIndex() {
        return skinIndex;
    }

    public List<ModelNode> children() {
        return children;
    }

    public Vec3 restTranslation() {
        return new Vec3(restTranslation[0], restTranslation[1], restTranslation[2]);
    }

    public float[] restTranslationArray() {
        return restTranslation.clone();
    }

    public float[] restRotationArray() {
        return restRotation.clone();
    }

    public float[] restScaleArray() {
        return restScale.clone();
    }

    // --- animated pose accessors: live arrays, read/write, no allocation ---

    /** Live animated translation. Size 3; write directly to avoid per-frame garbage. */
    public float[] translation() {
        return translation;
    }

    /** Live animated rotation quaternion, {@code (x, y, z, w)}. Size 4. */
    public float[] rotation() {
        return rotation;
    }

    /** Live animated scale. Size 3. */
    public float[] scale() {
        return scale;
    }

    public Mat4 restLocalTransform() {
        return restLocalTransform;
    }

    public Mat4 localTransform() {
        return localTransform;
    }

    public Mat4 globalTransform() {
        return globalTransform;
    }

    /**
     * Writes this node's animated pose into {@link #localTransform}. Cheaper than
     * {@link Mat4#compose} when nothing is animated, which is the common case for static parts.
     */
    public void updateLocalTransform() {
        if (translation[0] == restTranslation[0] && translation[1] == restTranslation[1]
                && translation[2] == restTranslation[2]
                && rotation[0] == restRotation[0] && rotation[1] == restRotation[1]
                && rotation[2] == restRotation[2] && rotation[3] == restRotation[3]
                && scale[0] == restScale[0] && scale[1] == restScale[1] && scale[2] == restScale[2]) {
            localTransform = restLocalTransform;
        } else {
            localTransform = Mat4.compose(translation, rotation, scale);
        }
    }

    public void setGlobalTransform(Mat4 global) {
        this.globalTransform = global;
    }

    /** Restores the authored rest pose; also clears any half-applied animation. */
    public final void resetToRest() {
        System.arraycopy(restTranslation, 0, translation, 0, 3);
        System.arraycopy(restRotation, 0, rotation, 0, 4);
        System.arraycopy(restScale, 0, scale, 0, 3);
        localTransform = restLocalTransform;
        globalTransform = restLocalTransform;
    }

    public int jointIndex() {
        return jointIndex;
    }

    public void setJointIndex(int jointIndex) {
        this.jointIndex = jointIndex;
    }

    // ------------------------------------------------------------------
    // Overrides
    // ------------------------------------------------------------------

    /**
     * True when this node carries any override at all: rotation, translation, scale or visibility.
     *
     * <p>This is what {@code ModelNodeRef.isOverridden()} reports. A caller uses it to tell "this
     * part is being driven" from "this part is showing whatever the clip says".
     */
    public boolean hasOverrides() {
        return rotationOverride != null || translationOverride != null || scaleOverridden
                || hiddenByOverride;
    }

    /** Replaces this node's local rotation with the quaternion {@code (x, y, z, w)}. */
    public void setRotationOverride(float x, float y, float z, float w) {
        if (rotationOverride == null) {
            rotationOverride = new float[4];
        }
        rotationOverride[0] = x;
        rotationOverride[1] = y;
        rotationOverride[2] = z;
        rotationOverride[3] = w;
    }

    /** Adds {@code (x, y, z)} to this node's local translation, in the model file's units. */
    public void setTranslationOverride(float x, float y, float z) {
        if (translationOverride == null) {
            translationOverride = new float[3];
        }
        translationOverride[0] = x;
        translationOverride[1] = y;
        translationOverride[2] = z;
    }

    /** Replaces this node's local scale with {@code (uniform, uniform, uniform)}. */
    public void setScaleOverride(float uniform) {
        this.uniformScaleOverride = uniform;
        this.scaleOverridden = true;
    }

    /**
     * Drops every transform override and this node's own hidden flag.
     *
     * <p>The effective {@link #isVisible()} flag is <b>not</b> recomputed here: it depends on the
     * node's ancestors, which this node cannot see. The caller that owns the tree does that - see
     * {@code ModelInstance#setNodeVisible} - which is why {@code ModelNodeRef.clear()} clears and
     * then re-propagates rather than only calling this.
     */
    public void clearOverrides() {
        rotationOverride = null;
        translationOverride = null;
        scaleOverridden = false;
        hiddenByOverride = false;
    }

    /**
     * Writes the overrides on top of the node's current pose (the live {@link #translation()},
     * {@link #rotation()} and {@link #scale()} arrays), leaving {@link #updateLocalTransform()} to
     * rebuild the local matrix.
     *
     * <p>Called by the pose pipeline once per frame, after the animation has written the tree and
     * <b>before</b> world transforms and joint matrices are derived - see
     * {@code AnimationPlayer#apply}. Applying an override after that derivation would leave a driven
     * joint's skin at the clip's pose, which is the difference between "the wing moves" and "the
     * wing moves but the skinned skin of it does not".
     *
     * <p>Not idempotent on an un-resampled pose: the translation override is added, so calling this
     * twice without a reset-to-rest + sample in between adds the offset twice. The sampler resets
     * every node to rest first, so the normal per-frame call is unaffected.
     */
    public void applyOverrides() {
        if (rotationOverride != null) {
            System.arraycopy(rotationOverride, 0, rotation, 0, 4);
        }
        if (translationOverride != null) {
            translation[0] += translationOverride[0];
            translation[1] += translationOverride[1];
            translation[2] += translationOverride[2];
        }
        if (scaleOverridden) {
            scale[0] = uniformScaleOverride;
            scale[1] = uniformScaleOverride;
            scale[2] = uniformScaleOverride;
        }
    }

    /**
     * True when this node's own visibility was overridden to hidden.
     *
     * <p>Not "is this node drawn": a visible node under a hidden parent is not drawn either. Use
     * {@link #isVisible()} for the inherited answer.
     */
    public boolean isHiddenByOverride() {
        return hiddenByOverride;
    }

    /** Sets this node's own hidden flag. The effective flag is the tree owner's to recompute. */
    public void setHiddenByOverride(boolean hidden) {
        this.hiddenByOverride = hidden;
    }

    /**
     * Effective visibility: false when this node or any ancestor is hidden, so hiding a gear-bay
     * door node hides the door and everything parented to it.
     *
     * <p>Valid at all times, not only after {@code update()}: the flag is written by the
     * propagation in {@code ModelInstance#setNodeVisible}, which runs when the override is set.
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Writes the effective visibility flag. <b>Internal to the propagation walk</b> in
     * {@code ModelInstance#setNodeVisible}: calling it directly puts a node's reported visibility
     * out of step with its ancestors', which is exactly the state the inherited rule exists to
     * avoid.
     */
    public void setEffectiveVisible(boolean effectiveVisible) {
        this.visible = effectiveVisible;
    }

    void addChild(ModelNode child) {
        children.add(child);
    }

    @Override
    public String toString() {
        return "ModelNode#" + index + "('" + name + "' mesh=" + meshIndex + " skin=" + skinIndex
                + " children=" + children.size() + ")";
    }
}
