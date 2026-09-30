package com.model3d.loader.client.render;

/**
 * The skinning arithmetic, in one place.
 *
 * <p>Extracted so the vertex fill and the normal fill cannot drift apart: a vertex and its normal must
 * go through the same joint blend, and computing them at two call sites is how a model ends up lit as
 * though it were somewhere it is not. The upper-3x3 rotation for normals is separated from the full
 * transform for positions for the same reason - the difference between them is exactly the translation,
 * and getting that wrong tilts every normal by the pivot offset, which looks like a lighting fault and
 * is a maths one.
 *
 * <p>The joint index is clamped rather than trusted. An index past the end of the skeleton is
 * undefined behaviour in GLSL and an out-of-bounds read here, and the parser has already been observed
 * to pass through indices a corrupt file pointed beyond the joint array.
 */
final class ModelSkinning {

    private ModelSkinning() {
    }

    /**
     * Blends the joint matrices for one vertex and applies them to a position.
     *
     * @return the caller's scratch array, holding the position in its first three elements
     */
    static float[] skinPosition(float[] jointMatrices, int jointCount, float[] jointIndices,
                                float[] jointWeights, int vertex, float x, float y, float z,
                                float[] scratch) {
        float outX = 0.0f;
        float outY = 0.0f;
        float outZ = 0.0f;
        int matrixCount = jointMatrices.length / 16;
        for (int influence = 0; influence < 4; influence++) {
            int slot = vertex * 4 + influence;
            if (slot >= jointIndices.length || slot >= jointWeights.length) {
                break;
            }
            float weight = jointWeights[slot];
            if (weight == 0.0f) {
                continue;
            }
            int base = clampJoint((int) jointIndices[slot], jointCount, matrixCount) * 16;
            if (base + 12 > jointMatrices.length) {
                continue;
            }
            outX += weight * (jointMatrices[base] * x + jointMatrices[base + 4] * y
                    + jointMatrices[base + 8] * z + jointMatrices[base + 12]);
            outY += weight * (jointMatrices[base + 1] * x + jointMatrices[base + 5] * y
                    + jointMatrices[base + 9] * z + jointMatrices[base + 13]);
            outZ += weight * (jointMatrices[base + 2] * x + jointMatrices[base + 6] * y
                    + jointMatrices[base + 10] * z + jointMatrices[base + 14]);
        }
        scratch[0] = outX;
        scratch[1] = outY;
        scratch[2] = outZ;
        return scratch;
    }

    /** The same blend applied to a normal, normalised afterwards. */
    static float[] skinNormal(float[] jointMatrices, int jointCount, float[] jointIndices,
                              float[] jointWeights, int vertex, float nx, float ny, float nz,
                              float[] scratch) {
        float outX = 0.0f;
        float outY = 0.0f;
        float outZ = 0.0f;
        int matrixCount = jointMatrices.length / 16;
        for (int influence = 0; influence < 4; influence++) {
            int slot = vertex * 4 + influence;
            if (slot >= jointIndices.length || slot >= jointWeights.length) {
                break;
            }
            float weight = jointWeights[slot];
            if (weight == 0.0f) {
                continue;
            }
            int base = clampJoint((int) jointIndices[slot], jointCount, matrixCount) * 16;
            if (base + 12 > jointMatrices.length) {
                continue;
            }
            outX += weight * (jointMatrices[base] * nx + jointMatrices[base + 4] * ny
                    + jointMatrices[base + 8] * nz);
            outY += weight * (jointMatrices[base + 1] * nx + jointMatrices[base + 5] * ny
                    + jointMatrices[base + 9] * nz);
            outZ += weight * (jointMatrices[base + 2] * nx + jointMatrices[base + 6] * ny
                    + jointMatrices[base + 10] * nz);
        }
        return normalise(outX, outY, outZ, scratch);
    }

    /** Rotates a direction by a matrix's upper 3x3, then normalises. */
    static float[] rotateDirection(float[] matrix, float x, float y, float z, float[] scratch) {
        return normalise(
                matrix[0] * x + matrix[4] * y + matrix[8] * z,
                matrix[1] * x + matrix[5] * y + matrix[9] * z,
                matrix[2] * x + matrix[6] * y + matrix[10] * z,
                scratch);
    }

    private static float[] normalise(float x, float y, float z, float[] scratch) {
        float lengthSquared = x * x + y * y + z * z;
        if (lengthSquared > 1.0e-12f) {
            float inverse = (float) (1.0 / Math.sqrt(lengthSquared));
            scratch[0] = x * inverse;
            scratch[1] = y * inverse;
            scratch[2] = z * inverse;
        } else {
            // A collapsed normal is left as-is rather than replaced with a default: an unlit vertex is a
            // visible symptom of bad input, whereas a fabricated up-vector hides it behind plausible
            // lighting.
            scratch[0] = x;
            scratch[1] = y;
            scratch[2] = z;
        }
        return scratch;
    }

    private static int clampJoint(int joint, int jointCount, int matrixCount) {
        int highest = Math.min(Math.max(jointCount, 1), Math.max(matrixCount, 1)) - 1;
        if (joint < 0) {
            return 0;
        }
        return Math.min(joint, highest);
    }
}
