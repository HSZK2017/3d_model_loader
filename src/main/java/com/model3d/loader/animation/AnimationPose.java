package com.model3d.loader.animation;

/**
 * Per-instance animation scratch space, sized once for a model and reused every frame.
 *
 * <p>Why this exists rather than a fresh array per frame: sampling a pose for a 200-joint
 * skeleton at 60 fps allocates a megabyte a second of short-lived arrays, and the resulting GC
 * sawtooth is visible as periodic stutter in an otherwise idle scene. Every array here is
 * allocated at construction and overwritten in place.
 *
 * <p>Owner: one {@code AnimationPose} belongs to one {@code ModelInstance} and must not be
 * shared between entities - the whole point is that it is the mutable per-instance state.
 */
public final class AnimationPose {

    private final int nodeCount;
    private final int jointCount;

    /** Flags bit 1 = translation written, bit 2 = rotation, bit 4 = scale; per node. */
    private final byte[] animatedFlags;

    /** Per-joint skinning matrices, {@code 16 * jointCount} floats, in column-major order. */
    private final float[] jointMatrices;

    /** Per-node world matrices, {@code 16 * nodeCount} floats, in column-major order. */
    private final float[] worldMatrices;

    public AnimationPose(int nodeCount, int jointCount) {
        this.nodeCount = nodeCount;
        this.jointCount = jointCount;
        this.animatedFlags = new byte[nodeCount];
        this.jointMatrices = new float[16 * Math.max(jointCount, 1)];
        this.worldMatrices = new float[16 * Math.max(nodeCount, 1)];
    }

    public int nodeCount() {
        return nodeCount;
    }

    public int jointCount() {
        return jointCount;
    }

    /** Per-node "was this property written by the animation" flags; cleared by {@link #clear()}. */
    public byte[] animatedFlags() {
        return animatedFlags;
    }

    /** Flat per-joint skinning matrices for the renderer; see the class comment for layout. */
    public float[] jointMatrices() {
        return jointMatrices;
    }

    /** Flat per-node world matrices, valid after {@code AnimationPlayer.apply} has run. */
    public float[] worldMatrices() {
        return worldMatrices;
    }

    /** Resets flags so a new frame starts from the rest pose. */
    public void clear() {
        java.util.Arrays.fill(animatedFlags, (byte) 0);
    }

    public static final byte FLAG_TRANSLATION = 1;
    public static final byte FLAG_ROTATION = 2;
    public static final byte FLAG_SCALE = 4;
}
