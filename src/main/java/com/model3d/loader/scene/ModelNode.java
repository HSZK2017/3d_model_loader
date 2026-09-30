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

    void addChild(ModelNode child) {
        children.add(child);
    }

    @Override
    public String toString() {
        return "ModelNode#" + index + "('" + name + "' mesh=" + meshIndex + " skin=" + skinIndex
                + " children=" + children.size() + ")";
    }
}
