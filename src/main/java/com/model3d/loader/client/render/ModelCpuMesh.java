package com.model3d.loader.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * The GL resources of one model for the CPU-skinning render path: a single dynamic VBO with a fixed
 * attribute layout, drawn with one {@code glDrawArrays}.
 *
 * <h2>Ported, deliberately</h2>
 * This is {@code ysm_epicfight_compat}'s {@code YsmCpuMesh}, whose author reports the mesh rendering
 * as verified across many users' environments. The layout, the buffer usage hint and the attribute
 * pointers are its values, not values chosen here:
 * <pre>
 *   attribute 0 : 3 floats, stride 24, offset  0    position
 *   attribute 1 : 2 floats, stride 24, offset 12    uv
 *   attribute 2 : 4 packed bytes (2_10_10_10_REV),  stride 24, offset 20   normal
 * </pre>
 *
 * <h2>Why the layout is taken whole rather than adapted</h2>
 * The vertex layout, the attribute pointers and the shader's {@code layout(location = N)} declarations
 * are one convention spread across three files. A period during which this project had a wrong
 * attribute count produced a mesh that was uploaded, submitted, reported no GL error and drew nothing at
 * all, and a period with a wrong attribute <i>slot</i> produced geometry scattered across the sky. Both
 * were conventions that had been written out by hand instead of taken from something that works, and
 * both cost days. So the values here are copied and the reasoning is recorded, rather than improved.
 *
 * <p>{@code GL_STREAM_DRAW} rather than {@code GL_DYNAMIC_DRAW} is also the original's choice: the
 * contents are written once per frame and never read back, which is a stream, not a dynamic reuse - and
 * some drivers pick a faster path for the hint that matches the actual access pattern.
 */
final class ModelCpuMesh {

    /** Bytes per vertex: position 3f + uv 2f + normal packed - see the class comment. */
    static final int STRIDE = CpuVertexWriter.STRIDE;

    /**
     * Attribute offsets inside a vertex, named so the pointer setup and the verification harness
     * cannot drift apart: 0 position (3 floats), 12 uv (2 floats), 20 normal (4 packed bytes).
     */
    static final int POSITION_OFFSET = 0;
    static final int UV_OFFSET = 12;
    static final int NORMAL_OFFSET = 20;

    final int vao;
    final int vbo;

    /** Maximum vertices the VBO can hold. */
    private final int capacity;

    private boolean disposed;

    private ModelCpuMesh(int vao, int vbo, int capacity) {
        this.vao = vao;
        this.vbo = vbo;
        this.capacity = capacity;
    }

    /**
     * Allocates the VBO and VAO for {@code capacity} vertices. Render thread only.
     *
     * <p>Sized once and never resized: a model's vertex count is fixed by its file, so the only thing
     * that changes per frame is the contents.
     */
    static ModelCpuMesh create(int capacity) {
        RenderSystem.assertOnRenderThread();
        if (capacity <= 0) {
            return null;
        }
        int vao = GL30.glGenVertexArrays();
        int vbo = GlStateManager._glGenBuffers();

        GL30.glBindVertexArray(vao);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        // Capacity only: the per-frame data is streamed with glBufferSubData after an orphaning
        // glBufferData(NULL). The access pattern is write-once-per-frame, hence STREAM rather than
        // DYNAMIC - the original's reasoning, kept because it is also correct here.
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) capacity * STRIDE, GL15.GL_STREAM_DRAW);

        // The pointers are the convention. Their offsets must equal the fields CpuVertexWriter writes,
        // and their slots must equal the shader's layout(location) declarations; a mismatch in either
        // produces a mesh with no error and no shape.
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, POSITION_OFFSET);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE, UV_OFFSET);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL33.GL_INT_2_10_10_10_REV, true, STRIDE, NORMAL_OFFSET);

        GL30.glBindVertexArray(0);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        return new ModelCpuMesh(vao, vbo, capacity);
    }

    int capacity() {
        return capacity;
    }


    /**
     * Streams the whole frame's vertices and binds the VAO, ready for {@link #drawRange}.
     *
     * <p>One upload for the model rather than one per material: the vertices for every material are
     * contiguous in the buffer, so a single {@code glBufferSubData} carries all of them and each draw
     * then reads its own slice. Uploading per material would send the same bytes once per group.
     *
     * <p>The per-material part of the draw - texture binding, uniforms, blend/cull state - belongs to
     * the caller: it needs the materials, and keeping it out of here is what lets one material's
     * state differ from the next.
     */
    boolean upload(java.nio.ByteBuffer source, int vertexCount) {
        if (disposed || vertexCount <= 0) {
            return false;
        }
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        // Orphan first, then fill. The GL server keeps the previous storage alive for any draw still in
        // flight while this frame's data goes into fresh storage; without it the upload can race a draw
        // that has not completed, which on weak drivers shows as flicker between poses and on a good one
        // never reproduces. This is the reference's reasoning and the reason is unchanged here.
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) vertexCount * STRIDE, GL15.GL_STREAM_DRAW);
        source.position(0);
        source.limit(vertexCount * STRIDE);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, source);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(vao);
        return true;
    }

    /** One {@code glDrawArrays} over a contiguous block of the uploaded buffer. */
    void drawRange(int firstVertex, int count) {
        if (disposed || count <= 0) {
            return;
        }
        GL11.glDrawArrays(GL11.GL_TRIANGLES, firstVertex, count);
    }

    /** Releases the VAO binding after the material groups have been drawn. */
    void unbind() {
        GL30.glBindVertexArray(0);
    }

    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        GlStateManager._glDeleteBuffers(vbo);
        GL30.glDeleteVertexArrays(vao);
    }
}
