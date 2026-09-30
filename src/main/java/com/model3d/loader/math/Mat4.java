package com.model3d.loader.math;

/**
 * A 4x4 matrix, column-major, stored as 16 floats - the same convention glTF uses, so no
 * transposition happens on the boundary between a file and this class.
 *
 * <p>{@code m[column * 4 + row]}. {@link #transform(float[], int, float, float, float, float)}
 * is the hot path (millions of calls to skin a mesh) and is written to be allocation-free.
 *
 * <p>Immutable, deliberately: these are shared across frames and across entity render passes,
 * and a mutable matrix shared by reference is a class of bug that is very hard to see.
 */
public final class Mat4 {

    /** Identity matrix, safe to share because {@link Mat4} is immutable. */
    public static final Mat4 IDENTITY = new Mat4(new float[] {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
    });

    private final float[] m;

    /** Takes ownership of {@code m}; callers must not mutate it afterwards. */
    public Mat4(float[] m) {
        if (m.length != 16) {
            throw new IllegalArgumentException("Mat4 needs 16 floats, got " + m.length);
        }
        this.m = m;
    }

    public float get(int column, int row) {
        return m[column * 4 + row];
    }

    /** Raw backing array. Do not mutate. */
    public float[] raw() {
        return m;
    }

    public Mat4 copy() {
        return new Mat4(m.clone());
    }

    /** this * other (this applied after other). */
    public Mat4 multiply(Mat4 other) {
        float[] r = new float[16];
        Mat4.mul(m, other.m, r);
        return new Mat4(r);
    }

    /**
     * Matrix product into {@code out}, which may alias {@code a} or {@code b} only if it is a
     * distinct array - alias-safe by copying operands when needed.
     */
    public static void mul(float[] a, float[] b, float[] out) {
        float[] lhs = a == out ? a.clone() : a;
        float[] rhs = b == out ? b.clone() : b;
        for (int c = 0; c < 4; c++) {
            int cb = c * 4;
            float b0 = rhs[cb];
            float b1 = rhs[cb + 1];
            float b2 = rhs[cb + 2];
            float b3 = rhs[cb + 3];
            out[cb]     = lhs[0] * b0 + lhs[4] * b1 + lhs[8]  * b2 + lhs[12] * b3;
            out[cb + 1] = lhs[1] * b0 + lhs[5] * b1 + lhs[9]  * b2 + lhs[13] * b3;
            out[cb + 2] = lhs[2] * b0 + lhs[6] * b1 + lhs[10] * b2 + lhs[14] * b3;
            out[cb + 3] = lhs[3] * b0 + lhs[7] * b1 + lhs[11] * b2 + lhs[15] * b3;
        }
    }

    /**
     * Writes {@code m * (x, y, z, 1)} into {@code out[offset..offset+3]}.
     * Allocation-free, no bounds work beyond what the JIT removes: this is the skinning inner loop.
     */
    public static void transform(float[] m, int offset, float x, float y, float z, float w, float[] out) {
        out[offset]     = m[0] * x + m[4] * y + m[8]  * z + m[12] * w;
        out[offset + 1] = m[1] * x + m[5] * y + m[9]  * z + m[13] * w;
        out[offset + 2] = m[2] * x + m[6] * y + m[10] * z + m[14] * w;
    }

    /** Translation-only matrix. */
    public static Mat4 translation(float x, float y, float z) {
        return new Mat4(new float[] {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                x, y, z, 1
        });
    }

    /** Non-uniform scale matrix. */
    public static Mat4 scale(float x, float y, float z) {
        return new Mat4(new float[] {
                x, 0, 0, 0,
                0, y, 0, 0,
                0, 0, z, 0,
                0, 0, 0, 1
        });
    }

    /** Rotation from a glTF quaternion {@code (x, y, z, w)}. */
    public static Mat4 fromQuat(float x, float y, float z, float w) {
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx = w * x, wy = w * y, wz = w * z;
        return new Mat4(new float[] {
                1 - 2 * (yy + zz), 2 * (xy + wz),     2 * (xz - wy),     0,
                2 * (xy - wz),     1 - 2 * (xx + zz), 2 * (yz + wx),     0,
                2 * (xz + wy),     2 * (yz - wx),     1 - 2 * (xx + yy), 0,
                0,                 0,                 0,                 1
        });
    }

