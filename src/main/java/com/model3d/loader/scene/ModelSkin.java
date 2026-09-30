package com.model3d.loader.scene;

import com.model3d.loader.math.Mat4;

/**
 * A skinning skeleton: the joints and the inverse bind matrices that take a vertex from
 * model space into each joint's space.
 *
 * <p>{@link #joints} holds indices into the scene's node array; {@link #inverseBindMatrices}
 * is parallel to it, and both have the same length. Keeping them as two parallel arrays
 * rather than a joint object per entry matters because the skinning loop reads the joint
 * node's {@code globalTransform} for every joint on every frame.
 */
public final class ModelSkin {

    private final String name;
    private final int[] joints;
    private final Mat4[] inverseBindMatrices;

    /** Index of the node this skin hangs off, or -1 when unspecified in the file. */
    private final int skeletonRoot;

    public ModelSkin(String name, int[] joints, Mat4[] inverseBindMatrices, int skeletonRoot) {
        if (joints.length != inverseBindMatrices.length) {
            throw new IllegalArgumentException("Skin '" + name + "' has " + joints.length
                    + " joints but " + inverseBindMatrices.length + " inverse bind matrices");
        }
        this.name = name;
        this.joints = joints;
        this.inverseBindMatrices = inverseBindMatrices;
        this.skeletonRoot = skeletonRoot;
    }

    public String name() {
        return name;
    }

    /** Node indices of the joints, in the order the file declared them. */
    public int[] joints() {
        return joints;
    }

    public Mat4[] inverseBindMatrices() {
        return inverseBindMatrices;
    }

    public int skeletonRoot() {
        return skeletonRoot;
    }

    public int jointCount() {
        return joints.length;
    }

    /** Index of a node in {@link #joints}, or -1 when the node is not a joint of this skin. */
    public int indexOfJoint(int nodeIndex) {
        for (int i = 0; i < joints.length; i++) {
            if (joints[i] == nodeIndex) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "ModelSkin('" + name + "' joints=" + joints.length + ")";
    }
}
