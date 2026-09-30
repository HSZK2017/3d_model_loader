package com.model3d.loader.scene;

import com.model3d.loader.math.Vec3;

import java.util.List;

/**
 * One drawable primitive: a triangle list with positions, optional normals/UVs, a material,
 * and - when the mesh is skinned - per-vertex joint indices and weights.
 *
 * <p>Arrays are flat and interleaved-free (one array per attribute) because that is what both
 * source formats hand over before a renderer decides its own vertex layout, and because a flat
 * array can be uploaded to GL with one {@code glBufferData} per attribute without a repack
 * copy at load time.
 *
 * <p>Index space: {@link #indices} is a {@code short[]} when the vertex count fits, which is
 * the usual case and halves index bandwidth. Use {@link #indicesInt()} for a format-agnostic
 * view; it widens on demand and caches the widened array.
 *
 * <p>Skinned meshes use glTF's convention: up to {@link #MAX_JOINTS_PER_VERTEX} joints per
 * vertex, weights normalized to sum to 1 (a primitive whose weights do not sum to 1 is
 * renormalized at parse time, because a partially-weighted vertex collapses toward the model
 * origin and looks like a broken skeleton rather than bad data).
 */
public final class ModelPrimitive {

    public static final int MAX_JOINTS_PER_VERTEX = 4;

    /** Human-readable origin of this primitive, for diagnostics: "mesh[0] 'Fuselage' primitive 0". */
    private final String name;

    private final float[] positions;
    private final float[] normals;      // may be null
    private final float[] uvs;          // may be null
    private final float[] tangents;     // may be null; xyzw
    private final short[] indices;
    private int[] indicesWidened;       // lazily built view, see indicesInt()
    private final float[] jointIndices; // may be null; 4 per vertex, float for GL compat
    private final float[] jointWeights; // may be null; 4 per vertex

    /** Index into {@link ModelScene#materials()}, never negative: a material is always assigned. */
    private final int materialIndex;

    private final int vertexCount;

    public ModelPrimitive(String name, float[] positions, float[] normals, float[] uvs,
                          float[] tangents, short[] indices, float[] jointIndices,
                          float[] jointWeights, int materialIndex) {
        if (positions == null || positions.length == 0) {
            throw new IllegalArgumentException("Primitive '" + name + "' has no positions");
        }
        if (positions.length % 3 != 0) {
            throw new IllegalArgumentException("Primitive '" + name + "' position array length "
                    + positions.length + " is not a multiple of 3");
        }
        this.name = name;
        this.positions = positions;
        this.vertexCount = positions.length / 3;
        this.normals = requireOrNull(normals, normals == null ? 0 : 3, vertexCount, name, "normal");
        this.uvs = requireOrNull(uvs, uvs == null ? 0 : 2, vertexCount, name, "uv");
        this.tangents = requireOrNull(tangents, tangents == null ? 0 : 4, vertexCount, name, "tangent");
        this.indices = indices;
        this.jointIndices = requireOrNull(jointIndices, jointIndices == null ? 0 : 4, vertexCount, name, "jointIndices");
        this.jointWeights = requireOrNull(jointWeights, jointWeights == null ? 0 : 4, vertexCount, name, "jointWeights");
        this.materialIndex = materialIndex;
    }

    private static float[] requireOrNull(float[] data, int stride, int vertexCount, String name, String what) {
        if (data == null) {
            return null;
        }
        if (stride == 0 || data.length != (long) vertexCount * stride) {
            throw new IllegalArgumentException("Primitive '" + name + "' " + what + " array length "
                    + data.length + " does not match " + vertexCount + " vertices at stride " + stride);
        }
        return data;
    }

    public String name() {
        return name;
    }

    public float[] positions() {
        return positions;
    }

    public float[] normals() {
        return normals;
    }

    public float[] uvs() {
        return uvs;
    }

    public float[] tangents() {
        return tangents;
    }

    public short[] indices() {
        return indices;
    }

    /**
     * Indices widened to {@code int[]}, cached. Only called by renderers that decided a
     * 32-bit index buffer is needed anyway (more than 65535 vertices).
     */
    public int[] indicesInt() {
        if (indicesWidened == null) {
            int[] widened = new int[indices.length];
            for (int i = 0; i < indices.length; i++) {
                widened[i] = indices[i] & 0xFFFF;
            }
            indicesWidened = widened;
        }
        return indicesWidened;
    }

    public float[] jointIndices() {
        return jointIndices;
    }

    public float[] jointWeights() {
        return jointWeights;
    }

    public int materialIndex() {
        return materialIndex;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int indexCount() {
        return indices.length;
    }

    public boolean isSkinned() {
        return jointIndices != null && jointWeights != null;
    }

    /** Largest joint index referenced, or -1 when unskinned. Used to validate against a skin. */
    public int maxJointIndex() {
        if (jointIndices == null) {
            return -1;
        }
        int max = -1;
        for (float jointIndex : jointIndices) {
            int asInt = (int) jointIndex;
            if (asInt > max) {
                max = asInt;
            }
        }
        return max;
    }

    /** Bounding box of the raw positions as {@code [minX, minY, minZ, maxX, maxY, maxZ]}. */
    public float[] computeBounds() {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += 3) {
            float x = positions[i];
            float y = positions[i + 1];
            float z = positions[i + 2];
            if (x < minX) {
                minX = x;
            }
            if (y < minY) {
                minY = y;
            }
            if (z < minZ) {
                minZ = z;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (y > maxY) {
                maxY = y;
            }
            if (z > maxZ) {
                maxZ = z;
            }
        }
        return new float[] { minX, minY, minZ, maxX, maxY, maxZ };
    }
}
