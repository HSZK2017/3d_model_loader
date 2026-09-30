package com.model3d.loader.format.gltf;

import com.model3d.loader.Model3D;

/**
 * Turns a glTF node {@code matrix} into the translation/rotation/scale triple {@link
 * com.model3d.loader.scene.ModelNode} stores.
 *
 * <p>{@code ModelNode} keeps a <b>TRS</b> pose, because that is the only form an animation can
 * overwrite per frame without the parser having to re-decompose anything. glTF, however, lets a node
 * carry a single 4x4 {@code matrix} instead, and exporters use it constantly: three of the 26 nodes
 * of {@code sukhoi_su-30_flanker_c.glb} and four of the seven in {@code pbr_sukhoi_su-30.glb} are
 * matrices, and node {@code Su30 _0} of the former mixes non-uniform scale with a translation and a
 * 180-degree rotation. Dropping those to identity would collapse the aircraft into its own origin.
 *
 * <p>The decomposition is the standard one and is <b>exact for the file's own matrix</b>:
 * {@code translation} is column 3, each scale factor is the length of the corresponding column, and
 * the rotation is the column-normalized upper-left 3x3. A negative determinant (a mirror, which a
 * right-handed rotation cannot express) is folded into the first scale factor, so
 * {@code compose(translation, rotation, scale)} reproduces the original matrix exactly rather than
 * reflected-and-therefore-inside-out.
 *
 * <p>Carrying the rotation as a quaternion also loses nothing that matters here: a 4x4 node matrix
 * in a glTF file is affine, and anything non-orthonormal in its upper-left 3x3 would not be a
 * rotation in the first place.
 */
final class TransformDecomposition {

    private TransformDecomposition() {
    }

    /**
     * Decomposes {@code matrix} (16 floats, column-major, the glTF layout) into {@code translation}
     * (3), {@code rotation} (quaternion xyzw, 4) and {@code scale} (3).
     *
     * @param element node name for diagnostics
     */
    static void decompose(float[] matrix, float[] translation, float[] rotation, float[] scale,
                          String element) {
        translation[0] = matrix[12];
        translation[1] = matrix[13];
        translation[2] = matrix[14];

        float c0x = matrix[0];
        float c0y = matrix[1];
        float c0z = matrix[2];
        float c1x = matrix[4];
        float c1y = matrix[5];
        float c1z = matrix[6];
        float c2x = matrix[8];
        float c2y = matrix[9];
        float c2z = matrix[10];

        float sx = length(c0x, c0y, c0z);
        float sy = length(c1x, c1y, c1z);
        float sz = length(c2x, c2y, c2z);
        scale[0] = sx;
        scale[1] = sy;
        scale[2] = sz;

        if (sx == 0.0f || sy == 0.0f || sz == 0.0f) {
            // A collapsed axis has no direction, so no rotation can be recovered from it. Keeping the
            // (zero) scale preserves the collapse; guessing a rotation would not.
            Model3D.LOGGER.warn("{}: node matrix has a zero-length column; using an identity rotation "
                    + "for scale ({}, {}, {})", element, sx, sy, sz);
            rotation[0] = 0.0f;
            rotation[1] = 0.0f;
            rotation[2] = 0.0f;
            rotation[3] = 1.0f;
            return;
        }

        // Column-normalized upper-left 3x3: r[row][col].
        float r00 = c0x / sx;
        float r10 = c0y / sx;
        float r20 = c0z / sx;
        float r01 = c1x / sy;
        float r11 = c1y / sy;
        float r21 = c1z / sy;
        float r02 = c2x / sz;
        float r12 = c2y / sz;
        float r22 = c2z / sz;

        float determinant = r00 * (r11 * r22 - r12 * r21)
                - r01 * (r10 * r22 - r12 * r20)
                + r02 * (r10 * r21 - r11 * r20);
        if (determinant < 0.0f) {
            // A mirror cannot be written as a rotation, so fold the reflection into the X scale.
            // Both the matrix and the decomposition then describe the same transform.
            r00 = -r00;
            r10 = -r10;
            r20 = -r20;
            scale[0] = -sx;
        }

        float trace = r00 + r11 + r22;
        float x;
        float y;
        float z;
        float w;
        if (trace > 0.0f) {
            float s = (float) Math.sqrt(trace + 1.0) * 2.0f;
            w = 0.25f * s;
            x = (r21 - r12) / s;
            y = (r02 - r20) / s;
            z = (r10 - r01) / s;
        } else if (r00 > r11 && r00 > r22) {
            float s = (float) Math.sqrt(1.0 + r00 - r11 - r22) * 2.0f;
            w = (r21 - r12) / s;
            x = 0.25f * s;
            y = (r01 + r10) / s;
            z = (r02 + r20) / s;
        } else if (r11 > r22) {
            float s = (float) Math.sqrt(1.0 + r11 - r00 - r22) * 2.0f;
            w = (r02 - r20) / s;
            x = (r01 + r10) / s;
            y = 0.25f * s;
            z = (r12 + r21) / s;
        } else {
            float s = (float) Math.sqrt(1.0 + r22 - r00 - r11) * 2.0f;
            w = (r10 - r01) / s;
            x = (r02 + r20) / s;
            y = (r12 + r21) / s;
            z = 0.25f * s;
        }

        // Mat4.fromQuat assumes a unit quaternion; round-off in the extraction above is ~1e-7, and a
        // non-unit quaternion shows up as a slight shear on everything below the node.
        float length = length(x, y, z, w);
        if (length == 0.0f) {
            rotation[0] = 0.0f;
            rotation[1] = 0.0f;
            rotation[2] = 0.0f;
            rotation[3] = 1.0f;
            return;
        }
        rotation[0] = x / length;
        rotation[1] = y / length;
        rotation[2] = z / length;
        rotation[3] = w / length;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float length(float x, float y, float z, float w) {
        return (float) Math.sqrt(x * x + y * y + z * z + w * w);
    }
}
