package com.model3d.loader.math;

/**
 * A 3-component float vector, immutable.
 *
 * <p>Used for the static parts of a model (positions in a node's local space, bounding boxes,
 * scales). Per-frame animation values deliberately do NOT allocate {@code Vec3} - see
 * {@code animation.AnimationPose}, which writes into flat float arrays.
 */
public final class Vec3 {

    public static final Vec3 ZERO = new Vec3(0.0f, 0.0f, 0.0f);
    public static final Vec3 ONE = new Vec3(1.0f, 1.0f, 1.0f);

    public final float x;
    public final float y;
    public final float z;

    public Vec3(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 subtract(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(float s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public float dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public Vec3 normalize() {
        float len = length();
        if (len == 0.0f || !Float.isFinite(len)) {
            return ZERO;
        }
        return scale(1.0f / len);
    }

    @Override
    public String toString() {
        return String.format("(%.4f, %.4f, %.4f)", x, y, z);
    }
}