    /** Translation * Rotation * Scale, the standard glTF node composition order. */
    public static Mat4 compose(float[] translation, float[] rotation, float[] scale) {
        float[] r = new float[16];
        Mat4.fromQuat(rotation[0], rotation[1], rotation[2], rotation[3]).writeInto(r);
        // Scale columns 0..2, each by its own factor - storage is m[column * 4 + row], so a column
        // is a contiguous triple. Scaling the ROWS instead computes S * R rather than the R * S this
        // method documents, and the two differ by a whole scale factor on any rotation that is not
        // diagonal in the scale's basis: a quarter turn about +Y with scale (2,1,4) sent a point at
        // (1,0,0) to (0,0,-4) instead of (0,0,-2). The fixture and corpus nodes all rotate about an
        // axis, where the two orders coincide, which is why only Mat4ComposeTest caught it.
        for (int c = 0; c < 3; c++) {
            r[c * 4]     *= scale[c];
            r[c * 4 + 1] *= scale[c];
            r[c * 4 + 2] *= scale[c];
        }
        r[12] = translation[0];
        r[13] = translation[1];
        r[14] = translation[2];
        return new Mat4(r);
    }

    private void writeInto(float[] target) {
        System.arraycopy(m, 0, target, 0, 16);
    }

    /**
     * General 4x4 inverse. Returns {@link #IDENTITY} for a singular matrix rather than a matrix
     * of infinities: a degenerate inverse-bind matrix would otherwise poison every skinned
     * vertex with NaN, which renders as an invisible model and reads like a shader bug.
     */
    public Mat4 invert() {
        float[] inv = new float[16];
        float[] a = m;
        inv[0]  =  a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15]
                 + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4]  = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15]
                 - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8]  =  a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15]
                 + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14]
                 - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1]  = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15]
                 - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5]  =  a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15]
                 + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9]  = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15]
                 - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] =  a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14]
                 + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2]  =  a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15]
                 + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6]  = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15]
                 - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] =  a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15]
                 + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14]
                 - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3]  = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11]
                 - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7]  =  a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11]
                 + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11]
                 - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] =  a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10]
                 + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];

        float det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (det == 0.0f || !Float.isFinite(det)) {
            return IDENTITY;
        }
        float invDet = 1.0f / det;
        for (int i = 0; i < 16; i++) {
            inv[i] *= invDet;
        }
        return new Mat4(inv);
    }

    /**
     * Transforms {@code (x, y, z)} as a position (w = 1) and returns the three components into
     * {@code out[offset..offset+2]}.
     */
    public void transformPoint(float x, float y, float z, float[] out, int offset) {
        float w = m[3] * x + m[7] * y + m[11] * z + m[15];
        if (w != 0.0f && w != 1.0f) {
            out[offset]     = (m[0] * x + m[4] * y + m[8]  * z + m[12]) / w;
            out[offset + 1] = (m[1] * x + m[5] * y + m[9]  * z + m[13]) / w;
            out[offset + 2] = (m[2] * x + m[6] * y + m[10] * z + m[14]) / w;
        } else {
            out[offset]     = m[0] * x + m[4] * y + m[8]  * z + m[12];
            out[offset + 1] = m[1] * x + m[5] * y + m[9]  * z + m[13];
            out[offset + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
        }
    }

    /**
     * Transforms {@code (x, y, z)} as a direction (w = 0) - no translation, no perspective
     * divide. Used for normals and for the first three columns of the inverse-transpose.
     */
    public void transformDirection(float x, float y, float z, float[] out, int offset) {
        out[offset]     = m[0] * x + m[4] * y + m[8]  * z;
        out[offset + 1] = m[1] * x + m[5] * y + m[9]  * z;
        out[offset + 2] = m[2] * x + m[6] * y + m[10] * z;
    }

    /** Largest absolute difference between two matrices; used by tests and by pose-change checks. */
    public float maxDifference(Mat4 other) {
        float max = 0.0f;
        for (int i = 0; i < 16; i++) {
            max = Math.max(max, Math.abs(m[i] - other.m[i]));
        }
        return max;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Mat4[");
        for (int row = 0; row < 4; row++) {
            if (row > 0) {
                sb.append("; ");
            }
            for (int col = 0; col < 4; col++) {
                sb.append(String.format("%.4f", get(col, row)));
                if (col < 3) {
                    sb.append(' ');
                }
            }
        }
        return sb.append(']').toString();
    }
}
