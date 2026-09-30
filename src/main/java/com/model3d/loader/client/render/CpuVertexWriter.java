package com.model3d.loader.client.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Writes skinned vertices into the interleaved buffer the CPU render path streams to GL.
 *
 * <h2>The layout, and where it comes from</h2>
 * Ported from {@code ysm_epicfight_compat}'s cpu_skin path, whose author reports it as verified
 * across many users' environments. Twenty-four bytes per vertex, interleaved:
 * <pre>
 *   offset  0 : position, 3 x float
 *   offset 12 : uv,       2 x float
 *   offset 20 : normal,   GL_INT_2_10_10_10_REV (4 bytes, normalised)
 * </pre>
 * The normal is packed rather than stored as three floats. That is the original's choice and it is
 * kept deliberately: three floats would be twelve bytes where four suffice, but more importantly the
 * packing is part of the same convention as the attribute pointers and the shader's
 * {@code layout(location = 2) in vec4 a_normal}, and conventions that are half-ported are how meshes
 * come out scrambled. Either take the layout whole or do not take it.
 *
 * <p>Indexing is expanded on the way in: a vertex used by two triangles is written twice. The original
 * works from pre-expanded vertex lists and draws with {@code glDrawArrays}, so an index buffer is
 * simply not part of the convention - and expansion costs four bytes per vertex once, against a code
 * path that has to be re-verified.
 */
final class CpuVertexWriter {

    /** Bytes per vertex; must match {@link #STRIDE} in {@link ModelCpuMesh} and the attribute pointers. */
    static final int STRIDE = 24;

    private ByteBuffer buffer;
    private int written;
    /**
     * Material index to each contiguous vertex block written for it, in draw order.
     *
     * <p>A material can own more than one block - the draw list walks the node hierarchy, so two meshes
     * sharing a material are not adjacent - and each block is its own draw. Keying by material alone
     * would merge non-contiguous runs into one range spanning other materials' vertices and draw them
     * with the wrong texture.
     */
    private final java.util.LinkedHashMap<Integer, java.util.List<int[]>> ranges =
            new java.util.LinkedHashMap<>();

    /** The open block: material index, start vertex, vertex count. */
    private int currentMaterial = -1;
    private int currentStart;
    private int currentCount;

    CpuVertexWriter(int capacityVertices) {
        this.buffer = allocate(capacityVertices);
    }

    /** A native buffer for {@code vertices} vertices, or null when nothing was asked for. */
    private static java.nio.ByteBuffer allocate(int vertices) {
        if (vertices <= 0) {
            return null;
        }
        return org.lwjgl.system.MemoryUtil.memAlloc(vertices * STRIDE)
                .order(ByteOrder.nativeOrder());
    }

    /**
     * Makes room for {@code vertices} vertices, reallocating only when the current buffer is too
     * small (or was freed).
     *
     * <p>The renderer keeps one writer for the whole session and calls this per draw, so the native
     * buffer is allocated when a model needs more room and never re-allocated for the same model.
     * Callers must call this before {@link #vertex}; a writer that was never sized has no storage.
     */
    void ensureCapacity(int vertices) {
        if (buffer != null && buffer.capacity() >= vertices * STRIDE) {
            return;
        }
        if (buffer != null) {
            org.lwjgl.system.MemoryUtil.memFree(buffer);
        }
        buffer = allocate(vertices);
    }

    /** Rewinds for a new frame. The storage is reused; only the fill level resets. */
    void reset() {
        if (buffer != null) {
            buffer.clear();
        }
        written = 0;
        ranges.clear();
        currentMaterial = -1;
        currentStart = 0;
        currentCount = 0;
    }

    /**
     * Records that subsequent vertices belong to {@code material}.
     *
     * <p>Ranges rather than a re-upload per material: a model with twenty-two materials would otherwise
     * be skinned twenty-two times over, and skinning is the expensive half of this path.
     */
    void beginMaterial(int material) {
        if (material != currentMaterial) {
            closeBlock();
            currentMaterial = material;
            currentStart = written;
            currentCount = 0;
        }
    }

    /** Records the open block and starts a new one. */
    private void closeBlock() {
        if (currentMaterial >= 0 && currentCount > 0) {
            ranges.computeIfAbsent(currentMaterial, key -> new java.util.ArrayList<>())
                    .add(new int[] {currentStart, currentCount});
        }
        currentCount = 0;
    }

    /** Material index to its blocks of {@code [startVertex, count]}, in draw order. */
    java.util.Map<Integer, java.util.List<int[]>> ranges() {
        closeBlock();
        return ranges;
    }

    /**
     * Writes one vertex in camera space.
     *
     * @param x     position, already transformed by the pose stack and the model's own transforms
     * @param nx    normal, unit length; the packing normalises again, so this is belt and braces
     */
    void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        buffer.putFloat(x).putFloat(y).putFloat(z);
        buffer.putFloat(u).putFloat(v);
        buffer.putInt(packNormal(nx, ny, nz));
        written++;
        currentCount++;
    }

    /** The filled buffer, or null when the writer has no storage - see {@link #ensureCapacity}. */
    ByteBuffer buffer() {
        return buffer;
    }

    int writtenVertices() {
        return written;
    }

    /** How many vertices the allocation can hold, which is what the VBO must be sized for. */
    int capacity() {
        return buffer == null ? 0 : buffer.capacity() / STRIDE;
    }

    /** Releases the native buffer. A freed writer must be re-sized before it is used again. */
    void free() {
        if (buffer != null) {
            org.lwjgl.system.MemoryUtil.memFree(buffer);
            buffer = null;
        }
    }

    /**
     * Packs a normal into {@code GL_INT_2_10_10_10_REV} form: three signed 10-bit fields and a 2-bit w.
     *
     * <p>Each field holds a value in {@code [-1, 1]} scaled to {@code [-511, 511]}, with the sign carried
     * in the tenth bit. The w field is left as 1 so the unpacked vector has a usable fourth component -
     * the shader reads {@code a_normal.xyz}, but leaving w zero would make the value a direction with no
     * magnitude if anything ever did use it.
     *
     * <p>Clamped before rounding: a normal component of exactly 1.0 scales to 511 and stays, but a
     * marginally-over-unit normal from accumulated skinning would overflow into the sign bit and turn a
     * small positive component into a large negative one - a single flipped vertex, which reads as a
     * spike sticking out of the model rather than as a maths error.
     */
    private static int packNormal(float nx, float ny, float nz) {
        int px = quantise(nx) & 0x3FF;
        int py = quantise(ny) & 0x3FF;
        int pz = quantise(nz) & 0x3FF;
        int pw = 1 & 0x3;
        return px | (py << 10) | (pz << 20) | (pw << 30);
    }

    private static int quantise(float component) {
        int value = Math.round(component * 511.0f);
        if (value > 511) {
            return 511;
        }
        if (value < -511) {
            return -511;
        }
        return value;
    }
}
